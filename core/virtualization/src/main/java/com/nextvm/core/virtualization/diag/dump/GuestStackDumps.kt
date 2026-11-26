package com.nextvm.core.virtualization.diag.dump

import com.nextvm.core.common.diagnostics.DiagFiles

/**
 * guest bind 完成后分批转储全线程栈（`stackdump-<pid>.log`），每进程只跑一次。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/diag/dump/GuestStackDumps.md`
 */
object GuestStackDumps {

    @Volatile
    private var scheduled = false

    /** 哪次 bind 的转储（驱动 late-marks 扩展）。 */
    @Volatile
    private var packageName: String? = null

    /** 采样节奏：联通 guest 在 base 之后追加 +40s/+80s/+160s（v68）。 */
    fun marksFor(pkg: String?): List<Pair<Long, String>> {
        val baseMarks = listOf(
            300L to "t+0.3s",
            700L to "t+0.7s",
            1_200L to "t+1.2s",
            2_000L to "t+2s",
            3_000L to "t+3s",
            8_000L to "t+8s",
            20_000L to "t+20s"
        )
        // v68: China Mobile's post-Agree stall sits INSIDE
        // ZWRisk.setup's native enumeration (launch90: main
        // RUNNABLE in Feinnhap.v at both t+8s and t+20s,
        // hundreds of supervisor-served readlinkat/fstatat
        // calls). Late marks answer the one open question —
        // guest-slow-but-finite vs never-returns — and show
        // what main does if setup ever completes.
        return if (pkg == "com.greenpoint.android.mc10086.activity") {
            baseMarks + listOf(
                40_000L to "t+40s",
                80_000L to "t+80s",
                160_000L to "t+160s"
            )
        } else {
            baseMarks
        }
    }

    fun schedule(pkg: String) {
        if (scheduled) return
        scheduled = true
        packageName = pkg
        Thread {
            try {
                val hostApp = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                    as? android.app.Application ?: return@Thread
                val out = java.io.File(
                    hostApp.filesDir, DiagFiles.stackDump(android.os.Process.myPid())
                )
                // Cadence (v29): logcat comparison against a real
                // device localized the guest's init-pipeline stall to
                // the first ~1.5s after process start (bind lands at
                // ~+0.45s), so sample densely across bind+0.3..+3s
                // to catch threads mid-flight at the stall moment;
                // +8s/+20s remain as steady-state anchors.
                val marks = marksFor(packageName)
                var elapsed = 0L
                for ((at, label) in marks) {
                    val wait = at - elapsed
                    if (wait > 0) Thread.sleep(wait)
                    elapsed = at
                    GuestMemoryDump.dumpGuestStacks(out, label)
                }
            } catch (_: Throwable) {
                // diagnostics must never disturb the guest
            }
        }.apply { isDaemon = true; name = "nv-stackdump" }.start()
    }
}
