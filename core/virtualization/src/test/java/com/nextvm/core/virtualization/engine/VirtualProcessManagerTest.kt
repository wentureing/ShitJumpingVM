package com.nextvm.core.virtualization.engine

import com.nextvm.core.model.VirtualConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VirtualProcessManager 的行为铆钉（纯 JVM，零 Android 依赖）。
 *
 * 守护槽位状态机：分配/复用/释放/PID 绑定/计数。槽位算错的表现是
 * **两个 guest 挤进同一个 `:pN` 进程**，或启动时拿不到槽 —— 都很难从现象反推。
 *
 * 说明：docs/输出/调研/调研-高频修改热点与测试优先级.md
 */
class VirtualProcessManagerTest {

    // ---------------- P1-P3 分配语义 ----------------

    @Test
    fun `P1 首个分配得到 slot 0`() {
        val pm = VirtualProcessManager()
        assertEquals(0, pm.allocateSlot("inst-a"))
        assertEquals(1, pm.getOccupiedCount())
        assertEquals(VirtualConstants.MAX_PROCESS_SLOTS - 1, pm.getFreeCount())
    }

    @Test
    fun `P2 同一 instance 重复分配返回同一槽`() {
        val pm = VirtualProcessManager()
        val first = pm.allocateSlot("inst-a")
        val again = pm.allocateSlot("inst-a")
        assertEquals("不得重复占用新槽", first, again)
        assertEquals(1, pm.getOccupiedCount())
    }

    @Test
    fun `P3 不同 instance 分到不同槽`() {
        val pm = VirtualProcessManager()
        val a = pm.allocateSlot("inst-a")
        val b = pm.allocateSlot("inst-b")
        assertTrue("两个实例必须不同槽", a != b)
        assertEquals(2, pm.getOccupiedCount())
    }

    // ---------------- P4 满槽 ----------------

    @Test
    fun `P4 槽位耗尽返回 -1`() {
        val pm = VirtualProcessManager(maxSlots = 2)
        assertEquals(0, pm.allocateSlot("a"))
        assertEquals(1, pm.allocateSlot("b"))
        assertEquals("无空槽", -1, pm.allocateSlot("c"))
        assertEquals(0, pm.getFreeCount())
    }

    // ---------------- P5-P6 释放 ----------------

    @Test
    fun `P5 释放后槽位可复用`() {
        val pm = VirtualProcessManager(maxSlots = 2)
        pm.allocateSlot("a")
        pm.allocateSlot("b")
        pm.releaseSlot(0)

        assertEquals(1, pm.getFreeCount())
        assertEquals("释放出的槽应被优先复用", 0, pm.allocateSlot("c"))
        assertEquals("c", pm.getInstanceForSlot(0))
        assertEquals("旧实例应已解绑", -1, pm.getSlotForInstance("a"))
    }

    @Test
    fun `P6 按 instance 释放`() {
        val pm = VirtualProcessManager()
        pm.allocateSlot("a")
        val slotB = pm.allocateSlot("b")
        pm.releaseSlotForInstance("b")

        assertEquals("b 已释放", -1, pm.getSlotForInstance("b"))
        assertEquals("a 仍在", 0, pm.getSlotForInstance("a"))
        assertEquals(1, pm.getOccupiedCount())
        assertEquals("释放的槽可再分配", slotB, pm.allocateSlot("c"))
    }

    @Test
    fun `P6b 释放越界或未占用 slot 不抛异常`() {
        val pm = VirtualProcessManager(maxSlots = 2)
        pm.releaseSlot(-1)
        pm.releaseSlot(2)
        pm.releaseSlotForInstance("not-there")
        assertEquals(0, pm.getOccupiedCount())
    }

    // ---------------- P7 查询与越界 ----------------

    @Test
    fun `P7 查询接口与越界返回`() {
        val pm = VirtualProcessManager(maxSlots = 2)
        pm.allocateSlot("a")

        assertEquals(0, pm.getSlotForInstance("a"))
        assertEquals(-1, pm.getSlotForInstance("missing"))
        assertEquals("a", pm.getInstanceForSlot(0))

        assertNull("越界 slot 查实例 -> null", pm.getInstanceForSlot(2))
        assertNull("负索引 -> null", pm.getInstanceForSlot(-1))
        assertEquals("槽位快照大小", 2, pm.getAllSlots().size)
    }

    // ---------------- P8 PID 绑定 ----------------

    @Test
    fun `P8 PID 绑定与反查`() {
        val pm = VirtualProcessManager()
        pm.allocateSlot("a")
        pm.setSlotPid(0, 12345)

        assertEquals(12345, pm.getAllSlots()[0].pid)
        assertEquals("按 pid 查槽", 0, pm.getSlotForPid(12345))
        assertEquals("未绑定的 pid -> -1", -1, pm.getSlotForPid(999))

        // 越界 setSlotPid 不抛、不越界写
        pm.setSlotPid(999, 1)
        assertEquals("越界写入应被忽略", 0, pm.getSlotForPid(12345))
    }

    // ---------------- P9 槽位命名（与 stub 映射的一致性） ----------------

    @Test
    fun `P9 槽位进程名与索引一致`() {
        val pm = VirtualProcessManager(maxSlots = 3)
        val slots = pm.getAllSlots()
        assertEquals(3, slots.size)
        slots.forEachIndexed { i, s ->
            assertEquals("slotIndex", i, s.slotIndex)
            assertEquals("进程名", ":p$i", s.processName)
        }
        assertFalse("初始应为空槽", slots[0].isOccupied)
    }
}
