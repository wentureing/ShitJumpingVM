package com.nextvm.core.virtualization.diag.probe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HijackProbes 的纯逻辑铆钉（H1–H2）。
 *
 * `sample`/`unwrap` 依赖 ActivityThread 与反射，不可纯 JVM 测（见镜像文档「约束与坑」）。
 *
 * 说明：docs/代码镜像/core/virtualization/diag/probe/HijackProbes.md
 */
class HijackProbesTest {

    @Test
    fun `H1 describe 对 null 返回 null 字面量`() {
        assertEquals("null", HijackProbes.describe(null))
    }

    @Test
    fun `H2 describe 返回类名原样`() {
        assertEquals("android.app.Instrumentation", HijackProbes.describe("android.app.Instrumentation"))
    }
}
