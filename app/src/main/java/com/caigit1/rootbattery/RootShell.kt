package com.caigit1.rootbattery

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 常驻 root shell。
 *
 * 为什么必须要有它：把刷新间隔降到 0.2s 后，如果每次刷新都 fork 一个 `su -c cat ...`，
 * 相当于每秒 5 次进程创建 + 5 次走 KernelSU 的授权链路，开销和耗电都不可接受，
 * 高频刷新本身也就失去了意义。
 *
 * 这里维持**单个** `su` 进程：通过 stdin 下发命令，用哨兵串界定一次输出的结束。
 * 输出用后台线程泵进阻塞队列，从而在 readLine() 上实现超时，避免永久阻塞。
 */
class RootShell(private val command: String = "su") {

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var pump: Thread? = null
    private val queue = LinkedBlockingQueue<String>()
    private val lock = Any()

    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * 启动或复用 shell。
     * 首次调用会触发 KernelSU / Magisk 的授权弹窗，因此超时给得比较宽。
     */
    fun ensureStarted(timeoutMs: Long = 8000): Boolean = synchronized(lock) {
        if (isAlive()) return true
        return try {
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            process = p
            stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
            queue.clear()

            pump = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).useLines { seq ->
                        seq.forEach { queue.offer(it) }
                    }
                } catch (_: Throwable) {
                    // 进程结束即退出；isAlive() 会随之变为 false
                }
            }.apply { isDaemon = true; start() }

            // 预热：确认 shell 真的可交互（授权未通过时这里会超时失败）
            exec("echo __READY__", timeoutMs)?.contains("__READY__") == true
        } catch (_: Throwable) {
            close()
            false
        }
    }

    /**
     * 执行命令，返回输出（不含哨兵行）。
     * 超时、shell 已死或异常时返回 null，由调用方决定是否降级到一次性命令。
     */
    fun exec(cmd: String, timeoutMs: Long = 3000): String? = synchronized(lock) {
        val w = stdin ?: return null
        if (!isAlive()) return null

        val marker = "__EOC_${System.nanoTime()}__"
        return try {
            w.write(cmd); w.write("\n")
            w.write("echo $marker"); w.write("\n")
            w.flush()

            val sb = StringBuilder()
            var out: String? = null
            val deadline = System.currentTimeMillis() + timeoutMs

            while (out == null) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                val line = queue.poll(remain, TimeUnit.MILLISECONDS) ?: break
                if (line.trim() == marker) {
                    out = sb.toString()
                    break
                }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
            out
        } catch (_: Throwable) {
            close()
            null
        }
    }

    fun close() = synchronized(lock) {
        runCatching { stdin?.close() }
        runCatching { process?.destroyForcibly() }
        stdin = null
        process = null
    }
}
