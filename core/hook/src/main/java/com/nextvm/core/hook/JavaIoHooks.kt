package com.nextvm.core.hook

import com.nextvm.core.common.DebugFlags
import com.nextvm.core.common.DebugTrace

/**
 * Java 层文件 I/O 重定向（v141 引入，v148 语义对齐）。
 *
 * ## 职责
 * - 用 `RegisterNatives` 替换 `libcore.io.Linux` 的 `open` / `access`，
 *   让 **Java 层**（`FileInputStream`/`FileOutputStream`/`FileChannel`…）的文件 I/O
 *   也走路径重定向。
 * - 提供安装结果、调用统计、最近异常记录的诊断输出。
 *
 * ## 不负责
 * - native 侧的 GOT/PLT patch（由 `NativeHookBridge` 负责）。
 * - 路径重定向**规则表**的维护（规则由 `NativeHookBridge.setupAppRedirections` /
 *   `setupExternalStorageRedirections` / `addPathRedirection` 管理；本类只消费 native 侧的表）。
 * - 进程判断（调用方 `initNativeHooks` 已在主进程提前 return）。
 *
 * ## 语义等价要求（v148）
 * 替换实现必须与 AOSP 的 `Linux_open` / `Linux_access` **行为一致**，否则会破坏真实应用：
 * - `open` 失败抛 `libcore.io.ErrnoException("open", errno)`（不是 FileNotFoundException）
 * - `access` 失败抛 `ErrnoException("access", errno)`（不是返回 false）
 * - 两者都要 EINTR 重试
 *
 * 反例见 `docs/输出/记录/记录-JavaIoHook导致Via不可交互.md`：v141 只对齐了成功路径，
 * 导致 Via 完全不可交互。
 */
object JavaIoHooks {

    /** 是否已成功安装（供诊断/探针读取）。 */
    @Volatile
    var installed: Boolean = false
        private set

    // ---- JNI（符号名与 native-hook.cpp 中的 Java_com_nextvm_core_hook_JavaIoHooks_* 对应）----

    private external fun nativeInstall(translate: Boolean): Boolean

    private external fun nativeGetStats(): String?

    private external fun nativeResetStats()

    private external fun nativeGetRecent(): String?

    private external fun nativeClearRecent()

    // ---- 对外 API ----

    /**
     * 按 `debug.nextvm.javaIo` / `debug.nextvm.javaIoTranslate` 安装。
     *
     * 前置条件：调用方已确认是 guest 进程，且 native 库可用；路径规则应已注册
     * （替换实现直接读 native 侧的 `g_path_redirects`）。
     *
     * @return 是否安装成功
     */
    fun install(): Boolean {
        val enabled = DebugFlags.isOn(DebugFlags.JAVA_IO, false)
        val translate = DebugFlags.isOn(DebugFlags.JAVA_IO_TRANSLATE, true)
        if (!enabled) {
            DebugTrace.event("javaIo", "SKIPPED by debug.nextvm.javaIo=0（等价于没有 v141）")
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

    /** 诊断：`total=… fail=… translated=… translate=…`；未安装/不可用时返回 null。 */
    fun stats(): String? = try {
        nativeGetStats()
    } catch (_: Throwable) {
        null
    }

    /** 诊断：最近 N 条「失败 / 发生翻译」的 open|access 明细（多行）。 */
    fun recent(): String? = try {
        nativeGetRecent()
    } catch (_: Throwable) {
        null
    }

    /** 重置统计与最近记录（guest 启动时调用，便于按次观察）。 */
    fun reset() {
        try {
            nativeResetStats()
            nativeClearRecent()
        } catch (_: Throwable) {
            // 未安装时忽略
        }
    }
}
