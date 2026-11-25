package com.nextvm.core.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PathRuleStore 的单元测试（纯 JVM，不需要 Android / native）。
 *
 * 它守护的是 B1 拆出的"纯状态机"：规则表 / 隐藏 / fake content / 属性覆盖 + 翻译语义。
 * 与 `NativeHookBridgePathTest`（走门面）互补：这里直接测状态机，失败时不必怀疑门面。
 *
 * 说明：docs/输出/调研/调研-NativeHookBridge可测试性评估.md
 */
class PathRuleStoreTest {

    private lateinit var store: PathRuleStore

    private val pkg = "mark.via"
    private val sandbox = "/sandbox/i1"

    @Before
    fun setUp() {
        store = PathRuleStore()
    }

    private fun installAppRules() {
        val ext = "$sandbox/sdcard/Android/data/$pkg"
        store.add("/data/data/$pkg/", "$sandbox/")
        store.add("/data/user/0/$pkg/", "$sandbox/")
        store.add("/storage/emulated/0/Android/data/$pkg/", "$ext/")
        store.add("/storage/emulated/0/Android/data/$pkg", ext)
    }

    // ---------------- S1-S3 规则表 + 翻译 ----------------

    @Test
    fun `S1 前缀与族根都命中`() {
        installAppRules()
        assertEquals("$sandbox/files/x", store.translate("/data/user/0/$pkg/files/x").path)
        assertEquals("族根", sandbox, store.translate("/data/user/0/$pkg").path)
        assertEquals("带尾斜杠的族根保留斜杠", "$sandbox/", store.translate("/data/user/0/$pkg/").path)
    }

    @Test
    fun `S2 兄弟包不串味`() {
        installAppRules()
        val inSibling = "/data/user/0/$pkg.pro/x"
        val t = store.translate(inSibling)
        assertEquals("原样返回", inSibling, t.path)
        assertNull("未命中时 matched 为 null", t.matched)
    }

    @Test
    fun `S3 同 key 后注册覆盖前者`() {
        store.add("/a/", "/first/")
        store.add("/a/", "/second/")
        assertEquals("/second/x", store.translate("/a/x").path)
        assertEquals(1, store.count())
    }

    // ---------------- S4-S5 删除 / 清空 ----------------

    @Test
    fun `S4 remove 后不再翻译`() {
        store.add("/a/", "/b/")
        store.remove("/a/")
        assertEquals(0, store.count())
        assertEquals("/a/x", store.translate("/a/x").path)
    }

    @Test
    fun `S5 clearAll 清空四类状态`() {
        store.add("/a/", "/b/")
        store.hide("/system/bin/su")
        store.setFakeContent("/proc/self/cmdline", "x")
        store.overrideProperty("ro.debuggable", "0")

        store.clearAll()

        assertEquals(0, store.count())
        assertEquals(0, store.hiddenCount())
        assertFalse(store.hasFakeContent("/proc/self/cmdline"))
        assertNull(store.propertyOverride("ro.debuggable"))
    }

    // ---------------- S6 隐藏（精确 + 优先） ----------------

    @Test
    fun `S6 隐藏精确且优先于翻译`() {
        store.add("/system/", "$sandbox/system/")
        store.hide("/system/bin/su")

        val hit = store.translate("/system/bin/su")
        assertEquals("/dev/null", hit.path)
        assertTrue("hiddenHit 必须为 true（门面据此不记翻译日志）", hit.hiddenHit)

        val sibling = store.translate("/system/bin/sudo")
        assertEquals("$sandbox/system/bin/sudo", sibling.path)
        assertFalse("普通翻译不应标记 hidden", sibling.hiddenHit)
    }

    // ---------------- S7-S8 fake content / 属性 ----------------

    @Test
    fun `S7 fake content 两种重载与覆盖`() {
        assertFalse(store.hasFakeContent("/p"))
        store.setFakeContent("/p", "text")
        assertEquals("text", String(store.fakeContent("/p")!!))
        store.setFakeContent("/p", "bin".toByteArray())
        assertEquals("bin", String(store.fakeContent("/p")!!))
    }

    @Test
    fun `S8 属性覆盖`() {
        assertNull(store.propertyOverride("ro.secure"))
        store.overrideProperty("ro.secure", "1")
        assertEquals("1", store.propertyOverride("ro.secure"))
    }
}
