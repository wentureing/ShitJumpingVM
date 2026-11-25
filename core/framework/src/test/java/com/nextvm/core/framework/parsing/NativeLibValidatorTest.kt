package com.nextvm.core.framework.parsing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * native lib 路径校验的行为铆钉（golden）。
 *
 * 语义来源：AOSP `libs/androidfw/ApkParsing.cpp` 的 `ValidLibraryPathLastSlash()` / `isFilenameSafe()`。
 * **只铆钉，不重构**。注意：`getPrimaryAbi()` / `getFallbackAbi()` 依赖 `android.os.Build`，不在本类覆盖范围。
 */
class NativeLibValidatorTest {

    // ---------------- validateLibraryPath：三段式 lib/<abi>/<name>.so ----------------

    @Test
    fun `标准路径合法`() {
        assertTrue(NativeLibValidator.validateLibraryPath("lib/arm64-v8a/libfoo.so"))
        assertTrue(NativeLibValidator.validateLibraryPath("lib/armeabi-v7a/libfoo.so"))
    }

    @Test
    fun `安全字符集合法`() {
        // isFilenameSafe 允许 A-Za-z0-9_.+-
        assertTrue(NativeLibValidator.validateLibraryPath("lib/x86_64/liba-b_c+d.so"))
    }

    @Test
    fun `后缀必须小写 so`() {
        assertFalse(NativeLibValidator.validateLibraryPath("lib/arm64-v8a/libfoo.SO"))
    }

    @Test
    fun `缺少 so 后缀不合法`() {
        assertFalse(NativeLibValidator.validateLibraryPath("lib/arm64-v8a/libfoo"))
    }

    @Test
    fun `未知 ABI 不合法`() {
        assertFalse(NativeLibValidator.validateLibraryPath("lib/unknown/libfoo.so"))
    }

    @Test
    fun `多一级目录不合法`() {
        assertFalse(NativeLibValidator.validateLibraryPath("lib/arm64-v8a/sub/libfoo.so"))
    }

    @Test
    fun `必须以 lib 开头`() {
        assertFalse(NativeLibValidator.validateLibraryPath("assets/lib/arm64-v8a/libfoo.so"))
    }

    @Test
    fun `空 ABI 或空文件名不合法`() {
        assertFalse(NativeLibValidator.validateLibraryPath("lib//libfoo.so"))
        assertFalse(NativeLibValidator.validateLibraryPath("lib/arm64-v8a/"))
    }

    // ---------------- isFilenameSafe ----------------

    @Test
    fun `文件名安全字符判定`() {
        assertTrue(NativeLibValidator.isFilenameSafe("libfoo.so"))
        assertTrue(NativeLibValidator.isFilenameSafe("lib-v1_2+b.so"))
        assertFalse("空格不安全", NativeLibValidator.isFilenameSafe("lib foo.so"))
        assertFalse("路径分隔符不安全", NativeLibValidator.isFilenameSafe("lib/foo.so"))
        assertFalse("制表符不安全", NativeLibValidator.isFilenameSafe("lib\tfoo.so"))
        assertFalse("空串不安全", NativeLibValidator.isFilenameSafe(""))
    }

    // ---------------- isValidAbiName ----------------

    @Test
    fun `ABI 白名单`() {
        assertTrue(NativeLibValidator.isValidAbiName("riscv64"))
        assertTrue(NativeLibValidator.isValidAbiName("mips64"))
        assertFalse(NativeLibValidator.isValidAbiName("arm64"))
        assertFalse(NativeLibValidator.isValidAbiName("ARM64-V8A"))
    }
}
