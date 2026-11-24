package com.nextvm.core.hook

import com.nextvm.core.common.DebugFlags
import com.nextvm.core.common.DebugTrace

/**
 * Java 层文件 I/O 重定向：用 RegisterNatives 替换 `libcore.io.Linux.open/access`。
 * 默认关闭；偏差与语义要求见 `docs/代码镜像/core/hook/JavaIoHooks.md`。
 */
object JavaIoHooks {

    @Volatile
    var installed: Boolean = false
        private set

    private external fun nativeInstall(translate: Boolean): Boolean

    private external fun nativeGetStats(): String?

    private external fun nativeResetStats()

    private external fun nativeGetRecent(): String?

    private external fun nativeClearRecent()

    /** 按开关安装；前提是 native 库可用且路径规则已注册。 */
    fun install(): Boolean {
        val enabled = DebugFlags.isOn(DebugFlags.JAVA_IO, false)
        val translate = DebugFlags.isOn(DebugFlags.JAVA_IO_TRANSLATE, true)
        if (!enabled) {
            DebugTrace.event("javaIo", "SKIPPED by debug.nextvm.javaIo=0")
            return false
        }
        val ok = try {
            nativeInstall(translate)
        } catch (t: Throwable) {
            DebugTrace.fail("javaIo", "nativeInstall threw", t)
            false
        }
        installed = ok
        DebugTrace.event("javaIo", "installed=$ok translate=$translate")
        return ok
    }

    /** `total=… fail=… translated=… translate=…`；不可用时返回 null。 */
    fun stats(): String? = try {
        nativeGetStats()
    } catch (_: Throwable) {
        null
    }

    /** 最近 N 条「失败 / 发生翻译」的 open|access 明细。 */
    fun recent(): String? = try {
        nativeGetRecent()
    } catch (_: Throwable) {
        null
    }

    fun reset() {
        try {
            nativeResetStats()
            nativeClearRecent()
        } catch (_: Throwable) {
        }
    }
}
