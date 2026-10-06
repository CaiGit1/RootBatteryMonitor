package com.caigit1.rootbattery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * 免 root 读取：只用 Android 公开 API。
 *
 * 与 [RootBatteryReader] 的取舍：
 *  - 拿得到：电量、温度、电压、电流（自算功率）、充电状态、充电类型、健康状态、
 *    循环次数（Android 14+）、电荷计数、技术
 *  - **拿不到**：充电协议（`POWER_SUPPLY_USB_TYPE` 属内核私有节点）、
 *    满电/设计容量（因此没有健康度百分比）、供电侧节点、原始 uevent
 *
 * 数据从 `ACTION_BATTERY_CHANGED` 这条 sticky 广播取 —— 系统会一直保留最后一次，
 * 无需注册接收器，`registerReceiver(null, filter)` 即可拿到。电流/电荷计数走
 * [BatteryManager.getIntProperty]，低温时部分机型会返回 `Integer.MIN_VALUE`，
 * 这里一律当作「无数据」而不是照抄，否则界面上会出现一个荒谬的负值。
 */
class SystemBatteryReader(private val context: Context) {

    fun close() {
        // 无需释放资源：没有常驻进程，也没有注册接收器
    }

    fun selfCheck(): List<SelfCheckItem> = buildList {
        add(SelfCheckItem("运行模式", true, "免 root（系统公开 API）"))
        val intent = batteryIntent()
        add(
            SelfCheckItem(
                "电量广播",
                intent != null,
                if (intent != null) "可读" else "系统未提供 ACTION_BATTERY_CHANGED"
            )
        )
        val bm = context.getSystemService(BatteryManager::class.java)
        add(SelfCheckItem("BatteryManager", bm != null, if (bm != null) "可用" else "不可用"))
        val current = bm?.intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        add(
            SelfCheckItem(
                "电流属性",
                current != null,
                if (current != null) "可读" else "该机型未实现电流属性"
            )
        )
    }

    fun readSnapshot(): MonitorResult {
        val intent = batteryIntent()
            ?: return MonitorResult.Error(
                MonitorError(MonitorErrorType.EMPTY_RESPONSE, "系统未提供电池状态广播")
            )
        val bm = context.getSystemService(BatteryManager::class.java)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else null

        // EXTRA_TEMPERATURE 单位是 0.1 °C
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null

        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }

        val currentUa = bm?.intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = currentUa?.let { it / 1000 }

        val chargeCounterUah = bm?.intProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        val cycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
        } else {
            null
        }

        return MonitorResult.Success(
            BatterySnapshot(
                timestampMs = System.currentTimeMillis(),
                sourcePath = "BatteryManager（免 root）",
                source = DataSource.SYSTEM,
                status = statusText(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)),
                health = healthText(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)),
                present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
                chargeType = pluggedText(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)),
                levelPercent = levelPercent,
                temperatureCelsius = temperature,
                voltageNowMv = voltageMv,
                currentNowMa = currentMa,
                chargeCounterRaw = chargeCounterUah,
                cycleCount = cycleCount,
                raw = buildMap {
                    // 免 root 也能给出原始来源，便于对照；这些是系统属性而非内核节点
                    put("SOURCE", "BatteryManager")
                    put("EXTRA_LEVEL", level.toString())
                    put("EXTRA_SCALE", scale.toString())
                    put("EXTRA_TEMPERATURE", tempTenths.toString())
                    put("EXTRA_VOLTAGE", voltageMv?.toString() ?: "-")
                    put("CURRENT_NOW_uA", currentUa?.toString() ?: "-")
                    put("CHARGE_COUNTER_uAh", chargeCounterUah?.toString() ?: "-")
                }
            )
        )
    }

    /** sticky 广播：注册 null 接收器即可读到最后一次电量状态，不必常驻接收器 */
    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun BatteryManager.intProperty(id: Int): Int? =
        runCatching { getIntProperty(id) }
            .getOrNull()
            // 机型未实现该属性时返回 MIN_VALUE，不能当成真实读数
            ?.takeIf { it != Int.MIN_VALUE }

    private fun statusText(value: Int): String? = when (value) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "未知"
        else -> null
    }

    private fun healthText(value: Int): String? = when (value) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
        BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
        BatteryManager.BATTERY_HEALTH_COLD -> "温度过低"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "异常"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "未知"
        else -> null
    }

    private fun pluggedText(value: Int): String = when (value) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "底座"
        else -> "未连接"
    }
}
