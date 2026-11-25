package com.nextvm.core.framework.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 包名校验的行为铆钉（golden）。
 *
 * 语义来源：AOSP `FrameworkParsingPackageUtils.validateName()`（逐字符规则）。
 * **本类只铆钉行为，不参与重构** —— `core:framework` 已稳定，测试的作用是"以后谁改它，这里先叫"。
 * 含若干**反直觉但正确**的 AOSP 行为（`"a..b"`、`"."`、空串），这些正是最容易被"顺手优化"改坏的地方。
 */
class PackageNameValidatorTest {

    // ---------------- 正常名称 ----------------

    @Test
    fun `标准包名合法`() {
        assertNull(PackageNameValidator.validateName("com.example.app", requireSeparator = true))
    }

    @Test
    fun `非首段允许数字与下划线`() {
        assertNull(PackageNameValidator.validateName("com.exa_mple2.app3", requireSeparator = true))
    }

    @Test
    fun `首段允许大写`() {
        assertNull(PackageNameValidator.validateName("SystemUI", requireSeparator = false))
    }

    // ---------------- 分隔符要求 ----------------

    @Test
    fun `缺少分隔符时报错`() {
        assertEquals(
            "must have at least one '.' separator",
            PackageNameValidator.validateName("com", requireSeparator = true),
        )
    }

    @Test
    fun `不要求分隔符时单段合法`() {
        assertNull(PackageNameValidator.validateName("com", requireSeparator = false))
    }

    // ---------------- 字符规则 ----------------

    @Test
    fun `首段不允许数字`() {
        assertEquals("bad character '1'", PackageNameValidator.validateName("1com.example", true))
    }

    @Test
    fun `首段不允许下划线`() {
        assertEquals("bad character '_'", PackageNameValidator.validateName("_com.example", true))
    }

    @Test
    fun `不允许连字符`() {
        assertEquals("bad character '-'", PackageNameValidator.validateName("com.exa-mple", true))
    }

    @Test
    fun `不允许斜杠（字符检查先于文件名检查）`() {
        assertEquals("bad character '/'", PackageNameValidator.validateName("com/example", true))
    }

    // ---------------- 反直觉但正确的 AOSP 行为（重点铆钉） ----------------

    @Test
    fun `连续点是允许的`() {
        // AOSP 的 validateName 不检查空段：'.' 只置 hasSep=true
        assertNull(PackageNameValidator.validateName("a..b", requireSeparator = true))
    }

    @Test
    fun `单独一个点也合法`() {
        assertNull(PackageNameValidator.validateName(".", requireSeparator = true))
    }

    @Test
    fun `空串在不要求分隔符时合法`() {
        assertNull(PackageNameValidator.validateName("", requireSeparator = false))
        assertEquals(
            "must have at least one '.' separator",
            PackageNameValidator.validateName("", requireSeparator = true),
        )
    }

    // ---------------- requireFilename ----------------

    @Test
    fun `文件名模式下拒绝双点`() {
        assertEquals(
            "Invalid filename",
            PackageNameValidator.validateName("com..example", requireSeparator = false, requireFilename = true),
        )
    }

    @Test
    fun `文件名模式下 223 合法 224 越界`() {
        val ok = "a".repeat(223)
        val tooLong = "a".repeat(224)
        assertNull(PackageNameValidator.validateName(ok, false, requireFilename = true))
        assertEquals(
            "the length of the name is greater than 223",
            PackageNameValidator.validateName(tooLong, false, requireFilename = true),
        )
    }

    // ---------------- validate() 的 ParseResult 包装 ----------------

    @Test
    fun `validate 返回 ParseResult`() {
        assertTrue(PackageNameValidator.validate("com.example", true).isSuccess)
        val bad = PackageNameValidator.validate("com", true)
        assertTrue(bad.isError)
    }
}
