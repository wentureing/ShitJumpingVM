package com.nextvm.core.virtualization.process

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `SlotUsage.countInUse` 的行为铆钉（U 系列）。
 *
 * 说明：docs/代码镜像/core/virtualization/process/SlotUsage.md
 */
class SlotUsageTest {

    private fun st(slot: Int, state: SlotState, instanceId: String? = null) =
        SlotStatus(slot = slot, state = state, instanceId = instanceId)

    @Test
    fun `U1 RUNNING 与 ORPHAN 都算占用`() {
        assertEquals(
            2,
            SlotUsage.countInUse(
                listOf(
                    st(1, SlotState.RUNNING, "a"),
                    st(2, SlotState.ORPHAN),
                )
            )
        )
    }

    @Test
    fun `U2 STALE 与 FREE 不算占用`() {
        assertEquals(
            1,
            SlotUsage.countInUse(
                listOf(
                    st(1, SlotState.STALE, "a"),
                    st(3, SlotState.RUNNING, "b"),
                )
            )
        )
        assertEquals(0, SlotUsage.countInUse(listOf(st(1, SlotState.STALE, "a"))))
    }

    @Test
    fun `U3 同一 slot 多条记录只算一次`() {
        // CONFLICT + RUNNING 同 slot（L2 现场）：仍是一个被占用的槽
        assertEquals(
            1,
            SlotUsage.countInUse(
                listOf(
                    st(1, SlotState.CONFLICT, "old"),
                    st(1, SlotState.RUNNING, "new"),
                )
            )
        )
        // 同一 slot 两个 ORPHAN 也是 1（按进程计）
        assertEquals(
            1,
            SlotUsage.countInUse(
                listOf(
                    st(1, SlotState.ORPHAN),
                    st(1, SlotState.ORPHAN),
                )
            )
        )
    }

    @Test
    fun `U4 空表是 0`() {
        assertEquals(0, SlotUsage.countInUse(emptyList()))
    }

    @Test
    fun `U5 五个 task 的同一个孤儿只算一个 slot`() {
        // 用户实测：同一 guest 点 5 次 → 1 个 :p1 进程 + 5 个 task，计数不应是 5
        assertEquals(
            1,
            SlotUsage.countInUse(
                listOf(
                    SlotStatus(
                        slot = 1,
                        state = SlotState.ORPHAN,
                        pid = 900,
                        taskCount = 5,
                    )
                )
            )
        )
    }
}
