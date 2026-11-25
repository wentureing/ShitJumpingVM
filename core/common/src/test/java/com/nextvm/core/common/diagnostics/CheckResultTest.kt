package com.nextvm.core.common.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CheckResult 的契约：字段约束 + 输出格式（golden 友好）。
 * 详见 `docs/设计/设计-诊断输出与golden验证.md`。
 */
class CheckResultTest {

    private fun ok(
        path: String = "activity",
        step: String = "2",
        name: String = "replaceClassLoader",
        expected: String = "guest",
        actual: String = "guest",
        context: Map<String, String> = mapOf("pkg" to "mark.via")
    ) = CheckResult(
        path = path, step = step, name = name, state = CheckState.OK,
        target = "LoadedApk.mClassLoader", expected = expected, actual = actual,
        context = context
    )

    @Test
    fun `OK 且 expected 等于 actual 可构造`() {
        assertEquals(CheckState.OK, ok().state)
    }

    @Test
    fun `OK 但 expected 不等于 actual 必须拒绝（防假成功）`() {
        val e = assertThrows(IllegalArgumentException::class.java) { ok(actual = "host") }
        assertTrue(e.message!!.contains("假成功"))
    }

    @Test
    fun `FAIL 必须带 reason`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            CheckResult(
                "activity", "2.5", "bootApplication", CheckState.FAIL,
                "Application", "created", "failed"
            )
        }
        assertTrue(e.message!!.contains("FAIL 必须带 reason"))
    }

    @Test
    fun `SKIP 必须带 reason（区分没走到与执行失败）`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            CheckResult(
                "activity", "2.5", "bootApplication", CheckState.SKIP,
                "Application", "created", "not_run"
            )
        }
        assertTrue(e.message!!.contains("SKIP 必须带 reason"))
    }

    @Test
    fun `expected 与 actual 不能为空`() {
        assertThrows(IllegalArgumentException::class.java) { ok(expected = "") }
        assertThrows(IllegalArgumentException::class.java) { ok(actual = "  ") }
    }

    @Test
    fun `输出格式固定且可解析`() {
        val line = ok().format(4)
        assertEquals(
            "0004 activity.2 replaceClassLoader ok=true " +
                "target=LoadedApk.mClassLoader expected=guest actual=guest ctx=pkg=mark.via",
            line
        )
    }

    @Test
    fun `seq 补零到 4 位`() {
        assertTrue(ok().format(1).startsWith("0001 "))
        assertTrue(ok().format(12345).startsWith("12345 "))
    }

    @Test
    fun `FAIL 与 SKIP 的输出分别是 false 与 skip`() {
        val fail = CheckResult(
            "native", "phase2", "loadNative", CheckState.FAIL,
            "libnextvm.so", "loaded", "missing", reason = "dlopen_failed"
        ).format(1)
        assertTrue(fail.contains("ok=false"))
        assertTrue(fail.contains("reason=dlopen_failed"))

        val skip = CheckResult(
            "activity", "2.5", "bootApplication", CheckState.SKIP,
            "Application", "created", "not_run", reason = "engine_not_ready"
        ).format(2)
        assertTrue(skip.contains("ok=skip"))
        assertTrue(skip.contains("reason=engine_not_ready"))
    }

    @Test
    fun `context 输出顺序稳定（不随 HashMap 随机）`() {
        val ctx = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val line = ok(context = ctx).format(1)
        assertTrue(line.contains("ctx=a=1,b=2,c=3"))
    }

    @Test
    fun `无 context 时不输出 ctx 段`() {
        val line = ok(context = emptyMap()).format(1)
        assertTrue("不应含 ctx=", !line.contains("ctx="))
    }
}
