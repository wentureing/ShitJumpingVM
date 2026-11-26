package com.nextvm.core.virtualization.process

/**
 * 一次「打开 guest」的完整决策（纯数据）：走哪条路、发哪个 stub、要不要登记/释放租约。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/process/LaunchPlanner.md`
 */
data class LaunchPlan(
    val slot: Int,
    val decision: SessionDecision,
    /** 要启动的 stub 组件；`BRING_TO_FRONT` 时为 null（不启动任何组件）。 */
    val stubClass: String?,
    val targetActivity: String,
    /** 新会话：需要把 stub 标记占用并登记租约。 */
    val registerLease: Boolean,
    /** 该丢掉租约（进程已不在，别复用死会话）。 */
    val dropLease: Boolean,
)

/** planner 的输入（全部是纯数据，不含 Android 类型）。 */
data class LaunchInput(
    val instanceId: String,
    val packageName: String,
    val slot: Int,
    val mainActivity: String,
    val launchMode: String,
    val sessionReuseEnabled: Boolean,
    val lease: SlotLease?,
    val status: SlotStatus?,
    /** 该 slot/launchMode 的候选 stub（按优先级；空 = 池耗尽）。 */
    val stubCandidates: List<String>,
)

/** planner 的输出。 */
sealed class LaunchOutcome {
    data class Planned(val plan: LaunchPlan) : LaunchOutcome()
    data class Failed(val message: String) : LaunchOutcome()
}

/**
 * 纯判定：输入「实例 + 租约 + 当前状态 + 候选 stub」→ 输出可执行的 [LaunchPlan]。
 *
 * 没有任何 Android 依赖，因此这一层可以在纯 JVM 里覆盖"复用/沿用/新会话/池耗尽"。
 */
object LaunchPlanner {

    fun plan(input: LaunchInput): LaunchOutcome {
        val decision = SessionReusePolicy.decide(
            enabled = input.sessionReuseEnabled,
            lease = input.lease,
            status = input.status,
        )
        val reuseStub = input.lease?.stubClassName?.takeIf { it.isNotBlank() }
        return when (decision) {
            SessionDecision.BRING_TO_FRONT -> LaunchOutcome.Planned(
                LaunchPlan(
                    slot = input.slot,
                    decision = decision,
                    stubClass = null,
                    targetActivity = input.mainActivity,
                    registerLease = false,
                    dropLease = false,
                )
            )

            SessionDecision.REUSE_STUB -> {
                val stub = reuseStub ?: input.stubCandidates.firstOrNull()
                    ?: return LaunchOutcome.Failed(noStubMessage(input))
                LaunchOutcome.Planned(
                    LaunchPlan(
                        slot = input.slot,
                        decision = decision,
                        stubClass = stub,
                        targetActivity = input.mainActivity,
                        registerLease = false,
                        dropLease = false,
                    )
                )
            }

            SessionDecision.NEW_SESSION -> {
                val stub = input.stubCandidates.firstOrNull()
                    ?: return LaunchOutcome.Failed(noStubMessage(input))
                LaunchOutcome.Planned(
                    LaunchPlan(
                        slot = input.slot,
                        decision = decision,
                        stubClass = stub,
                        targetActivity = input.mainActivity,
                        registerLease = true,
                        // 只有"有租约可丢"时才算 drop：lease==null 时 release 本就是 no-op，
                        // 但不该对外表达"丢了租约"（P3 固定的行为）
                        dropLease = input.lease != null &&
                            SessionReusePolicy.shouldDropLease(decision, input.status),
                    )
                )
            }
        }
    }

    /** 池耗尽时的错误文案（与引擎日志逐字一致，`checks.log` 里能直接 grep）。 */
    fun noStubMessage(input: LaunchInput): String =
        "No available stub for process slot ${input.slot}" +
            " (instance=${input.instanceId} mode=${input.launchMode})"
}
