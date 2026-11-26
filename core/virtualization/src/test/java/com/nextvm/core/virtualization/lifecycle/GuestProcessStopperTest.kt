package com.nextvm.core.virtualization.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GuestProcessStopper.findPids` 的纯逻辑铆钉（不打真进程）。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/GuestProcessStopper.md
 */
class GuestProcessStopperTest {

    private fun e(pid: Int, cmd: String, uid: Int) = GuestProcessStopper.ProcEntry(pid, cmd, uid)

    @Test
    fun `S1 精确匹配包名进程`() {
        val entries = listOf(e(100, "mark.via", 10001), e(101, "com.nextvm.app", 10001))
        assertEquals(listOf(100), GuestProcessStopper.findPids(entries, "mark.via", 10001))
    }

    @Test
    fun `S2 匹配子进程（pkg 冒号后缀）`() {
        val entries = listOf(
            e(100, "mark.via", 10001),
            e(101, "mark.via:remote", 10001),
            e(102, "mark.via.other", 10001) // 不同包，不能误杀
        )
        assertEquals(listOf(100, 101), GuestProcessStopper.findPids(entries, "mark.via", 10001))
    }

    @Test
    fun `S3 只匹配同 uid（跨 uid 杀不动也不该匹配）`() {
        val entries = listOf(e(100, "mark.via", 10001), e(200, "mark.via", 10002))
        assertEquals(listOf(100), GuestProcessStopper.findPids(entries, "mark.via", 10001))
    }

    @Test
    fun `S4 空包名不匹配任何进程`() {
        val entries = listOf(e(100, "mark.via", 10001))
        assertTrue(GuestProcessStopper.findPids(entries, "", 10001).isEmpty())
    }
}
