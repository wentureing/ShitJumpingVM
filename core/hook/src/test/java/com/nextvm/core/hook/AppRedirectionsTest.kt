package com.nextvm.core.hook

import com.nextvm.core.model.PathKind
import com.nextvm.core.model.PathKinds
import com.nextvm.core.model.RedirectOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定三条契约：
 * 1. **白名单隔离**：`externalDataRoot == null` ⇒ 规则集与"没有内容目录外部化"逐条相同；
 * 2. **同构映射**：白名单下 guest 自拼 `.../files/<type>` 与 getter 的 `external_data/files/<type>` 对齐；
 * 3. **注册/删除对称**：`build()` 的 from 与 `removeAppRedirections` 派生的 key 完全一致（否则删不干净）。
 */
class AppRedirectionsTest {

    private val guest = "mark.via"
    private val other = "com.example.other"
    private val sandbox = "/data/user/0/com.nextvm.app/files/virtual/data/mark.via_1"
    private val extRoot = "${AppRedirections.EXTERNAL_DATA_ROOT}/$guest/external_data/"

    private fun baseline(g: String = guest, dir: String = sandbox) =
        AppRedirections.build(g, dir, null)

    private fun bstate(g: String = guest, dir: String = sandbox) =
        AppRedirections.build(g, dir, "${AppRedirections.EXTERNAL_DATA_ROOT}/$g/external_data/")

    /** 模拟 native 的 redirect_path：最长前缀优先，其余部分原样拼接。 */
    private fun resolve(rules: List<Pair<String, String>>, path: String): String? {
        val best = rules.filter { path.startsWith(it.first) }.maxByOrNull { it.first.length }
            ?: return null
        return best.second + path.substring(best.first.length)
    }

    // ---------------- 一、规则集：有/无白名单逐条对比 ----------------

    @Test
    fun `非白名单规则数与历史基线一致_16 条且不含 vsandbox`() {
        val rules = baseline()
        assertEquals(16, rules.size)
        assertTrue("非白名单不得出现 vsandbox", rules.none { it.second.contains("vsandbox") })
        assertTrue("非白名单不得出现 external_data", rules.none { it.second.contains("external_data") })
    }

    @Test
    fun `白名单只改 6 条外部族_其余 10 条逐条与基线相同`() {
        val base = baseline().toMap()
        val b = bstate().toMap()
        assertEquals("规则条数不变", base.size, b.size)

        val changed = base.keys.filter { base[it] != b[it] }.toSet()
        val expectedChanged = setOf(
            "/storage/emulated/0/Android/data/$guest/",
            "/sdcard/Android/data/$guest/",
            "/storage/emulated/0/Android/data/$guest",
            "/sdcard/Android/data/$guest",
            AppRedirections.HOST_EXT_PREFIX_A,
            AppRedirections.HOST_EXT_PREFIX_B,
        )
        assertEquals("变化的必须恰好是这 6 条", expectedChanged, changed)
        assertTrue("变化的都指向 vsandbox/external_data", changed.all { b[it]!!.startsWith(extRoot) })

        // 未变化的 10 条：data/data、data/user/0、obb×4、media×4
        val unchanged = base.keys - changed
        assertEquals(10, unchanged.size)
        for (k in unchanged) assertEquals("未变项必须逐字相同：$k", base[k], b[k])
        assertTrue(unchanged.any { it.startsWith("/data/data/") })
        assertTrue(unchanged.any { it.startsWith("/data/user/0/") })
        assertTrue(unchanged.any { it.contains("/Android/obb/") })
        assertTrue(unchanged.any { it.contains("/Android/media/") })
    }

    @Test
    fun `注册的 from 与 removeAppRedirections 派生的 key 完全一致`() {
        val registered = AppRedirections.build(guest, sandbox, extRoot).map { it.first }.toSet()

        val removed = LinkedHashSet<String>()
        for (kind in PathKind.entries) {
            if (PathKinds.of(kind).owner != RedirectOwner.APP) continue
            removed += PathKinds.hostForms(kind, guest)
        }
        removed += PathKinds.hostFallbackForms(com.nextvm.core.common.VirtualConstants.HOST_PACKAGE)

        assertEquals("注册与删除必须对称，否则残留规则", removed, registered)
    }

    // ---------------- 二、映射结果：多路径 × 两态 ----------------

    @Test
    fun `自拼宿主路径在两种状态下的落点对照`() {
        // guest 用 Environment + getOpPackageName() 拼出来的读路径
        val cases = listOf(
            "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht" to
                "$sandbox/sdcard/Android/data/$guest/files/offline/a.mht",
            "/sdcard/Android/data/com.nextvm.app/files/tabs/t1" to
                "$sandbox/sdcard/Android/data/$guest/files/tabs/t1",
            "/storage/emulated/0/Android/data/com.nextvm.app/cache/c1" to
                "$sandbox/sdcard/Android/data/$guest/cache/c1",
        )
        for ((input, expectSandbox) in cases) {
            assertEquals("非白名单：$input", expectSandbox, resolve(baseline(), input))
        }

        val bRules = bstate()
        assertEquals(
            "${extRoot}files/offline/a.mht",
            resolve(bRules, "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht"),
        )
        assertEquals(
            "${extRoot}files/tabs/t1",
            resolve(bRules, "/sdcard/Android/data/com.nextvm.app/files/tabs/t1"),
        )
        assertEquals(
            "${extRoot}cache/c1",
            resolve(bRules, "/storage/emulated/0/Android/data/com.nextvm.app/cache/c1"),
        )
    }

    @Test
    fun `同构验证_自拼路径与 getter rel 落到同一目录`() {
        // getter（VirtualContext）在白名单下拼的是 EXTERNAL_CONTENT_ROOT/<pkg>/external_data/files/<type>
        val getterForOffline = "${AppRedirections.EXTERNAL_DATA_ROOT}/$guest/external_data/files/offline"
        val resolvedDir = resolve(
            bstate(),
            "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/x.mht",
        )!!.substringBeforeLast('/')
        assertEquals("自拼路径必须与 getter 同目录", getterForOffline, resolvedDir)

        val getterForCache = "${AppRedirections.EXTERNAL_DATA_ROOT}/$guest/external_data/cache"
        val resolvedCache = resolve(
            bstate(),
            "/storage/emulated/0/Android/data/com.nextvm.app/cache/y",
        )!!.substringBeforeLast('/')
        assertEquals(getterForCache, resolvedCache)
    }

    @Test
    fun `guest 自身包名的外部路径在两种状态下都指向同一落点`() {
        for ((name, rules) in listOf("baseline" to baseline(), "bstate" to bstate())) {
            val target = rules.first { it.first == "/storage/emulated/0/Android/data/$guest/" }.second
            assertEquals(name, resolve(rules, "/storage/emulated/0/Android/data/$guest/files/x"),
                target + "files/x")
        }
    }

    // ---------------- 三、互不危害 ----------------

    @Test
    fun `白名单 guest 的规则不影响另一个非白名单 guest`() {
        val viaRules = bstate().toMap().keys
        val otherRules = AppRedirections.build(other, "/sandbox/$other", null)
        // 另一个 guest 的 from 里不应出现 mark.via 或 vsandbox 目标
        assertTrue(otherRules.none { it.first.contains(guest) })
        assertTrue(otherRules.none { it.second.contains("vsandbox") })
        // 另一个 guest 的路径用另一 guest 的规则解析，绝不命中 via 的规则
        val path = "/storage/emulated/0/Android/data/com.nextvm.app/files/a"
        assertEquals(
            "/sandbox/$other/sdcard/Android/data/$other/files/a",
            resolve(otherRules, path),
        )
        assertTrue("via 规则集与 other 规则集 from 不相交", (viaRules intersect otherRules.map { it.first }.toSet()).isEmpty())
    }

    @Test
    fun `同一 guest 两态切换互不残留_规则集大小恒定`() {
        val b = bstate()
        val base = baseline()
        assertEquals(b.size, base.size)
        // 切回基线后不得残留任何 vsandbox 目标
        assertTrue(baseline().none { it.second.contains("vsandbox") })
        // 再切回白名单，内容与首次一致（幂等）
        assertEquals(b.toMap(), bstate().toMap())
    }

    @Test
    fun `build 与规则顺序无关_最长前缀优先`() {
        val rules = bstate()
        val shuffled = rules.shuffled(java.util.Random(42))
        val path = "/storage/emulated/0/Android/data/com.nextvm.app/files/offline/a.mht"
        assertEquals(resolve(rules, path), resolve(shuffled, path))
    }

    @Test
    fun `不属于任何规则的外部路径不被改写`() {
        for (rules in listOf(baseline(), bstate())) {
            assertNull(resolve(rules, "/storage/emulated/0/Download/a.pdf"))
            assertNull(resolve(rules, "/storage/emulated/0/DCIM/a.jpg"))
        }
    }

    @Test
    fun `obb 与 media 不受白名单影响`() {
        val base = baseline().toMap()
        val b = bstate().toMap()
        val keys = base.keys.filter { it.contains("/Android/obb/") || it.contains("/Android/media/") }
        assertEquals(8, keys.size)
        for (k in keys) {
            assertEquals(k, base[k], b[k])
            assertNotEquals("obb/media 不得指向 vsandbox", true, b[k]!!.contains("vsandbox"))
        }
    }
}
