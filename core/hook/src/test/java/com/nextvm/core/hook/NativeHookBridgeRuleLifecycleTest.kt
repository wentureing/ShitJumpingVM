package com.nextvm.core.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 规则表生命周期与附属状态的行为铆钉（golden）。
 *
 * 为什么补这一层：`NativeHookBridge` 是 churn 最高的 Kotlin 文件之一（接手后 23 次提交、580 行改动），
 * 但已有测试只覆盖"翻译结果"（T1–T43），**没有覆盖注册/删除/清空/隐藏/fake content 这些状态机**。
 * 这些状态机出错的表现是"规则残留"或"删不干净"，比翻译错更难发现。
 *
 * 说明见 docs/输出/调研/调研-高频修改热点与测试优先级.md
 */
class NativeHookBridgeRuleLifecycleTest {

    private lateinit var bridge: NativeHookBridge

    private val pkg = "com.example.guest"
    private val sandbox = "/sandbox/i1"

    @Before
    fun setUp() {
        bridge = NativeHookBridge()
    }

    // ---------------- R1-R4：注册 / 快照 / 覆盖 / 删除 ----------------

    @Test
    fun `R1 注册后快照与计数一致`() {
        bridge.addPathRedirection("/a/", "/to-a/")
        bridge.addPathRedirection("/b/", "/to-b/")
        assertEquals(2, bridge.getRedirectionCount())
        assertEquals(
            mapOf("/a/" to "/to-a/", "/b/" to "/to-b/"),
            bridge.getPathRedirectSnapshot(),
        )
    }

    @Test
    fun `R2 同 key 后注册覆盖前者`() {
        bridge.addPathRedirection("/a/", "/first/")
        bridge.addPathRedirection("/a/", "/second/")
        assertEquals(1, bridge.getRedirectionCount())
        assertEquals("/second/x", bridge.translatePath("/a/x"))
    }

    @Test
    fun `R3 删除后不再翻译且计数减少`() {
        bridge.addPathRedirection("/a/", "/to-a/")
        bridge.removePathRedirection("/a/")
        assertEquals(0, bridge.getRedirectionCount())
        assertEquals("/a/x", bridge.translatePath("/a/x"))
    }

    @Test
    fun `R4 清空后快照为空`() {
        bridge.addPathRedirection("/a/", "/to-a/")
        bridge.clearPathRedirections()
        assertTrue(bridge.getPathRedirectSnapshot().isEmpty())
        assertEquals("/a/x", bridge.translatePath("/a/x"))
    }

    // ---------------- R5-R8：三个 remove 的边界（只删自己那一批） ----------------

    @Test
    fun `R5 removeAppRedirections 只删本包规则`() {
        bridge.setupAppRedirections(pkg, "i1", sandbox)
        bridge.addPathRedirection("/unrelated/", "/keep/")
        val before = bridge.getRedirectionCount()

        bridge.removeAppRedirections(pkg)

        assertEquals("只留下无关规则", 1, bridge.getRedirectionCount())
        assertTrue("无关规则必须留存", bridge.getPathRedirectSnapshot().containsKey("/unrelated/"))
        assertTrue("删掉的应比注册的少", before > bridge.getRedirectionCount())
        // 被删的族不再翻译（含 ROOT_FORM 形态也不应残留）
        assertEquals("/data/user/0/$pkg", bridge.translatePath("/data/user/0/$pkg"))
    }

    @Test
    fun `R6 removeExternalStorageRedirections 只删共享存储根`() {
        bridge.setupExternalStorageRedirections("i1", "$sandbox/sdcard")
        bridge.addPathRedirection("/unrelated/", "/keep/")
        bridge.removeExternalStorageRedirections()

        assertEquals(1, bridge.getRedirectionCount())
        assertEquals("/keep/", bridge.translatePath("/unrelated/"))
        assertEquals("/sdcard/Download/x", bridge.translatePath("/sdcard/Download/x"))
    }

    @Test
    fun `R7 removePathFidelityRedirections 只删 apk 与 lib`() {
        bridge.setupPathFidelityRedirections(pkg, "i1", sandbox, "$sandbox/apks/x.apk")
        val before = bridge.getRedirectionCount()
        bridge.removePathFidelityRedirections(pkg)
        assertEquals("应只少 2 条（APK / LIB）", before - 2, bridge.getRedirectionCount())
        // 其余 data/external 规则仍在
        assertEquals("$sandbox/files/x", bridge.translatePath("/data/user/0/$pkg/files/x"))
    }

    @Test
    fun `R8 注册后删除回到干净状态`() {
        bridge.setupAppRedirections(pkg, "i1", sandbox)
        assertTrue(bridge.getRedirectionCount() > 0)
        bridge.removeAppRedirections(pkg)
        assertTrue(
            "删除后不应残留本包的任何规则",
            bridge.getPathRedirectSnapshot().keys.none { it.contains(pkg) },
        )
    }

    // ---------------- R9-R10：隐藏路径（精确语义，与规则的前缀语义相反） ----------------

    @Test
    fun `R9 隐藏是精确匹配且优先于翻译`() {
        bridge.hidePath("/system/bin/su")
        bridge.addPathRedirection("/system/", "/sandbox/system/")

        assertTrue(bridge.isPathHidden("/system/bin/su"))
        assertEquals(1, bridge.getHiddenPathCount())
        assertEquals("隐藏优先", "/dev/null", bridge.translatePath("/system/bin/su"))
        assertEquals("子路径仍走翻译", "/sandbox/system/bin/sudo", bridge.translatePath("/system/bin/sudo"))
    }

    @Test
    fun `R10 取消隐藏后恢复翻译`() {
        bridge.hidePath("/data/local/tmp/x")
        bridge.unhidePath("/data/local/tmp/x")
        assertFalse(bridge.isPathHidden("/data/local/tmp/x"))
        assertEquals(0, bridge.getHiddenPathCount())
        assertEquals("/data/local/tmp/x", bridge.translatePath("/data/local/tmp/x"))
    }

    // ---------------- R11：fake content 状态 ----------------

    @Test
    fun `R11 fake content 读写与覆盖`() {
        assertFalse(bridge.hasFakeContent("/proc/self/cmdline"))
        assertNull(bridge.getFakeContent("/proc/self/cmdline"))

        bridge.setFakeFileContent("/proc/self/cmdline", "guest.pkg")
        assertTrue(bridge.hasFakeContent("/proc/self/cmdline"))
        assertEquals("guest.pkg", String(bridge.getFakeContent("/proc/self/cmdline")!!))

        // ByteArray 重载与覆盖
        bridge.setFakeFileContent("/proc/self/cmdline", "other".toByteArray())
        assertEquals("other", String(bridge.getFakeContent("/proc/self/cmdline")!!))
    }

    // ---------------- R12：属性覆盖状态 ----------------

    @Test
    fun `R12 属性覆盖单个与批量`() {
        assertNull(bridge.getPropertyOverride("ro.debuggable"))
        bridge.spoofSystemProperty("ro.debuggable", "0")
        assertEquals("0", bridge.getPropertyOverride("ro.debuggable"))

        bridge.spoofSystemProperties(mapOf("ro.build.type" to "user", "ro.secure" to "1"))
        assertEquals("user", bridge.getPropertyOverride("ro.build.type"))
        assertEquals("1", bridge.getPropertyOverride("ro.secure"))
    }
}
