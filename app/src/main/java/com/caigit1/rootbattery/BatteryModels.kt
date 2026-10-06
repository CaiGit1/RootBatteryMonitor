package com.caigit1.rootbattery

import kotlin.math.roundToInt

/**
 * 充电器（供电侧）信息，来自 usb / wireless / ucsi 等非 battery 节点。
 */
data class ChargerInfo(
    val node: String,
    val type: String? = null,
    val online: Boolean? = null,
    val usbType: String? = null,
    val voltageNowMv: Int? = null,
    val currentNowMa: Int? = null,
    val currentMaxMa: Int? = null,
    val inputCurrentLimitMa: Int? = null,
    val temperatureCelsius: Double? = null
) {
    val voltageText: String get() = voltageNowMv?.let { "$it mV" } ?: "--"
    val currentText: String get() = currentNowMa?.let { "$it mA" } ?: "--"
    val currentMaxText: String get() = currentMaxMa?.let { "$it mA" } ?: "--"
    val inputLimitText: String get() = inputCurrentLimitMa?.let { "$it mA" } ?: "--"
    val temperatureText: String get() = temperatureCelsius?.let { fmt1(it) + "°C" } ?: "--"
    val onlineText: String get() = when (online) {
        true -> "在线"
        false -> "未连接"
        null -> "--"
    }
}

data class BatterySnapshot(
    val timestampMs: Long,
    /** 实际读取的节点路径，便于排障 */
    val sourcePath: String? = null,

    // ── 标识与状态 ──
    val name: String? = null,
    val type: String? = null,
    val status: String? = null,
    val health: String? = null,
    val present: Boolean? = null,
    val technology: String? = null,
    val modelName: String? = null,
    val chargeType: String? = null,

    // ── 电量 ──
    val levelPercent: Int? = null,

    // ── 温度 / 电压 / 电流 / 功率 ──
    val temperatureCelsius: Double? = null,
    val voltageNowMv: Int? = null,
    val voltageOcvMv: Int? = null,
    val voltageMaxMv: Int? = null,
    val currentNowMa: Int? = null,
    val powerNowMw: Int? = null,
    val powerAvgMw: Int? = null,

    // ── 容量与寿命 ──
    val chargeFullMah: Int? = null,
    val chargeFullDesignMah: Int? = null,
    /**
     * 电荷计数：各 ROM 单位不统一。
     * 本机实测原值 3220，若按 µAh→mAh 换算会得到误导性的「3 mAh」（与 72%×4702mAh 完全不符），
     * 故这里保留内核原值、不做换算。
     */
    val chargeCounterRaw: Int? = null,
    val cycleCount: Int? = null,

    // ── 时间估算 ──
    val timeToFullSeconds: Long? = null,
    val timeToEmptySeconds: Long? = null,

    // ── 充电控制 ──
    val chargeControlLimit: Int? = null,
    val chargeControlLimitMax: Int? = null,
    val constantChargeCurrentMa: Int? = null,

    // ── 充电器节点 ──
    val charger: ChargerInfo? = null,

    val raw: Map<String, String> = emptyMap()
) {
    /** 电池健康度 = 当前满电容量 ÷ 设计容量。这是原工程完全没体现、但最能反映电池损耗的指标。 */
    val healthPercent: Double?
        get() {
            val full = chargeFullMah ?: return null
            val design = chargeFullDesignMah ?: return null
            if (design <= 0) return null
            return full * 100.0 / design
        }

    val healthPercentText: String get() = healthPercent?.let { fmt1(it) + "%" } ?: "--"
    val temperatureText: String get() = temperatureCelsius?.let { fmt1(it) + "°C" } ?: "--"
    val voltageText: String get() = voltageNowMv?.let { "$it mV" } ?: "--"
    val ocvText: String get() = voltageOcvMv?.let { "$it mV" } ?: "--"
    val voltageMaxText: String get() = voltageMaxMv?.let { "$it mV" } ?: "--"
    val currentText: String get() = currentNowMa?.let { "$it mA" } ?: "--"
    val powerNowText: String get() = powerNowMw?.let { "$it mW" } ?: "--"
    val powerAvgText: String get() = powerAvgMw?.let { "$it mW" } ?: "--"
    val chargeFullText: String get() = chargeFullMah?.let { "$it mAh" } ?: "--"
    val chargeFullDesignText: String get() = chargeFullDesignMah?.let { "$it mAh" } ?: "--"
    val chargeCounterText: String get() = chargeCounterRaw?.let { "$it（原值）" } ?: "--"

    /** 由 V×I 推算的功率，用于与内核 POWER_NOW 交叉校验（本机实测两者差异很大） */
    val computedPowerMw: Int?
        get() {
            val v = voltageNowMv ?: return null
            val i = currentNowMa ?: return null
            // 注意单位：mV × mA = 10⁻⁶ W = µW，必须再 ÷1000 才是 mW
            return (kotlin.math.abs(v.toLong() * i) / 1000L).toInt()
        }
    val computedPowerText: String get() = computedPowerMw?.let { "$it mW" } ?: "--"
    val constantChargeCurrentText: String get() = constantChargeCurrentMa?.let { "$it mA" } ?: "--"
    val chargeControlLimitText: String
        get() {
            val cur = chargeControlLimit ?: return "--"
            val max = chargeControlLimitMax
            return if (max != null) "$cur / $max" else "$cur"
        }

    val presentText: String get() = when (present) {
        true -> "已装电池"
        false -> "未检测到电池"
        null -> "--"
    }

    val timeToFullText: String get() = formatDuration(timeToFullSeconds)
    val timeToEmptyText: String get() = formatDuration(timeToEmptySeconds)
}

/**
 * 图表用的轻量采样点。
 * 刻意只存绘图需要的几个数值，不保存整份 raw map —— 0.2s 刷新下最多留存 600 个点，
 * 若每点都带 26 项 uevent 映射，内存会无谓膨胀。
 */
data class TrendPoint(
    val timestampMs: Long,
    val levelPercent: Int?,
    val temperatureCelsius: Double?,
    val voltageMv: Int?,
    val currentMa: Int?,
    val powerMw: Int?
)

fun BatterySnapshot.toTrendPoint(): TrendPoint = TrendPoint(
    timestampMs = timestampMs,
    levelPercent = levelPercent,
    temperatureCelsius = temperatureCelsius,
    voltageMv = voltageNowMv,
    currentMa = currentNowMa,
    // 图表用自算功率而非内核 POWER_NOW：本机实测内核值（10 W）与 V×I（约 0.6 W）相差百倍
    powerMw = computedPowerMw
)

internal fun fmt1(v: Double): String = ((v * 10).roundToInt() / 10.0).toString()

/** 秒 → 可读时长；内核用 -1 / 0xFFFF(65535) 表示未知，统一显示为 -- */
internal fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return "--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

enum class MonitorErrorType {
    NO_ROOT,
    BATTERY_FILE_NOT_FOUND,
    PERMISSION_DENIED,
    SELINUX_BLOCK,
    EMPTY_RESPONSE,
    COMMAND_FAILURE,
    SERVICE_START_FAILED,
    OVERLAY_PERMISSION_MISSING,
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

/** 悬浮窗可选的显示字段，由用户在设置页自行勾选。 */
enum class OverlayField(val label: String) {
    LEVEL("电量"),
    TEMPERATURE("温度"),
    VOLTAGE("电压"),
    CURRENT("电流"),
    POWER("功率"),
    COMPUTED_POWER("V×I"),
    STATUS("状态"),
    CHARGE_TYPE("充电类型"),
    HEALTH("健康度"),
    CYCLE_COUNT("循环次数"),
    CHARGER("充电器");

    fun textOf(s: BatterySnapshot): String = when (this) {
        LEVEL -> s.levelPercent?.let { "$it%" } ?: "--"
        TEMPERATURE -> s.temperatureText
        VOLTAGE -> s.voltageText
        CURRENT -> s.currentText
        POWER -> s.powerNowText
        COMPUTED_POWER -> s.computedPowerText
        STATUS -> s.status ?: "--"
        CHARGE_TYPE -> s.chargeType ?: "--"
        HEALTH -> s.healthPercentText
        CYCLE_COUNT -> s.cycleCount?.toString() ?: "--"
        CHARGER -> s.charger?.node ?: "--"
    }

    companion object {
        val DEFAULT: Set<OverlayField> = setOf(LEVEL, TEMPERATURE, VOLTAGE, CURRENT)
    }
}
