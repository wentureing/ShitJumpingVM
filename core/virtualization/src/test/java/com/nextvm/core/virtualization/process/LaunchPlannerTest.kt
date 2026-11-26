package com.nextvm.core.virtualization.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LaunchPlanner` 的行为铆钉（P 系列）：一次 launch 的纯决策。
 *
 * 说明：docs/代码镜像/core/virtualization/process/LaunchPlanner.md
 */
class LaunchPlannerTest {

    private fun lease(
        slot: Int = 1,
        instanceId: String = "inst-a",
        stub: String = "com.nextvm.app.stub.P1\$Standard00",
    ) = SlotLease(slot, instanceId, stub, acquiredAtMs = 1L)

    private fun status(
        slot: Int = 1,
        state: SlotState = SlotState.RUNNING,
        taskId: Int = 0,
        instanceId: String? = "inst-a",
    ) = SlotStatus(slot = slot, state = state, instanceId = instanceId, taskId = taskId)

    private fun input(
        lease: SlotLease? = null,
        status: SlotStatus? = null,
        enabled: Boolean = true,
        candidates: List<String> = listOf(
            "com.nextvm.app.stub.P1\$Standard01",
            "com.nextvm.app.stub.P1\$Standard02",
        ),
        slot: Int = 1,
        mainActivity: String = "mark.via.Shell",
        launchMode: String = "standard",
    ) = LaunchInput(
        instanceId = "inst-a",
        packageName = "mark.via",
        slot = slot,
        mainActivity = mainActivity,
        launchMode = launchMode,
        sessionReuseEnabled = enabled,
        lease = lease,
        status = status,
        stubCandidates = candidates,
    )

    private fun planOf(i: LaunchInput): LaunchPlan =
        (LaunchPlanner.plan(i) as LaunchOutcome.Planned).plan

    // ---------------- P1-P3 三条主路径 ----------------

    @Test
    fun `P1 活会话有 task 时切前台且不碰 stub 池`() {
        val p = planOf(input(lease = lease(), status = status(taskId = 42)))

        assertEquals(SessionDecision.BRING_TO_FRONT, p.decision)
        assertNull("切前台不能分配 stub", p.stubClass)
        assertFalse("切前台不登记新租约", p.registerLease)
        assertFalse(p.dropLease)
        assertEquals("mark.via.Shell", p.targetActivity)
    }

    @Test
    fun `P2 进程在但没界面时沿用租约里的 stub`() {
        val p = planOf(
            input(
                lease = lease(stub = "com.nextvm.app.stub.P1\$Standard03"),
                status = status(taskId = 0),
            )
        )

        assertEquals(SessionDecision.REUSE_STUB, p.decision)
        assertEquals("com.nextvm.app.stub.P1\$Standard03", p.stubClass)
        assertFalse("沿用不重复登记租约", p.registerLease)
        assertFalse(p.dropLease)
    }

    @Test
    fun `P3 无租约时开新会话并登记`() {
        val p = planOf(input())

        assertEquals(SessionDecision.NEW_SESSION, p.decision)
        assertEquals("com.nextvm.app.stub.P1\$Standard01", p.stubClass)
        assertTrue("新会话要登记租约", p.registerLease)
        assertFalse("没有租约可丢", p.dropLease)
    }

    // ---------------- P4 STALE ----------------

    @Test
    fun `P4 租约在但进程没了 新会话并丢租约`() {
        val p = planOf(input(lease = lease(), status = status(state = SlotState.STALE)))

        assertEquals(SessionDecision.NEW_SESSION, p.decision)
        assertTrue("STALE 必须丢租约", p.dropLease)
        assertTrue(p.registerLease)
    }

    // ---------------- P5-P6 池耗尽 ----------------

    @Test
    fun `P5 池耗尽时失败且文案带 instance 与 mode`() {
        val outcome = LaunchPlanner.plan(input(candidates = emptyList()))
        assertTrue(outcome is LaunchOutcome.Failed)
        assertEquals(
            "No available stub for process slot 1 (instance=inst-a mode=standard)",
            (outcome as LaunchOutcome.Failed).message,
        )
    }

    @Test
    fun `P6 STALE 且池耗尽时也失败（不能误登记）`() {
        val outcome = LaunchPlanner.plan(
            input(lease = lease(), status = status(state = SlotState.STALE), candidates = emptyList())
        )
        assertTrue(outcome is LaunchOutcome.Failed)
    }

    // ---------------- P7 开关 ----------------

    @Test
    fun `P7 开关关闭时即使会话活着也开新会话 但不丢活租约`() {
        val p = planOf(
            input(
                lease = lease(),
                status = status(taskId = 42),
                enabled = false,
            )
        )

        assertEquals(SessionDecision.NEW_SESSION, p.decision)
        assertFalse("开关关闭时租约仍活着，不能丢", p.dropLease)
        assertEquals("com.nextvm.app.stub.P1\$Standard01", p.stubClass)
    }

    // ---------------- P8 脏租约兜底 ----------------

    @Test
    fun `P8 租约里 stub 为空时回退到候选`() {
        val p = planOf(
            input(lease = lease(stub = ""), status = status(taskId = 0))
        )
        assertEquals(SessionDecision.REUSE_STUB, p.decision)
        assertEquals("空 stub 不能用，回退到候选首个", "com.nextvm.app.stub.P1\$Standard01", p.stubClass)
    }

    // ---------------- P9 文案唯一来源 ----------------

    @Test
    fun `P9 noStubMessage 与引擎日志格式一致`() {
        val i = input(candidates = emptyList(), slot = 7, launchMode = "singleTop")
        assertEquals(
            "No available stub for process slot 7 (instance=inst-a mode=singleTop)",
            LaunchPlanner.noStubMessage(i),
        )
    }
}
