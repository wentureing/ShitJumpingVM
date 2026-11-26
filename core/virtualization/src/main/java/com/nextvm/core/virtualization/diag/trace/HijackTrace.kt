package com.nextvm.core.virtualization.diag.trace

import com.nextvm.core.common.diagnostics.DiagFiles
import com.nextvm.core.virtualization.diag.probe.HijackProbes

/**
 * 壳（hijack/SecNeo）SDK 初始化追踪：在 bind 之后 +1/+4/+12/+25s 采样并写
 * `hijacktrace-<pid>.log`，每进程一次、只对联通 guest。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/diag/trace/HijackTrace.md`
 */
object HijackTrace {

    private const val TARGET_PACKAGE = "com.sinovatech.unicom.ui"

    @Volatile
    private var scheduled = false

    /** 只对联通 guest 追踪（其它 guest 没有 hijack/SecNeo 那条初始化链）。 */
    fun isTarget(packageName: String): Boolean = packageName == TARGET_PACKAGE

    /**
     * @param stockInitNote v35 实验的注记（由 VAM 的 stock-init 换装状态决定）
     * @param stockInitExperiment v35 换装实验是否启用
     * @param unwrapEnabled v34 摘除实验是否启用（两者默认都关）
     */
    fun schedule(
        packageName: String,
        stockInitNote: String,
        stockInitExperiment: Boolean,
        unwrapEnabled: Boolean,
    ) {
        if (!isTarget(packageName)) return
        if (scheduled) return
        scheduled = true
        Thread {
            try {
                val hostApp = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                    as? android.app.Application ?: return@Thread
                val out = java.io.File(
                    hostApp.filesDir, DiagFiles.hijackTrace(android.os.Process.myPid())
                )
                val marks = listOf(
                    1_000L to "t+1s", 4_000L to "t+4s",
                    12_000L to "t+12s", 25_000L to "t+25s"
                )
                var elapsed = 0L
                for ((at, label) in marks) {
                    val wait = at - elapsed
                    if (wait > 0) Thread.sleep(wait)
                    elapsed = at
                    HijackProbes.sample(out, label, stockInitNote)
                    HijackProbes.unwrap(out, label, stockInitExperiment, unwrapEnabled)
                }
            } catch (_: Throwable) {
                // diagnostics must never disturb the guest
            }
        }.apply { isDaemon = true; name = "nv-hijacktrace" }.start()
    }
}
