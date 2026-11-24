package com.nextvm.core.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模块级单测：路径重定向规则表（v154）。
 *
 * 这个类是整个容器"路径翻译"的 Kotlin 侧真相来源，必须与 native 侧
 * `redirect_path()` 语义一致（最长前缀 + 单次替换）。这里把四条规则钉住。
 */
class PathRedirectRegistryTest {

    @Test
    fun `最长前缀优先`() {
        val r = PathRedirectRegistry()
        r.add("/data/data/", "/root/")
        r.add("/data/data/com.a/", "/sandbox/A/")
        // com.a 的规则更长 -> 必须命中它
        assertEquals("/sandbox/A/x.txt", r.translate("/data/data/com.a/x.txt"))
        // 其它包命中较短的
        assertEquals("/root/com.b/y", r.translate("/data/data/com.b/y"))
    }

    @Test
    fun `未命中任何规则时返回原路径`() {
        val r = PathRedirectRegistry()
        r.add("/data/data/com.a/", "/sandbox/A/")
        assertEquals("/sdcard/foo", r.translate("/sdcard/foo"))
    }

    @Test
    fun `隐藏路径翻译成 dev null`() {
        val r = PathRedirectRegistry(isHidden = { it == "/data/adb/magisk" })
        assertEquals("/dev/null", r.translate("/data/adb/magisk"))
        // 前缀相同但不是同一路径 -> 不隐藏
        assertEquals("/data/adb/magisk2", r.translate("/data/adb/magisk2"))
    }

    @Test
    fun `只做一次替换不递归`() {
        val r = PathRedirectRegistry()
        r.add("/data/data/com.a/", "/sandbox/A/")
        // 目标前缀本身也能被另一条规则命中，但翻译不应二次进行
        r.add("/sandbox/A/", "/deeper/")
        assertEquals("/sandbox/A/x", r.translate("/data/data/com.a/x"))
    }

    @Test
    fun `remove 之后不再命中`() {
        val r = PathRedirectRegistry()
        r.add("/data/data/com.a/", "/sandbox/A/")
        r.remove("/data/data/com.a/")
        assertEquals("/data/data/com.a/x", r.translate("/data/data/com.a/x"))
        assertEquals(0, r.size())
    }

    @Test
    fun `clear 清空全部规则`() {
        val r = PathRedirectRegistry()
        r.add("/a/", "/x/")
        r.add("/b/", "/y/")
        r.clear()
        assertEquals(0, r.size())
        assertEquals("/a/z", r.translate("/a/z"))
    }

    @Test
    fun `add 会触发 native 同步回调`() {
        val added = mutableListOf<Pair<String, String>>()
        val r = PathRedirectRegistry(onAdd = { f, t -> added.add(f to t) })
        r.add("/a/", "/x/")
        assertEquals(listOf("/a/" to "/x/"), added)
    }
}
