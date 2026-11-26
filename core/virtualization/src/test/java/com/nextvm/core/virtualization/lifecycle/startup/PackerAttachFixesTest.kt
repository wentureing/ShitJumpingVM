package com.nextvm.core.virtualization.lifecycle.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PackerAttachFixes` 的两个纯判据（P 系列）。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/startup/PackerAttachFixes.md
 */
class PackerAttachFixesTest {

    private val dataDir = "/data/user/0/com.nextvm.app/files/virtual/data/mark.via_x/files"

    // ---------------- P1-P5 壳类判据（原为内联 5 个 contains） ----------------

    @Test
    fun `P1 com stub StubApp 判为壳类`() {
        assertTrue(PackerAttachFixes.isPackerStubClass("com.stub.StubApp"))
    }

    @Test
    fun `P2 apkwrapper 判为壳类`() {
        assertTrue(PackerAttachFixes.isPackerStubClass("com.secneo.apkwrapper.AW"))
    }

    @Test
    fun `P3 普通 guest 不是壳类`() {
        assertFalse(PackerAttachFixes.isPackerStubClass("mark.via.BrowserApp"))
    }

    @Test
    fun `P4 大小写不敏感`() {
        assertTrue(PackerAttachFixes.isPackerStubClass("STUBAPP"))
        assertTrue(PackerAttachFixes.isPackerStubClass("ShellApp"))
        assertTrue(PackerAttachFixes.isPackerStubClass("com.qihoo.PROTECT"))
    }

    @Test
    fun `P5 空串不是壳类`() {
        assertFalse(PackerAttachFixes.isPackerStubClass(""))
    }

    // ---------------- P6-P9 字段路径重定向（原为内联 replace + 两个条件） ----------------

    @Test
    fun `P6 data data 前缀被替换成 guest dataDir`() {
        assertEquals(
            "$dataDir/lib",
            PackerAttachFixes.redirectedPath("/data/data/com.foo/lib", dataDir),
        )
    }

    @Test
    fun `P7 data user 0 前缀不处理（原实现只看 data data）`() {
        // 固定既有行为：原条件要求 value 含 "/data/data/" 才进入
        assertNull(PackerAttachFixes.redirectedPath("/data/user/0/com.foo/files/x", dataDir))
    }

    @Test
    fun `P8 容器自身路径不改`() {
        assertNull(
            PackerAttachFixes.redirectedPath(
                "/data/data/com.nextvm.app/files/x",
                dataDir,
            )
        )
    }

    @Test
    fun `P9 不含 data data 的值不改`() {
        assertNull(PackerAttachFixes.redirectedPath("/sdcard/Download/a.so", dataDir))
        assertNull(PackerAttachFixes.redirectedPath("", dataDir))
    }
}
