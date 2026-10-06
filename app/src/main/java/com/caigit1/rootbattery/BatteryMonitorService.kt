package com.caigit1.rootbattery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
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
        val overlayLocked: Boolean = false,
        /** Android 16 实况通知：把常驻通知提升为状态栏/锁屏上的实时活动 */
        val liveUpdateEnabled: Boolean = true,
        /** 实况通知的呈现方式：自动 / 小米超级岛 / 类原生 AOSP */
        val islandMode: IslandMode = IslandMode.DEFAULT
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = BatteryMonitorRepository()
    private val config = MutableStateFlow(ServiceConfig())

    private lateinit var overlay: FloatingOverlay
    private var loopJob: Job? = null
    private var lastNotifyAtMs = 0L
    private var islandCaps: HyperOsIsland.Capabilities? = null

    override fun onCreate() {
        super.onCreate()
        overlay = FloatingOverlay(this)
        createChannel()

        // 上岛能力自查：区分「系统不支持岛」与「本应用未被授权焦点通知」。
        // 结果缓存下来 —— hasFocusPermission 是跨进程调用，不能跟着刷新频率问。
        islandCaps = HyperOsIsland.probe(this)

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
            overlayLocked = store.overlayLocked,
            liveUpdateEnabled = store.liveUpdateEnabled,
            islandMode = store.islandMode
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
                ),
                liveUpdateEnabled = intent.getBooleanExtra(
                    EXTRA_LIVE_UPDATE, fallback.liveUpdateEnabled
                ),
                islandMode = intent.getStringExtra(EXTRA_ISLAND_MODE)
                    ?.let { IslandMode.fromName(it) }
                    ?: fallback.islandMode
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

        if (!cfg.notificationEnabled) {
            notify(buildNotification("悬浮窗运行中（通知栏明细已关闭）"))
            return
        }

        // 实况通知的重点就是功率与温度：折叠态只留最紧凑的一行，
        // 展开态（BigText）再补电量/电压/电流 —— 状态栏位置寸土寸金，不能一上来就堆满。
        val power = snapshot.computedPowerText
        val temp = snapshot.temperatureText
        // 岛里正文槽位是固定宽度：实测再加内容（如电量）会被直接裁掉，
        // 而左侧那段空白也不随文本变长而收缩。所以这里只放最关键的功率与温度。
        val compact = "$power$SHORT_TEXT_SEPARATOR$temp"
        val expanded = buildString {
            append("功率 ").append(power)
            append("  ·  温度 ").append(temp)
            snapshot.levelPercent?.let { append("  ·  电量 ").append(it).append('%') }
            append("  ·  ").append(snapshot.voltageText)
            snapshot.currentNowMa?.let { append("  ·  ").append(snapshot.currentText) }
        }
        // 由「用户选择 + 设备能力」共同决定这次走哪条路。
        // 两条路互斥：HyperOS 上若同时请求实况通知，系统会用它自己的转换逻辑
        // 覆盖岛内容（左区留空并忽略 miui.focus.param）。
        val caps = islandCaps
        val effectiveMode = caps?.let { HyperOsIsland.resolveMode(cfg.islandMode, it) }
        val islandJson = if (
            cfg.liveUpdateEnabled && effectiveMode == HyperOsIsland.EffectiveMode.XIAOMI_ISLAND
        ) {
            HyperOsIsland.buildParams(
                powerText = power,
                tempText = temp,
                levelText = snapshot.levelPercent?.let { "$it%" } ?: "--"
            )
        } else {
            null
        }
        notify(
            buildNotification(
                compact,
                liveText = expanded.takeIf { cfg.liveUpdateEnabled },
                islandJson = islandJson
            )
        )
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

    /**
     * 构建常驻通知。
     *
     * @param liveText 非 null 时把通知**提升为实况通知**（Android 16 Live Updates）：
     *   样式换成 `BigTextStyle`（系统允许被提升的四种样式之一），
     *   置上 `EXTRA_REQUEST_PROMOTED_ONGOING` 请求提升，
     *   并设置「短关键文本」。
     *
     * **短关键文本不是可选润色，而是本功能能用的前提**：
     * HyperOS 会把实况通知直接渲染成小米超级岛，岛上正文位取的就是 `shortCriticalText`；
     * 不设置时系统回退显示通知标题，结果岛上只剩一行「Root Battery Monitor」，
     * 功率与温度全部看不见（实机确认过这个现象）。
     *
     * 两处不得不绕的原因，都不是随手写的：
     *  - 内联 `EXTRA_REQUEST_PROMOTED_ONGOING` 的常量名与取值：它是 API 36 才有的字段，
     *    而本工程 compileSdk 仍是 35，直接引用编译不过。取值由 SDK 存根确认：
     *    `javap -constants android.app.Notification` 输出
     *    `EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"`。
     *  - `setShortCriticalText` 用反射调用：同样是 API 36 的方法，且 androidx core 1.16
     *    都还没封装它。它是**公开 API（非 hidden）**，只是本机编译链没跟上
     *    （本地只有 `platforms/android-36.1`，AGP 8.6 不认这个目录名），
     *    反射 + API 判断比为一行字符串升级整条编译链划算。
     *
     * 用平台 `Notification.Builder` 而非 `NotificationCompat.Builder`：后者没有
     * shortCriticalText 的口子；而本工程 minSdk 26 已完全覆盖平台 Builder 的能力
     * （带 channel 的构造重载正是 API 26 引入的）。
     */
    private fun buildNotification(
        content: String,
        liveText: String? = null,
        islandJson: String? = null
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            // 平台 Builder 没有 setSilent —— 那是 NotificationCompat 的便利方法，
            // 内部靠清空 sound/vibrate/defaults 实现。API 26 起铃声与震动由通知渠道决定，
            // 本渠道是 IMPORTANCE_LOW，本就静默；这里只需声明「只提示一次」。
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)

        // 实况通知的样式必须是系统允许被提升的四种之一。
        //
        // 关于超级岛里 [图标] 与 [短关键文本] 之间那段空白：已逐一实测排除四种填法
        // （改短标题 / 旧式 setProgress / setSubText / ProgressStyle），
        // 岛的布局始终不变。那段是 HyperOS 为自家焦点通知载荷（大岛/小岛字段）预留的
        // 内容区，AOSP 实况通知没有对应数据 —— 只能靠 miui.focus.param 原生路径填，
        // 而那条路需要小米的商务审批。故这里保持 BigTextStyle：不冒险顶掉展开态信息。
        // 两条路互斥，实机上试出来的：
        //  - 发「实况通知」（PROMOTED_ONGOING）→ HyperOS 走它自己的转换，
        //    把 shortCriticalText 塞进右区、左区留空，且忽略 miui.focus.param；
        //  - 只发原生岛载荷 → 左右两区都由我们控制，能填满整条岛。
        // 所以能上岛时选原生载荷；不支持岛的机型（如 Pixel）才退回 AOSP 实况通知。
        val useNativeIsland = islandJson != null
        if (liveText != null && Build.VERSION.SDK_INT >= API_36 && !useNativeIsland) {
            builder.setStyle(Notification.BigTextStyle().bigText(liveText))
            builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            applyShortCriticalText(builder, content)
        }

        val notification = builder.build()

        // 岛载荷按官方指南的做法挂在**构建完成的通知**的 extras 上
        // （指南示例即 `notification.extras.putString("miui.focus.param", ...)`）。
        if (islandJson != null) {
            notification.extras.putString(HyperOsIsland.EXTRA_FOCUS_PARAM, islandJson)
            notification.extras.putBundle(
                HyperOsIsland.EXTRA_FOCUS_PICS,
                Bundle().apply {
                    putParcelable(
                        HyperOsIsland.picKey(),
                        Icon.createWithResource(this@BatteryMonitorService, R.drawable.ic_stat_battery)
                    )
                }
            )
        }
        return notification
    }

    /**
     * 设置实况通知的短关键文本（超级岛正文位）。
     *
     * 系统对它有长度上限，但该上限没有暴露在 SDK 里，本地无法预先得知。
     * 因此按「完整 → 精简」逐个候选试，成功的那个打进日志；
     * 全部被拒时明确记一条 —— 这比静默失败强，至少能在 logcat 里看出岛为何是空的。
     */
    private fun applyShortCriticalText(builder: Notification.Builder, text: String) {
        val setter = runCatching {
            Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java)
        }.getOrNull()
        if (setter == null) {
            Log.w(TAG, "本机没有 setShortCriticalText，跳过短关键文本")
            return
        }

        val candidates = listOf(text, text.substringBefore(SHORT_TEXT_SEPARATOR))
        for (candidate in candidates) {
            if (runCatching { setter.invoke(builder, candidate) }.isSuccess) {
                Log.i(TAG, "实况通知短关键文本 = \"$candidate\"")
                return
            }
        }
        Log.w(TAG, "短关键文本全部候选被拒，超级岛将回退显示通知标题")
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
        private const val EXTRA_LIVE_UPDATE = "live_update_enabled"
        private const val EXTRA_ISLAND_MODE = "island_mode"

        private const val NOTIFICATION_TITLE = "电池"

        /** 折叠态两段信息的连接符，同时也是短关键文本的截断点 */
        private const val SHORT_TEXT_SEPARATOR = "  ·  "

        /** Android 16 = API 36。compileSdk 仍是 35，所以不能引用 Build.VERSION_CODES 里的新名字 */
        private const val API_36 = 36

        /**
         * `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` 的常量名与取值。
         * 取值由 SDK 存根确认：`javap -constants android.app.Notification` 输出
         * `EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"`。
         */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

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
            overlayLocked: Boolean,
            liveUpdateEnabled: Boolean,
            islandMode: IslandMode
        ): Intent = Intent(context, BatteryMonitorService::class.java).apply {
            putExtra(EXTRA_INTERVAL_MS, intervalMs)
            putExtra(EXTRA_NOTIFICATION, notificationEnabled)
            putExtra(EXTRA_OVERLAY, overlayEnabled)
            putExtra(EXTRA_OVERLAY_ALPHA, overlayAlpha)
            putExtra(EXTRA_OVERLAY_BG, overlayBackground.name)
            putExtra(EXTRA_THEME_MODE, themeMode.name)
            putExtra(EXTRA_OVERLAY_LOCKED, overlayLocked)
            putExtra(EXTRA_LIVE_UPDATE, liveUpdateEnabled)
            putExtra(EXTRA_ISLAND_MODE, islandMode.name)
            putStringArrayListExtra(
                EXTRA_OVERLAY_FIELDS,
                ArrayList(overlayFields.map { it.name })
            )
        }
    }
}
