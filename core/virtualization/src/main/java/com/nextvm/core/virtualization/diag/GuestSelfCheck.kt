package com.nextvm.core.virtualization.diag

import android.app.Application
import com.nextvm.core.common.diagnostics.DiagChannel
import com.nextvm.core.common.diagnostics.DiagFiles
import com.nextvm.core.hook.JavaIoHooks
import com.nextvm.core.hook.NativeHookBridge
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import java.io.File

/**
 * guest 进程内的自检：用**这个进程自己的**规则表与诊断文件跑一遍 [ContainerSelfCheck]。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/diag/GuestSelfCheck.md`
 */
internal object GuestSelfCheck {

    /** 由 `VirtualApplicationManager` 在首次 `onActivityResumed` 且存在请求文件时调用；**仅 debug 包**。 */
    fun run(nativeHookBridge: NativeHookBridge, app: Application) {
        if (!com.nextvm.core.common.DebugFlags.isDebugBuild()) return
        val probe = object : PathProbe {
            override fun add(fromPrefix: String, toPrefix: String) =
                nativeHookBridge.addPathRedirection(fromPrefix, toPrefix)
            override fun remove(fromPrefix: String) =
                nativeHookBridge.removePathRedirection(fromPrefix)
            override fun translate(path: String) = nativeHookBridge.translatePath(path)
            override fun redirectionCount() = nativeHookBridge.getRedirectionCount()
            override fun nativeAvailable() = nativeHookBridge.isNativeAvailable()
            override fun ruleSnapshot() = nativeHookBridge.getPathRedirectSnapshot()
            override fun javaIoStats() = JavaIoStats.stats()
            override fun javaIoInstalled() = JavaIoStats.installed()
        }
        val checker = ContainerSelfCheck(
            paths = probe,
            // 写 guest 自己的 cacheDir：自检不留痕迹，也不进用户可见目录
            writeRoot = File(app.cacheDir, "selfcheck"),
            // guest 侧读不到主进程的 path_spoof.conf（路径会被翻译），S6 记 skipped
            confText = null,
            diagFile = File(app.filesDir, DiagFiles.DEBUG_TRACE),
            confRequired = false,
        )
        val checks = checker.run()
        val line = checker.report(checks)
        runCatching {
            val ch = DiagChannel(File(app.filesDir, DiagFiles.CHECKS))
            ch.append("S guestselfcheck $line\n")
            checks.forEach { c ->
                ch.append("S guestselfcheck.${c.id} ${if (c.ok) "ok" else "FAIL"} ${c.detail}\n")
            }
        }
        GuestBreadcrumb.log("guestselfcheck $line")
    }

    /** 把 JavaIoHooks 的静态入口收在一处，便于测试替换。 */
    private object JavaIoStats {
        fun stats(): String? = JavaIoHooks.stats()
        fun installed(): Boolean = JavaIoHooks.installed
    }
}
