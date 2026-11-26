package com.nextvm.core.common.diagnostics

import java.io.File

/**
 * 单文件诊断通道：追加、超限保留尾部（环形）、失败静默。
 *
 * 镜像文档：`docs/代码镜像/core/common/diagnostics/DiagChannel.md`
 */
class DiagChannel(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keepTail: Boolean = true,
) {

    private val lock = Any()

    /** 追加一段文本；返回是否写入成功（任何 IO 失败都吞掉并返回 false）。 */
    fun append(text: String): Boolean = synchronized(lock) {
        try {
            rotateIfNeeded(text.length)
            file.appendText(text)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 当前文件大小（不存在返回 0），供诊断/探针使用。 */
    fun size(): Long = try {
        if (file.exists()) file.length() else 0L
    } catch (_: Throwable) {
        0L
    }

    private fun rotateIfNeeded(incoming: Int) {
        val len = file.length()
        if (len + incoming <= maxBytes) return
        // 单条就超过上限：轮转也留不下别的，直接追加（宁可超限也不丢这条）。
        if (incoming >= maxBytes) return
        if (!keepTail) {
            file.writeText("")
            return
        }
        val keep = (maxBytes / KEEP_DIVISOR).toInt().coerceAtLeast(1)
        val tail = if (len > 0) file.readText().takeLast(keep) else ""
        file.writeText(ROTATE_MARK + System.currentTimeMillis() + " ###\n" + tail)
    }

    companion object {
        /** 单文件上限（沿用 debug-trace.log 的历史阈值）。 */
        const val DEFAULT_MAX_BYTES: Long = 1_500_000L

        /** 超限后保留尾部 `1/KEEP_DIVISOR`。 */
        const val KEEP_DIVISOR: Int = 4

        /** 轮转标记行前缀。 */
        const val ROTATE_MARK: String = "### rotated @"
    }
}
