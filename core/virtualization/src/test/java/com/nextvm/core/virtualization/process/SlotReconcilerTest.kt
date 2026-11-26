package com.nextvm.core.virtualization.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SlotReconciler` 的纯逻辑铆钉（S 系列）。
 *
 * 说明：docs/代码镜像/core/virtualization/process/SlotReconciler.md
 */
class SlotReconcilerTest {

    private fun lease(
        slot: Int,
        instanceId: String,
        stub: String = "com.nextvm.app.stub.P${slot}\$Standard00",
        at: Long = 1_000L,
    ) = SlotLease(slot, instanceId, stub, at)

    private fun proc(slot: Int, pid: Int) = ProcSnapshot(slot, pid)

    private fun task(
        taskId: Int,
        slot: Int,
        instanceId: String? = null,
        numActivities: Int = 1,
        top: String = "",
    ) = TaskSnapshot(taskId, slot, instanceId, numActivities, top)

    // ---------------- S1-S3 单租约 ----------------

    @Test
    fun `S1 租约在 进程在 任务在 判 RUNNING`() {
        val plan = SlotReconciler.reconcile(
            leases = listOf(lease(1, "inst-a")),
            procs = listOf(proc(1, 4242)),
            tasks = listOf(task(77, 1, "inst-a", top = "com.nextvm.app.stub.P1\$Standard00")),
            records = listOf(RecordSnapshot("inst-a", "mark.via")),
        )
        val st = plan.statuses.single()
        assertEquals(SlotState.RUNNING, st.state)
        assertEquals(1, st.slot)
        assertEquals("inst-a", st.instanceId)
        assertEquals("mark.via", st.packageName)
        assertEquals(4242, st.pid)
        assertEquals(77, st.taskId)
        assertEquals("", st.note)
        assertTrue(plan.releaseLeases.isEmpty())
        assertTrue(plan.releaseStubsForSlots.isEmpty())
        assertTrue(plan.markNotRunning.isEmpty())
        assertTrue(plan.orphans.isEmpty())
    }

    @Test
    fun `S2 有租约 无进程 判 STALE 并要求归还`() {
        val l = lease(1, "inst-a")
        val plan = SlotReconciler.reconcile(
            leases = listOf(l),
            procs = emptyList(),
            tasks = emptyList(),
        )
        val st = plan.statuses.single()
        assertEquals(SlotState.STALE, st.state)
        assertEquals("stale: no process", st.note)
        assertEquals(listOf(l), plan.releaseLeases)
        assertEquals(listOf(1), plan.releaseStubsForSlots)
        assertEquals(listOf("inst-a"), plan.markNotRunning)
    }

    @Test
    fun `S3 有租约 有进程 无任务 仍 RUNNING 且不归还`() {
        val plan = SlotReconciler.reconcile(
            leases = listOf(lease(2, "inst-b")),
            procs = listOf(proc(2, 555)),
            tasks = listOf(task(9, 2, "inst-b", numActivities = 0)),
        )
        val st = plan.statuses.single()
        assertEquals(SlotState.RUNNING, st.state)
        assertEquals("no-task", st.note)
        assertEquals(555, st.pid)
        assertTrue(plan.releaseLeases.isEmpty())
        assertTrue(plan.releaseStubsForSlots.isEmpty())
    }

    // ---------------- S4 无租约有进程 ----------------

    @Test
    fun `S4 无租约 有进程 判 ORPHAN 且不自动清理`() {
        val plan = SlotReconciler.reconcile(
            leases = emptyList(),
            procs = listOf(proc(3, 777)),
            tasks = emptyList(),
        )
        val st = plan.statuses.single()
        assertEquals(SlotState.ORPHAN, st.state)
        assertEquals(777, st.pid)
        assertEquals(1, plan.orphans.size)
        assertTrue("ORPHAN 不得进入归还清单", plan.releaseLeases.isEmpty())
        assertTrue("ORPHAN 不得进入归还清单", plan.releaseStubsForSlots.isEmpty())
        assertTrue("ORPHAN 不得清 isRunning", plan.markNotRunning.isEmpty())
    }

    // ---------------- S5 同 slot 两实例 ----------------

    @Test
    fun `S5 同 slot 两实例 判 CONFLICT 并保留最近者`() {
        val old = lease(1, "inst-old", at = 100L)
        val new = lease(1, "inst-new", at = 900L)
        val plan = SlotReconciler.reconcile(
            leases = listOf(old, new),
            procs = listOf(proc(1, 123)),
            tasks = listOf(task(5, 1, "inst-new")),
        )
        assertEquals(2, plan.statuses.size)

        val conflict = plan.statuses.first { it.instanceId == "inst-old" }
        assertEquals(SlotState.CONFLICT, conflict.state)
        assertTrue(conflict.note.contains("inst-new"))

        val running = plan.statuses.first { it.instanceId == "inst-new" }
        assertEquals(SlotState.RUNNING, running.state)
    }

    // ---------------- S6-S7 空槽与归还粒度 ----------------

    @Test
    fun `S6 空闲槽不出现在列表里`() {
        val plan = SlotReconciler.reconcile(
            leases = listOf(lease(4, "inst-d")),
            procs = listOf(proc(4, 1)),
            tasks = emptyList(),
            maxSlots = 10,
        )
        assertEquals(1, plan.statuses.size)
        assertEquals(4, plan.statuses.single().slot)
    }

    @Test
    fun `S7 STALE 的归还清单只含该 slot`() {
        val plan = SlotReconciler.reconcile(
            leases = listOf(lease(1, "a"), lease(2, "b"), lease(5, "c")),
            procs = listOf(proc(2, 20), proc(5, 50)),
            tasks = emptyList(),
        )
        assertEquals(listOf(1), plan.releaseStubsForSlots)
        assertEquals(listOf("a"), plan.markNotRunning)
        assertEquals(1, plan.releaseLeases.size)
        assertEquals(3, plan.statuses.size)
    }

    // ---------------- S8 任务归属不符 ----------------

    @Test
    fun `S8 任务归属与租约不符 判 CONFLICT`() {
        val plan = SlotReconciler.reconcile(
            leases = listOf(lease(1, "inst-a")),
            procs = listOf(proc(1, 11)),
            tasks = listOf(task(31, 1, "inst-OTHER")),
        )
        val st = plan.statuses.single()
        assertEquals(SlotState.CONFLICT, st.state)
        assertTrue(st.note.contains("inst-OTHER"))
        assertTrue("CONFLICT 归还不得擅自动手", plan.releaseLeases.isEmpty())
    }

    // ---------------- S9-S10 边界与幂等 ----------------

    @Test
    fun `S9 maxSlots 边界 越界租约不入结果`() {
        val plan = SlotReconciler.reconcile(
            leases = listOf(lease(0, "a"), lease(9, "b"), lease(10, "c")),
            procs = listOf(proc(0, 1), proc(9, 2), proc(10, 3)),
            tasks = emptyList(),
            maxSlots = 10,
        )
        assertEquals(listOf(0, 9), plan.statuses.map { it.slot })
    }

    @Test
    fun `S10 同输入两次判定结果一致且按 slot 升序`() {
        val leases = listOf(lease(3, "c"), lease(1, "a"), lease(2, "b"))
        val procs = listOf(proc(3, 30), proc(1, 10))
        val tasks = emptyList<TaskSnapshot>()

        val first = SlotReconciler.reconcile(leases, procs, tasks)
        val second = SlotReconciler.reconcile(leases, procs, tasks)
        assertEquals(first, second)
        assertEquals(first.statuses.map { it.slot }, first.statuses.map { it.slot }.sorted())
        assertEquals(listOf(2), first.releaseStubsForSlots)
    }

    // ---------------- S11-S13 解析函数 ----------------

    @Test
    fun `S11 stub 类名解析 slot`() {
        assertEquals(0, SlotReconciler.parseSlotFromStubClass("com.nextvm.app.stub.P0\$Standard00"))
        assertEquals(3, SlotReconciler.parseSlotFromStubClass("com.nextvm.app.stub.P3\$S02"))
        assertEquals(9, SlotReconciler.parseSlotFromStubClass("com.nextvm.app.stub.P9\$CP00"))
        assertNull(SlotReconciler.parseSlotFromStubClass("mark.via.Shell"))
        assertNull(SlotReconciler.parseSlotFromStubClass("com.nextvm.app.stub.X1\$Standard00"))
        assertNull(SlotReconciler.parseSlotFromStubClass(null))
    }

    @Test
    fun `S12 进程名解析 slot`() {
        assertEquals(1, SlotReconciler.parseSlotFromProcessName("com.nextvm.app", "com.nextvm.app:p1"))
        assertEquals(9, SlotReconciler.parseSlotFromProcessName("com.nextvm.app", "com.nextvm.app:p9"))
        assertNull(SlotReconciler.parseSlotFromProcessName("com.nextvm.app", "com.nextvm.app"))
        assertNull(SlotReconciler.parseSlotFromProcessName("com.nextvm.app", "com.nextvm.app:x"))
        assertNull(SlotReconciler.parseSlotFromProcessName("com.nextvm.app", "other.app:p1"))
        assertNull(SlotReconciler.parseSlotFromProcessName("com.nextvm.app", null))
    }

    @Test
    fun `S13 多任务时取第一个有 Activity 的`() {
        val plan = SlotReconciler.reconcile(
            leases = listOf(lease(1, "inst-a")),
            procs = listOf(proc(1, 44)),
            tasks = listOf(
                task(100, 1, "inst-a", numActivities = 0),
                task(101, 1, "inst-a", numActivities = 2, top = "com.nextvm.app.stub.P1\$Standard01"),
            ),
        )
        val st = plan.statuses.single()
        assertEquals(101, st.taskId)
        assertEquals("com.nextvm.app.stub.P1\$Standard01", st.topComponent)
    }

    @Test
    fun `S14 同 slot 多次点击时 ORPHAN 只算一行并带 taskCount`() {
        // 现象：同一 guest 点 5 次 → 1 个 :p1 进程 + 多个 task（每个 stub 独立 affinity）
        val plan = SlotReconciler.reconcile(
            leases = emptyList(),
            procs = listOf(proc(1, 900)),
            tasks = listOf(
                task(201, 1, null),
                task(202, 1, null),
                task(203, 1, null),
                task(204, 1, null),
                task(205, 1, null),
            ),
        )
        val st = plan.statuses.single()
        assertEquals(SlotState.ORPHAN, st.state)
        assertEquals(900, st.pid)
        assertEquals("5 次点击仍只有 1 个孤儿（按 slot/进程计），task 数单独给出", 5, st.taskCount)
        assertEquals(1, plan.orphans.size)
    }

    @Test
    fun `S15 无 task 的孤儿 taskCount 为 0`() {
        val plan = SlotReconciler.reconcile(
            leases = emptyList(),
            procs = listOf(proc(2, 55)),
            tasks = emptyList(),
        )
        assertEquals(0, plan.statuses.single().taskCount)
    }

    @Test
    fun `S16 RUNNING 与 STALE 也带 taskCount`() {
        val running = SlotReconciler.reconcile(
            leases = listOf(lease(1, "a")),
            procs = listOf(proc(1, 10)),
            tasks = listOf(task(1, 1, "a"), task(2, 1, "a")),
        ).statuses.single()
        assertEquals(2, running.taskCount)

        val stale = SlotReconciler.reconcile(
            leases = listOf(lease(2, "b")),
            procs = emptyList(),
            tasks = listOf(task(3, 2, "b")),
        ).statuses.single()
        assertEquals(1, stale.taskCount)
    }

    @Test
    fun `S17 空 task 也计入 taskCount（它是残留）`() {
        val plan = SlotReconciler.reconcile(
            leases = emptyList(),
            procs = listOf(proc(1, 7)),
            tasks = listOf(
                task(11, 1, null, numActivities = 0),
                task(12, 1, null, numActivities = 3),
            ),
        )
        val st = plan.statuses.single()
        assertEquals(2, st.taskCount)
        assertEquals("判定用的仍是第一个有 Activity 的 task", 12, st.taskId)
    }

    @Test
    fun `S18 task 明细按 taskId 升序给出`() {
        val plan = SlotReconciler.reconcile(
            leases = emptyList(),
            procs = listOf(proc(1, 8)),
            tasks = listOf(
                task(205, 1, null, top = "P1\$Standard04"),
                task(201, 1, null, top = "P1\$Standard00"),
                task(203, 1, null, top = "P1\$Standard02"),
            ),
        )
        val st = plan.statuses.single()
        assertEquals(listOf(201, 203, 205), st.tasks.map { it.taskId })
        assertEquals("P1\$Standard00", st.tasks.first().topComponent)
        assertEquals(3, st.taskCount)
    }

    @Test
    fun `S19 不同 slot 的 task 不混在一起`() {
        val plan = SlotReconciler.reconcile(
            leases = emptyList(),
            procs = listOf(proc(1, 11), proc(2, 22)),
            tasks = listOf(task(301, 1, null), task(302, 2, null), task(303, 2, null)),
        )
        val p1 = plan.statuses.first { it.slot == 1 }
        val p2 = plan.statuses.first { it.slot == 2 }
        assertEquals(listOf(301), p1.tasks.map { it.taskId })
        assertEquals(listOf(302, 303), p2.tasks.map { it.taskId })
    }
}
