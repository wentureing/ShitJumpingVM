package com.nextvm.core.common.diagnostics

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 诊断文件的唯一落盘出口：调用方只给「通道名 + 内容」，不关心目录/锁/上限。
 *
 * 镜像文档：`docs/代码镜像/core/common/diagnostics/DiagOutput.md`
 */
object DiagOutput {

    @Volatile
    private var dir: File? = null

    private val channels = ConcurrentHashMap<String, DiagChannel>()

    /** 绑定当前诊断目录（guest bind 时调用；切换目录会清空通道缓存）。 */
    fun setDir(d: File?) {
        if (dir == d) return
        channels.clear()
        dir = d
    }

    /** 当前诊断目录；未绑定时返回 null。 */
    fun dir(): File? = dir

    /** 是否已绑定目录（未绑定 = 等价 release，直接丢弃）。 */
    fun isReady(): Boolean = dir != null

    /** 追加一段文本到具名通道；未绑定目录或写失败返回 false。 */
    fun append(
        name: String,
        text: String,
        maxBytes: Long = DiagChannel.DEFAULT_MAX_BYTES,
    ): Boolean {
        val d = dir ?: return false
        return appendIn(d, name, text, maxBytes)
    }

    /** 追加一行（自动补换行）。 */
    fun line(
        name: String,
        text: String,
        maxBytes: Long = DiagChannel.DEFAULT_MAX_BYTES,
    ): Boolean = append(name, text + "\n", maxBytes)

    /**
     * 写入**显式目录**（主进程替某个 guest 写诊断时用），不影响全局 [dir]。
     */
    fun appendIn(
        dir: File,
        name: String,
        text: String,
        maxBytes: Long = DiagChannel.DEFAULT_MAX_BYTES,
    ): Boolean {
        val ch = channels.computeIfAbsent(dir.path + "/" + name) {
            dir.mkdirs()
            DiagChannel(File(dir, name), maxBytes)
        }
        return ch.append(text)
    }

    /** 写入显式目录的一行。 */
    fun lineIn(
        dir: File,
        name: String,
        text: String,
        maxBytes: Long = DiagChannel.DEFAULT_MAX_BYTES,
    ): Boolean = appendIn(dir, name, text + "\n", maxBytes)

    /** 覆盖写二进制/短文本（maps、dex 这类一次性快照）。 */
    fun overwrite(name: String, text: String): Boolean {
        val d = dir ?: return false
        return try {
            d.mkdirs()
            File(d, name).writeText(text)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 当前大小（供诊断/探针核对）；未绑定返回 0。 */
    fun size(name: String): Long {
        val d = dir ?: return 0L
        return channels[d.path + "/" + name]?.size()
            ?: try {
                File(d, name).takeIf { it.exists() }?.length() ?: 0L
            } catch (_: Throwable) {
                0L
            }
    }

    /** 仅测试用：清空通道缓存与目录。 */
    fun reset() {
        channels.clear()
        dir = null
    }
}
