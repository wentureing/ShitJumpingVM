package com.nextvm.core.virtualization.diag.dump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GuestStackDumps.marksFor` 的纯逻辑铆钉（采样节奏表）。
 *
 * 说明：docs/代码镜像/core/virtualization/diag/dump/GuestStackDumps.md
 */
class GuestStackDumpsTest {

    @Test
    fun `G1 普通 guest 用 7 个基础采样点`() {
        val marks = GuestStackDumps.marksFor("mark.via")
        assertEquals(7, marks.size)
        assertEquals(listOf(300L, 700L, 1_200L, 2_000L, 3_000L, 8_000L, 20_000L), marks.map { it.first })
    }

    @Test
    fun `G2 联通 guest 追加 40s 到 160s 三个 late marks`() {
        val marks = GuestStackDumps.marksFor("com.greenpoint.android.mc10086.activity")
        assertEquals(10, marks.size)
        assertEquals(listOf(40_000L, 80_000L, 160_000L), marks.takeLast(3).map { it.first })
    }

    @Test
    fun `G3 采样点严格递增（sleep 差值的算法依赖这一点）`() {
        for (pkg in listOf("mark.via", "com.greenpoint.android.mc10086.activity")) {
            val at = GuestStackDumps.marksFor(pkg).map { it.first }
            assertTrue("$pkg: ${at}", at.zipWithNext().all { (a, b) -> b > a })
        }
    }

    @Test
    fun `G4 包名为 null 时走基础表（不 NPE）`() {
        assertEquals(7, GuestStackDumps.marksFor(null).size)
    }
}
