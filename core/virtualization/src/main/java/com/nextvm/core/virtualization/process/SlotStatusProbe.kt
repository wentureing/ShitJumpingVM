package com.nextvm.core.virtualization.process

import android.app.ActivityManager
import android.content.Context
import com.nextvm.core.model.VirtualIntentExtras

/**
 * 从系统取「真实在跑什么」（只读，不改任何容器状态）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/process/SlotStatusProbe.md`
 */
object SlotStatusProbe {

    /** 系统侧 `com.nextvm.app:pN` 进程（guest 进程的 argv0 被改过，系统记录不受影响）。 */
    fun runningProcessSlots(context: Context): List<ProcSnapshot> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        val host = context.packageName
        return runCatching {
            am.runningAppProcesses.orEmpty().mapNotNull { info ->
                val slot = SlotReconciler.parseSlotFromProcessName(host, info.processName)
                    ?: return@mapNotNull null
                ProcSnapshot(slot, info.pid)
            }
        }.getOrElse { emptyList() }
    }

    /** 容器自己的 task（含 `:pN` 进程里的）：只留能解析出 slot 的那些。 */
    fun appTasks(context: Context): List<TaskSnapshot> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        return runCatching {
            am.appTasks.orEmpty().mapNotNull { appTask ->
                val info = runCatching { appTask.taskInfo }.getOrNull() ?: return@mapNotNull null
                val base = runCatching { info.baseIntent }.getOrNull()
                val topClass = runCatching { info.topActivity?.className }.getOrNull()
                val baseClass = runCatching { base?.component?.className }.getOrNull()
                val slot = SlotReconciler.parseSlotFromStubClass(topClass)
                    ?: SlotReconciler.parseSlotFromStubClass(baseClass)
                    ?: return@mapNotNull null
                TaskSnapshot(
                    taskId = info.taskId,
                    slot = slot,
                    instanceId = runCatching {
                        base?.getStringExtra(VirtualIntentExtras.INSTANCE_ID)
                    }.getOrNull(),
                    numActivities = runCatching { info.numActivities }.getOrDefault(0),
                    topComponent = topClass ?: baseClass,
                )
            }
        }.getOrElse { emptyList() }
    }

    /** 一次取齐两份系统事实（UI/引擎入口用；任一失败返回空表，不抛）。 */
    fun snapshot(context: Context): Pair<List<ProcSnapshot>, List<TaskSnapshot>> =
        runningProcessSlots(context) to appTasks(context)
}
