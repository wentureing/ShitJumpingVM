package com.nextvm.core.common.diagnostics

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文件通道：异步批量写，避免在主线程/关键路径上做同步 I/O。
 * `error` 例外 —— 同步直写，宁可慢也不能丢。
 * 详见 `docs/设计/设计-诊断输出抽象层.md`。
 */
class FileSink(
    private val file: File,
    private val maxBytes: Long = 1_500_000L,
    private val flushMillis: Long = 200L,
    private val flushLines: Int = 64
) : DiagnosticsSink {

    private val queue = LinkedBlockingQueue<String>()
    private val started = AtomicBoolean(false)
    private val lock = Any()

    private fun ensureWorker() {
        if (!started.compareAndSet(false, true)) return
        val t = Thread({ runLoop() }, "nextvm-diag")
        t.isDaemon = true
        t.start()
    }

    private fun runLoop() {
        val batch = ArrayList<String>(flushLines)
        while (true) {
            try {
                val first = queue.poll(flushMillis, TimeUnit.MILLISECONDS)
                if (first == null) continue
                batch.clear()
                batch.add(first)
                queue.drainTo(batch, flushLines - 1)
                writeLines(batch)
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // 落盘失败不影响主流程
            }
        }
    }

    private fun writeLines(lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                if (file.length() > maxBytes) file.writeText("")
                file.appendText(lines.joinToString(""))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCheck(result: CheckResult, seq: Int) {
        ensureWorker()
        queue.offer(result.format(seq) + "\n")
    }

    override fun onError(tag: String, message: String, cause: Throwable?) {
        // 同步直写：错误不能丢
        writeLines(listOf("E $tag $message ${cause?.javaClass?.name ?: ""}\n"))
    }

    override fun onStage(tag: String, message: String) {
        ensureWorker()
        queue.offer("S $tag $message\n")
    }

    /** 进程退出前调用，尽量把队列刷完。 */
    fun flushNow() {
        val rest = ArrayList<String>()
        queue.drainTo(rest)
        writeLines(rest)
    }
}
