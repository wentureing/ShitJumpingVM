package com.nextvm.core.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * HostReadPolicy 的 17 条用例（sandboxsensor P1-2）。
 *
 * **为什么不用 Robolectric**：被测的 HostReadPolicy 只依赖 java.io.File
 * 与 canonicalPath，是纯 JVM 逻辑，不需要 Android 运行时。
 * Robolectric 会额外拉 android-all-instrumented（Android 15 的约 100 MB jar），
 * 在 CI 里下载不稳定（p23 的 19 个用例全因它失败）。纯 JUnit 零下载、秒级完成。
 *
 * 覆盖：正常路径、`..` 逃逸、前缀攻击、跨实例、其他应用、系统目录、
 *      容器外部、导入目录、非白名单公共目录、空输入、深逃逸、软链、
 *      尾斜杠、NUL 注入、超长路径。
 */
class HostReadPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dataRoot: File
    private lateinit var extRoot: File
    private val instanceId = "com.example_1700000000000"
    private val otherId = "com.other_1700000000001"

    @Before
    fun setUp() {
        dataRoot = File(tmp.newFolder("files", "virtual"), "data").apply { mkdirs() }
        File(dataRoot, instanceId).mkdirs()
        File(dataRoot, otherId).mkdirs()

        extRoot = tmp.newFolder("emulated0")
        File(extRoot, "Android/data/com.nextvm.app/files").mkdirs()
        File(extRoot, "Download").mkdirs()
        File(extRoot, "DCIM").mkdirs()
    }

    private fun resolve(path: String): String? =
        HostReadPolicy.resolve(dataRoot, instanceId, path, extRoot.absolutePath)

    private fun touch(relative: String): File {
        val f = File(dataRoot, relative)
        f.parentFile?.mkdirs()
        f.writeText("x")
        return f
    }

    // 1) 正常路径
    @Test
    fun ownSandboxFileIsAllowed() {
        val f = touch("$instanceId/files/a.html")
        assertNotNull(resolve(f.absolutePath))
    }

    // 2) `..` 逃逸到另一个实例
    @Test
    fun dotDotEscapeToOtherInstanceIsRejected() {
        val f = touch("$otherId/files/a.html")
        val escaping = File(dataRoot, "$instanceId/../$otherId/files/a.html").absolutePath
        assertNull(resolve(escaping))
        assertNull("对照：直接用真实路径也应被拒（跨实例）", resolve(f.absolutePath))
    }

    // 3) 前缀攻击：<id>abc 不该命中 <id>
    @Test
    fun prefixAttackIsRejected() {
        val sibling = touch("${instanceId}abc/files/a.html")
        assertNull(resolve(sibling.absolutePath))
    }

    // 4) 跨实例：直接访问另一个实例的沙箱
    @Test
    fun crossInstanceIsRejected() {
        val f = touch("$otherId/files/a.html")
        assertNull(resolve(f.absolutePath))
    }

    // 5) 其他应用
    @Test
    fun otherAppPrivateDirIsRejected() {
        assertNull(resolve("/data/data/com.other.app/files/x"))
        assertNull(resolve("/data/user/0/com.other.app/files/x"))
    }

    // 6) 系统目录
    @Test
    fun systemDirsAreRejected() {
        assertNull(resolve("/system/etc/hosts"))
        assertNull(resolve("/vendor/lib64/libc.so"))
        assertNull(resolve("/apex/com.android.runtime/lib64/libc.so"))
    }

    // 7) 容器外部私有目录
    @Test
    fun containerExternalDirIsAllowed() {
        val f = File(extRoot, "Android/data/com.nextvm.app/files/x.html").apply { writeText("x") }
        assertNotNull(resolve(f.absolutePath))
    }

    // 8) 导入目录
    @Test
    fun downloadDirIsAllowed() {
        val f = File(extRoot, "Download/x.html").apply { writeText("x") }
        assertNotNull(resolve(f.absolutePath))
    }

    // 9) 非白名单公共目录
    @Test
    fun otherPublicDirsAreRejected() {
        val f = File(extRoot, "DCIM/x.jpg").apply { writeText("x") }
        assertNull(resolve(f.absolutePath))
    }

    // 10) 空输入
    @Test
    fun blankInputIsRejected() {
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }

    // 11) 深逃逸
    @Test
    fun deepEscapeIsRejected() {
        val p = File(dataRoot, "$instanceId/files/../../../etc/passwd").absolutePath
        assertNull(resolve(p))
    }

    // 12) 软链指向白名单外
    @Test
    fun symlinkOutsideWhitelistIsRejected() {
        val outside = tmp.newFolder("outside")
        val target = File(outside, "secret.txt").apply { writeText("s") }
        val link = File(dataRoot, "$instanceId/files/link.txt")
        link.parentFile?.mkdirs()
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }.isSuccess
        assumeTrue("当前文件系统不支持软链，跳过", created)
        assertNull(resolve(link.absolutePath))
    }

    // 13) 尾斜杠
    @Test
    fun trailingSlashIsAllowed() {
        File(dataRoot, "$instanceId/files").mkdirs()
        assertNotNull(resolve(File(dataRoot, "$instanceId/files").absolutePath + File.separator))
    }

    // 14) NUL 注入
    @Test
    fun nulByteIsRejected() {
        assertNull(resolve("/data/user/0/x\u0000.html"))
    }

    // 15) 超长路径
    @Test
    fun overlongPathIsRejected() {
        val long = "/" + "a".repeat(5000)
        assertNull(resolve(long))
    }

    // 附带：instanceId 合法性与空/非法值
    @Test
    fun unsafeInstanceIdDisablesOwnSandboxBranch() {
        assertTrue(HostReadPolicy.isSafeInstanceId(instanceId))
        assertFalse(HostReadPolicy.isSafeInstanceId("../etc"))
        assertFalse(HostReadPolicy.isSafeInstanceId("a/b"))
        assertFalse(HostReadPolicy.isSafeInstanceId(""))
        val f = touch("$instanceId/files/a.html")
        assertNull(
            "非法 instanceId 时不应放行任何沙箱路径",
            HostReadPolicy.resolve(dataRoot, "../etc", f.absolutePath, extRoot.absolutePath)
        )
    }

    // 附带：返回值是规范化后的路径
    @Test
    fun resolvedPathIsCanonical() {
        val f = touch("$instanceId/files/a.html")
        val r = resolve(File(dataRoot, "$instanceId/files/../files/a.html").absolutePath)
        assertEquals(f.canonicalPath, r)
    }
}
