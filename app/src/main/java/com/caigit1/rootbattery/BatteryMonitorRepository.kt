package com.caigit1.rootbattery

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class BatteryMonitorRepository(
    private val reader: RootBatteryReader = RootBatteryReader()
) {
    suspend fun selfCheck(): List<SelfCheckItem> = reader.selfCheck()

    suspend fun refreshOnce(): MonitorResult = reader.readBatteryUevent()

    fun close() = reader.close()

    /**
     * 按给定间隔持续采样。
     *
     * 两点讲究：
     *  - **扣掉读取耗时**再 delay，否则 0.2s 的设定会变成「0.2s + 读取耗时」，高频档位尤其明显
     *  - 读取失败时退避到 [retryDelayMs]，避免高频失败把 logcat 刷爆、也避免空转烧电
     */
    fun poll(intervalMs: Long, retryDelayMs: Long = 2000L): Flow<MonitorResult> = flow {
        while (true) {
            val started = System.currentTimeMillis()
            val result = reader.readBatteryUevent()
            emit(result)

            val target = if (result is MonitorResult.Success) {
                intervalMs
            } else {
                maxOf(intervalMs, retryDelayMs)
            }
            val elapsed = System.currentTimeMillis() - started
            delay((target - elapsed).coerceAtLeast(MIN_DELAY_MS))
        }
    }

    private companion object {
        const val MIN_DELAY_MS = 20L
    }
}
