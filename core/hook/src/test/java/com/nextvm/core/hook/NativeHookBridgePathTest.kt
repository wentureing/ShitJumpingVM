package com.nextvm.core.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 路径重定向规则的契约测试（纯 JVM，不依赖 native）。
 *
 * 为什么可纯 JVM：`translatePath` 只读 Kotlin 的 Set/Map；
 * `addPathRedirection` 在 `nativeHooksAvailable == false` 时不会调 JNI，
 * 而纯 JVM 环境下该标志恒为 false。
 *
 * 分组：
 *   T1-T7   基础契约（最长前缀 / 兜底规则 / 未命中 / hidden / 覆盖 / 五类前缀）
 *   T8-T13  前缀替换的正确性（曾因 String.replace 全局替换出错）
 *   T14-T20 边界与异常输入（前缀边界 / 包名冲突 / 空串 / 双斜杠 / 超长 / 中文）
 *   T21-T24 幂等与链式（必须只翻一次）
 */
class NativeHookBridgePathTest {

    private lateinit var bridge: NativeHookBridge

    @Before
    fun setUp() {
        bridge = NativeHookBridge()
    }

    // ---------------- T1-T7 基础契约 ----------------

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

    /**
     * T2：宿主包名路径必须落到 guest 自己的 external 目录。
     *
     * 这是离线页面「保存到 A、从 B 读」那个 bug 的回归测试：
     * guest 保存走 getExternalFilesDir -> <sandbox>/sdcard/Android/data/<guest>/
     * guest 读取自己拼 .../Android/data/<getOpPackageName()>/（= 宿主包名）
     * 两边必须是同一个目录。
     */
    @Test
    fun `T2 宿主包名路径落到 guest external 目录`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        val out = bridge.translatePath(
            "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht"
        )
        if (NativeHookBridge.HOST_EXTERNAL_FALLBACK) {
            assertEquals("/sandbox/sdcard/Android/data/mark.via/files/offline/a.mht", out)
        } else {
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
        assertEquals("/sandbox/system/bin/ls", bridge.translatePath("/system/bin/ls"))
    }

    /** T6：同一前缀重复注册时，后注册的覆盖前者（Map 语义）。 */
    @Test
    fun `T6 重复注册同前缀后者覆盖`() {
        bridge.addPathRedirection("/a/", "/first/")
        bridge.addPathRedirection("/a/", "/second/")
        assertEquals("/second/b", bridge.translatePath("/a/b"))
    }

    /** T7：五类必需前缀都被注册（data / user-0 / external / obb）。 */
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

    // ---------------- T8-T13 前缀替换的正确性 ----------------

    /**
     * T8：**只替换前缀，不能替换路径中间出现的同样子串**。
     *
     * 旧实现用 originalPath.replace(bestMatch, bestRedirect)，
     * 输入 /a/x/a/y 会变成 /sandbox/x/sandbox/y —— 完全错误的路径。
     */
    @Test
    fun `T8 中间出现的同样子串不得被替换`() {
        bridge.addPathRedirection("/a/", "/sandbox/")
        assertEquals("/sandbox/x/a/y", bridge.translatePath("/a/x/a/y"))
        assertNotEquals("/sandbox/x/sandbox/y", bridge.translatePath("/a/x/a/y"))
    }

    /** T9：输入恰好等于规则前缀（无剩余部分）时，返回目标前缀。 */
    @Test
    fun `T9 精确匹配前缀`() {
        bridge.addPathRedirection("/data/data/mark.via/", "/sandbox/")
        assertEquals("/sandbox/", bridge.translatePath("/data/data/mark.via/"))
    }

    /** T10：目标前缀无尾斜杠、输入带剩余斜杠 —— 不能拼出双斜杠。 */
    @Test
    fun `T10 目标无尾斜杠不产生双斜杠`() {
        bridge.addPathRedirection("/origin/", "/target")
        assertEquals("/target/x", bridge.translatePath("/origin/x"))
    }

    /** T11：目标前缀有尾斜杠、输入剩余部分也以斜杠开头 —— 只保留一个。 */
    @Test
    fun `T11 两边都有斜杠只保留一个`() {
        bridge.addPathRedirection("/origin", "/target/")
        assertEquals("/target/a/b", bridge.translatePath("/origin/a/b"))
    }

    /** T12：真实规则组合下，offline 目录的完整往返。 */
    @Test
    fun `T12 offline 目录完整往返`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        assertEquals(
            "/sandbox/sdcard/Android/data/mark.via/files/offline/百度一下.mht",
            bridge.translatePath(
                "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/百度一下.mht"
            )
        )
        // 短路径形式 /sdcard/... 也必须落到同一处
        assertEquals(
            "/sandbox/sdcard/Android/data/mark.via/files/offline/x.mht",
            bridge.translatePath("/sdcard/Android/data/com.nextvm.app/files/offline/x.mht")
        )
    }

    /** T13：多条规则并存时，最长前缀仍是决定性因素（与注册顺序无关）。 */
    @Test
    fun `T13 注册顺序不影响最长前缀`() {
        val b2 = NativeHookBridge()
        b2.addPathRedirection("/x/y/z/", "/deep/")
        b2.addPathRedirection("/x/", "/shallow/")
        assertEquals("/deep/1", b2.translatePath("/x/y/z/1"))

        val b3 = NativeHookBridge()
        b3.addPathRedirection("/x/", "/shallow/")
        b3.addPathRedirection("/x/y/z/", "/deep/")
        assertEquals("/deep/1", b3.translatePath("/x/y/z/1"))
    }

    // ---------------- T14-T20 边界与异常输入 ----------------

    /**
     * T14：前缀边界 —— 规则 /a/b 不应匹配 /a/bc。
     *
     * 已知行为：当前实现是纯 startsWith，所以 /a/bc/x 会被 /a/b 命中。
     * 这条测试把现状钉住，便于日后改进时看到差异。
     */
    @Test
    fun `T14 前缀边界现状`() {
        bridge.addPathRedirection("/a/b/", "/target/")
        // 带尾斜杠的规则不会误伤 /a/bc/（因为要求 "/a/b/" 前缀）
        assertEquals("/a/bc/x", bridge.translatePath("/a/bc/x"))
        assertEquals("/target/x", bridge.translatePath("/a/b/x"))
    }

    /**
     * T15：guest 包名互为前缀时不串味。
     *
     * mark.via 与 mark.via.pro 是两个不同应用，规则必须靠尾部斜杠区分。
     */
    @Test
    fun `T15 包名互为前缀不串味`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        assertEquals(
            "/sandbox/a",
            bridge.translatePath("/data/data/mark.via/a")
        )
        // mark.via.pro 不应被 mark.via 的规则命中
        assertEquals(
            "/data/data/mark.via.pro/a",
            bridge.translatePath("/data/data/mark.via.pro/a")
        )
    }

    /** T16：空字符串输入必须原样返回（不能抛异常）。 */
    @Test
    fun `T16 空字符串原样返回`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        assertEquals("", bridge.translatePath(""))
    }

    /** T17：相对路径不被改写（只处理绝对前缀）。 */
    @Test
    fun `T17 相对路径不改写`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        assertEquals("data/data/mark.via/a", bridge.translatePath("data/data/mark.via/a"))
    }

    /** T18：超长路径（2KB）能正常翻译。 */
    @Test
    fun `T18 超长路径`() {
        bridge.addPathRedirection("/origin/", "/target/")
        val deep = "/origin/" + (1..200).joinToString("/") { "seg$it" }
        val out = bridge.translatePath(deep)
        assertTrue(out.startsWith("/target/"))
        assertEquals(deep.length, out.length)
    }

    /** T19：带空格与中文的路径能正常翻译。 */
    @Test
    fun `T19 空格与中文路径`() {
        bridge.addPathRedirection("/origin/", "/target/")
        assertEquals(
            "/target/我的 页面/离线 保存.mht",
            bridge.translatePath("/origin/我的 页面/离线 保存.mht")
        )
    }

    /** T20：hidePath 是精确匹配，不做前缀扩展（su 不应连累 sudo）。 */
    @Test
    fun `T20 hidePath 精确匹配`() {
        bridge.hidePath("/system/bin/su")
        assertEquals("/dev/null", bridge.translatePath("/system/bin/su"))
        assertEquals("/system/bin/sudo", bridge.translatePath("/system/bin/sudo"))
    }

    // ---------------- T21-T24 幂等与链式 ----------------

    /**
     * T21：**翻译必须幂等** —— 翻译后的沙箱路径不能再被规则命中。
     *
     * 这是多层 hook 并存的安全前提：Java 层翻译后再调 libc，
     * libc 的 GOT hook 看到沙箱路径必须原样透传。
     */
    @Test
    fun `T21 翻译幂等`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        val once = bridge.translatePath("/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht")
        val twice = bridge.translatePath(once)
        assertEquals(once, twice)
    }

    /** T22：规则链 A->B、B->C 时，只翻译一次（不会连续跳到 C）。 */
    @Test
    fun `T22 规则链只翻译一次`() {
        bridge.addPathRedirection("/a/", "/b/")
        bridge.addPathRedirection("/b/", "/c/")
        assertEquals("/b/x", bridge.translatePath("/a/x"))
    }

    /** T23：真实规则集下，宿主包名路径的再次翻译必须不变（防双重翻译）。 */
    @Test
    fun `T23 宿主包名路径幂等`() {
        bridge.setupAppRedirections("mark.via", "mark.via_1", "/sandbox")
        val p = "/storage/emulated/0/Android/data/com.nextvm.app/files/x"
        val once = bridge.translatePath(p)
        assertEquals(once, bridge.translatePath(once))
    }

    /** T24：hidden 判定发生在重定向之前 —— 即使路径同时命中规则，也返回 /dev/null。 */
    @Test
    fun `T24 hidden 优先于重定向`() {
        bridge.addPathRedirection("/data/data/mark.via/", "/sandbox/")
        bridge.hidePath("/data/data/mark.via/files/secret")
        assertEquals("/dev/null", bridge.translatePath("/data/data/mark.via/files/secret"))
        assertEquals("/sandbox/files/public", bridge.translatePath("/data/data/mark.via/files/public"))
    }
}
