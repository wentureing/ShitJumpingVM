package com.nextvm.core.virtualization.diag.trace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `HijackTrace.isTarget` 的纯逻辑铆钉（只追踪联通 guest）。
 *
 * 说明：docs/代码镜像/core/virtualization/diag/trace/HijackTrace.md
 */
class HijackTraceTest {

    @Test
    fun `H1 联通 guest 是目标`() {
        assertTrue(HijackTrace.isTarget("com.sinovatech.unicom.ui"))
    }

    @Test
    fun `H2 其它 guest 不是目标`() {
        assertFalse(HijackTrace.isTarget("mark.via"))
        assertFalse(HijackTrace.isTarget("com.sandbox.probe"))
    }

    @Test
    fun `H3 空包名不匹配`() {
        assertFalse(HijackTrace.isTarget(""))
    }

    @Test
    fun `H4 精确匹配，前缀相似的包不算`() {
        // 原判据是 `!=` 精确比较，固定它（避免以后被改成 startsWith）
        assertFalse(HijackTrace.isTarget("com.sinovatech.unicom.ui2"))
        assertFalse(HijackTrace.isTarget("com.sinovatech.unicom"))
    }
}
