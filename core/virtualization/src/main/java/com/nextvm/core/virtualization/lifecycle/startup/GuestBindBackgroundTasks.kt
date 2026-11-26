package com.nextvm.core.virtualization.lifecycle.startup

import com.nextvm.core.virtualization.lifecycle.DexMemDump
import com.nextvm.core.virtualization.lifecycle.StateProbe
import com.nextvm.core.virtualization.lifecycle.WebViewWarmup
import com.nextvm.core.virtualization.lifecycle.WebViewWatch
import java.io.File

/**
 * bind 完成后的 4 个后台观察任务（框架路径与 legacy 路径共用同一顺序）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/GuestBindBackgroundTasks.md`
 */
object GuestBindBackgroundTasks {

    fun scheduleObservers(packageName: String, filesDir: File) {
        DexMemDump.schedule(packageName, filesDir)
        StateProbe.schedule(packageName, filesDir)
        WebViewWatch.schedule(packageName, filesDir)
        WebViewWarmup.schedule(packageName, filesDir)
    }
}
