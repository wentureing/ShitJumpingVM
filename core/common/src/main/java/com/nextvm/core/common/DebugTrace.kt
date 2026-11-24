package com.nextvm.core.common

import java.io.File
import timber.log.Timber

/**
 * 统一调试输出（v145）—— 覆盖关键路径，落盘而非只打 logcat。
 *
 * 为什么必须落盘：实测本设备（vivo）会吞掉**非默认 uid** 进程的 `Log.i`
 * （容器每次干净安装 uid 都会变），logcat 里拿不到 guest 的任何输出。
 * 文件是唯一可靠通道。
 *
 * ## guest 侧的两个特殊处理（v146）
 *
 * 1. **init 之前的事件不丢**：guest 最早的 hook 安装/规则注册发生在
 *    `getOrCreateClassLoader` 阶段，那时还拿不到可写的 filesDir。
 *    这些事件先进入内存缓冲（上限 [MAX_PENDING] 行），`init` 后一次性刷盘。
 * 2. **单一通道**：所有事件最终都走 `Timber`（文件 Tree），
 *    这样既有的 `Timber.tag(...).i(...)` 日志也会被同一份文件收集，
 *    不需要在每个类里改成 DebugTrace。
 *
 * ## release 安全
 *
 * 只有 `DebugFlags.ENABLED`（= `BuildConfig.DEBUG`）为真才会 plant 文件 Tree；
 * release 里 `event/detail/fail` 第一行就返回，且会被 R8 删除。
 */
object DebugTrace {

    private const val MAX_BYTES = 1_500_000L
    private const val LOG_NAME = "debug-trace.log"
    private const val MAX_PENDING = 800

    @Volatile
    private var fileTree: FileLogTree? = null

    /** init 前的事件缓冲（guest 早期关键路径）。 */
    private val pending = ArrayDeque<String>()

    /** 挂载/卸载文件输出。[filesDir] 为 null 时卸载（release 就是这种情况）。 */
    fun init(filesDir: File?) {
        if (filesDir == null) {
            fileTree?.let { Timber.uproot(it) }
            fileTree = null
            return
        }
        if (fileTree != null) return
        try {
            if (!filesDir.exists()) filesDir.mkdirs()
            val tree = FileLogTree(File(filesDir, LOG_NAME))
            // 先把 init 之前缓冲的事件刷进文件
            synchronized(pending) {
                pending.forEach { tree.appendRaw(it) }
                pending.clear()
            }
            fileTree = tree
            Timber.plant(tree)
        } catch (_: Throwable) {
            // 日志初始化失败绝不能影响主流程
        }
    }

    /** 当前输出文件路径（供诊断显示）。 */
    fun currentFile(): String? = fileTree?.file?.absolutePath

    /** 关键事件（始终写）。 */
    fun event(tag: String, message: String) {
        if (!DebugFlags.isDebugBuild()) return
        emit(android.util.Log.INFO, "Dbg/$tag", message, null)
    }

    /** 高频细节（仅 verbose）。 */
    fun detail(tag: String, message: String) {
        if (!DebugFlags.isOn(DebugFlags.VERBOSE, false)) return
        emit(android.util.Log.DEBUG, "Dbg/$tag", message, null)
    }

    /** 失败事件（始终写）。 */
    fun fail(tag: String, message: String, throwable: Throwable? = null) {
        if (!DebugFlags.isDebugBuild()) return
        emit(android.util.Log.ERROR, "Dbg/$tag", message, throwable)
    }

    /**
     * 单一出口：文件 Tree 还没挂上时先缓冲，挂上后走 Timber。
     */
    private fun emit(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val tree = fileTree
        if (tree == null) {
            val line = formatLine(priority, tag, message, throwable)
            synchronized(pending) {
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(line)
            }
            return
        }
        try {
            Timber.tag(tag).log(priority, throwable, message)
        } catch (_: Throwable) {
        }
    }

    private fun formatLine(
        priority: Int,
        tag: String,
        message: String,
        throwable: Throwable?
    ): String = buildString {
        append('[').append(System.currentTimeMillis()).append("] [")
        append(levelLetter(priority)).append("] [").append(processTag()).append("] [")
        append(tag).append("] ").append(message).append('\n')
        if (throwable != null) {
            append("    ").append(throwable.javaClass.name).append(": ")
            append(throwable.message).append('\n')
        }
    }

    private fun processTag(): String = try {
        val name = android.app.Application.getProcessName()
        if (name == null || name.isEmpty()) "?" else name.substringAfterLast(':')
    } catch (_: Throwable) {
        "?"
    }

    private fun levelLetter(priority: Int): String = when (priority) {
        android.util.Log.VERBOSE -> "V"
        android.util.Log.DEBUG -> "D"
        android.util.Log.INFO -> "I"
        android.util.Log.WARN -> "W"
        android.util.Log.ERROR -> "E"
        else -> "?"
    }

    /**
     * 文件 Tree：单文件、超限截断、按行追加。
     * 只由 [init] 在可调试包下 plant，release 永远不会构造它。
     */
    private class FileLogTree(val file: File) : Timber.Tree() {

        private val lock = Any()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            appendRaw(formatLine(priority, tag ?: "-", message, t))
        }

        /** 直接追加一整行（含换行），供缓冲刷盘复用。 */
        fun appendRaw(line: String) {
            synchronized(lock) {
                try {
                    if (file.length() > MAX_BYTES) file.writeText("")
                    file.appendText(line)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
