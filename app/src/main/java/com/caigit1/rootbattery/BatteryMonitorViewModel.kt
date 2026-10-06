package com.caigit1.rootbattery

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 图表最多保留的采样点数。0.2s 档位下约等于 2 分钟窗口。 */
private const val MAX_TREND_POINTS = 600

data class BatteryMonitorUiState(
    val rootReady: Boolean = false,
    val selfChecks: List<SelfCheckItem> = emptyList(),
    val latest: BatterySnapshot? = null,
    val history: List<TrendPoint> = emptyList(),
    val error: MonitorError? = null,

    /** 刷新间隔（毫秒），0.2s 起、0.1s 步进 */
    val intervalMs: Long = SettingsStore.DEFAULT_INTERVAL_MS,

    val alertsEnabled: Boolean = true,
    /** 通知栏常驻（显示实时数值） */
    val notificationEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val overlayFields: Set<OverlayField> = OverlayField.DEFAULT,
    /** 深色模式策略 */
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    /** 悬浮窗背景不透明度 */
    val overlayAlpha: Float = SettingsStore.DEFAULT_OVERLAY_ALPHA,
    /** 悬浮窗背景取色角色 */
    val overlayBackground: OverlayBackground = OverlayBackground.DEFAULT,
    val overlayPermissionGranted: Boolean = false
) {
    val serviceRunning: Boolean get() = notificationEnabled || overlayEnabled
}

class BatteryMonitorViewModel(
    application: Application,
    private val repository: BatteryMonitorRepository
) : AndroidViewModel(application) {

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                if (modelClass.isAssignableFrom(BatteryMonitorViewModel::class.java)) {
                    val application =
                        checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    return BatteryMonitorViewModel(application, BatteryMonitorRepository()) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    private val settings = SettingsStore(application)
    private val _uiState = MutableStateFlow(BatteryMonitorUiState())
    val uiState: StateFlow<BatteryMonitorUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        _uiState.update {
            it.copy(
                intervalMs = settings.intervalMs,
                alertsEnabled = settings.alertsEnabled,
                notificationEnabled = settings.notificationEnabled,
                overlayEnabled = settings.overlayEnabled,
                overlayFields = settings.overlayFields,
                themeMode = settings.themeMode,
                overlayAlpha = settings.overlayAlpha,
                overlayBackground = settings.overlayBackground,
                overlayPermissionGranted = canDrawOverlays()
            )
        }
        // 进入界面时若服务本应在跑（例如上次退出前是开的），确保它被拉起
        syncService()
        runSelfCheck()
        startPolling()
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    // ────────────────────── 用户操作 ──────────────────────

    fun runSelfCheck() {
        viewModelScope.launch {
            val checks = repository.selfCheck()
            _uiState.update {
                it.copy(
                    selfChecks = checks,
                    rootReady = checks.firstOrNull()?.ok == true,
                    // 自检成功时清掉陈旧的错误横幅，失败时保留由刷新路径写入的错误
                    error = if (checks.firstOrNull()?.ok == true) null else it.error
                )
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { applyResult(repository.refreshOnce()) }
    }

    /** 设置刷新间隔（毫秒）。会持久化、重启界面采样、并同步给后台服务。 */
    fun setIntervalMs(ms: Long) {
        val clamped = ms.coerceIn(SettingsStore.MIN_INTERVAL_MS, SettingsStore.MAX_INTERVAL_MS)
            .let { snapToStep(it) }
        if (clamped == _uiState.value.intervalMs) return

        settings.intervalMs = clamped
        _uiState.update { it.copy(intervalMs = clamped) }
        startPolling()
        syncService()
    }

    fun setAlertsEnabled(enabled: Boolean) {
        settings.alertsEnabled = enabled
        _uiState.update { it.copy(alertsEnabled = enabled) }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        settings.notificationEnabled = enabled
        _uiState.update { it.copy(notificationEnabled = enabled) }
        syncService()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        if (enabled && !canDrawOverlays()) {
            _uiState.update {
                it.copy(
                    overlayEnabled = false,
                    overlayPermissionGranted = false,
                    error = MonitorError(
                        MonitorErrorType.OVERLAY_PERMISSION_MISSING,
                        "缺少「显示在其他应用上层」权限，请在系统设置中开启后重试"
                    )
                )
            }
            return
        }
        settings.overlayEnabled = enabled
        _uiState.update { it.copy(overlayEnabled = enabled, overlayPermissionGranted = canDrawOverlays()) }
        syncService()
    }

    fun setOverlayField(field: OverlayField, enabled: Boolean) {
        val next = _uiState.value.overlayFields.toMutableSet().apply {
            if (enabled) add(field) else remove(field)
        }
        if (next.isEmpty()) return // 至少留一项，否则悬浮窗没内容可显示
        settings.overlayFields = next
        _uiState.update { it.copy(overlayFields = next) }
        syncService()
    }

    /** 悬浮窗背景不透明度，0.2 – 1.0。 */
    fun setOverlayAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(SettingsStore.MIN_OVERLAY_ALPHA, 1f)
        if (clamped == _uiState.value.overlayAlpha) return
        settings.overlayAlpha = clamped
        _uiState.update { it.copy(overlayAlpha = clamped) }
        syncService()
    }

    /** 悬浮窗背景取色角色（Material You）。 */
    fun setOverlayBackground(background: OverlayBackground) {
        if (background == _uiState.value.overlayBackground) return
        settings.overlayBackground = background
        _uiState.update { it.copy(overlayBackground = background) }
        syncService()
    }

    /** 深色模式策略。悬浮窗的明暗也走这个设置，因此需要同步给后台服务。 */
    fun setThemeMode(mode: ThemeMode) {
        if (mode == _uiState.value.themeMode) return
        settings.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
        syncService()
    }

    /** 从系统设置返回后刷新权限状态 */
    fun refreshOverlayPermission() {
        _uiState.update { it.copy(overlayPermissionGranted = canDrawOverlays()) }
    }

    // ────────────────────── 内部实现 ──────────────────────

    private fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(getApplication())

    private fun snapToStep(ms: Long): Long {
        val step = SettingsStore.STEP_INTERVAL_MS
        val min = SettingsStore.MIN_INTERVAL_MS
        val snapped = min + ((ms - min) / step) * step
        return snapped.coerceIn(min, SettingsStore.MAX_INTERVAL_MS)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repository.poll(_uiState.value.intervalMs).collect { applyResult(it) }
        }
    }

    /**
     * 把当前配置推给后台服务：需要跑就 startForegroundService（同 Intent 再次调用
     * 会走 onStartCommand 更新配置），不需要跑就 stopService。
     */
    private fun syncService() {
        val ctx = getApplication<Application>()
        val s = _uiState.value
        val wantRunning = s.notificationEnabled || s.overlayEnabled

        try {
            if (wantRunning) {
                ContextCompat.startForegroundService(
                    ctx,
                    BatteryMonitorService.buildIntent(
                        ctx,
                        intervalMs = s.intervalMs,
                        notificationEnabled = s.notificationEnabled,
                        overlayEnabled = s.overlayEnabled,
                        overlayFields = s.overlayFields,
                        overlayAlpha = s.overlayAlpha,
                        overlayBackground = s.overlayBackground,
                        themeMode = s.themeMode
                    )
                )
            } else {
                ctx.stopService(Intent(ctx, BatteryMonitorService::class.java))
            }
        } catch (t: Throwable) {
            // 例：Android 14+ 缺 FOREGROUND_SERVICE_DATA_SYNC，或后台启动前台服务被系统拒绝
            _uiState.update {
                it.copy(
                    notificationEnabled = false,
                    overlayEnabled = false,
                    error = MonitorError(
                        MonitorErrorType.SERVICE_START_FAILED,
                        "后台服务启动失败：${t.message ?: t::class.java.simpleName}"
                    )
                )
            }
        }
    }

    private fun applyResult(result: MonitorResult) {
        when (result) {
            is MonitorResult.Success -> _uiState.update { state ->
                val merged = (state.history + result.snapshot.toTrendPoint()).takeLast(MAX_TREND_POINTS)
                state.copy(latest = result.snapshot, history = merged, error = null)
            }

            is MonitorResult.Error -> _uiState.update { it.copy(error = result.error) }
        }
    }
}
