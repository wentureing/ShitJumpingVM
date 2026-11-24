package com.nextvm.core.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块级单测：`/proc` 伪造（v154）。
 *
 * 只测**纯逻辑**部分（伪造内容生成），不碰 native 与真实 `/proc`。
 */
class ProcSpoofingTest {

    private fun newSpoofer(): ProcSpoofing = ProcSpoofing(
        setFakeContent = { _, _ -> },
        nativeAvailable = { false },
        nativeSpoofProcSelf = { _, _ -> },
        nativeSetProcSpoofSuspended = { },
        nativeSetProcessName = { },
        nativeArmSeccompDiag = { },
        nativeSpoofSystemProperty = { _, _ -> }
    )

    @Test
    fun `伪造的 status 包含 guest 包名与 pid`() {
        val s = newSpoofer()
        val text = String(s.buildFakeProcStatus(4242, "mark.via"))
        assertTrue("应包含 Name", text.contains("Name:\tmark.via"))
        assertTrue("应包含 Pid", text.contains("Pid:\t4242"))
        assertTrue("应包含 Tgid", text.contains("Tgid:\t4242"))
    }

    @Test
    fun `status 不包含容器特征`() {
        val text = String(newSpoofer().buildFakeProcStatus(1, "mark.via"))
        assertFalse("不应出现容器包名", text.contains("nextvm"))
        assertFalse("不应出现容器包名", text.contains("com.nextvm.app"))
    }

    @Test
    fun `spoofProcSelf 写入三份伪造内容`() {
        val written = mutableMapOf<String, ByteArray>()
        val s = ProcSpoofing(
            setFakeContent = { p, c -> written[p] = c },
            nativeAvailable = { false },
            nativeSpoofProcSelf = { _, _ -> },
            nativeSetProcSpoofSuspended = { },
            nativeSetProcessName = { },
            nativeArmSeccompDiag = { },
            nativeSpoofSystemProperty = { _, _ -> }
        )
        s.spoofProcSelf(7, "com.foo")
        assertTrue(written.containsKey(ProcSpoofing.PROC_SELF_CMDLINE))
        assertTrue(written.containsKey(ProcSpoofing.PROC_SELF_COMM))
        assertTrue(written.containsKey(ProcSpoofing.PROC_SELF_STATUS))
        // cmdline 必须以 NUL 结尾
        val cmdline = written[ProcSpoofing.PROC_SELF_CMDLINE]!!
        assertTrue(cmdline.isNotEmpty() && cmdline.last() == 0.toByte())
    }

    @Test
    fun `comm 超过 15 字符会被截断`() {
        val written = mutableMapOf<String, ByteArray>()
        val s = ProcSpoofing(
            setFakeContent = { p, c -> written[p] = c },
            nativeAvailable = { false },
            nativeSpoofProcSelf = { _, _ -> },
            nativeSetProcSpoofSuspended = { },
            nativeSetProcessName = { },
            nativeArmSeccompDiag = { },
            nativeSpoofSystemProperty = { _, _ -> }
        )
        s.spoofProcSelf(1, "com.a.very.long.package.name")
        val comm = String(written[ProcSpoofing.PROC_SELF_COMM]!!).trim()
        assertTrue("comm 长度必须 <= 15，实际=$comm", comm.length <= 15)
    }

    @Test
    fun `reset 清空伪造状态`() {
        val s = newSpoofer()
        s.spoofProcSelf(9, "com.foo")
        s.spoofSystemProperty("ro.debuggable", "0")
        assertTrue(s.propertyOverridesSnapshot().isNotEmpty())
        s.reset()
        assertTrue(s.propertyOverridesSnapshot().isEmpty())
        assertTrue(s.spoofedPid == -1)
    }
}
