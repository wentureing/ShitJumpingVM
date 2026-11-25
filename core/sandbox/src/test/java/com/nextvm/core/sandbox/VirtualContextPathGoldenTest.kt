package com.nextvm.core.sandbox

import android.content.Context
import com.nextvm.core.sandbox.testsupport.Golden
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `VirtualContext` 的路径矩阵（Robolectric）。
 * 用 golden 锁定 26 个重写方法的实际输出；详见 `docs/输出/调研/调研-Robolectric适用性.md`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VirtualContextPathGoldenTest {

    private lateinit var root: File
    private lateinit var sandbox: SandboxManager
    private lateinit var ctx: VirtualContext

    @Before
    fun setUp() {
        val base = RuntimeEnvironment.getApplication() as Context
        root = File(base.cacheDir, "vc-test-root").apply { mkdirs() }
        sandbox = SandboxManager(base, root)
        ctx = VirtualContext(base, "1", GUEST, sandbox)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 把绝对路径相对化，避免临时目录导致 golden 不稳定。 */
    private fun rel(p: Any?): String = when (p) {
        null -> "null"
        is File -> rel(p.absolutePath)
        is String -> p.replace(root.absolutePath, "<ROOT>").replace(GUEST, "<GUEST>")
        is Array<*> -> p.joinToString("|") { rel(it) }
        else -> p.toString()
    }

    @Test
    fun `路径矩阵与批准文件一致`() {
        val lines = buildList {
            add("packageName => ${ctx.packageName}")
            add("opPackageName => ${ctx.opPackageName}")
            add("filesDir => ${rel(ctx.filesDir)}")
            add("cacheDir => ${rel(ctx.cacheDir)}")
            add("codeCacheDir => ${rel(ctx.codeCacheDir)}")
            add("dataDir => ${rel(ctx.dataDir)}")
            add("noBackupFilesDir => ${rel(ctx.noBackupFilesDir)}")
            add("databasePath(n.db) => ${rel(ctx.getDatabasePath("n.db"))}")
            add("dir(x,0) => ${rel(ctx.getDir("x", 0))}")
            add("externalFilesDir(null) => ${rel(ctx.getExternalFilesDir(null))}")
            add("externalFilesDirs(null) => ${rel(ctx.getExternalFilesDirs(null))}")
            add("externalCacheDir => ${rel(ctx.externalCacheDir)}")
            add("externalCacheDirs => ${rel(ctx.externalCacheDirs)}")
            add("externalMediaDirs => ${rel(ctx.externalMediaDirs)}")
            add("obbDir => ${rel(ctx.obbDir)}")
            add("obbDirs => ${rel(ctx.obbDirs)}")
        }
        Golden.verify("VirtualContextPath", lines.joinToString("\n") + "\n")
    }

    @Test
    fun `所有路径都在沙箱内或被显式置空`() {
        val paths = listOf(
            ctx.filesDir, ctx.cacheDir, ctx.codeCacheDir, ctx.dataDir,
            ctx.noBackupFilesDir, ctx.getDatabasePath("n.db"), ctx.getDir("x", 0)
        )
        val rootPath = root.absolutePath
        for (p in paths) {
            assertTrue("路径必须在沙箱根下: ${p.absolutePath}", p.absolutePath.startsWith(rootPath))
        }
    }

    @Test
    fun `packageName 返回 guest 包名`() {
        assertTrue(ctx.packageName == GUEST)
    }

    private companion object {
        const val GUEST = "mark.via"
    }
}
