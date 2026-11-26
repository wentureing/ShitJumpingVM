package com.nextvm.core.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PathPattern` 的行为铆钉（纯 JVM，与 C++ `path_pattern_test.cpp` 用例一一对应）。
 *
 * 为什么重要：匹配语义一旦两端不一致，guest 的路径会"看得到却打不开"，且没有异常。
 * 说明：docs/输出/变更/20261126-诊断白名单与路径匹配统一.md
 */
class PathPatternTest {

    @Test
    fun `G1-G8 通配匹配`() {
        assertTrue(PathPattern.globMatches("*.log", "a.log"))
        assertFalse(PathPattern.globMatches("*.log", "a.logx"))
        assertTrue(PathPattern.globMatches("/data/*/files", "/data/x/files"))
        assertTrue(PathPattern.globMatches("/a/*", "/a/b/c"))
        assertTrue(PathPattern.globMatches("*", ""))
        assertFalse(PathPattern.globMatches("/a/*", "/b/c"))
        assertTrue(PathPattern.globMatches("/a/**/c", "/a/b/c"))
        assertFalse(PathPattern.globMatches("abc", "abcd"))
    }

    @Test
    fun `P1-P7 前缀与根形态`() {
        val p = PathPattern.parse("/data/user/0/com.a/")
        assertEquals(PathPattern.Kind.PREFIX, p.kind)
        assertTrue(p.matches("/data/user/0/com.a/files"))
        assertFalse("前缀不得误伤 com.ab", p.matches("/data/user/0/com.ab/files"))
        assertTrue(p.isRootForm("/data/user/0/com.a"))
        assertFalse(p.isRootForm("/data/user/0/com.a/files"))

        // 无尾斜杠规则：既有语义是"前缀"（golden 锁定），这里保持
        val bare = PathPattern.parse("/storage/emulated/0/Android/data/com.a")
        assertTrue(bare.matches("/storage/emulated/0/Android/data/com.a"))
        assertTrue(bare.matches("/storage/emulated/0/Android/data/com.a/files"))
    }

    @Test
    fun `E1-E3 精确匹配`() {
        val e = PathPattern.exact("/system/bin/su")
        assertTrue(e.matches("/system/bin/su"))
        assertFalse("精确不误伤 sudo", e.matches("/system/bin/sudo"))
        assertFalse(e.matches("/system/bin/su/x"))
    }

    @Test
    fun `K1-K3 含通配字符即 Glob`() {
        val g = PathPattern.parse("/sdcard/*.txt")
        assertEquals(PathPattern.Kind.GLOB, g.kind)
        assertTrue(g.matches("/sdcard/note.txt"))
        assertTrue("星跨 '/' 也命中（简化语义）", g.matches("/sdcard/sub/note.txt"))
    }

    @Test
    fun `R 系列 PathRuleStore 仍按最长前缀与根形态选择`() {
        val store = PathRuleStore()
        store.add("/data/data/com.a/", "/sandbox/com.a/")
        store.add("/data/data/com.a/files/", "/sandbox/com.a/files/")
        // 最长前缀胜
        assertEquals(
            "/sandbox/com.a/files/x.txt",
            store.translate("/data/data/com.a/files/x.txt").path
        )
        // 根形态（无尾斜杠的路径命中带尾斜杠规则）
        store.add("/data/data/com.b/", "/sandbox/com.b/")
        assertEquals("/sandbox/com.b", store.translate("/data/data/com.b").path)
    }
}
