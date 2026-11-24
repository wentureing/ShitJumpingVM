package com.nextvm.core.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路径重定向规则表：真实规则矩阵 + 最长前缀优先。
 * 详细说明见 `docs/代码镜像/core/hook/PathRedirectRegistry.md`。
 */
class PathRedirectRegistryTest {

    /** 复刻 NativeHookBridge.setupAppRedirections + setupExternalStorageRedirections 的真实规则。 */
    private fun realWorldRegistry(): PathRedirectRegistry {
        val r = PathRedirectRegistry()
        val pkg = "mark.via"
        val sandbox = "/data/user/0/com.nextvm.app/files/virtual/data/mark.via_1"
        val sdcard = "$sandbox/sdcard"
        // setupAppRedirections
        r.add("/data/data/$pkg/", "$sandbox/")
        r.add("/data/user/0/$pkg/", "$sandbox/")
        r.add("/storage/emulated/0/Android/data/$pkg/", "$sandbox/external_data/")
        r.add("/sdcard/Android/data/$pkg/", "$sandbox/external_data/")
        r.add("/storage/emulated/0/Android/obb/$pkg/", "$sandbox/obb/")
        r.add("/sdcard/Android/obb/$pkg/", "$sandbox/obb/")
        // setupExternalStorageRedirections
        r.add("/sdcard/", "$sdcard/")
        r.add("/storage/emulated/0/", "$sdcard/")
        r.add("/mnt/sdcard/", "$sdcard/")
        r.add("/storage/self/primary/", "$sdcard/")
        return r
    }

    @Test
    fun `真实规则 - data 私有目录`() {
        val r = realWorldRegistry()
        val base = "/data/user/0/com.nextvm.app/files/virtual/data/mark.via_1"
        assertEquals("$base/db/x.db", r.translate("/data/data/mark.via/db/x.db"))
        assertEquals("$base/db/x.db", r.translate("/data/user/0/mark.via/db/x.db"))
    }

    @Test
    fun `真实规则 - sdcard 根目录`() {
        val r = realWorldRegistry()
        val sdcard = "/data/user/0/com.nextvm.app/files/virtual/data/mark.via_1/sdcard"
        assertEquals("$sdcard/Download/a.pdf", r.translate("/sdcard/Download/a.pdf"))
        assertEquals("$sdcard/DCIM/b.jpg", r.translate("/storage/emulated/0/DCIM/b.jpg"))
        assertEquals("$sdcard/c.txt", r.translate("/mnt/sdcard/c.txt"))
        assertEquals("$sdcard/d.txt", r.translate("/storage/self/primary/d.txt"))
    }

    @Test
    fun `真实规则 - Android data 段优先于 sdcard 根`() {
        val r = realWorldRegistry()
        val base = "/data/user/0/com.nextvm.app/files/virtual/data/mark.via_1"
        // 更长的前缀必须赢，否则会错误落到 sdcard/
        assertEquals("$base/external_data/f", r.translate("/sdcard/Android/data/mark.via/f"))
        assertEquals("$base/obb/g", r.translate("/sdcard/Android/obb/mark.via/g"))
    }

    @Test
    fun `真实规则 - 其它应用路径不翻译`() {
        val r = realWorldRegistry()
        assertEquals("/sdcard/Android/data/other.app/x", r.translate("/sdcard/Android/data/other.app/x"))
        assertEquals("/data/data/other.app/x", r.translate("/data/data/other.app/x"))
    }

    @Test
    fun `最长前缀优先（假规则）`() {
        val r = PathRedirectRegistry()
        r.add("/data/data/", "/root/")
        r.add("/data/data/com.a/", "/sandbox/A/")
        assertEquals("/sandbox/A/x.txt", r.translate("/data/data/com.a/x.txt"))
        assertEquals("/root/com.b/y", r.translate("/data/data/com.b/y"))
    }

    @Test
    fun `未命中返回原路径`() {
        val r = PathRedirectRegistry()
        r.add("/data/data/com.a/", "/sandbox/A/")
        assertEquals("/system/lib64/libc.so", r.translate("/system/lib64/libc.so"))
    }

    @Test
    fun `隐藏路径翻译成 dev null`() {
        val r = PathRedirectRegistry(isHidden = { it == "/data/adb/magisk" })
        assertEquals("/dev/null", r.translate("/data/adb/magisk"))
        assertEquals("/data/adb/magisk2", r.translate("/data/adb/magisk2"))
    }

    @Test
    fun `只替换一次不递归`() {
        val r = PathRedirectRegistry()
        r.add("/data/data/com.a/", "/sandbox/A/")
        r.add("/sandbox/A/", "/deeper/")
        assertEquals("/sandbox/A/x", r.translate("/data/data/com.a/x"))
    }

    @Test
    fun `remove 与 clear`() {
        val r = PathRedirectRegistry()
        r.add("/a/", "/x/")
        r.remove("/a/")
        assertEquals("/a/z", r.translate("/a/z"))
        assertEquals(0, r.size())

        r.add("/a/", "/x/")
        r.add("/b/", "/y/")
        r.clear()
        assertEquals(0, r.size())
    }

    @Test
    fun `add remove clear 会触发 native 回调`() {
        val events = mutableListOf<String>()
        val r = PathRedirectRegistry(
            onAdd = { f, t -> events.add("add:$f=$t") },
            onRemove = { events.add("remove:$it") },
            onClear = { events.add("clear") }
        )
        r.add("/a/", "/x/")
        r.remove("/a/")
        r.clear()
        assertEquals(listOf("add:/a/=/x/", "remove:/a/", "clear"), events)
        assertTrue(events.size == 3)
    }
}
