package com.nextvm.core.virtualization.diag.suppress

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AutofillSuppressor 的纯逻辑铆钉（A1–A3）。
 *
 * `disableForActivity`/`disablePerView` 依赖 Activity/View，不可纯 JVM 测（见镜像文档）。
 *
 * 说明：docs/代码镜像/core/virtualization/diag/suppress/AutofillSuppressor.md
 */
class AutofillSuppressorTest {

    @Test
    fun `A1 formatModes 渲染 mode 到 count`() {
        assertEquals("8:1,0:3", AutofillSuppressor.formatModes(mapOf(8 to 1, 0 to 3)))
    }

    @Test
    fun `A2 formatModes 空 map`() {
        assertEquals("", AutofillSuppressor.formatModes(emptyMap()))
    }

    @Test
    fun `A3 formatExplicit 用分号拼接`() {
        assertEquals(
            "A mode=1 hints=-;B mode=8 hints=email",
            AutofillSuppressor.formatExplicit(listOf("A mode=1 hints=-", "B mode=8 hints=email"))
        )
        assertEquals("", AutofillSuppressor.formatExplicit(emptyList()))
    }
}
