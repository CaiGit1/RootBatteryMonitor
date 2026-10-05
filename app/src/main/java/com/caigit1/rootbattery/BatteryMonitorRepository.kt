package com.caigit1.rootbattery

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class BatteryMonitorRepository(
    private val reader: RootBatteryReader = RootBatteryReader()
) {
    suspend fun selfCheck(): List<SelfCheckItem> = reader.selfCheck()

    suspend fun refreshOnce(): MonitorResult = reader.readBatteryUevent()

    fun poll(intervalMs: Long, retryDelayMs: Long = 2000L): Flow<MonitorResult> = flow {
        while (true) {
            val result = reader.readBatteryUevent()
            emit(result)
            val delayMs = if (result is MonitorResult.Success) intervalMs else retryDelayMs
            delay(delayMs)
        }
    }
}
