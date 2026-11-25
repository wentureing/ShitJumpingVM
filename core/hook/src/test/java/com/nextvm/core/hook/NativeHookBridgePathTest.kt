package com.nextvm.core.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 路径重定向规则的契约测试（纯 JVM，不依赖 native）。
 *
 * 为什么可纯 JVM：`translatePath` 只读 Kotlin 的 Set/Map；
 * `addPathRedirection` 在 `nativeHooksAvailable == false` 时不会调 JNI，
 * 而纯 JVM 环境下该标志恒为 false。
 */
class NativeHookBridgePathTest {

    private lateinit var bridge: NativeHookBridge

    @Before
    fun setUp() {
        bridge = NativeHookBridge()
    }

    /** T1：多条规则命中时，最长前缀胜出（不是先注册的胜出）。 */
    @Test
    fun `T1 最长前缀优先`() {
        bridge.addPathRedirection("/storage/emulated/0/", "/sandbox/sdcard/")
        bridge.addPathRedirection(
            "/storage/emulated/0/Android/data/com.nextvm.app/",
            "/sandbox/host_ext/"
        )
        assertEquals(
            "/sandbox/host_ext/files/x",
            bridge.translatePath("/storage/emulated/0/Android/data/com.nextvm.app/files/x")
        )
    }

    /** T2：setupAppRedirections 必须注册「宿主 external 兜底」规则。 */
    @Test
    fun `T2 宿主兜底规则已注册`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        val out = bridge.translatePath(
            "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht"
        )
        if (NativeHookBridge.HOST_EXTERNAL_FALLBACK) {
            assertEquals("/sandbox/host_ext/files/offline/a.mht", out)
        } else {
            // 开关关闭时必须原样返回（证明确实能回退）
            assertEquals("/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht", out)
        }
    }

    /** T3：guest 自己的 external 路径仍走 external_data，不受兜底规则影响。 */
    @Test
    fun `T3 guest 自身路径不受影响`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        assertEquals(
            "/sandbox/external_data/files/x",
            bridge.translatePath("/storage/emulated/0/Android/data/mark.via/files/x")
        )
    }

    /** T4：没命中任何规则的路径必须原样返回（不能误改）。 */
    @Test
    fun `T4 未命中原样返回`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        val p = "/data/app/com.other/base.apk"
        assertEquals(p, bridge.translatePath(p))
    }

    /** T5：hiddenPaths 的判定优先于路径重定向。 */
    @Test
    fun `T5 hiddenPaths 优先于重定向`() {
        bridge.addPathRedirection("/system/", "/sandbox/system/")
        bridge.hidePath("/system/bin/su")
        assertEquals("/dev/null", bridge.translatePath("/system/bin/su"))
        // 同前缀但未被隐藏的路径仍走重定向
        assertEquals("/sandbox/system/bin/ls", bridge.translatePath("/system/bin/ls"))
    }

    /** T6：同一前缀重复注册时，后注册的覆盖前者（Map 语义）。 */
    @Test
    fun `T6 重复注册同前缀后者覆盖`() {
        bridge.addPathRedirection("/a/", "/first/")
        bridge.addPathRedirection("/a/", "/second/")
        assertEquals("/second/b", bridge.translatePath("/a/b"))
    }

    /** T7：五个必需前缀都被注册（data / user-0 / external / obb）。 */
    @Test
    fun `T7 setupAppRedirections 覆盖五类前缀`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        assertEquals("/sandbox/a", bridge.translatePath("/data/data/mark.via/a"))
        assertEquals("/sandbox/a", bridge.translatePath("/data/user/0/mark.via/a"))
        assertEquals(
            "/sandbox/external_data/a",
            bridge.translatePath("/storage/emulated/0/Android/data/mark.via/a")
        )
        assertEquals(
            "/sandbox/external_data/a",
            bridge.translatePath("/sdcard/Android/data/mark.via/a")
        )
        assertEquals(
            "/sandbox/obb/a",
            bridge.translatePath("/storage/emulated/0/Android/obb/mark.via/a")
        )
    }
}
