package com.nextvm.core.virtualization.engine

import com.nextvm.core.model.VirtualConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * StubRegistry 的行为铆钉（纯 JVM，零 Android 依赖）。
 *
 * 它守护两类契约：
 *  ① **类名格式**必须与 `AndroidManifest.xml` 里声明的 stub 完全一致（改格式 = 启动链断裂）；
 *  ② **inUse 池**的分配/释放语义（重复分配同一个 stub 会导致两个组件互踩）。
 *
 * 说明：docs/输出/调研/调研-高频修改热点与测试优先级.md
 */
class StubRegistryTest {

    private lateinit var registry: StubRegistry

    @Before
    fun setUp() {
        registry = StubRegistry()
    }

    // ---------------- G1-G2 清单与类名格式（与 manifest 的契约） ----------------

    @Test
    fun `G1 类名格式与 manifest 约定一致`() {
        val mapping = registry.getMapping(0)
        assertNotNull(mapping)
        assertEquals(
            "标准 Activity 命名",
            "com.nextvm.app.stub.P0\$Standard00",
            mapping!!.stubActivityStandard[0],
        )
        assertEquals("singleTop", "com.nextvm.app.stub.P0\$SingleTop00", mapping.stubActivitySingleTop[0])
        assertEquals("singleTask", "com.nextvm.app.stub.P0\$SingleTask00", mapping.stubActivitySingleTask[0])
        assertEquals(
            "singleInstance",
            "com.nextvm.app.stub.P0\$SingleInstance00",
            mapping.stubActivitySingleInstance[0],
        )
        assertEquals("Service", "com.nextvm.app.stub.P0\$S00", mapping.stubServices[0])
        assertEquals("Provider", "com.nextvm.app.stub.P0\$CP00", mapping.stubProviders[0])
        assertEquals("Receiver", "com.nextvm.app.stub.P0\$R00", mapping.stubReceivers[0])
    }

    @Test
    fun `G2 每个 slot 的各类 stub 数量与常量一致`() {
        for (slot in 0 until VirtualConstants.MAX_PROCESS_SLOTS) {
            val m = registry.getMapping(slot)!!
            assertEquals("slot$slot standard", VirtualConstants.STUB_ACTIVITIES_STANDARD, m.stubActivityStandard.size)
            assertEquals("slot$slot singleTop", VirtualConstants.STUB_ACTIVITIES_SINGLE_TOP, m.stubActivitySingleTop.size)
            assertEquals("slot$slot singleTask", VirtualConstants.STUB_ACTIVITIES_SINGLE_TASK, m.stubActivitySingleTask.size)
            assertEquals(
                "slot$slot singleInstance",
                VirtualConstants.STUB_ACTIVITIES_SINGLE_INSTANCE,
                m.stubActivitySingleInstance.size,
            )
            assertEquals("slot$slot services", VirtualConstants.STUB_SERVICES, m.stubServices.size)
            assertEquals("slot$slot providers", VirtualConstants.STUB_PROVIDERS, m.stubProviders.size)
            assertEquals("slot$slot receivers", VirtualConstants.STUB_RECEIVERS, m.stubReceivers.size)
        }
        // 边界：slot 数 = MAX_PROCESS_SLOTS，越界无映射
        assertNotNull(registry.getMapping(VirtualConstants.MAX_PROCESS_SLOTS - 1))
        assertNull(registry.getMapping(VirtualConstants.MAX_PROCESS_SLOTS))
    }

    // ---------------- G3-G5 Activity 解析 ----------------

    @Test
    fun `G3 未知 launchMode 走 standard`() {
        assertEquals("com.nextvm.app.stub.P0\$Standard00", registry.resolveStub(0, "standard"))
        // 新建 registry：避免 inUse 影响
        assertEquals("com.nextvm.app.stub.P0\$Standard00", StubRegistry().resolveStub(0, "weird-mode"))
    }

    @Test
    fun `G4 各 launchMode 落到对应池`() {
        assertEquals("com.nextvm.app.stub.P1\$SingleTop00", registry.resolveStub(1, "singleTop"))
        assertEquals("com.nextvm.app.stub.P1\$SingleTask00", registry.resolveStub(1, "singleTask"))
        assertEquals("com.nextvm.app.stub.P1\$SingleInstance00", registry.resolveStub(1, "singleInstance"))
    }

    @Test
    fun `G5 同一 stub 不会被分配两次 耗尽返回 null`() {
        val first = registry.resolveStub(0, "singleInstance")
        assertNotNull(first)
        // singleInstance 每 slot 只有 1 个
        assertNull("耗尽后应为 null", registry.resolveStub(0, "singleInstance"))

        registry.releaseStub(first!!)
        assertEquals("释放后应可再次分配", first, registry.resolveStub(0, "singleInstance"))
    }

    // ---------------- G6 未知 slot ----------------

    @Test
    fun `G6 越界 slot 返回 null`() {
        assertNull(registry.resolveStub(-1, "standard"))
        assertNull(registry.resolveStub(999, "standard"))
        assertNull(registry.resolveServiceStub(999))
        assertNull(registry.resolveProviderStub(999))
        assertNull(registry.getMapping(999))
    }

    // ---------------- G7-G8 Service / Provider ----------------

    @Test
    fun `G7 service 与 provider 各自成池`() {
        val svc = registry.resolveServiceStub(0)
        val cp = registry.resolveProviderStub(0)
        assertNotNull(svc)
        assertNotNull(cp)
        assertTrue(svc!!.contains("\$S"))
        assertTrue(cp!!.contains("\$CP"))

        registry.releaseStub(svc)
        assertEquals("释放后可复用", svc, registry.resolveServiceStub(0))
    }

    @Test
    fun `G8 service 池耗尽`() {
        repeat(VirtualConstants.STUB_SERVICES) { assertNotNull(registry.resolveServiceStub(0)) }
        assertNull(registry.resolveServiceStub(0))
    }

    // ---------------- G9-G10 反查与批量释放 ----------------

    @Test
    fun `G9 前缀判定与反查 slot`() {
        assertTrue(registry.isStubComponent("com.nextvm.app.stub.P3\$Standard01"))
        assertFalse(registry.isStubComponent("mark.via.MainActivity"))

        assertEquals(3, registry.getSlotForStub("com.nextvm.app.stub.P3\$Standard01"))
        assertEquals(-1, registry.getSlotForStub("mark.via.MainActivity"))
    }

    @Test
    fun `G10 releaseAllStubsForSlot 释放该 slot 全部`() {
        val std = registry.resolveStub(2, "standard")!!
        val svc = registry.resolveServiceStub(2)!!
        registry.releaseAllStubsForSlot(2)

        assertEquals("释放后首个 standard 应可再分配", std, registry.resolveStub(2, "standard"))
        assertEquals("service 同理", svc, registry.resolveServiceStub(2))
        // 其它 slot 不受影响
        assertNotNull(registry.resolveStub(1, "standard"))
    }

    // ---------------- R1-R3 L1：stopApp 归还路径（2026-11-27 Step 1） ----------------

    @Test
    fun `R1 池子耗尽后归还可再次解析`() {
        val used = (0 until VirtualConstants.STUB_ACTIVITIES_STANDARD)
            .map { registry.resolveStub(1, "standard")!! }
        assertEquals(VirtualConstants.STUB_ACTIVITIES_STANDARD, used.size)
        assertNull("耗尽后应为 null", registry.resolveStub(1, "standard"))

        registry.releaseAllStubsForSlot(1)

        assertEquals("归还后首个应可再分配", used.first(), registry.resolveStub(1, "standard"))
    }

    @Test
    fun `R2 只归还该 slot 其余 slot 保留已用记录`() {
        val s1 = registry.resolveStub(1, "standard")!!
        val s2 = registry.resolveStub(2, "standard")!!
        registry.releaseAllStubsForSlot(1)

        assertEquals("slot1 已归还", s1, registry.resolveStub(1, "standard"))
        val nextS2 = registry.resolveStub(2, "standard")
        assertNotNull(nextS2)
        assertNotEquals("slot2 的已用记录不应被清掉", s2, nextS2)
    }

    @Test
    fun `R3 从未分配过的 slot 归还是 no-op`() {
        registry.releaseAllStubsForSlot(5)
        registry.releaseAllStubsForSlot(-1)
        assertNotNull(registry.resolveStub(5, "standard"))
    }

    @Test
    fun `R4 candidatesFor 过滤已占用（否则新实例永远拿到同一个 stub）`() {
        val all = registry.candidatesFor(0, "standard")
        assertEquals(VirtualConstants.STUB_ACTIVITIES_STANDARD, all.size)

        registry.markInUse(all.first())
        val afterOne = registry.candidatesFor(0, "standard")
        assertEquals(VirtualConstants.STUB_ACTIVITIES_STANDARD - 1, afterOne.size)
        assertFalse("已占用的不能再当候选", afterOne.contains(all.first()))

        // 全部占满 → 空列表（planner 会给出 No available stub）
        all.forEach { registry.markInUse(it) }
        assertTrue(registry.candidatesFor(0, "standard").isEmpty())
    }
}
