package com.caigit1.rootbattery

import kotlin.math.roundToInt

data class BatterySnapshot(
    val timestampMs: Long,
    val levelPercent: Int?,
    val temperatureCelsius: Double?,
    val voltageMillivolts: Int?,
    val status: String?,
    val health: String?,
    val raw: Map<String, String>
) {
    val temperatureText: String
        get() = temperatureCelsius?.let { "${(it * 10).roundToInt() / 10.0}°C" } ?: "--"

    val voltageText: String
        get() = voltageMillivolts?.let { "${it}mV" } ?: "--"
}

enum class MonitorErrorType {
    NO_ROOT,
    BATTERY_FILE_NOT_FOUND,
    PERMISSION_DENIED,
    SELINUX_BLOCK,
    EMPTY_RESPONSE,
    COMMAND_FAILURE,
    UNKNOWN
}

data class MonitorError(
    val type: MonitorErrorType,
    val message: String
)

sealed interface MonitorResult {
    data class Success(val snapshot: BatterySnapshot) : MonitorResult
    data class Error(val error: MonitorError) : MonitorResult
}

data class SelfCheckItem(
    val title: String,
    val ok: Boolean,
    val detail: String
)
