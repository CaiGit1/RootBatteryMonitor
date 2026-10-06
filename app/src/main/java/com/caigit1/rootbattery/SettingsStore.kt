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
            val parsed = raw.mapNotNull { name ->
                runCatching { OverlayField.valueOf(name) }.getOrNull()
            }.toSet()
            return if (parsed.isEmpty()) OverlayField.DEFAULT else parsed
        }
        set(v) = prefs.edit()
            .putStringSet(KEY_OVERLAY_FIELDS, v.map { it.name }.toSet())
            .apply()

    companion object {
        private const val PREFS_NAME = "root_battery_monitor"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_NOTIFICATION = "notification_enabled"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_OVERLAY_FIELDS = "overlay_fields"

        /** 0.2s 起，0.1s 步进 */
        const val MIN_INTERVAL_MS = 200L

        /** 上限 5s —— 再慢就没必要常驻监控了 */
        const val MAX_INTERVAL_MS = 5000L
        const val STEP_INTERVAL_MS = 100L
        const val DEFAULT_INTERVAL_MS = 1000L

        /** 高频刷新时的耗电提示阈值 */
        const val FAST_WARN_MS = 500L
    }
}
