package com.nextvm.core.common.diagnostics

import java.io.File

/**
 * 单文件诊断通道：追加、超限清空、失败静默。
 *
 * 把"怎么落盘"收到一处：各组件不再直接 `File(...).appendText(...)`，
 * 但**文件名与内容格式由调用方决定**（本类不改格式）。
 * 说明见 docs/输出/变更/20261125-common职责化与探针接入.md
 */
class DiagChannel(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val lock = Any()

    /** 追加一行（含换行由调用方决定）。超限时先清空；任何 IO 失败都吞掉。 */
    fun append(text: String) {
        synchronized(lock) {
            try {
                if (file.length() > maxBytes) file.writeText("")
                file.appendText(text)
            } catch (_: Throwable) {
                // 诊断失败绝不能影响主流程
            }
        }
    }

    /** 当前文件大小（不存在返回 0），供诊断/探针使用。 */
    fun size(): Long = try {
        if (file.exists()) file.length() else 0L
    } catch (_: Throwable) {
        0L
    }

    companion object {
        /** 与 DebugTrace 原有阈值保持一致（debug-trace.log 单文件 1.5 MB）。 */
        const val DEFAULT_MAX_BYTES: Long = 1_500_000L
    }
}
