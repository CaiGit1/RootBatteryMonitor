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

    /** 充电协议（已解析方括号中的当前生效项）。lazy：避免每次读取都重新解析。 */
    val protocol: UsbProtocol by lazy { parseUsbProtocol(usbType) }

    /** 充电协议简称，适合悬浮窗这类紧凑位置 */
    val usbTypeShort: String get() = protocol.active

    /** 充电协议可读全称 */
    val usbTypeText: String get() = protocol.activeText
}

/**
 * `POWER_SUPPLY_USB_TYPE` 的解析结果。
 * 界面**只展示当前生效的协议**（[active] / [activeText]）；[supported] 仅作为解析产物保留，
 * 完整原始串仍可在「原始 uevent」卡片里看到。
 */
data class UsbProtocol(
    /** 当前生效协议的短名，如 SDP / PD / DCP */
    val active: String,
    /** 当前生效协议的原始标识符 */
    val activeRaw: String?,
    /** 该口支持的全部协议标识符 */
    val supported: List<String>
) {
    /** 只讲当前协议 —— 不把支持列表铺到界面上 */
    val activeText: String
        get() = activeRaw?.let { describeUsbProtocol(it) } ?: "--"
}

/**
 * 解析 `POWER_SUPPLY_USB_TYPE`。
 *
 * **该属性不是单值**：内核把「本口支持的全部类型」以空格分隔列出，并用方括号标出
 * **当前生效**的那一项。本机实测：
 * ```
 * Unknown [SDP] DCP CDP ACA C PD PD_DRP PD_PPS BrickID
 * ```
 * 之前当成单值直接显示，界面上就会出现一整行无意义的标识符串（实测截图里那一长条），
 * 而且会把 WRAP_CONTENT 的悬浮窗撑宽。必须取方括号里的那一项。
 */
internal fun parseUsbProtocol(raw: String?): UsbProtocol {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return UsbProtocol("--", null, emptyList())

    val active = Regex("""\[([^\]]+)]""").find(text)?.groupValues?.get(1)?.trim()
    val supported = text
        .replace(Regex("""\[([^\]]+)]"""), " $1 ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .distinct()

    return UsbProtocol(
        active = active?.let { shortUsbProtocol(it) } ?: "--",
        activeRaw = active,
        supported = supported
    )
}

private fun shortUsbProtocol(key: String): String = when (key.uppercase()) {
    "SDP", "DCP", "CDP", "ACA", "PD" -> key.uppercase()
    "C" -> "Type-C"
    "PD_DRP" -> "PD DRP"
    "PD_PPS" -> "PD PPS"
    "BRICKID" -> "BrickID"
    "HVDCP" -> "HVDCP"
    "HVDCP_3" -> "HVDCP3"
    "HVDCP_3P5" -> "HVDCP3.5"
    "PROPRIETARY" -> "私有协议"
    "UNKNOWN", "NONE", "" -> "--"
    else -> key
}

private fun describeUsbProtocol(key: String): String = when (key.uppercase()) {
    "SDP" -> "SDP 标准下行口（电脑 USB，上限 500mA）"
    "DCP" -> "DCP 专用充电口（常见 1.5A）"
    "CDP" -> "CDP 充电下行口（可充电 + 数据 1.5A）"
    "ACA" -> "ACA 配件充电适配器"
    "C" -> "USB Type-C 口"
    "PD" -> "USB-PD 功率传输"
    "PD_DRP" -> "USB-PD 双角色（可供电也可受电）"
    "PD_PPS" -> "USB-PD PPS 可编程电源"
    "BRICKID" -> "BrickID（充电器私有识别）"
    "HVDCP" -> "HVDCP 高压专用充电（QC 类）"
    "HVDCP_3" -> "HVDCP 3.0 高压专用充电"
    "HVDCP_3P5" -> "HVDCP 3.5 高压专用充电"
    "PROPRIETARY" -> "厂商私有快充协议"
    "UNKNOWN", "NONE", "" -> "未知"
    else -> key
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
    // 刻意不解析 POWER_NOW / POWER_AVG：实测机型上内核恒返回 10000 与 5000
    // （即 10W / 5W 的固定占位值），与 V×I 自算结果相差百倍，属无效数据。
    // 展示它反而误导用户，故不进入任何界面；原始值仍可在「原始 uevent」卡片查看。

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
    val currentText: String get() = currentNowMa?.let { formatSigned(it, "mA") } ?: "--"
    val chargeFullText: String get() = chargeFullMah?.let { "$it mAh" } ?: "--"
    val chargeFullDesignText: String get() = chargeFullDesignMah?.let { "$it mAh" } ?: "--"
    val chargeCounterText: String get() = chargeCounterRaw?.let { "$it（原值）" } ?: "--"

    /** 由 V×I 推算的功率。不取绝对值：内核用电流正负表示方向，取绝对值会丢掉方向信息。 */
    val computedPowerMw: Int?
        get() {
            val v = voltageNowMv ?: return null
            val i = currentNowMa ?: return null
            // 单位：mV × mA = 10⁻⁶ W = µW，必须再 ÷1000 才是 mW
            return (v.toLong() * i / 1000L).toInt()
        }
    val computedPowerText: String get() = computedPowerMw?.let { formatSigned(it, "mW") } ?: "--"
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

/**
 * 带显式符号的数值格式化。
 * 内核用电流/功率的正负表示方向（不同 ROM 约定相反，本机为「负=充电」），
 * 正值若不加 "+" 就无法一眼看出方向，因此这里显式标注。
 */
internal fun formatSigned(value: Int, unit: String): String =
    if (value > 0) "+$value $unit" else "$value $unit"

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

/**
 * 深色模式策略。
 *
 * 注意：这不是「跟随系统」一个开关就够 —— 用户可能希望系统是深色但本应用保持浅色
 * （或反之）。强制模式还必须同步驱动状态栏图标颜色，否则会出现亮色顶栏配浅色图标、
 * 图标看不见的问题。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        val DEFAULT = SYSTEM
    }
}

/**
 * 悬浮窗背景的取色角色。
 *
 * 为什么需要这个选择：Material You 的 `surface` / `surfaceContainer*` 系列是**中性色** ——
 * 它们只带极淡的壁纸色调（深色下约 `#2B2930`），看上去就是「黑灰」。
 * 真正携带壁纸色度的是 `primary` / `secondary` / `tertiary` 及其 container。
 * 因此这里把选择权交给用户，默认用带色度的主色，而不是让人误以为取色失效。
 */
enum class OverlayBackground(val label: String) {
    SURFACE("中性灰"),
    PRIMARY("主色"),
    SECONDARY("次色"),
    TERTIARY("第三色");

    companion object {
        val DEFAULT = PRIMARY
    }
}

/** 悬浮窗可选的显示字段，由用户在设置页自行勾选。 */enum class OverlayField(val label: String) {
    LEVEL("电量"),
    TEMPERATURE("温度"),
    VOLTAGE("电压"),
    CURRENT("电流"),
    /** 自算功率（电压×电流）。内核 POWER_NOW 实测无效，已不再作为选项。 */
    POWER("功率"),
    STATUS("状态"),
    CHARGE_TYPE("充电类型"),
    /** 充电协议：由供电节点的 POWER_SUPPLY_USB_TYPE 解析（PD / DCP / CDP / HVDCP …） */
    USB_TYPE("充电协议"),
    HEALTH("健康度"),
    CYCLE_COUNT("循环次数"),
    CHARGER("充电器");

    fun textOf(s: BatterySnapshot): String = when (this) {
        LEVEL -> s.levelPercent?.let { "$it%" } ?: "--"
        TEMPERATURE -> s.temperatureText
        VOLTAGE -> s.voltageText
        CURRENT -> s.currentText
        POWER -> s.computedPowerText
        STATUS -> s.status ?: "--"
        CHARGE_TYPE -> s.chargeType ?: "--"
        USB_TYPE -> s.charger?.usbTypeShort ?: "--"
        HEALTH -> s.healthPercentText
        CYCLE_COUNT -> s.cycleCount?.toString() ?: "--"
        CHARGER -> s.charger?.node ?: "--"
    }

    companion object {
        val DEFAULT: Set<OverlayField> = setOf(LEVEL, TEMPERATURE, VOLTAGE, CURRENT)
    }
}
