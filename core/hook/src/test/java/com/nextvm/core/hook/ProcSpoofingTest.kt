package com.nextvm.core.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/proc` 伪造：内容格式合规性 + 写入行为。
 * 详细说明见 `docs/代码镜像/core/hook/ProcSpoofing.md`。
 */
class ProcSpoofingTest {

    private fun newSpoofer(
        written: MutableMap<String, ByteArray> = mutableMapOf()
    ): ProcSpoofing = ProcSpoofing(
        setFakeContent = { p, c -> written[p] = c },
        nativeAvailable = { false },
        nativeSpoofProcSelf = { _, _ -> },
        nativeSetProcSpoofSuspended = { },
        nativeSetProcessName = { },
        nativeArmSeccompDiag = { },
        nativeSpoofSystemProperty = { _, _ -> }
    )

    @Test
    fun `status 包含 guest 身份且不含容器特征`() {
        val text = String(newSpoofer().buildFakeProcStatus(4242, "mark.via"))
        assertTrue(text.contains("Name:\tmark.via"))
        assertTrue(text.contains("Pid:\t4242"))
        assertTrue(text.contains("Tgid:\t4242"))
        assertFalse("不应出现容器包名", text.contains("nextvm"))
        assertFalse("不应出现容器包名", text.contains("com.nextvm.app"))
    }

    @Test
    fun `status 每行符合 Key 冒号 Tab Value 格式`() {
        val lines = String(newSpoofer().buildFakeProcStatus(1, "com.foo"))
            .lines().filter { it.isNotBlank() }
        assertTrue("至少要有 Name/Pid/Tgid", lines.size >= 3)
        for (line in lines) {
            assertTrue("行格式不合规: $line", Regex("^[A-Za-z]+:\\t.*$").matches(line))
        }
    }

    @Test
    fun `status 的 Name 不超过 15 字符（内核 comm 限制）`() {
        val text = String(newSpoofer().buildFakeProcStatus(1, "com.a.very.long.package.name"))
        val nameLine = text.lines().first { it.startsWith("Name:") }
        val name = nameLine.substringAfter("Name:\t")
        assertTrue("Name 过长: $name", name.length <= 15)
    }

    @Test
    fun `spoofProcSelf 写入三份内容且 cmdline 以 NUL 结尾`() {
        val written = mutableMapOf<String, ByteArray>()
        newSpoofer(written).spoofProcSelf(7, "com.foo")
        assertTrue(written.containsKey(ProcSpoofing.PROC_SELF_CMDLINE))
        assertTrue(written.containsKey(ProcSpoofing.PROC_SELF_COMM))
        assertTrue(written.containsKey(ProcSpoofing.PROC_SELF_STATUS))
        val cmdline = written[ProcSpoofing.PROC_SELF_CMDLINE]!!
        assertTrue(cmdline.isNotEmpty() && cmdline.last() == 0.toByte())
    }

    @Test
    fun `comm 超过 15 字符被截断`() {
        val written = mutableMapOf<String, ByteArray>()
        newSpoofer(written).spoofProcSelf(1, "com.a.very.long.package.name")
        val comm = String(written[ProcSpoofing.PROC_SELF_COMM]!!).trim()
        assertTrue("comm 长度必须 <= 15，实际=$comm", comm.length <= 15)
    }

    @Test
    fun `spoofedPid 与包名被记录下来`() {
        val s = newSpoofer()
        s.spoofProcSelf(9, "com.foo")
        assertEquals(9, s.spoofedPid)
        assertEquals("com.foo", s.spoofedPackageName)
        assertEquals("com.foo", s.spoofedProcessName)
    }

    @Test
    fun `属性覆盖可累积与查询`() {
        val s = newSpoofer()
        s.spoofSystemProperty("ro.debuggable", "0")
        s.spoofSystemProperties(mapOf("ro.secure" to "1", "ro.build.type" to "user"))
        val snap = s.propertyOverridesSnapshot()
        assertEquals("0", snap["ro.debuggable"])
        assertEquals("1", snap["ro.secure"])
        assertEquals("user", snap["ro.build.type"])
    }

    @Test
    fun `reset 清空全部状态`() {
        val s = newSpoofer()
        s.spoofProcSelf(9, "com.foo")
        s.spoofSystemProperty("ro.debuggable", "0")
        s.reset()
        assertTrue(s.propertyOverridesSnapshot().isEmpty())
        assertEquals(-1, s.spoofedPid)
        assertEquals(null, s.spoofedPackageName)
    }
}
