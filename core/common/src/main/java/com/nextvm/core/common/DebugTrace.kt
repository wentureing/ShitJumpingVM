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
 * 实现方式：用 **Timber 的 Tree 扩展点**（项目 8 个模块已依赖 Timber），
 * 而不是自己写 appendText 散落各处 —— 这样：
 *   - 分级、tag、异常打印都由 Timber 统一处理
 *   - 需要时可以再 plant 别的 Tree（例如转发到 logcat / 上传），不用改插桩代码
 *   - 只在可调试包才 plant，release 不产生文件
 *
 * 插桩约定：
 *   - [event]  关键事件（阶段切换、hook 安装结果、规则数量）——始终写
 *   - [fail]   失败（失败是排查的第一线索）——始终写
 *   - [detail] 高频细节（每次目录方法调用等）——仅 `debug.nextvm.verbose=1` 时写
 */
object DebugTrace {

    private const val MAX_BYTES = 1_500_000L
    private const val LOG_NAME = "debug-trace.log"

    @Volatile
    private var fileTree: FileLogTree? = null

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
        Timber.tag("Dbg/$tag").i(message)
    }

    /** 高频细节（仅 verbose）。 */
    fun detail(tag: String, message: String) {
        if (!DebugFlags.isOn(DebugFlags.VERBOSE, false)) return
        Timber.tag("Dbg/$tag").d(message)
    }

    /** 失败事件（始终写）。 */
    fun fail(tag: String, message: String, throwable: Throwable? = null) {
        if (!DebugFlags.isDebugBuild()) return
        Timber.tag("Dbg/$tag").e(throwable, message)
    }

    /**
     * 文件 Tree：单文件、超限截断、按行追加。
     * 只由 [init] 在可调试包下 plant，release 永远不会构造它。
     */
    private class FileLogTree(val file: File) : Timber.Tree() {

        private val lock = Any()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val line = buildString {
                append('[').append(System.currentTimeMillis()).append("] [")
                append(levelLetter(priority)).append("] [").append(tag ?: "-").append("] ")
                append(message)
                append('\n')
                if (t != null) {
                    append("    ").append(t.javaClass.name).append(": ").append(t.message)
                    append('\n')
                }
            }
            synchronized(lock) {
                try {
                    if (file.length() > MAX_BYTES) file.writeText("")
                    file.appendText(line)
                } catch (_: Throwable) {
                }
            }
        }

        private fun levelLetter(priority: Int): String = when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            else -> "?"
        }
    }
}
