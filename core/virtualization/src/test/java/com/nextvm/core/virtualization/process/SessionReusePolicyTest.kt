package com.nextvm.core.virtualization.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SessionReusePolicy` 的纯逻辑铆钉（R 系列）。
 *
 * 说明：docs/代码镜像/core/virtualization/process/SessionReusePolicy.md
 */
class SessionReusePolicyTest {

    private fun lease(slot: Int = 1, instanceId: String = "inst-a") =
        SlotLease(slot, instanceId, "com.nextvm.app.stub.P$slot\$Standard00", acquiredAtMs = 1L)

    private fun status(
        slot: Int = 1,
        state: SlotState = SlotState.RUNNING,
        taskId: Int = 0,
        instanceId: String? = "inst-a",
    ) = SlotStatus(slot = slot, state = state, instanceId = instanceId, taskId = taskId)

    // ---------------- R1-R3 开关与前置条件 ----------------

    @Test
    fun `R1 开关关闭时永远新会话`() {
        val d = SessionReusePolicy.decide(
            enabled = false,
            lease = lease(),
            status = status(taskId = 99),
        )
        assertEquals(SessionDecision.NEW_SESSION, d)
    }

    @Test
    fun `R2 没有租约时新会话`() {
        val d = SessionReusePolicy.decide(
            enabled = true,
            lease = null,
            status = status(taskId = 99),
        )
        assertEquals(SessionDecision.NEW_SESSION, d)
    }

    @Test
    fun `R3 探测不到状态时新会话`() {
        val d = SessionReusePolicy.decide(enabled = true, lease = lease(), status = null)
        assertEquals(SessionDecision.NEW_SESSION, d)
    }

    // ---------------- R4-R6 三种决策 ----------------

    @Test
    fun `R4 状态 RUNNING 且有 task 时切前台`() {
        val d = SessionReusePolicy.decide(
            enabled = true,
            lease = lease(),
            status = status(taskId = 42),
        )
        assertEquals(SessionDecision.BRING_TO_FRONT, d)
    }

    @Test
    fun `R5 进程在但没有 task 时沿用原 stub`() {
        val d = SessionReusePolicy.decide(
            enabled = true,
            lease = lease(),
            status = status(taskId = 0),
        )
        assertEquals(SessionDecision.REUSE_STUB, d)
    }

    @Test
    fun `R6 状态是 STALE 时新会话`() {
        val d = SessionReusePolicy.decide(
            enabled = true,
            lease = lease(),
            status = status(state = SlotState.STALE, taskId = 0),
        )
        assertEquals(SessionDecision.NEW_SESSION, d)
    }

    // ---------------- R7 降级 ----------------

    @Test
    fun `R7 切前台失败的降级是沿用原 stub 而不是新会话`() {
        // 失败后不能新分配 stub：那会落进另一个 affinity → 新 Task（正是本轮要修的现象）
        assertEquals(SessionDecision.REUSE_STUB, SessionReusePolicy.failedBringDecision())
    }

    // ---------------- R8 租约是否该丢 ----------------

    @Test
    fun `R8 只有进程确实不在了才丢租约`() {
        assertFalse(
            "RUNNING 时不能丢租约",
            SessionReusePolicy.shouldDropLease(
                SessionDecision.NEW_SESSION,
                status(state = SlotState.RUNNING),
            ),
        )
        assertTrue(
            "STALE 应丢",
            SessionReusePolicy.shouldDropLease(
                SessionDecision.NEW_SESSION,
                status(state = SlotState.STALE),
            ),
        )
        assertTrue(
            "探测不到也应丢（不复用死会话）",
            SessionReusePolicy.shouldDropLease(SessionDecision.NEW_SESSION, null),
        )
        assertFalse(
            "非 NEW_SESSION 决策不涉及丢租约",
            SessionReusePolicy.shouldDropLease(
                SessionDecision.REUSE_STUB,
                status(state = SlotState.STALE),
            ),
        )
    }
}
