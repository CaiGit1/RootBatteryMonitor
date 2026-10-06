package com.caigit1.rootbattery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 唯一的长驻服务：同时负责
 *   - 按用户设定的间隔采样
 *   - 更新通知栏（前台服务本就要求有通知）
 *   - 维护悬浮窗
 *
 * 采样循环是全应用唯一的轮询源，避免悬浮窗与界面各poll一份、把 root 读取量翻倍。
 */
class BatteryMonitorService : Service() {

    data class ServiceConfig(
        val intervalMs: Long = SettingsStore.DEFAULT_INTERVAL_MS,
        val notificationEnabled: Boolean = false,
        val overlayEnabled: Boolean = false,
        val overlayFields: Set<OverlayField> = OverlayField.DEFAULT,
        val overlayAlpha: Float = SettingsStore.DEFAULT_OVERLAY_ALPHA,
        val overlayBackground: OverlayBackground = OverlayBackground.DEFAULT,
        /** 悬浮窗的明暗跟随应用的深色模式设置，而不是无条件跟随系统 */
        val themeMode: ThemeMode = ThemeMode.DEFAULT,
        /** 悬浮窗勿扰（锁定）：不可互动、不可双击唤起应用 */
        val overlayLocked: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = BatteryMonitorRepository()
    private val config = MutableStateFlow(ServiceConfig())

    private lateinit var overlay: FloatingOverlay
    private var loopJob: Job? = null
    private var lastNotifyAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        overlay = FloatingOverlay(this)
        createChannel()

        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在读取电池信息"))
        } catch (t: Throwable) {
            // 缺 FOREGROUND_SERVICE_DATA_SYNC 等情况下会抛 SecurityException，
            // 不要让它打崩进程：停掉自己并把原因写进 logcat。
            Log.e(TAG, "startForeground 失败，服务退出", t)
            stopSelf()
            return
        }

        scope.launch {
            config.collect { cfg ->
                applyOverlayConfig(cfg)
                restartLoop(cfg)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SettingsStore(this)
        val fallback = ServiceConfig(
            intervalMs = store.intervalMs,
            notificationEnabled = store.notificationEnabled,
            overlayEnabled = store.overlayEnabled,
            overlayFields = store.overlayFields,
            overlayAlpha = store.overlayAlpha,
            overlayBackground = store.overlayBackground,
            themeMode = store.themeMode,
            overlayLocked = store.overlayLocked
        )

        config.value = if (intent == null) {
            fallback
        } else {
            ServiceConfig(
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, fallback.intervalMs)
                    .coerceIn(SettingsStore.MIN_INTERVAL_MS, SettingsStore.MAX_INTERVAL_MS),
                notificationEnabled = intent.getBooleanExtra(
                    EXTRA_NOTIFICATION, fallback.notificationEnabled
                ),
                overlayEnabled = intent.getBooleanExtra(EXTRA_OVERLAY, fallback.overlayEnabled),
                overlayFields = intent.getStringArrayListExtra(EXTRA_OVERLAY_FIELDS)
                    ?.mapNotNull { runCatching { OverlayField.valueOf(it) }.getOrNull() }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
                    ?: fallback.overlayFields,
                overlayAlpha = intent.getFloatExtra(EXTRA_OVERLAY_ALPHA, fallback.overlayAlpha)
                    .coerceIn(SettingsStore.MIN_OVERLAY_ALPHA, 1f),
                overlayBackground = intent.getStringExtra(EXTRA_OVERLAY_BG)
                    ?.let { name ->
                        runCatching { OverlayBackground.valueOf(name) }.getOrNull()
                    }
                    ?: fallback.overlayBackground,
                themeMode = intent.getStringExtra(EXTRA_THEME_MODE)
                    ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
                    ?: fallback.themeMode,
                overlayLocked = intent.getBooleanExtra(
                    EXTRA_OVERLAY_LOCKED, fallback.overlayLocked
                )
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        overlay.hide()
        repository.close()
        super.onDestroy()
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun applyOverlayConfig(cfg: ServiceConfig) {
        // show()/hide() 内部会把操作派发到主线程，并自行记录失败原因；
        // 这里不再根据返回值打印「缺少权限」——那会把线程异常之类的真实原因掩盖成权限问题。
        if (cfg.overlayEnabled) {
            overlay.show(
                fields = cfg.overlayFields,
                alpha = cfg.overlayAlpha,
                backgroundStyle = cfg.overlayBackground,
                themeMode = cfg.themeMode,
                locked = cfg.overlayLocked
            )
        } else {
            overlay.hide()
        }
    }

    private fun restartLoop(cfg: ServiceConfig) {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                val startedAt = System.currentTimeMillis()

                when (val result = repository.refreshOnce()) {
                    is MonitorResult.Success -> {
                        overlay.update(result.snapshot)
                        maybeNotifySuccess(cfg, result.snapshot)
                    }

                    is MonitorResult.Error -> maybeNotifyError(cfg, result.error)
                }

                // 扣掉读取耗时，保证间隔是「两次采样起点之差」而不是「间隔 + 耗时」
                val elapsed = System.currentTimeMillis() - startedAt
                delay((cfg.intervalMs - elapsed).coerceAtLeast(MIN_DELAY_MS))
            }
        }
    }

    /**
     * 通知更新节流。
     * 0.2s 档位下若每拍都 notify，等于每秒 5 次跨进程通知刷新，纯属浪费还可能被系统限流。
     */
    private fun maybeNotifySuccess(cfg: ServiceConfig, snapshot: BatterySnapshot) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAtMs < NOTIFY_MIN_INTERVAL_MS) return
        lastNotifyAtMs = now

        val text = if (cfg.notificationEnabled) {
            buildString {
                append(snapshot.levelPercent?.let { "$it%" } ?: "--")
                append("  ·  ")
                append(snapshot.temperatureText)
                append("  ·  ")
                append(snapshot.voltageText)
                snapshot.currentNowMa?.let { append("  ·  $it mA") }
            }
        } else {
            "悬浮窗运行中（通知栏明细已关闭）"
        }
        notify(buildNotification(text))
    }

    private fun maybeNotifyError(cfg: ServiceConfig, error: MonitorError) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAtMs < NOTIFY_ERROR_MIN_INTERVAL_MS) return
        lastNotifyAtMs = now
        if (!cfg.notificationEnabled) return
        notify(buildNotification("读取异常：${error.message}"))
    }

    private fun notify(notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Root Battery Monitor")
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "电池监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "电池监控常驻通知" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "BatteryMonitorService"
        private const val CHANNEL_ID = "root_battery_monitor"
        private const val NOTIFICATION_ID = 20261004

        private const val EXTRA_INTERVAL_MS = "interval_ms"
        private const val EXTRA_NOTIFICATION = "notification_enabled"
        private const val EXTRA_OVERLAY = "overlay_enabled"
        private const val EXTRA_OVERLAY_FIELDS = "overlay_fields"
        private const val EXTRA_OVERLAY_ALPHA = "overlay_alpha"
        private const val EXTRA_OVERLAY_BG = "overlay_background"
        private const val EXTRA_THEME_MODE = "theme_mode"
        private const val EXTRA_OVERLAY_LOCKED = "overlay_locked"

        private const val NOTIFY_MIN_INTERVAL_MS = 1000L
        private const val NOTIFY_ERROR_MIN_INTERVAL_MS = 10_000L
        private const val MIN_DELAY_MS = 20L

        fun buildIntent(
            context: Context,
            intervalMs: Long,
            notificationEnabled: Boolean,
            overlayEnabled: Boolean,
            overlayFields: Set<OverlayField>,
            overlayAlpha: Float,
            overlayBackground: OverlayBackground,
            themeMode: ThemeMode,
            overlayLocked: Boolean
        ): Intent = Intent(context, BatteryMonitorService::class.java).apply {
            putExtra(EXTRA_INTERVAL_MS, intervalMs)
            putExtra(EXTRA_NOTIFICATION, notificationEnabled)
            putExtra(EXTRA_OVERLAY, overlayEnabled)
            putExtra(EXTRA_OVERLAY_ALPHA, overlayAlpha)
            putExtra(EXTRA_OVERLAY_BG, overlayBackground.name)
            putExtra(EXTRA_THEME_MODE, themeMode.name)
            putExtra(EXTRA_OVERLAY_LOCKED, overlayLocked)
            putStringArrayListExtra(
                EXTRA_OVERLAY_FIELDS,
                ArrayList(overlayFields.map { it.name })
            )
        }
    }
}
