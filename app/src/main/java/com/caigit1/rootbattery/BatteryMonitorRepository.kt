package com.caigit1.rootbattery

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 读取层：优先走 root 直读 sysfs，拿不到 root 时**自动降级**到系统公开 API。
 *
 * 模式判定用 [RootBatteryReader.hasRootAccess] 显式去问，**不靠错误类型推断** ——
 * 无 root 时 `su` 起不来，路径探测的结果是「节点不存在」而非「权限拒绝」，
 * 按错误类型判会误判成 ROM 差异，于是永远降不了级（实测踩过）。
 *
 * 判定结果只做一次并固定：既避免每拍都去试 root，也避免用户在已 root 机器上
 * 因为一次偶发读取失败就被悄悄换成字段更少的免 root 模式。
 */
class BatteryMonitorRepository(private val context: Context) {

    private val rootReader = RootBatteryReader()
    private val systemReader = SystemBatteryReader(context)

    /** null 表示尚未判定 */
    @Volatile
    private var useSystem: Boolean? = null

    val isSystemMode: Boolean get() = useSystem == true

    suspend fun selfCheck(): List<SelfCheckItem> =
        if (resolveSystemMode()) systemReader.selfCheck() else rootReader.selfCheck()

    suspend fun refreshOnce(): MonitorResult = read()

    fun close() {
        rootReader.close()
        systemReader.close()
    }

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
            val result = read()
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

    private suspend fun read(): MonitorResult =
        if (resolveSystemMode()) systemReader.readSnapshot() else rootReader.readBatteryUevent()

    /** 是否走免 root 模式；只判定一次 */
    private suspend fun resolveSystemMode(): Boolean {
        useSystem?.let { return it }
        val system = !rootReader.hasRootAccess()
        useSystem = system
        return system
    }

    private companion object {
        const val MIN_DELAY_MS = 20L
    }
}
