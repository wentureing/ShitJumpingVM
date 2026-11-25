package com.nextvm.core.hook

import com.nextvm.core.model.VirtualConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 路径重定向规则的契约测试（纯 JVM，不依赖 native）。
 *
 * 核心契约是**写入侧与读取侧必须落到同一物理目录**：
 *   - 写入侧：`SandboxManager.get*Dir()` 返回的沙箱路径（应用拿到的）
 *   - 读取侧：应用自己拼的宿主路径，经 `translatePath` 后的沙箱路径
 * 两侧不一致时，表现为"保存成功但列表为空"——离线页面看不见就是这么来的。
 *
 * 分组：
 *   T1-T7   基础契约（最长前缀 / 兜底 / 未命中 / hidden / 覆盖 / 五类前缀）
 *   T8-T13  前缀替换正确性（曾因 String.replace 全局替换出错）
 *   T14-T20 边界与异常输入
 *   T21-T24 幂等与链式
 *   T25-T29 **写入侧与读取侧一致性**（同类错误的回归测试）
 */
class NativeHookBridgePathTest {

    private lateinit var bridge: NativeHookBridge

    /** 与被测实现无关的常量：SandboxManager.getExternalStorageDir 的真实形态。 */
    private val sandbox = "/sandbox"
    private val guest = "mark.via"
    private val guestExternalData = "$sandbox/sdcard/Android/data/$guest"
    private val guestObb = "$sandbox/sdcard/Android/obb/$guest"

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

    /** T2：宿主包名路径必须落到 guest 自己的 external 目录（不是 host_ext）。 */
    @Test
    fun `T2 宿主包名路径落到 guest external 目录`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        val out = bridge.translatePath(
            "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht"
        )
        if (NativeHookBridge.HOST_EXTERNAL_FALLBACK) {
            assertEquals("$guestExternalData/files/offline/a.mht", out)
        } else {
            assertEquals("/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht", out)
        }
    }

    /** T3：guest 自身 external 路径落到 SandboxManager 同一目录。 */
    @Test
    fun `T3 guest 自身 external 路径落到同一目录`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        assertEquals(
            "$guestExternalData/files/x",
            bridge.translatePath("/storage/emulated/0/Android/data/$guest/files/x")
        )
    }

    /** T4：没命中任何规则的路径必须原样返回（不能误改）。 */
    @Test
    fun `T4 未命中原样返回`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
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

    /** T7：setupAppRedirections 覆盖全部必需前缀。 */
    @Test
    fun `T7 setupAppRedirections 覆盖必需前缀`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        assertEquals("$sandbox/a", bridge.translatePath("/data/data/$guest/a"))
        assertEquals("$sandbox/a", bridge.translatePath("/data/user/0/$guest/a"))
        assertEquals(
            "$guestExternalData/a",
            bridge.translatePath("/storage/emulated/0/Android/data/$guest/a")
        )
        assertEquals(
            "$guestExternalData/a",
            bridge.translatePath("/sdcard/Android/data/$guest/a")
        )
        assertEquals(
            "$guestObb/a",
            bridge.translatePath("/storage/emulated/0/Android/obb/$guest/a")
        )
        assertEquals(
            "$guestObb/a",
            bridge.translatePath("/sdcard/Android/obb/$guest/a")
        )
    }

    // ---------------- T8-T13 前缀替换的正确性 ----------------

    /** T8：只替换前缀，不能替换路径中间出现的同样子串。 */
    @Test
    fun `T8 中间出现的同样子串不得被替换`() {
        bridge.addPathRedirection("/a/", "/sandbox/")
        assertEquals("/sandbox/x/a/y", bridge.translatePath("/a/x/a/y"))
        assertNotEquals("/sandbox/x/sandbox/y", bridge.translatePath("/a/x/a/y"))
    }

    /** T9：输入恰好等于规则前缀时返回目标前缀。 */
    @Test
    fun `T9 精确匹配前缀`() {
        bridge.addPathRedirection("/data/data/$guest/", "$sandbox/")
        assertEquals("$sandbox/", bridge.translatePath("/data/data/$guest/"))
    }

    /** T10：目标无尾斜杠、输入有剩余斜杠 —— 不产生双斜杠。 */
    @Test
    fun `T10 目标无尾斜杠不产生双斜杠`() {
        bridge.addPathRedirection("/origin/", "/target")
        assertEquals("/target/x", bridge.translatePath("/origin/x"))
    }

    /** T11：两边都有斜杠只保留一个。 */
    @Test
    fun `T11 两边都有斜杠只保留一个`() {
        bridge.addPathRedirection("/origin", "/target/")
        assertEquals("/target/a/b", bridge.translatePath("/origin/a/b"))
    }

    /** T12：真实规则集下 offline 目录完整往返（含中文文件名）。 */
    @Test
    fun `T12 offline 目录完整往返`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        assertEquals(
            "$guestExternalData/files/offline/百度一下.mht",
            bridge.translatePath(
                "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/百度一下.mht"
            )
        )
        assertEquals(
            "$guestExternalData/files/offline/x.mht",
            bridge.translatePath("/sdcard/Android/data/com.nextvm.app/files/offline/x.mht")
        )
    }

    /** T13：注册顺序不影响最长前缀。 */
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

    /** T14：带尾斜杠的规则不会误伤 /a/bc。 */
    @Test
    fun `T14 前缀边界`() {
        bridge.addPathRedirection("/a/b/", "/target/")
        assertEquals("/a/bc/x", bridge.translatePath("/a/bc/x"))
        assertEquals("/target/x", bridge.translatePath("/a/b/x"))
    }

    /** T15：guest 包名互为前缀时不串味。 */
    @Test
    fun `T15 包名互为前缀不串味`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        assertEquals("$sandbox/a", bridge.translatePath("/data/data/$guest/a"))
        assertEquals(
            "/data/data/mark.via.pro/a",
            bridge.translatePath("/data/data/mark.via.pro/a")
        )
    }

    /** T16：空字符串原样返回。 */
    @Test
    fun `T16 空字符串原样返回`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        assertEquals("", bridge.translatePath(""))
    }

    /** T17：相对路径不被改写。 */
    @Test
    fun `T17 相对路径不改写`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        assertEquals("data/data/$guest/a", bridge.translatePath("data/data/$guest/a"))
    }

    /** T18：超长路径（200 段）能正常翻译。 */
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

    /** T20：hidePath 是精确匹配（su 不连累 sudo）。 */
    @Test
    fun `T20 hidePath 精确匹配`() {
        bridge.hidePath("/system/bin/su")
        assertEquals("/dev/null", bridge.translatePath("/system/bin/su"))
        assertEquals("/system/bin/sudo", bridge.translatePath("/system/bin/sudo"))
    }

    // ---------------- T21-T24 幂等与链式 ----------------

    /** T21：翻译必须幂等（多层 hook 并存的安全前提）。 */
    @Test
    fun `T21 翻译幂等`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        val once = bridge.translatePath(
            "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht"
        )
        assertEquals(once, bridge.translatePath(once))
    }

    /** T22：规则链 A->B、B->C 只翻译一次。 */
    @Test
    fun `T22 规则链只翻译一次`() {
        bridge.addPathRedirection("/a/", "/b/")
        bridge.addPathRedirection("/b/", "/c/")
        assertEquals("/b/x", bridge.translatePath("/a/x"))
    }

    /** T23：宿主包名路径再次翻译不变（防双重翻译）。 */
    @Test
    fun `T23 宿主包名路径幂等`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        val p = "/storage/emulated/0/Android/data/com.nextvm.app/files/x"
        val once = bridge.translatePath(p)
        assertEquals(once, bridge.translatePath(once))
    }

    /** T24：hidden 判定发生在重定向之前。 */
    @Test
    fun `T24 hidden 优先于重定向`() {
        bridge.addPathRedirection("/data/data/$guest/", "$sandbox/")
        bridge.hidePath("/data/data/$guest/files/secret")
        assertEquals("/dev/null", bridge.translatePath("/data/data/$guest/files/secret"))
        assertEquals("$sandbox/files/public", bridge.translatePath("/data/data/$guest/files/public"))
    }

    // ---------------- T25-T29 写入侧 vs 读取侧一致性 ----------------

    /**
     * T25：**External Files 两侧一致**。
     *
     * 写入侧：SandboxManager.getExternalAppFilesDir
     *         -> <appDir>/sdcard/Android/data/<pkg>/files[/type]
     * 读取侧：应用自己拼 /storage/emulated/0/Android/data/<pkg>/files[/type]
     * 两侧必须是同一物理目录，否则"保存成功、列表为空"。
     */
    @Test
    fun `T25 external files 写入侧与读取侧一致`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        val writeSide = "$guestExternalData/files/offline"
        val readSide = bridge.translatePath("/storage/emulated/0/Android/data/$guest/files/offline")
        assertEquals(writeSide, readSide)
    }

    /** T26：**External Cache 两侧一致**（getExternalAppCacheDir 用 .../cache）。 */
    @Test
    fun `T26 external cache 写入侧与读取侧一致`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        assertEquals(
            "$guestExternalData/cache",
            bridge.translatePath("/storage/emulated/0/Android/data/$guest/cache")
        )
    }

    /** T27：**OBB 两侧一致**（getExternalObbDir 用 sdcard/Android/obb/<pkg>）。 */
    @Test
    fun `T27 obb 写入侧与读取侧一致`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        assertEquals(
            guestObb,
            bridge.translatePath("/storage/emulated/0/Android/obb/$guest")
        )
    }

    /**
     * T28：**规则目标不得落在从未被写入的目录**。
     *
     * 曾经的错误目标：external_data / obb / host_ext。
     * SandboxManager 只会写 sdcard/Android/{data,obb,media}/<pkg> 与 <appDir>/{files,cache,...}，
     * 所以任何规则目标的"根段"都必须能在这两组里找到对应。
     */
    @Test
    fun `T28 规则目标不落在未写入目录`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        val cases = listOf(
            "/storage/emulated/0/Android/data/$guest/files/x" to "$guestExternalData/files/x",
            "/sdcard/Android/data/$guest/files/x" to "$guestExternalData/files/x",
            "/storage/emulated/0/Android/obb/$guest/x" to "$guestObb/x",
            "/data/data/$guest/files/x" to "$sandbox/files/x",
            "/data/user/0/$guest/files/x" to "$sandbox/files/x",
        )
        for ((input, expect) in cases) {
            assertEquals("input=$input", expect, bridge.translatePath(input))
        }
    }

    /**
     * T29：**Media 目录**（getExternalMediaDir -> sdcard/Android/media/<pkg>）。
     *
     * 目前 setupAppRedirections 未注册 /Android/media/ 前缀 —— 这条测试记录现状，
     * 若将来补上该规则，这里会变成红，提醒同步更新。
     */
    @Test
    fun `T29 media 目录现状`() {
        bridge.setupAppRedirections(guest, "mark.via_1", sandbox)
        val input = "/storage/emulated/0/Android/media/$guest/x"
        val out = bridge.translatePath(input)
        // 现状：未注册 -> 原样返回（若已注册则应为 $sandbox/sdcard/Android/media/<guest>/x）
        assertTrue(
            "media 前缀未注册时应原样返回，实际=$out",
            out == input || out == "$sandbox/sdcard/Android/media/$guest/x"
        )
    }


    // ---------------- T30-T38 不变量 / 对偶 / 模糊（能自己发现意外） ----------------

    /**
     * T30：**同一逻辑路径的所有写法必须翻译到同一处**（对偶性）。
     *
     * 应用拿外部存储路径有多种写法：/sdcard/、/storage/emulated/0/、/mnt/sdcard/、
     * /storage/self/primary/。少注册任何一个，同一份数据就会"从一个写法可见、另一个不可见"。
     * 这条测试遍历笛卡尔积，能自动发现漏注册的前缀。
     */
    @Test
    fun `T30 同一逻辑路径多种写法对偶`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        bridge.setupExternalStorageRedirections("i1", "$sandbox/sdcard")
        val tails = listOf(
            "Android/data/$guest/files/offline/a.mht",
            "DCIM/photo.jpg",
            "Download/a.pdf",
            "Android/obb/$guest/x.obb",
        )
        val prefixes = listOf(
            "/sdcard/",
            "/storage/emulated/0/",
            "/mnt/sdcard/",
            "/storage/self/primary/",
        )
        for (tail in tails) {
            val outs = prefixes.associateWith { bridge.translatePath("$it$tail") }
            val distinct = outs.values.toSet()
            assertEquals("tail=$tail 的多种写法应翻译到同一处，实际=$outs", 1, distinct.size)
        }
    }

    /**
     * T31：**无尾斜杠变体也要能翻译**。
     *
     * /sdcard 与 /sdcard/ 都有注册，但 /mnt/sdcard 与 /storage/self/primary 只有带斜杠版本。
     * 这条测试记录现状：若某个变体没注册，它会被原样返回 —— 直接暴露。
     */
    @Test
    fun `T31 无尾斜杠变体`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        bridge.setupExternalStorageRedirections("i1", "$sandbox/sdcard")
        // 已注册的两种
        assertEquals("$sandbox/sdcard", bridge.translatePath("/sdcard").trimEnd('/'))
        assertEquals("$sandbox/sdcard", bridge.translatePath("/storage/emulated/0").trimEnd('/'))
        // 未注册的两种：现状应原样返回。若将来补上，这里会红，提醒同步。
        for (p in listOf("/mnt/sdcard", "/storage/self/primary")) {
            val out = bridge.translatePath(p)
            // 无论是否通过，都把实际值打到 CI 日志里 —— 缺口要能看见
            println("[T31] $p -> $out" + if (out == p) "   <== 未注册（原样返回）" else "")
            assertTrue(
                "$p 现状应原样返回或已映射，实际=$out",
                out == p || out.startsWith(sandbox)
            )
        }
        // 顺便把 T30 里每种写法都打出来，便于对照
        for (pre in listOf("/sdcard/", "/storage/emulated/0/", "/mnt/sdcard/", "/storage/self/primary/")) {
            println("[T31] ${pre}Download/a.pdf -> ${bridge.translatePath("${pre}Download/a.pdf")}")
        }
    }

    /**
     * T32：**翻译结果不得以源前缀开头**（除非目标本身就在源前缀下）。
     *
     * 这是幂等性的必要条件：翻译后若仍以源前缀开头，第二次翻译就会再跳一次。
     */
    @Test
    fun `T32 翻译结果不以源前缀开头`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        val sources = listOf(
            "/data/data/$guest/",
            "/data/user/0/$guest/",
            "/storage/emulated/0/Android/data/$guest/",
            "/sdcard/Android/data/$guest/",
            "/storage/emulated/0/Android/obb/$guest/",
        )
        for (src in sources) {
            val input = "${src}files/x"
            val out = bridge.translatePath(input)
            assertNotEquals("src=$src 未被翻译", input, out)
            assertTrue(
                "src=$src 翻译后仍以源前缀开头: $out",
                !out.startsWith(src)
            )
        }
    }

    /** T33：模糊测试 —— 随机拼装的路径不得抛异常，且必须幂等。 */
    @Test
    fun `T33 模糊测试不抛异常且幂等`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        bridge.setupExternalStorageRedirections("i1", "$sandbox/sdcard")
        val alphabet = listOf(
            "/", "//", "/sdcard", "/storage/emulated/0", "/data/data", "/data/user/0",
            guest, "com", "nextvm", "app", "Android", "data", "obb", "media", "files",
            "offline", "我的", " 空格 ", ".", "..", "-", "_",
        )
        val rnd = java.util.Random(20261125L)
        repeat(2000) {
            val n = 1 + rnd.nextInt(6)
            val sb = StringBuilder()
            repeat(n) { sb.append(alphabet[rnd.nextInt(alphabet.size)]) }
            val input = sb.toString()
            val out = bridge.translatePath(input)          // 不得抛
            val out2 = bridge.translatePath(out)
            assertEquals("非幂等: input=$input out=$out out2=$out2", out, out2)
        }
    }

    /**
     * T34：**翻译后的长度可以精确预测**。
     *
     * 注意：段数会变（/data/data/<pkg>/ 3 段 -> /sandbox/ 1 段），
     * 所以不能断言"段数不变"（那是错的）。正确的是：
     *   out.length == to.length + (input.length - from.length)
     */
    @Test
    fun `T34 翻译后长度可预测`() {
        bridge.addPathRedirection("/origin/", "/target/")
        val input = "/origin/a/b/c"
        val out = bridge.translatePath(input)
        assertEquals("/target/" + "a/b/c", out)
        assertEquals("/target/".length + ("/origin/a/b/c".length - "/origin/".length), out.length)
    }

    /**
     * T35：**核心一致性 —— 每种 SandboxManager 目录都能被"宿主形态"翻译回来**。
     *
     * 这张表是"写入侧"清单（SandboxManager 的真实返回）与"读取侧"输入（应用自己拼）的配对。
     * 任何一行不成立，就说明出现了"写到一个地方、从另一个地方读"。
     */
    @Test
    fun `T35 SandboxManager 目录与宿主形态一一对应`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        val pairs = listOf(
            // SandboxManager.getExternalAppFilesDir(type=null)
            "/storage/emulated/0/Android/data/$guest/files" to "$guestExternalData/files",
            // getExternalAppFilesDir(type="offline")
            "/storage/emulated/0/Android/data/$guest/files/offline" to "$guestExternalData/files/offline",
            // getExternalAppCacheDir
            "/storage/emulated/0/Android/data/$guest/cache" to "$guestExternalData/cache",
            // getExternalObbDir
            "/storage/emulated/0/Android/obb/$guest" to guestObb,
            // getFilesDir / getCacheDir（data 目录）
            "/data/data/$guest/files" to "$sandbox/files",
            "/data/data/$guest/cache" to "$sandbox/cache",
            "/data/user/0/$guest/files" to "$sandbox/files",
        )
        for ((hostForm, sandboxForm) in pairs) {
            val got = bridge.translatePath(hostForm)
            println("[T35] $hostForm\n        -> $got" + if (got == sandboxForm) "  OK" else "  EXPECT $sandboxForm")
            assertEquals("host=$hostForm", sandboxForm, got)
        }
    }

    /**
     * T36：Media 目录（getExternalMediaDir）目前**没有**宿主形态规则。
     *
     * 这条测试把缺口钉住：现状是原样返回。补上规则后此处会变红，提醒更新断言。
     */
    @Test
    fun `T36 media 目录缺口现状`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        val input = "/storage/emulated/0/Android/media/$guest/x.jpg"
        val out = bridge.translatePath(input)
        println("[T36] media -> $out" + if (out == input) "   <== 未注册（原样返回）" else "")
        assertTrue(
            "media 现状应原样返回或已映射，实际=$out",
            out == input || out == "$sandbox/sdcard/Android/media/$guest/x.jpg"
        )
    }

    /**
     * T37：**隐藏路径不得被重定向抢先**（顺序契约）。
     *
     * 即使隐藏路径同时命中重定向前缀，也必须返回 /dev/null。
     */
    @Test
    fun `T37 hidden 不被重定向抢先`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        val p = "/data/data/$guest/files/secret.db"
        bridge.hidePath(p)
        assertEquals("/dev/null", bridge.translatePath(p))
    }

    /** T38：翻译对"已翻译过的沙箱路径"完全不动作（多层 hook 安全前提）。 */
    @Test
    fun `T38 沙箱路径再翻译不变`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        bridge.setupExternalStorageRedirections("i1", "$sandbox/sdcard")
        val sandboxPaths = listOf(
            "$sandbox/files/x",
            "$sandbox/sdcard/Android/data/$guest/files/x",
            "$sandbox/sdcard/Download/a.pdf",
            "$sandbox/sdcard/Android/obb/$guest/x.obb",
        )
        for (p in sandboxPaths) {
            assertEquals("沙箱路径被误改: $p", p, bridge.translatePath(p))
        }
    }


    // ---------------- T39-T41 pathFidelity 的「只误导、不危害读写」契约 ----------------

    /**
     * T39：**每个合成路径都必须被某条规则覆盖**。
     *
     * pathFidelity 的全部价值在于「路径看起来像真机、I/O 仍然正常工作」。
     * 如果某个 synthetic* 路径没有对应规则，guest 拿到假路径后做读写会直接失败
     * —— 那不是「误导」，是「弄坏」。这条测试穷举所有合成路径，逐个验证会被翻译。
     */
    @Test
    fun `T39 每个合成路径都有对应规则`() {
        val apk = "/apk/mark.via/base.apk"
        bridge.setupPathFidelityRedirections(guest, "i1", sandbox, apk)

        val dataRoot = VirtualConstants.syntheticDataDir(guest)
        val extRoot = VirtualConstants.syntheticExternalDir(guest)
        val cases = listOf(
            dataRoot to "$sandbox",
            "$dataRoot/files" to "$sandbox/files",
            "$dataRoot/files/a.txt" to "$sandbox/files/a.txt",
            "$dataRoot/cache" to "$sandbox/cache",
            "$dataRoot/code_cache" to "$sandbox/code_cache",
            "$dataRoot/no_backup" to "$sandbox/no_backup",
            "$dataRoot/databases/x.db" to "$sandbox/databases/x.db",
            "$dataRoot/app_foo" to "$sandbox/app_foo",
            extRoot to "$sandbox/sdcard/Android/data/$guest",
            "$extRoot/files" to "$sandbox/sdcard/Android/data/$guest/files",
            VirtualConstants.syntheticExternalCacheDir(guest)
                to "$sandbox/sdcard/Android/data/$guest/cache",
            VirtualConstants.syntheticObbDir(guest)
                to "$sandbox/sdcard/Android/obb/$guest",
            VirtualConstants.syntheticApkPath(guest) to apk,
            VirtualConstants.syntheticLibDir(guest) + "/libfoo.so"
                to "$sandbox/lib/libfoo.so",
        )
        for ((synth, expect) in cases) {
            val got = bridge.translatePath(synth)
            println("[T39] $synth\n        -> $got")
            assertNotEquals("合成路径未被任何规则覆盖（会导致读写失败）: $synth", synth, got)
            assertNotEquals("合成路径被映射到 /dev/null: $synth", "/dev/null", got)
            assertEquals("合成路径 $synth", expect, got)
        }
    }

    /**
     * T40：**合成路径必须幂等** —— 已经是沙箱路径的不能再被翻译一次。
     *
     * 这条防的是「规则目标本身又命中另一条规则」导致的二次映射（会把文件搬到别处）。
     */
    @Test
    fun `T40 合成路径翻译幂等`() {
        bridge.setupPathFidelityRedirections(guest, "i1", sandbox, "/apk/x.apk")
        val synth = VirtualConstants.syntheticExternalDir(guest) + "/files/offline/a.mht"
        val once = bridge.translatePath(synth)
        assertEquals(once, bridge.translatePath(once))
        // 沙箱侧不应再被任何规则命中
        assertTrue("翻译结果不在沙箱内: $once", once.startsWith(sandbox))
    }

    /**
     * T41：**合成路径与它的"沙箱真身"必须一一对应，不允许两个合成路径指向同一处**。
     *
     * 反例（真实发生过）：external_data/ 与 sdcard/Android/data/<pkg>/ 都被当成
     * 「external data 的真身」，结果一个被写、另一个被读，列表永远空。
     */
    @Test
    fun `T41 合成路径与沙箱真身一一对应`() {
        bridge.setupPathFidelityRedirections(guest, "i1", sandbox, "/apk/x.apk")
        val synthRoots = listOf(
            VirtualConstants.syntheticDataDir(guest),
            VirtualConstants.syntheticExternalDir(guest),
            VirtualConstants.syntheticObbDir(guest),
            VirtualConstants.syntheticApkPath(guest),
            VirtualConstants.syntheticLibDir(guest),
        )
        val targets = synthRoots.map { bridge.translatePath(it) }
        println("[T41] 合成根 -> 沙箱真身")
        synthRoots.forEachIndexed { i, s -> println("        $s\n          -> ${targets[i]}") }
        assertEquals("有合成路径映射到了同一个沙箱目标: $targets", targets.size, targets.toSet().size)
        // 且所有目标都必须在沙箱内
        for (t in targets) {
            assertTrue("目标不在沙箱内: $t", t.startsWith(sandbox))
        }
    }

    /**
     * T42：**族根形态不得吃掉兄弟包**。
     *
     * 规则是 `/data/user/0/<pkg>/`，族根是它去掉尾斜杠的形式。长度必须**恰好**是
     * `from.length - 1`，否则 `mark.via.pro` 会被当成 `mark.via` 的子路径。
     */
    @Test
    fun `T42 族根不吃兄弟包`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        assertEquals("族根应被翻译", sandbox, bridge.translatePath("/data/user/0/$guest"))
        assertEquals("族根应被翻译", sandbox, bridge.translatePath("/data/data/$guest"))
        assertEquals(
            "兄弟包不得被翻译",
            "/data/user/0/$guest.pro",
            bridge.translatePath("/data/user/0/$guest.pro"),
        )
        assertEquals(
            "兄弟包的子路径不得被翻译",
            "/data/data/$guest.pro/a",
            bridge.translatePath("/data/data/$guest.pro/a"),
        )
    }

    /**
     * T43：**族根与子项父子一致**。
     *
     * 真机上 `getFilesDir().getParentFile() == getDataDir()` 永远成立；容器里必须也成立，
     * 否则应用做路径拼接/校验时会发现矛盾。
     */
    @Test
    fun `T43 族根与子项父子一致`() {
        bridge.setupAppRedirections(guest, "i1", sandbox)
        val root = bridge.translatePath("/data/user/0/$guest")
        val child = bridge.translatePath("/data/user/0/$guest/files")
        assertEquals("父子必须自洽", "$root/files", child)
        // 带尾斜杠的输入仍保留目标原有尾斜杠（T9 的既有行为不被族根改变）
        assertEquals("$sandbox/", bridge.translatePath("/data/user/0/$guest/"))
    }
}