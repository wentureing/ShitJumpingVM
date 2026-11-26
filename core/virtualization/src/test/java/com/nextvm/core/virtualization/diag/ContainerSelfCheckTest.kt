package com.nextvm.core.virtualization.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ContainerSelfCheck 的行为铆钉（C1–C9）。
 *
 * 自检的价值是"主动跑一次真实操作并断言结果"，所以测试用**假 PathProbe** 模拟
 * 规则表与 native 计数，验证每个检查项的判定逻辑（而不是只测字符串）。
 *
 * 说明：docs/代码镜像/core/virtualization/diag/ContainerSelfCheck.md
 */
class ContainerSelfCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 内存版规则表：前缀匹配，与 NativeHookBridge.translatePath 的语义一致。 */
    private class FakeProbe : PathProbe {
        val rules = linkedMapOf<String, String>()

        /** S8 用：第一次调用返回 before，之后返回 after（模拟"写诊断前/后"两次采样）。 */
        var statsBefore: String? = null
        var statsAfter: String? = null
        private var statsCalls = 0

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
        override fun ruleSnapshot(): Map<String, String> = rules.toMap()
        override fun javaIoStats(): String? = if (statsCalls++ == 0) statsBefore else statsAfter
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
    fun `C2 规则翻译后可写且不留痕迹`() {
        // writeRoot 模拟 context.cacheDir（app 私有），不再写 virtualRoot。
        val writeRoot = File(tmp.root, "cache/selfcheck")
        val c = ContainerSelfCheck(FakeProbe(), writeRoot, "[豁免名单]\n", null).run()
        assertTrue(check("S3", c).detail, check("S3", c).ok)
        assertFalse("临时写入文件应被清理", File(writeRoot, "w/f.txt").exists())
        assertFalse("临时目录也必须删干净", writeRoot.exists())
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
        // 2026-11-26 增 S7/S8 后由 6 变 8（行为偏移：应当变，非笔误）
        assertEquals(8, checks.size)
    }

    @Test
    fun `C8 S7 真实规则抽样：有规则则 ok，空表则 fail`() {
        val probe = FakeProbe()
        val empty = ContainerSelfCheck(probe, tmp.root, "[豁免名单]\n", null).run()
        assertFalse("规则表为空必须判失败（否则真实规则没注册也全绿）", check("S7", empty).ok)

        probe.rules["/data/user/0/com.a/"] = "/sandbox/a/"
        val ok = ContainerSelfCheck(probe, tmp.root, "[豁免名单]\n", null).run()
        val s7 = check("S7", ok)
        assertTrue(s7.detail, s7.ok)
        assertTrue(s7.detail.contains("/data/user/0/com.a/"))
        // 抽样不得污染规则表
        assertEquals(1, probe.rules.size)
    }

    @Test
    fun `C9 S8 诊断写入只增 diag 不增 total`() {
        val diag = File(tmp.root, "debug-trace.log")

        // a) 未安装 javaIo hook：skipped 而不是 fail
        val skipped = ContainerSelfCheck(FakeProbe(), tmp.root, "[豁免名单]\n", diag).run()
        assertTrue(check("S8", skipped).ok)
        assertTrue(check("S8", skipped).detail.contains("skipped"))

        // b) diag 增 3、total 不变 -> ok
        val good = FakeProbe().apply {
            statsBefore = "total=10 fail=0 translated=0 translate=1 diag=5"
            statsAfter = "total=10 fail=0 translated=0 translate=1 diag=8"
        }
        assertTrue(check("S8", ContainerSelfCheck(good, tmp.root, "[豁免名单]\n", diag).run()).ok)

        // c) total 也被计入 -> fail（日志自身污染指标）
        val bad = FakeProbe().apply {
            statsBefore = "total=10 fail=0 translated=0 translate=1 diag=5"
            statsAfter = "total=16 fail=0 translated=0 translate=1 diag=8"
        }
        val s8bad = check("S8", ContainerSelfCheck(bad, tmp.root, "[豁免名单]\n", diag).run())
        assertFalse(s8bad.detail, s8bad.ok)
        assertTrue(s8bad.detail.contains("totalDelta=6"))
    }
}
