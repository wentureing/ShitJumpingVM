package com.nextvm.core.hook

import com.nextvm.core.model.PathKind
import com.nextvm.core.model.PathPolicy
import com.nextvm.core.model.PathSpoofConfig
import com.nextvm.core.model.VirtualConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 路径欺骗豁免机制的契约测试（纯 JVM）。
 *
 * 语义（见 docs/设计/设计-澄清-欺骗与隔离与合成路径.md）：
 *   豁免 = 关掉「路径形态欺骗」（不产生合成路径），**保留沙箱隔离**（规则仍注册）
 *
 * 覆盖两组：
 *   H1-H4  配置文件的生成、往返、回落
 *   H5-H9  豁免对 PathPolicy / VirtualConstants 的实际影响（含「路由不转换、写入仍可拦」）
 */
class PathSpoofPolicyTest {

    private lateinit var dir: File

    /** 白名单里、且默认**不**在豁免名单的包（用于对照） */
    private val whitelisted = VirtualConstants.CM_PACKAGE

    /** 由 writeTemplate 默认加进豁免名单的包 */
    private val defaultExempt = "mark.via"

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "spoof_test_${System.nanoTime()}")
        dir.mkdirs()
        PathSpoofConfig.load(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------------- H1-H4 配置文件 ----------------

    /** H1：首次 load 生成模板文件，且默认把 mark.via 放进豁免名单。 */
    @Test
    fun `H1 首次加载生成模板且默认豁免 mark_via`() {
        assertTrue("配置文件未生成", File(dir, PathSpoofConfig.FILE_NAME).exists())
        assertTrue("mark.via 应默认豁免", PathSpoofConfig.isGuestExempt(defaultExempt))
        assertEquals(listOf(defaultExempt), PathSpoofConfig.exemptList())

        val text = File(dir, PathSpoofConfig.FILE_NAME).readText()
        assertTrue("模板缺少「豁免名单」节", text.contains("[豁免名单]"))
        assertTrue("模板缺少「各族开关」节", text.contains("[各族开关]"))
        assertTrue("模板缺少注释说明", text.contains("#"))
    }

    /** H2：增删豁免会立刻写回文件，重新 load 后仍然一致（往返）。 */
    @Test
    fun `H2 豁免增删往返`() {
        assertTrue(PathSpoofConfig.setGuestExempt("com.example.a", true))
        assertTrue(PathSpoofConfig.setGuestExempt("com.example.b", true))
        assertTrue(PathSpoofConfig.isGuestExempt("com.example.a"))

        // 重复添加应返回 false（无变化）
        assertFalse(PathSpoofConfig.setGuestExempt("com.example.a", true))

        PathSpoofConfig.load(dir)   // 模拟重启
        assertTrue(PathSpoofConfig.isGuestExempt("com.example.a"))
        assertTrue(PathSpoofConfig.isGuestExempt("com.example.b"))
        assertTrue(PathSpoofConfig.isGuestExempt(defaultExempt))

        assertTrue(PathSpoofConfig.setGuestExempt("com.example.a", false))
        PathSpoofConfig.load(dir)
        assertFalse(PathSpoofConfig.isGuestExempt("com.example.a"))
        assertTrue(PathSpoofConfig.isGuestExempt("com.example.b"))
    }

    /** H3：族开关 off 会写回，且重新 load 后仍生效。 */
    @Test
    fun `H3 族开关往返`() {
        assertTrue("缺省应为开", PathSpoofConfig.isKindEnabled(PathKind.OBB.name))
        assertTrue(PathSpoofConfig.setKindEnabled(PathKind.OBB.name, false))
        PathSpoofConfig.load(dir)
        assertFalse(PathSpoofConfig.isKindEnabled(PathKind.OBB.name))
        assertTrue("其它族不受影响", PathSpoofConfig.isKindEnabled(PathKind.FILES.name))
    }

    /** H4：文件损坏时不抛异常，回落到「全部不豁免、全部族开启」= 旧行为。 */
    @Test
    fun `H4 文件损坏时安全回落`() {
        val f = File(dir, PathSpoofConfig.FILE_NAME)
        f.writeText("\u0000\u0001 乱码 \uFFFD\n[未知节]\nxxxx=yyyy\n")
        PathSpoofConfig.load(dir)   // 不应抛
        assertFalse(PathSpoofConfig.isGuestExempt(defaultExempt))   // 回落 = 不豁免
        assertTrue(PathSpoofConfig.isKindEnabled(PathKind.FILES.name)) // 回落 = 全部开
    }

    // ---------------- H5-H9 豁免的实际影响 ----------------

    /**
     * H5：**豁免优先级最高** —— `isPathFidelityGuest` 直接返回 false，
     * 即使该包同时在 PATH_FIDELITY_WHITELIST 里。
     */
    @Test
    fun `H5 豁免优先于白名单`() {
        // 先确认 mark.via 原本在白名单（否则本测试没有对照意义）
        assertTrue(PathSpoofConfig.isGuestExempt(defaultExempt))

        // 豁免生效 -> isPathFidelityGuest 必须是 false
        assertFalse(
            "豁免应压过白名单",
            VirtualConstants.isPathFidelityGuest(defaultExempt)
        )

        // 移除豁免 -> 回到白名单行为（true）
        PathSpoofConfig.setGuestExempt(defaultExempt, false)
        assertTrue(VirtualConstants.isPathFidelityGuest(defaultExempt))
    }

    /**
     * H6：**豁免后所有路径族都不产生合成路径**。
     *
     * 这是「路径不转换」的核心断言：PathPolicy.isSpoofEnabled 对 14 个族全部 false。
     */
    @Test
    fun `H6 豁免后所有族都不合成`() {
        assertTrue(PathSpoofConfig.isGuestExempt(defaultExempt))
        for (kind in PathKind.entries) {
            assertFalse(
                "豁免后 $kind 不应产生合成路径",
                PathPolicy.isSpoofEnabled(kind, defaultExempt)
            )
        }
    }

    /** H7：非豁免的白名单包仍然合成（证明 H6 不是「全关」的假阳性）。 */
    @Test
    fun `H7 白名单未豁免仍合成`() {
        assertFalse("前提：该包不在豁免名单", PathSpoofConfig.isGuestExempt(whitelisted))
        assertTrue(VirtualConstants.isPathFidelityGuest(whitelisted))
        assertTrue(PathPolicy.isSpoofEnabled(PathKind.FILES, whitelisted))
        assertTrue(PathPolicy.isSpoofEnabled(PathKind.EXTERNAL_FILES, whitelisted))
    }

    /** H8：族开关优先于白名单（关掉某族后，即使白名单也不合成）。 */
    @Test
    fun `H8 族开关优先于白名单`() {
        assertTrue(PathPolicy.isSpoofEnabled(PathKind.OBB, whitelisted))
        PathSpoofConfig.setKindEnabled(PathKind.OBB.name, false)
        assertFalse(PathPolicy.isSpoofEnabled(PathKind.OBB, whitelisted))
        assertTrue("其它族不受影响", PathPolicy.isSpoofEnabled(PathKind.FILES, whitelisted))
    }

    /**
     * H9：**豁免只关「欺骗」，不关「隔离」**。
     *
     * 「可拦截写入」依赖规则仍然注册 —— 对应 `isRedirectionEnabled` 恒为 true。
     * 真正的写入拦截在 native 层（纯 JVM 测不了），这里钉住的是策略契约：
     * 豁免后规则注册不受影响，所以 guest 的 I/O 仍然落在沙箱。
     */
    @Test
    fun `H9 豁免不影响隔离`() {
        assertTrue(PathSpoofConfig.isGuestExempt(defaultExempt))
        assertTrue(
            "豁免后仍必须注册翻译规则（否则 guest 会读写真宿主）",
            PathPolicy.isRedirectionEnabled(defaultExempt)
        )
        assertTrue(PathPolicy.isRedirectionEnabled(whitelisted))
        assertTrue(PathPolicy.isRedirectionEnabled("com.any.other"))
    }

    /** H11：模板包含「欺骗名单」段，且默认写的是 CM（不是硬编码在代码分支里）。 */
    @Test
    fun `H11 模板包含可配置的欺骗名单`() {
        val text = File(dir, PathSpoofConfig.FILE_NAME).readText()
        assertTrue("模板缺少「欺骗名单」节", text.contains("[欺骗名单]"))
        assertEquals(listOf(VirtualConstants.CM_PACKAGE), PathSpoofConfig.spoofList())
        assertTrue(PathSpoofConfig.isSpoofListed(VirtualConstants.CM_PACKAGE))
    }

    /** H12：欺骗名单可运行时增删，并写回文件（改完重启生效）。 */
    @Test
    fun `H12 欺骗名单增删往返`() {
        val target = "com.example.target"
        assertFalse(PathSpoofConfig.isSpoofListed(target))
        assertTrue(PathSpoofConfig.setSpoofListed(target, true))
        assertTrue(PathSpoofConfig.isSpoofListed(target))
        assertTrue(
            "改动应写回文件",
            File(dir, PathSpoofConfig.FILE_NAME).readText().contains(target),
        )
        assertTrue(PathSpoofConfig.setSpoofListed(target, false))
        assertFalse(PathSpoofConfig.isSpoofListed(target))
    }

    /** H13：两份名单彼此独立（豁免名单里的包不能自动进欺骗名单）。 */
    @Test
    fun `H13 豁免名单与欺骗名单互不影响`() {
        assertTrue(PathSpoofConfig.isGuestExempt(defaultExempt))
        assertFalse("豁免包不应在欺骗名单", PathSpoofConfig.isSpoofListed(defaultExempt))
        assertTrue(PathSpoofConfig.isSpoofListed(whitelisted))
        assertFalse("欺骗名单不应进豁免", PathSpoofConfig.isGuestExempt(whitelisted))
    }

    /** H10：isLoaded 反映 load 是否已执行（CmdReceiver LIST 会用它做诊断）。 */
    @Test
    fun `H10 isLoaded 反映加载状态`() {
        assertTrue(PathSpoofConfig.isLoaded())
        assertNotNull(PathSpoofConfig.exemptList())
    }
}
