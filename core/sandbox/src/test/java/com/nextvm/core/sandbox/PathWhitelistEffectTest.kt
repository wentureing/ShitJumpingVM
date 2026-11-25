package com.nextvm.core.sandbox

import com.nextvm.core.model.PathKind
import com.nextvm.core.model.PathKinds
import com.nextvm.core.model.PathPolicy
import com.nextvm.core.model.PathSpoofConfig
import com.nextvm.core.model.RedirectOwner
import com.nextvm.core.model.VirtualConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 「白名单是否生效」的自动化验证（纯 JVM，不需要 Robolectric）。
 *
 * 被测的是一条真实调用链，不新增抽象：
 *   `path_spoof.conf` 两份名单 → `VirtualConstants.isPathFidelityGuest`
 *   → `VirtualContext.contentDir` 的判据 [shouldUseSyntheticContent]
 *   → （隔离侧）`PathPolicy.isRedirectionEnabled` + `PathKinds.rulesFor`
 *
 * 语义（docs/设计/设计-澄清-欺骗与隔离与合成路径.md）：
 *   欺骗（合成路径）= 可配置名单；豁免 = 关欺骗**不关隔离**（写入仍落沙箱）。
 */
class PathWhitelistEffectTest {

    private lateinit var dir: File

    /** 默认进「欺骗名单」的包（模板里写的是 CM_PACKAGE）。 */
    private val spoofed = VirtualConstants.CM_PACKAGE

    /** 默认进「豁免名单」的包。 */
    private val exempt = PathSpoofConfig.DEFAULT_EXEMPT_PKG

    private val stranger = "com.example.not.listed"

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "whitelist_test_${System.nanoTime()}")
        dir.mkdirs()
        PathSpoofConfig.load(dir)
    }

    @After
    fun tearDown() {
        // 单例状态会影响同模块其它测试，显式还原
        PathSpoofConfig.setGuestExempt(spoofed, false)
        PathSpoofConfig.setSpoofListed(stranger, false)
        dir.deleteRecursively()
    }

    // ---------------- W1-W2：默认名单生效 ----------------

    /** W1：配置模板生成后，欺骗名单里的包 `isPathFidelityGuest` 为真。 */
    @Test
    fun `W1 欺骗名单内的包生效`() {
        assertTrue("模板未生成", File(dir, PathSpoofConfig.FILE_NAME).exists())
        assertTrue("默认欺骗名单应包含 $spoofed", PathSpoofConfig.isSpoofListed(spoofed))
        assertTrue("$spoofed 应启用合成路径", VirtualConstants.isPathFidelityGuest(spoofed))
    }

    /** W2：默认豁免（via）与名单外的包都不合成。 */
    @Test
    fun `W2 豁免与名单外都不合成`() {
        assertTrue("$exempt 应在豁免名单", PathSpoofConfig.isGuestExempt(exempt))
        assertFalse("豁免包不合成", VirtualConstants.isPathFidelityGuest(exempt))
        assertFalse("名单外不合成", VirtualConstants.isPathFidelityGuest(stranger))
    }

    // ---------------- W3-W4：运行时改名单立即生效（重启后仍生效的另一半） ----------------

    /** W3：把包加进欺骗名单 → 立即合成；移出 → 立即回真。 */
    @Test
    fun `W3 改欺骗名单立即生效`() {
        assertFalse(VirtualConstants.isPathFidelityGuest(stranger))

        assertTrue(PathSpoofConfig.setSpoofListed(stranger, true))
        assertTrue("加进名单后应合成", VirtualConstants.isPathFidelityGuest(stranger))
        assertTrue("应写回文件", File(dir, PathSpoofConfig.FILE_NAME).readText().contains(stranger))

        assertTrue(PathSpoofConfig.setSpoofListed(stranger, false))
        assertFalse("移出名单后不应合成", VirtualConstants.isPathFidelityGuest(stranger))
    }

    /** W4：重复增删返回 false（CmdReceiver 用它回“无变化”）。 */
    @Test
    fun `W4 重复增删返回 false`() {
        assertTrue(PathSpoofConfig.setSpoofListed(stranger, true))
        assertFalse("重复添加", PathSpoofConfig.setSpoofListed(stranger, true))
        assertFalse("移除不在册的包", PathSpoofConfig.setGuestExempt(stranger, false))
    }

    // ---------------- W5：豁免优先于欺骗名单 ----------------

    /** W5：同一个包同时在两份名单里 → 豁免优先（不欺骗）。 */
    @Test
    fun `W5 豁免优先于欺骗名单`() {
        assertTrue(VirtualConstants.isPathFidelityGuest(spoofed))
        assertTrue(PathSpoofConfig.setGuestExempt(spoofed, true))
        assertFalse("豁免优先，应停止合成", VirtualConstants.isPathFidelityGuest(spoofed))
        assertTrue("隔离不受影响", PathPolicy.isRedirectionEnabled(spoofed))
    }

    // ---------------- W6：contentDir 判据与名单的联动 ----------------

    /** W6：`VirtualContext.contentDir` 的判据随名单变化（这是"看到什么路径"的唯一决策点）。 */
    @Test
    fun `W6 内容目录判据随名单变化`() {
        // 名单内 + 未强制真 → 合成
        assertTrue(shouldUseSyntheticContent(VirtualConstants.isPathFidelityGuest(spoofed), false))
        // 豁免 → pathFidelity=false → 真路径（WebView 的 isolated 进程才读得到）
        assertFalse(shouldUseSyntheticContent(VirtualConstants.isPathFidelityGuest(exempt), false))
        // 全局强制真（contentPathsUseReal=true）→ 任何包都不合成
        assertFalse(shouldUseSyntheticContent(true, true))
    }

    // ---------------- W7：豁免只关欺骗，不关隔离 ----------------

    /** W7：无论哪条分支，翻译规则都仍然注册 —— 这是"写入仍导入沙箱"的结构前提。 */
    @Test
    fun `W7 隔离规则不随名单变化`() {
        val sandbox = "/sandbox"
        assertEquals("APP 规则条数", 14, PathKinds.rulesFor(RedirectOwner.APP, spoofed, sandbox).size)
        assertEquals("豁免 guest 同样注册", 14, PathKinds.rulesFor(RedirectOwner.APP, exempt, sandbox).size)
        assertEquals("名单外 guest 同样注册", 14, PathKinds.rulesFor(RedirectOwner.APP, stranger, sandbox).size)
        assertTrue(PathPolicy.isRedirectionEnabled(exempt))
        assertTrue(PathPolicy.isRedirectionEnabled(stranger))
        // 规则目标必须都在沙箱内（写入不会漏到宿主）
        for ((_, to) in PathKinds.rulesFor(RedirectOwner.APP, exempt, sandbox)) {
            assertTrue("目标不在沙箱内: $to", to.trimEnd('/') == sandbox || to.startsWith("$sandbox/"))
        }
        // 每个族都有合成形态可被翻译（族根缺口由 T39/T41 另案跟进）
        assertTrue(PathKinds.synthetic(PathKind.FILES, exempt).startsWith("/data/user/0/$exempt"))
    }
}
