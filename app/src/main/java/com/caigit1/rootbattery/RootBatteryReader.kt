package com.caigit1.rootbattery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val SU = "su -c"

/**
 * 只读读取电池 uevent（不写入 sysfs）。
 *
 * 设计要点：
 *
 * 1. **常驻 root shell**（[RootShell]）承担高频读取。刷新间隔可低至 0.2s，
 *    若每次刷新都 fork `su -c cat`，就是每秒 5 次进程创建 + KernelSU 授权链路往返，
 *    开销与耗电都不可接受。shell 不可用时自动降级为一次性命令。
 *
 * 2. **路径与供电节点走缓存**。节点探测（列目录 + 逐节点试读）与充电器节点读取
 *    都远比「读一个文件」昂贵，绝不能跟着 0.2s 的节奏跑：
 *    - 电池节点路径：探测一次后长期缓存，读取失败时才失效重探
 *    - 供电节点：5 秒 TTL
 *
 * 3. **所有文件探测都在 root 上下文**。App 自身 UID 在 Android 10+ 受 SELinux 限制
 *    无法 `stat` `/sys`，用 `File.exists()` 会恒为 false 并误报「路径不存在」。
 *
 * 4. 命令超时后**绝不**调用 `exitValue()`（进程未退出时抛 `IllegalThreadStateException`）。
 */
class RootBatteryReader {

    private val shell = RootShell()

    @Volatile
    private var cachedBatteryPath: String? = null

    @Volatile
    private var cachedCharger: ChargerInfo? = null

    @Volatile
    private var chargerFetchedAtMs: Long = 0L

    fun close() = shell.close()

    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val out = shellExec("id", SHELL_TIMEOUT_MS) ?: oneShot("$SU id")?.output
        out?.contains("uid=0") == true
    }

    /**
     * 一次刷新。高频路径只做「读 battery uevent」这一件事，
     * 其余昂贵操作用缓存兜住。
     */
    suspend fun readBatteryUevent(): MonitorResult = withContext(Dispatchers.IO) {
        val path = cachedBatteryPath ?: discoverBatteryPath()
        if (path == null) {
            return@withContext MonitorResult.Error(
                MonitorError(
                    MonitorErrorType.BATTERY_FILE_NOT_FOUND,
                    "未找到可读的电池 uevent 节点（已尝试 /sys/class/power_supply/*/uevent）"
                )
            )
        }

        val raw = shellExec("cat '$path'", FAST_TIMEOUT_MS)
            ?: oneShot("$SU cat '$path'")?.takeIf { it.exitCode == 0 }?.output

        if (raw.isNullOrBlank()) {
            cachedBatteryPath = null // 失效，下次重新探测
            return@withContext MonitorResult.Error(
                MonitorError(MonitorErrorType.COMMAND_FAILURE, "读取失败：$path")
            )
        }
        if (!raw.contains("POWER_SUPPLY_")) {
            cachedBatteryPath = null
            return@withContext MonitorResult.Error(
                MonitorError(classifyFailure(raw), raw.take(200))
            )
        }

        val values = UeventParser.parse(raw)
        if (values.isEmpty()) {
            return@withContext MonitorResult.Error(
                MonitorError(MonitorErrorType.EMPTY_RESPONSE, "读取成功但内容为空：$path")
            )
        }

        MonitorResult.Success(UeventParser.toSnapshot(values, chargerInfo(), path))
    }

    /**
     * 环境自检。三项文件结论全部出自 root 上下文，保证不会出现
     * 「存在=失败 / 可读=通过」这种自相矛盾。
     */
    suspend fun selfCheck(): List<SelfCheckItem> = withContext(Dispatchers.IO) {
        val idOut = shellExec("id", SHELL_TIMEOUT_MS) ?: oneShot("$SU id")?.output
        val rootGranted = idOut?.contains("uid=0") == true

        if (!rootGranted) {
            return@withContext listOf(
                SelfCheckItem(
                    "Root 可用", false,
                    "su 不可用或未授权（KernelSU 请在管理器中为本应用授予 root）"
                ),
                SelfCheckItem("电池节点", false, "root 不可用，无法探测 /sys/class/power_supply"),
                SelfCheckItem("节点可读", false, "root 不可用，无法读取"),
                SelfCheckItem("常驻 shell", false, "root 不可用")
            )
        }

        val resolved = cachedBatteryPath ?: discoverBatteryPath()
        val stat = resolved?.let { shellExec("ls -l '$it'", SHELL_TIMEOUT_MS) }
        val readable = resolved?.let {
            val r = shellExec("cat '$it'", SHELL_TIMEOUT_MS)
            !r.isNullOrBlank() && r.contains("POWER_SUPPLY_")
        } ?: false
        val charger = chargerInfo()
        val shellOk = shell.isAlive()

        listOf(
            SelfCheckItem("Root 可用", true, idOut.lineSequence().firstOrNull()?.trim().orEmpty()),
            SelfCheckItem("电池节点", resolved != null, resolved ?: "未找到可读的 uevent 节点"),
            SelfCheckItem(
                "节点可读", readable,
                stat?.lineSequence()?.firstOrNull()?.trim() ?: "未能定位节点"
            ),
            SelfCheckItem(
                "供电节点", charger != null,
                charger?.let { "${it.node}（${it.onlineText}，${it.type ?: "?"}）" }
                    ?: "未发现 usb / wireless 等供电节点"
            ),
            SelfCheckItem(
                "常驻 shell", shellOk,
                if (shellOk) "已复用单个 su 进程，高频刷新不会反复创建进程"
                else "未启用，已降级为每次 fork 一次性 su（高频刷新时开销较大）"
            )
        )
    }

    // ────────────────────────── 内部实现 ──────────────────────────

    private fun discoverBatteryPath(): String? {
        val names = shellExec("ls /sys/class/power_supply/", SHELL_TIMEOUT_MS)
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        for (name in orderCandidates(names)) {
            val candidate = "/sys/class/power_supply/$name/uevent"
            val head = shellExec("cat '$candidate'", SHELL_TIMEOUT_MS)
                ?: oneShot("$SU cat '$candidate'")?.takeIf { it.exitCode == 0 }?.output
            if (!head.isNullOrBlank() && head.contains("POWER_SUPPLY_")) {
                cachedBatteryPath = candidate
                return candidate
            }
        }
        return null
    }

    private fun orderCandidates(names: List<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val preferred = PREFERRED_NAMES.flatMap { want ->
            names.filter { it.equals(want, ignoreCase = true) }
        }
        val rest = names.filterNot { n -> PREFERRED_NAMES.any { it.equals(n, ignoreCase = true) } }
        return (preferred + rest).distinct()
    }

    private fun chargerInfo(): ChargerInfo? {
        val now = System.currentTimeMillis()
        if (now - chargerFetchedAtMs < CHARGER_TTL_MS) return cachedCharger
        val info = readCharger()
        cachedCharger = info
        chargerFetchedAtMs = now
        return info
    }

    /**
     * 读取供电侧节点，选信息量最大的那个。
     * 本机实测 usb 与 ucsi 都是 ONLINE=1，但只有 usb 带 INPUT_CURRENT_LIMIT / TEMP，
     * 所以不能简单取「第一个 online 的」。
     */
    private fun readCharger(): ChargerInfo? {
        val names = shellExec("ls /sys/class/power_supply/", SHELL_TIMEOUT_MS)
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() && !it.equals("battery", ignoreCase = true) }
            ?: emptyList()
        if (names.isEmpty()) return null

        val scored = names.mapNotNull { name ->
            val p = "/sys/class/power_supply/$name/uevent"
            val raw = shellExec("cat '$p'", SHELL_TIMEOUT_MS)
                ?: oneShot("$SU cat '$p'")?.takeIf { it.exitCode == 0 }?.output
            if (raw.isNullOrBlank()) return@mapNotNull null
            val v = UeventParser.parse(raw)
            if (v.isEmpty()) return@mapNotNull null
            Triple(name, v, scoreCharger(v))
        }

        val best = scored.maxByOrNull { it.third } ?: return null
        return UeventParser.toCharger(best.first, best.second)
    }

    private fun scoreCharger(v: Map<String, String>): Int {
        var score = 0
        if (v["POWER_SUPPLY_ONLINE"] == "1") score += 100
        for (key in CHARGER_SCORE_KEYS) if (v.containsKey(key)) score += 1
        return score
    }

    /** 常驻 shell 执行；shell 未就绪时返回 null，由调用方降级。 */
    private fun shellExec(cmd: String, timeoutMs: Long): String? {
        if (!shell.ensureStarted()) return null
        return shell.exec(cmd, timeoutMs)
    }

    private fun classifyFailure(output: String): MonitorErrorType {
        val lower = output.lowercase()
        return when {
            "permission denied" in lower -> MonitorErrorType.PERMISSION_DENIED
            "selinux" in lower || "avc" in lower || "denied" in lower -> MonitorErrorType.SELINUX_BLOCK
            "no such file" in lower || "not found" in lower -> MonitorErrorType.BATTERY_FILE_NOT_FOUND
            else -> MonitorErrorType.COMMAND_FAILURE
        }
    }

    /**
     * 一次性命令（降级路径 + 自检用）。
     * 超时后不再调用 exitValue()、也不再去 readText()，避免异常与永久阻塞。
     */
    private fun oneShot(command: String, timeoutSeconds: Long = 10L): CommandResult? {
        var process: Process? = null
        return try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(-1, "命令超时（${timeoutSeconds}s）")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            CommandResult(process.exitValue(), output)
        } catch (t: Throwable) {
            runCatching { process?.destroyForcibly() }
            CommandResult(-1, t.message ?: t::class.java.simpleName)
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private companion object {
        val PREFERRED_NAMES = listOf(
            "battery", "bms", "BAT0", "BAT1", "maxfg", "main", "batt", "bms0"
        )
        val CHARGER_SCORE_KEYS = listOf(
            "POWER_SUPPLY_USB_TYPE",
            "POWER_SUPPLY_VOLTAGE_NOW",
            "POWER_SUPPLY_CURRENT_NOW",
            "POWER_SUPPLY_CURRENT_MAX",
            "POWER_SUPPLY_INPUT_CURRENT_LIMIT",
            "POWER_SUPPLY_TEMP",
            "POWER_SUPPLY_VOLTAGE_MAX",
            "POWER_SUPPLY_TYPE"
        )

        /** 高频路径超时：0.2s 刷新下必须够快，卡住就跳过这一拍 */
        const val FAST_TIMEOUT_MS = 1500L
        const val SHELL_TIMEOUT_MS = 3000L

        /** 供电节点变化很慢，5 秒刷一次足够 */
        const val CHARGER_TTL_MS = 5000L
    }
}

/**
 * uevent 解析：POWER_SUPPLY_* 的键值对 → 结构化快照。
 *
 * 单位换算（内核 power_supply 约定）：
 *   TEMP            0.1 °C   → ÷10
 *   VOLTAGE_*       µV       → ÷1000 = mV
 *   CURRENT_*       µA       → ÷1000 = mA
 *   POWER_*         µW       → ÷1000 = mW
 *   CHARGE_FULL/_DESIGN  µAh → ÷1000 = mAh
 *   TIME_TO_*       秒；-1 与 0xFFFF(65535) 均表示未知
 */
private object UeventParser {

    fun parse(raw: String): Map<String, String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.split('=', limit = 2) }
            .filter { it.size == 2 }
            .associate { it[0] to it[1] }

    fun toSnapshot(
        v: Map<String, String>,
        charger: ChargerInfo?,
        sourcePath: String?
    ): BatterySnapshot = BatterySnapshot(
        timestampMs = System.currentTimeMillis(),
        sourcePath = sourcePath,

        name = v["POWER_SUPPLY_NAME"],
        type = v["POWER_SUPPLY_TYPE"],
        status = v["POWER_SUPPLY_STATUS"],
        health = v["POWER_SUPPLY_HEALTH"],
        present = int(v, "POWER_SUPPLY_PRESENT")?.let { it != 0 },
        technology = v["POWER_SUPPLY_TECHNOLOGY"],
        modelName = v["POWER_SUPPLY_MODEL_NAME"],
        chargeType = v["POWER_SUPPLY_CHARGE_TYPE"],

        levelPercent = int(v, "POWER_SUPPLY_CAPACITY"),

        temperatureCelsius = temp(v, "POWER_SUPPLY_TEMP"),
        voltageNowMv = scaled(v, "POWER_SUPPLY_VOLTAGE_NOW", 1000),
        voltageOcvMv = scaled(v, "POWER_SUPPLY_VOLTAGE_OCV", 1000),
        voltageMaxMv = scaled(v, "POWER_SUPPLY_VOLTAGE_MAX", 1000),
        currentNowMa = scaled(v, "POWER_SUPPLY_CURRENT_NOW", 1000),
        powerNowMw = scaled(v, "POWER_SUPPLY_POWER_NOW", 1000),
        powerAvgMw = scaled(v, "POWER_SUPPLY_POWER_AVG", 1000),

        chargeFullMah = scaled(v, "POWER_SUPPLY_CHARGE_FULL", 1000),
        chargeFullDesignMah = scaled(v, "POWER_SUPPLY_CHARGE_FULL_DESIGN", 1000),
        chargeCounterRaw = int(v, "POWER_SUPPLY_CHARGE_COUNTER"),
        cycleCount = int(v, "POWER_SUPPLY_CYCLE_COUNT"),

        timeToFullSeconds = seconds(v, "POWER_SUPPLY_TIME_TO_FULL_NOW")
            ?: seconds(v, "POWER_SUPPLY_TIME_TO_FULL_AVG"),
        timeToEmptySeconds = seconds(v, "POWER_SUPPLY_TIME_TO_EMPTY_NOW")
            ?: seconds(v, "POWER_SUPPLY_TIME_TO_EMPTY_AVG"),

        chargeControlLimit = int(v, "POWER_SUPPLY_CHARGE_CONTROL_LIMIT"),
        chargeControlLimitMax = int(v, "POWER_SUPPLY_CHARGE_CONTROL_LIMIT_MAX"),
        constantChargeCurrentMa = scaled(v, "POWER_SUPPLY_CONSTANT_CHARGE_CURRENT", 1000),

        charger = charger,
        raw = v
    )

    fun toCharger(node: String, v: Map<String, String>): ChargerInfo = ChargerInfo(
        node = v["POWER_SUPPLY_NAME"] ?: node,
        type = v["POWER_SUPPLY_TYPE"],
        online = int(v, "POWER_SUPPLY_ONLINE")?.let { it != 0 },
        usbType = v["POWER_SUPPLY_USB_TYPE"],
        voltageNowMv = scaled(v, "POWER_SUPPLY_VOLTAGE_NOW", 1000),
        currentNowMa = scaled(v, "POWER_SUPPLY_CURRENT_NOW", 1000),
        currentMaxMa = scaled(v, "POWER_SUPPLY_CURRENT_MAX", 1000),
        inputCurrentLimitMa = scaled(v, "POWER_SUPPLY_INPUT_CURRENT_LIMIT", 1000),
        temperatureCelsius = temp(v, "POWER_SUPPLY_TEMP")
    )

    private fun int(v: Map<String, String>, key: String): Int? =
        v[key]?.trim()?.toIntOrNull()

    private fun scaled(v: Map<String, String>, key: String, divisor: Int): Int? =
        int(v, key)?.let { kotlin.math.round(it.toDouble() / divisor).toInt() }

    private fun temp(v: Map<String, String>, key: String): Double? =
        int(v, key)?.let { it / 10.0 }

    /** 内核用 -1 / 0xFFFF 表示「未知」，必须过滤，否则会显示成 65535 秒。 */
    private fun seconds(v: Map<String, String>, key: String): Long? {
        val n = v[key]?.trim()?.toLongOrNull() ?: return null
        return if (n <= 0 || n >= 65535) null else n
    }
}
