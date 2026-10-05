package com.caigit1.rootbattery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private const val BATTERY_UEVENT_PATH = "/sys/class/power_supply/battery/uevent"

class RootBatteryReader {

    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        runCommand("su -c id").output.contains("uid=0")
    }

    suspend fun readBatteryUevent(): MonitorResult = withContext(Dispatchers.IO) {
        if (!File(BATTERY_UEVENT_PATH).exists()) {
            return@withContext MonitorResult.Error(
                MonitorError(MonitorErrorType.BATTERY_FILE_NOT_FOUND, "未找到电池 uevent 路径")
            )
        }

        val rootAvailable = hasRootAccess()
        if (!rootAvailable) {
            return@withContext MonitorResult.Error(
                MonitorError(MonitorErrorType.NO_ROOT, "未获得 root 权限")
            )
        }

        val response = runCommand("su -c cat $BATTERY_UEVENT_PATH")
        if (response.exitCode != 0) {
            return@withContext MonitorResult.Error(
                MonitorError(classifyFailure(response.output), response.output.ifBlank { "读取失败" })
            )
        }

        val parsed = UeventParser.parse(response.output)
        if (parsed.isEmpty()) {
            return@withContext MonitorResult.Error(
                MonitorError(MonitorErrorType.EMPTY_RESPONSE, "读取成功但内容为空")
            )
        }

        MonitorResult.Success(UeventParser.toSnapshot(parsed))
    }

    suspend fun selfCheck(): List<SelfCheckItem> = withContext(Dispatchers.IO) {
        val rootOutput = runCommand("su -c id")
        val rootGranted = rootOutput.output.contains("uid=0")
        val fileExists = File(BATTERY_UEVENT_PATH).exists()
        val readResult = runCommand("su -c ls -l $BATTERY_UEVENT_PATH")

        listOf(
            SelfCheckItem("Root 可用", rootGranted, rootOutput.output.ifBlank { "未检测到 root" }),
            SelfCheckItem("uevent 路径存在", fileExists, BATTERY_UEVENT_PATH),
            SelfCheckItem("uevent 可读", readResult.exitCode == 0, readResult.output.ifBlank { "读取权限异常" })
        )
    }

    private fun classifyFailure(output: String): MonitorErrorType {
        val lower = output.lowercase()
        return when {
            "permission denied" in lower -> MonitorErrorType.PERMISSION_DENIED
            "selinux" in lower || "avc" in lower -> MonitorErrorType.SELINUX_BLOCK
            else -> MonitorErrorType.COMMAND_FAILURE
        }
    }

    private fun runCommand(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor(4, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText().trim()
            CommandResult(process.exitValue(), output)
        } catch (t: Throwable) {
            CommandResult(-1, t.message.orEmpty())
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)
}

private object UeventParser {
    fun parse(raw: String): Map<String, String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map {
                val split = it.split('=', limit = 2)
                split[0] to split[1]
            }
            .toMap()
    }

    fun toSnapshot(values: Map<String, String>): BatterySnapshot {
        val now = System.currentTimeMillis()
        return BatterySnapshot(
            timestampMs = now,
            levelPercent = values["POWER_SUPPLY_CAPACITY"]?.toIntOrNull(),
            temperatureCelsius = values["POWER_SUPPLY_TEMP"]?.toDoubleOrNull()?.div(10.0),
            voltageMillivolts = values["POWER_SUPPLY_VOLTAGE_NOW"]?.toIntOrNull()?.div(1000),
            status = values["POWER_SUPPLY_STATUS"],
            health = values["POWER_SUPPLY_HEALTH"],
            raw = values
        )
    }
}
