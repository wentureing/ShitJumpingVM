package com.nextvm.core.virtualization.process

/**
 * process slot 的观测状态（只读快照）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/process/SlotStatus.md`
 */
enum class SlotState {
    /** 无租约、无进程。 */
    FREE,

    /** 有租约、进程在（可能没有 Activity）。 */
    RUNNING,

    /** 有租约、进程已不在 —— 需要回收。 */
    STALE,

    /** 无租约、进程在 —— 只报告，不自动清理。 */
    ORPHAN,

    /** 同一 slot 上出现多实例 / 任务归属与租约不一致。 */
    CONFLICT,
}

/** 一行 slot 状态（UI 直接渲染，不含任何 Android 对象）。 */
data class SlotStatus(
    val slot: Int,
    val state: SlotState,
    val instanceId: String? = null,
    val packageName: String? = null,
    val pid: Int = 0,
    val taskId: Int = 0,
    val topComponent: String? = null,
    val note: String = "",
    /** 该 slot 在系统里残留的 task 总数（同一 slot 多次点击会产生多个）。 */
    val taskCount: Int = 0,
)

/** 系统侧进程快照（`com.nextvm.app:pN` → slot + pid）。 */
data class ProcSnapshot(val slot: Int, val pid: Int)

/** 容器自己的任务快照（task 归属哪个 guest 由 stub 类名 / extras 解析）。 */
data class TaskSnapshot(
    val taskId: Int,
    val slot: Int? = null,
    val instanceId: String? = null,
    val numActivities: Int = 0,
    val topComponent: String? = null,
)

/** VAMS/引擎侧已知实例（用于补 packageName）。 */
data class RecordSnapshot(val instanceId: String, val packageName: String? = null)

/** reconcile 结果：UI 要显示什么 + 引擎要清理什么。 */
data class ReconcilePlan(
    val statuses: List<SlotStatus> = emptyList(),
    val releaseLeases: List<SlotLease> = emptyList(),
    val releaseStubsForSlots: List<Int> = emptyList(),
    val markNotRunning: List<String> = emptyList(),
    val orphans: List<SlotStatus> = emptyList(),
)
