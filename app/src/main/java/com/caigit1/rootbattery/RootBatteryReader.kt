package com.caigit1.rootbattery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val SU = "su -c"

/**
 * 只读读取电池 uevent（不写入 sysfs）。
 *
 * 本文件修复了原实现的四个缺陷：
 *
 *  1. 【权限错配 → 误报"路径不存在"】所有文件探测都放到 root 上下文执行。
 *     App 自身 UID 在 Android 10+ 受 SELinux 限制访问 /sys，`File.exists()` 会返回 false，
 *     从而把「root 其实能读」误判成「路径不存在」。
 *
 *  2. 【自检自相矛盾】"路径存在"与"是否可读"必须出自同一权限环境。
 *
 *  3. 【超时崩溃】命令超时后绝不调用 exitValue()——进程未退出时它抛
 *     IllegalThreadStateException，会把自检/刷新直接打崩。
 *
 *  4. 【路径硬编码】枚举 /sys/class/power_supply 逐个探测，兼容 bms / Battery / BAT0 等命名。
 *
 * 另：除 battery 节点外，还会读取供电侧节点（usb / wireless / ucsi）获取充电器信息。
 */
class RootBatteryReader {

    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val r = runCommand("$SU id")
        r.exitCode == 0 && r.output.contains("uid=0")
    }

    suspend fun readBatteryUevent(): MonitorResult = withContext(Dispatchers.IO) {
        // 顺序很重要：先确认 root，绝不能用 App 权限先探测文件
        val root = runCommand("$SU id")
        if (root.exitCode != 0 || !root.output.contains("uid=0")) {
            return@withContext MonitorResult.Error(
                MonitorError(MonitorErrorType.NO_ROOT, root.output.ifBlank { "未获得 root 权限" })
            )
        }

        val entries = listPowerSupply()
        val path = resolveBatteryPath(entries)
            ?: return@withContext MonitorResult.Error(
                MonitorError(
                    MonitorErrorType.BATTERY_FILE_NOT_FOUND,
                    "/sys/class/power_supply 下未找到可读的电池 uevent 节点"
                )
            )

        val response = runCommand("$SU cat '$path'")
        if (response.exitCode != 0) {
            return@withContext MonitorResult.Error(
                MonitorError(classifyFailure(response.output), response.output.ifBlank { "读取失败：$path" })
            )
        }

        val parsed = UeventParser.parse(response.output)
        if (parsed.isEmpty()) {
            return@withContext MonitorResult.Error(
                MonitorError(MonitorErrorType.EMPTY_RESPONSE, "读取成功但内容为空：$path")
            )
        }

        MonitorResult.Success(
            UeventParser.toSnapshot(parsed, readCharger(entries), path)
        )
    }

    /**
     * 环境自检。
     * 三项结论全部出自 root 上下文，保证不会互相矛盾。
     */
    suspend fun selfCheck(): List<SelfCheckItem> = withContext(Dispatchers.IO) {
        val idOut = runCommand("$SU id")
        val rootGranted = idOut.exitCode == 0 && idOut.output.contains("uid=0")

        if (!rootGranted) {
            val why = idOut.output.ifBlank {
                "su 不可用或未授权（KernelSU 请在管理器中为本应用授予 root）"
            }
            return@withContext listOf(
                SelfCheckItem("Root 可用", false, why),
                SelfCheckItem("电池节点", false, "root 不可用，无法探测 /sys/class/power_supply"),
                SelfCheckItem("节点可读", false, "root 不可用，无法读取")
            )
        }

        val entries = listPowerSupply()
        val resolved = resolveBatteryPath(entries)
        val stat = resolved?.let { runCommand("$SU ls -l '$it'") }
        val readable = resolved?.let {
            val r = runCommand("$SU cat '$it'")
            r.exitCode == 0 && r.output.contains("POWER_SUPPLY_")
        } ?: false
        val charger = readCharger(entries)

        listOf(
            SelfCheckItem(
                "Root 可用",
                true,
                idOut.output.lineSequence().firstOrNull()?.trim().orEmpty()
            ),
            SelfCheckItem(
                "电池节点",
                resolved != null,
                resolved ?: "候选：${entries.joinToString().ifBlank { "（空）" }}"
            ),
            SelfCheckItem(
                "节点可读",
                readable,
                stat?.output?.lineSequence()?.firstOrNull()?.trim() ?: "未能定位节点，无法 stat"
            ),
            SelfCheckItem(
                "供电节点",
                charger != null,
                charger?.let { "${it.node}（${it.onlineText}，${it.type ?: "?"}）" }
                    ?: "未发现 usb / wireless 等供电节点"
            )
        )
    }

    private fun listPowerSupply(): List<String> =
        runCommand("$SU ls /sys/class/power_supply/")
            .output.split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    /**
     * 读取供电侧节点：优先 online=1 的（正在供电的那个），否则取第一个带 TYPE 的。
     */
    private fun readCharger(entries: List<String>): ChargerInfo? {
        val candidates = entries.filterNot { it.equals("battery", ignoreCase = true) }
        if (candidates.isEmpty()) return null

        val read = candidates.mapNotNull { name ->
            val p = "/sys/class/power_supply/$name/uevent"
            val r = runCommand("$SU cat '$p'")
            if (r.exitCode != 0) return@mapNotNull null
            val values = UeventParser.parse(r.output)
            if (values.isEmpty()) return@mapNotNull null
            name to values
        }

        if (read.isEmpty()) return null

        // 选信息量最大的节点，而不是"第一个 online 的"。
        // 本机实测：usb 与 ucsi 都是 ONLINE=1，但 usb 才带 INPUT_CURRENT_LIMIT / TEMP；
        // 之前只取第一个，导致"输入限流""充电器温度"两行为空。
        val best = read.maxByOrNull { (_, v) -> scoreCharger(v) } ?: return null
        return UeventParser.toCharger(best.first, best.second)
    }

    private fun scoreCharger(v: Map<String, String>): Int {
        var score = 0
        if (v["POWER_SUPPLY_ONLINE"] == "1") score += 100
        for (key in CHARGER_SCORE_KEYS) if (v.containsKey(key)) score += 1
        return score
    }

    /**
     * 在 root 上下文里定位真正的电池 uevent 节点。
     * 先按常见命名优先试，再遍历其余条目；判定标准是内容里出现 POWER_SUPPLY_ 前缀键。
     */
    private fun resolveBatteryPath(knownEntries: List<String>? = null): String? {
        val names = knownEntries ?: listPowerSupply()
        if (names.isEmpty()) return null

        val preferred = PREFERRED_NAMES.flatMap { want ->
            names.filter { it.equals(want, ignoreCase = true) }
        }
        val rest = names.filterNot { n -> PREFERRED_NAMES.any { it.equals(n, ignoreCase = true) } }

        for (name in (preferred + rest).distinct()) {
            val candidate = "/sys/class/power_supply/$name/uevent"
            val r = runCommand("$SU cat '$candidate'")
            if (r.exitCode == 0 && r.output.contains("POWER_SUPPLY_")) return candidate
        }
        return null
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
     * 执行只读命令。
     * 关键：超时后不再调用 exitValue()（会抛异常），也不再去 readText()（会永久阻塞）。
     */
    private fun runCommand(command: String, timeoutSeconds: Long = 10L): CommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(-1, "命令超时（${timeoutSeconds}s）：$command")
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
 *   CHARGE_FULL/_DESIGN/_COUNTER  µAh → ÷1000 = mAh
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
        int(v, key)?.let { (it.toDouble() / divisor).roundToIntSafe() }

    private fun temp(v: Map<String, String>, key: String): Double? =
        int(v, key)?.let { it / 10.0 }

    /** 内核用 -1 / 0xFFFF 表示"未知"，必须过滤掉，否则会显示成 18 小时或 65535 秒。 */
    private fun seconds(v: Map<String, String>, key: String): Long? {
        val n = v[key]?.trim()?.toLongOrNull() ?: return null
        return if (n <= 0 || n >= 65535) null else n
    }

    private fun Double.roundToIntSafe(): Int =
        kotlin.math.round(this).toInt()
}
