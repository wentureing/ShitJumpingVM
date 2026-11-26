package com.nextvm.core.virtualization.process

/**
 * 打开 guest 时该走哪条路（会话复用 / 沿用 stub / 新会话）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/process/SessionReusePolicy.md`
 */
enum class SessionDecision {
    /** 没有可用会话：按原来的"分配新 stub + startActivity"走。 */
    NEW_SESSION,

    /** 进程还在但界面没了（Activity 全 finish）：沿用租约里的 stub 重开，仍不新起进程。 */
    REUSE_STUB,

    /** 会话活着且有 task：先切前台，不分配 stub、不 startActivity。 */
    BRING_TO_FRONT,
}

/**
 * 纯判定：把"开关 + 租约 + 当前 slot 状态"映射成一个决策。
 *
 * `BRING_TO_FRONT` 失败时由调用方降级为 `REUSE_STUB`（见 [failedBringDecision]）。
 */
object SessionReusePolicy {

    fun decide(enabled: Boolean, lease: SlotLease?, status: SlotStatus?): SessionDecision = when {
        !enabled || lease == null -> SessionDecision.NEW_SESSION
        status == null -> SessionDecision.NEW_SESSION
        status.state != SlotState.RUNNING -> SessionDecision.NEW_SESSION
        status.taskId > 0 -> SessionDecision.BRING_TO_FRONT
        else -> SessionDecision.REUSE_STUB
    }

    /** 切前台失败（ROM 限制 / task 已消失）时的降级决策。 */
    fun failedBringDecision(): SessionDecision = SessionDecision.REUSE_STUB

    /** 该决策是否应把租约丢掉（只有"进程确实不在了"才丢）。 */
    fun shouldDropLease(decision: SessionDecision, status: SlotStatus?): Boolean =
        decision == SessionDecision.NEW_SESSION && status?.state != SlotState.RUNNING
}
