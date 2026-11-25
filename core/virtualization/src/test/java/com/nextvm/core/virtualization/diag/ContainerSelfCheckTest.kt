package com.nextvm.core.virtualization.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ContainerSelfCheck 的行为铆钉（C1–C7）。
 *
 * 自检的价值是"主动跑一次真实操作并断言结果"，所以测试用**假 PathProbe** 模拟
 * 规则表行为，验证每个检查项的判定逻辑（而不是只测字符串）。
 *
 * 说明：docs/代码镜像/core/virtualization/diag/ContainerSelfCheck.md
 */
class ContainerSelfCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 内存版规则表：前缀匹配，与 NativeHookBridge.translatePath 的语义一致。 */
    private class FakeProbe : PathProbe {
        val rules = linkedMapOf<String, String>()
        override fun add(fromPrefix: String, toPrefix: String) { rules[fromPrefix] = toPrefix }
        override fun remove(fromPrefix: String) { rules.remove(fromPrefix) }
        override fun translate(path: String): String {
            for ((from, to) in rules) {
                if (path.startsWith(from)) return to + path.removePrefix(from)
            }
            return path
        }
        override fun redirectionCount() = rules.size
        override fun nativeAvailable() = true
    }

    private fun check(id: String, checks: List<ContainerSelfCheck.Check>) =
        checks.first { it.id == id }

    @Test
    fun `C1 规则命中与移除`() {
        val probe = FakeProbe()
        val c = ContainerSelfCheck(probe, tmp.root, "[豁免名单]\nmark.via\n", null).run()
        assertTrue(check("S1", c).ok)
        assertTrue(check("S2", c).ok)
        assertTrue("自检不能留下临时规则", probe.rules.isEmpty())
    }

    @Test
    fun `C2 写入落在沙箱并清理`() {
        val sandbox = tmp.newFolder("virtual")
        val c = ContainerSelfCheck(FakeProbe(), sandbox, "[豁免名单]\n", null).run()
        assertTrue(check("S3", c).detail, check("S3", c).ok)
        assertFalse(
            "临时写入文件应被清理",
            File(sandbox, "selfcheck/w/f.txt").exists()
        )
        assertFalse(
            "临时目录也必须删干净（不能留在用户可见目录里）",
            File(sandbox, "selfcheck").exists()
        )
    }

    @Test
    fun `C3 未知路径不被改写`() {
        val c = ContainerSelfCheck(FakeProbe(), tmp.root, "[豁免名单]\n", null).run()
        assertTrue(check("S4", c).ok)
    }

    @Test
    fun `C4 诊断通道可写且长度增长`() {
        val diag = File(tmp.root, "debug-trace.log")
        diag.writeText("old\n")
        val c = ContainerSelfCheck(FakeProbe(), tmp.root, "[豁免名单]\n", diag).run()
        assertTrue(check("S5", c).ok)
        assertTrue(diag.readText().contains("selfcheck probe"))
    }

    @Test
    fun `C5 无诊断文件时跳过而不是失败`() {
        val c = ContainerSelfCheck(FakeProbe(), tmp.root, "[豁免名单]\n", null).run()
        val s5 = check("S5", c)
        assertTrue(s5.ok)
        assertTrue(s5.detail.contains("skipped"))
    }

    @Test
    fun `C6 配置缺失或缺段判失败`() {
        val missing = ContainerSelfCheck(FakeProbe(), tmp.root, null, null).run()
        assertFalse(check("S6", missing).ok)

        val bad = ContainerSelfCheck(FakeProbe(), tmp.root, "[别的段]\n", null).run()
        assertFalse(check("S6", bad).ok)

        val good = ContainerSelfCheck(FakeProbe(), tmp.root, "[豁免名单]\nmark.via\n", null).run()
        assertTrue(check("S6", good).ok)
    }

    @Test
    fun `C7 report 汇总格式`() {
        val c = ContainerSelfCheck(FakeProbe(), tmp.root, "[豁免名单]\n", null)
        val checks = c.run()
        val line = c.report(checks)
        assertTrue(line.startsWith("hookcheck ok="))
        assertTrue(line.contains("fail="))
        assertTrue(line.contains("S1:ok"))
        assertEquals(6, checks.size)
    }
}
