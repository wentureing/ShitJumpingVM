package com.nextvm.core.common.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由与 sink 行为：序号分配、异常隔离、错误不丢。
 * 详见 `docs/设计/设计-诊断输出抽象层.md`。
 */
class DiagnosticsRouterTest {

    private fun ok(name: String = "x") = CheckResult(
        "activity", "0", name, CheckState.OK, "t", "v", "v"
    )

    @Test
    fun `check 序号从 1 开始递增`() {
        val sink = RecordingSink()
        val router = DiagnosticsRouter(listOf(sink))
        router.check(ok("a"))
        router.check(ok("b"))
        val lines = sink.checks
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("0001 "))
        assertTrue(lines[1].startsWith("0002 "))
        assertEquals(2, router.checkCount)
    }

    @Test
    fun `多个 sink 收到同一序号（顺序一致）`() {
        val s1 = RecordingSink()
        val s2 = RecordingSink()
        val router = DiagnosticsRouter(listOf(s1, s2))
        router.check(ok("a"))
        assertEquals(s1.checks, s2.checks)
    }

    @Test
    fun `某个 sink 抛异常不影响其它 sink，也不影响调用方`() {
        val bad = object : DiagnosticsSink {
            override fun onCheck(result: CheckResult, seq: Int) = throw RuntimeException("boom")
        }
        val good = RecordingSink()
        val router = DiagnosticsRouter(listOf(bad, good))
        router.check(ok("a"))          // 不应抛
        assertEquals(1, good.checks.size)
    }

    @Test
    fun `error 会同时进 RecordingSink 的 errors 段`() {
        val sink = RecordingSink()
        val router = DiagnosticsRouter(listOf(sink))
        router.error("v142", "register failed", IllegalStateException("x"))
        assertEquals(1, sink.errorLines.size)
        assertTrue(sink.errorLines[0].contains("v142"))
    }

    @Test
    fun `dump 输出分 checks 与 errors 两段`() {
        val sink = RecordingSink()
        val router = DiagnosticsRouter(listOf(sink))
        router.check(ok("a"))
        router.error("t", "m", null)
        val dump = sink.dump()
        assertTrue(dump.startsWith("--- checks ---"))
        assertTrue(dump.contains("--- errors ---"))
        // checks 段在 errors 段之前
        assertTrue(dump.indexOf("--- checks ---") < dump.indexOf("--- errors ---"))
    }

    @Test
    fun `Noop 实现不产生任何输出也不抛`() {
        Diagnostics.Noop.check(ok())
        Diagnostics.Noop.error("t", "m", null)
        Diagnostics.Noop.stage("t", "m")
    }
}
