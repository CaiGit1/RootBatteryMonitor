package com.caigit1.rootbattery

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量设置持久化。
 * 用 SharedPreferences 而不是 DataStore，避免为几个开关引入额外依赖与协程样板。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 刷新间隔（毫秒），范围 [MIN_INTERVAL_MS, MAX_INTERVAL_MS]，步进 STEP_INTERVAL_MS */
    var intervalMs: Long
        get() = prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        set(v) = prefs.edit()
            .putLong(KEY_INTERVAL, v.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS))
            .apply()

    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS, true)
        set(v) = prefs.edit().putBoolean(KEY_ALERTS, v).apply()

    /** 通知栏常驻（前台服务） */
    var notificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFICATION, v).apply()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY, v).apply()

    var overlayFields: Set<OverlayField>
        get() {
            val raw = prefs.getStringSet(KEY_OVERLAY_FIELDS, null)
                ?: return OverlayField.DEFAULT
            val parsed = raw.mapNotNull { parseFieldName(it) }.toSet()
            return if (parsed.isEmpty()) OverlayField.DEFAULT else parsed
        }
        set(v) = prefs.edit()
            .putStringSet(KEY_OVERLAY_FIELDS, v.map { it.name }.toSet())
            .apply()

    /**
     * 旧版本把自算功率命名为 COMPUTED_POWER；后来内核 POWER_NOW 被判定为无效数据，
     * 它成为唯一的「功率」项并更名为 POWER。这里做一次名字迁移，
     * 否则老用户已勾选的功率项会在升级后被 valueOf 静默丢弃。
     */
    private fun parseFieldName(raw: String): OverlayField? = when (raw) {
        "COMPUTED_POWER" -> OverlayField.POWER
        else -> runCatching { OverlayField.valueOf(raw) }.getOrNull()
    }

    /** 悬浮窗背景取色角色 */
    var overlayBackground: OverlayBackground
        get() {
            val raw = prefs.getString(KEY_OVERLAY_BG, null) ?: return OverlayBackground.DEFAULT
            return runCatching { OverlayBackground.valueOf(raw) }
                .getOrDefault(OverlayBackground.DEFAULT)
        }
        set(v) = prefs.edit().putString(KEY_OVERLAY_BG, v.name).apply()

    /** 深色模式策略：跟随系统 / 始终浅色 / 始终深色 */
    var themeMode: ThemeMode
        get() {
            val raw = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.DEFAULT
            return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.DEFAULT)
        }
        set(v) = prefs.edit().putString(KEY_THEME_MODE, v.name).apply()

    /** 悬浮窗背景不透明度，[MIN_OVERLAY_ALPHA, 1.0] */
    var overlayAlpha: Float
        get() = prefs.getFloat(KEY_OVERLAY_ALPHA, DEFAULT_OVERLAY_ALPHA)
            .coerceIn(MIN_OVERLAY_ALPHA, 1f)
        set(v) = prefs.edit()
            .putFloat(KEY_OVERLAY_ALPHA, v.coerceIn(MIN_OVERLAY_ALPHA, 1f))
            .apply()

    companion object {
        private const val PREFS_NAME = "root_battery_monitor"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_NOTIFICATION = "notification_enabled"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_OVERLAY_FIELDS = "overlay_fields"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
        private const val KEY_OVERLAY_BG = "overlay_background"
        private const val KEY_THEME_MODE = "theme_mode"

        /** 0.2s 起，0.1s 步进 */
        const val MIN_INTERVAL_MS = 200L

        /** 上限 5s —— 再慢就没必要常驻监控了 */
        const val MAX_INTERVAL_MS = 5000L
        const val STEP_INTERVAL_MS = 100L
        const val DEFAULT_INTERVAL_MS = 1000L

        /** 高频刷新时的耗电提示阈值 */
        const val FAST_WARN_MS = 500L

        /** 悬浮窗背景不透明度：下限保证文字仍可读 */
        const val MIN_OVERLAY_ALPHA = 0.2f
        const val DEFAULT_OVERLAY_ALPHA = 0.85f
        const val OVERLAY_ALPHA_STEP = 0.05f
    }
}
