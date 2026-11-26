package com.nextvm.core.virtualization.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `SlotLeaseRegistry` 的行为铆钉（L 系列）。
 *
 * 说明：docs/代码镜像/core/virtualization/process/SlotLeaseRegistry.md
 */
class SlotLeaseRegistryTest {

    private lateinit var registry: SlotLeaseRegistry

    @Before
    fun setUp() {
        registry = SlotLeaseRegistry()
    }

    @Test
    fun `L1 acquire 幂等 同实例重复登记返回既有租约`() {
        val first = registry.acquire(1, "inst-a", "com.nextvm.app.stub.P1\$Standard00", nowMs = 10L)
        val again = registry.acquire(3, "inst-a", "com.nextvm.app.stub.P3\$Standard00", nowMs = 20L)

        assertEquals(first, again)
        assertEquals(1, again.slot)
        assertEquals(10L, again.acquiredAtMs)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `L2 byInstance 与 bySlot 双向可查`() {
        registry.acquire(2, "inst-b", "com.nextvm.app.stub.P2\$Standard00", nowMs = 5L)

        assertNotNull(registry.byInstance("inst-b"))
        assertEquals("inst-b", registry.bySlot(2)?.instanceId)
        assertNull(registry.byInstance("inst-x"))
        assertNull(registry.bySlot(7))
    }

    @Test
    fun `L3 release 幂等 且只释放指定实例`() {
        registry.acquire(1, "inst-a", "stub-a", nowMs = 1L)
        registry.acquire(2, "inst-b", "stub-b", nowMs = 2L)

        val released = registry.release("inst-a")
        assertEquals("inst-a", released?.instanceId)
        assertNull(registry.release("inst-a"))
        assertEquals(1, registry.all().size)
        assertEquals("inst-b", registry.all().single().instanceId)
        assertNull(registry.bySlot(1))
        assertNotNull(registry.bySlot(2))
    }

    @Test
    fun `L4 noteTaskId 回填后 all 里可见`() {
        registry.acquire(1, "inst-a", "stub-a", nowMs = 1L)
        val updated = registry.noteTaskId("inst-a", 4242)

        assertEquals(4242, updated?.taskId)
        assertEquals(4242, registry.byInstance("inst-a")?.taskId)
        assertEquals(4242, registry.bySlot(1)?.taskId)
        assertEquals(4242, registry.all().single().taskId)
    }

    @Test
    fun `L5 all 按 slot 升序`() {
        registry.acquire(5, "e", "stub-e", nowMs = 1L)
        registry.acquire(1, "a", "stub-a", nowMs = 2L)
        registry.acquire(3, "c", "stub-c", nowMs = 3L)

        assertEquals(listOf(1, 3, 5), registry.all().map { it.slot })
    }

    @Test
    fun `L6 同 slot 第二实例不覆盖 bySlot`() {
        registry.acquire(1, "inst-a", "stub-a", nowMs = 1L)
        registry.acquire(1, "inst-b", "stub-b", nowMs = 2L)

        assertEquals("inst-a", registry.bySlot(1)?.instanceId)
        assertEquals(2, registry.all().size)
        assertEquals(setOf("inst-a", "inst-b"), registry.all().map { it.instanceId }.toSet())
    }

    @Test
    fun `L7 clear 后全空`() {
        registry.acquire(1, "inst-a", "stub-a", nowMs = 1L)
        registry.clear()

        assertEquals(0, registry.all().size)
        assertNull(registry.byInstance("inst-a"))
        assertNull(registry.bySlot(1))
    }

    @Test
    fun `L8 release 后可以按新的 slot 与 stub 重新登记`() {
        registry.acquire(1, "inst-a", "stub-a", nowMs = 1L)
        registry.release("inst-a")

        val again = registry.acquire(2, "inst-a", "stub-a2", nowMs = 9L)
        assertEquals(2, again.slot)
        assertEquals("stub-a2", again.stubClassName)
        assertEquals(9L, again.acquiredAtMs)
        assertEquals(2, registry.bySlot(2)?.slot)
        assertNull("旧 slot 不应残留", registry.bySlot(1))
    }

    @Test
    fun `L9 noteTaskId 对不存在的实例返回 null 且不建租约`() {
        assertNull(registry.noteTaskId("ghost", 123))
        assertEquals(0, registry.all().size)
    }
}
