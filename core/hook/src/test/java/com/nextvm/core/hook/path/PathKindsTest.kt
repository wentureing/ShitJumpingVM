package com.nextvm.core.hook.path

import com.nextvm.core.model.PathKind
import com.nextvm.core.model.PathKinds
import com.nextvm.core.model.RedirectOwner
import com.nextvm.core.model.VirtualConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录配置数据表的结构与等价性测试。
 *
 * 守两件事：① 表是 14 族的**唯一来源**（与 [VirtualConstants] 逐字一致）；
 * ② 从表生成的规则对，与改造前手写的规则**逐对相等**（行为不变）。
 */
class PathKindsTest {

    private val pkg = "mark.via"
    private val hostPkg = "com.nextvm.app"
    private val sandbox = "/sandbox"
    private val apk = "/apk/mark.via/base.apk"

    // ---------------- K1：表覆盖全部 14 族 ----------------

    @Test
    fun `K1 表覆盖全部路径族且不重复`() {
        assertEquals("族数量", PathKind.entries.size, PathKinds.ALL.size)
        assertEquals(
            "有重复族",
            PathKind.entries.toSet(),
            PathKinds.ALL.map { it.kind }.toSet(),
        )
        for (kind in PathKind.entries) {
            assertEquals("of($kind) 应唯一", kind, PathKinds.of(kind).kind)
        }
    }

    // ---------------- K2：合成路径与 VirtualConstants 逐字一致 ----------------

    @Test
    fun `K2 合成路径与 VirtualConstants 逐字一致`() {
        assertEquals(VirtualConstants.syntheticDataDir(pkg), PathKinds.synthetic(PathKind.DATA_ROOT, pkg))
        assertEquals(VirtualConstants.syntheticApkPath(pkg), PathKinds.synthetic(PathKind.APK, pkg))
        assertEquals(VirtualConstants.syntheticLibDir(pkg), PathKinds.synthetic(PathKind.NATIVE_LIB, pkg))
        assertEquals(VirtualConstants.syntheticExternalDir(pkg), PathKinds.synthetic(PathKind.EXTERNAL_DATA, pkg))
        assertEquals(
            VirtualConstants.syntheticExternalCacheDir(pkg),
            PathKinds.synthetic(PathKind.EXTERNAL_CACHE, pkg),
        )
        assertEquals(VirtualConstants.syntheticMediaDir(pkg), PathKinds.synthetic(PathKind.MEDIA, pkg))
        assertEquals(VirtualConstants.syntheticObbDir(pkg), PathKinds.synthetic(PathKind.OBB, pkg))

        // 真机形态（与 AOSP 一致），改动必须是有意的
        assertEquals("/data/user/0/$pkg", PathKinds.synthetic(PathKind.DATA_ROOT, pkg))
        assertEquals("/data/user/0/$pkg/files", PathKinds.synthetic(PathKind.FILES, pkg))
        assertEquals("/data/user/0/$pkg/cache", PathKinds.synthetic(PathKind.CACHE, pkg))
        assertEquals("/data/user/0/$pkg/code_cache", PathKinds.synthetic(PathKind.CODE_CACHE, pkg))
        assertEquals("/data/user/0/$pkg/no_backup", PathKinds.synthetic(PathKind.NO_BACKUP, pkg))
        assertEquals("/data/user/0/$pkg/databases/x.db", PathKinds.synthetic(PathKind.DATABASES, pkg, "x.db"))
        assertEquals("/data/user/0/$pkg/app_foo", PathKinds.synthetic(PathKind.APP_DIR, pkg, "foo"))
        assertEquals(
            "/storage/emulated/0/Android/data/$pkg/files/offline",
            PathKinds.synthetic(PathKind.EXTERNAL_FILES, pkg, "offline"),
        )
    }

    // ---------------- K3：沙箱相对位置（与 SandboxManager 的目录布局一致） ----------------

    @Test
    fun `K3 沙箱相对位置与实例目录布局一致`() {
        val expected = mapOf(
            PathKind.DATA_ROOT to "",
            PathKind.FILES to "files",
            PathKind.CACHE to "cache",
            PathKind.CODE_CACHE to "code_cache",
            PathKind.NO_BACKUP to "no_backup",
            PathKind.EXTERNAL_DATA to "sdcard/Android/data/$pkg",
            PathKind.EXTERNAL_CACHE to "sdcard/Android/data/$pkg/cache",
            PathKind.OBB to "sdcard/Android/obb/$pkg",
            PathKind.MEDIA to "sdcard/Android/media/$pkg",
            PathKind.NATIVE_LIB to "lib",
        )
        for ((kind, rel) in expected) {
            assertEquals("sandboxRel($kind)", rel, PathKinds.sandboxRelOf(kind, pkg))
        }
        assertEquals(
            "external files 带 type",
            "sdcard/Android/data/$pkg/files/offline",
            PathKinds.sandboxRelOf(PathKind.EXTERNAL_FILES, pkg, "offline"),
        )
        assertEquals("databases 带 name", "databases/x.db", PathKinds.sandboxRelOf(PathKind.DATABASES, pkg, "x.db"))
        assertEquals("app dir 带 name", "app_foo", PathKinds.sandboxRelOf(PathKind.APP_DIR, pkg, "foo"))
        // APK 的沙箱位置由调用方给出（virtualRoot/apks/<instance>.apk），不在实例目录下
        assertEquals("APK 无 sandboxRel", null, PathKinds.sandboxRelOf(PathKind.APK, pkg))
    }

    // ---------------- K4：规则对与改造前手写的 16 条逐对相等 ----------------

    @Test
    fun `K4 APP 规则对与手写实现逐对相等`() {
        val actual = PathKinds.rulesFor(RedirectOwner.APP, pkg, sandbox).toSet() +
            PathKinds.hostFallbackRules(hostPkg, pkg, sandbox).toSet()

        val externalDataDir = "$sandbox/sdcard/Android/data/$pkg"
        val externalObbDir = "$sandbox/sdcard/Android/obb/$pkg"
        val externalMediaDir = "$sandbox/sdcard/Android/media/$pkg"
        val expected = setOf(
            // data root：两种写法
            "/data/data/$pkg/" to "$sandbox/",
            "/data/user/0/$pkg/" to "$sandbox/",
            // external data：长/短 × 含/不含尾斜杠
            "/storage/emulated/0/Android/data/$pkg/" to "$externalDataDir/",
            "/sdcard/Android/data/$pkg/" to "$externalDataDir/",
            "/storage/emulated/0/Android/data/$pkg" to externalDataDir,
            "/sdcard/Android/data/$pkg" to externalDataDir,
            // obb
            "/storage/emulated/0/Android/obb/$pkg/" to "$externalObbDir/",
            "/sdcard/Android/obb/$pkg/" to "$externalObbDir/",
            "/storage/emulated/0/Android/obb/$pkg" to externalObbDir,
            "/sdcard/Android/obb/$pkg" to externalObbDir,
            // media
            "/storage/emulated/0/Android/media/$pkg/" to "$externalMediaDir/",
            "/sdcard/Android/media/$pkg/" to "$externalMediaDir/",
            "/storage/emulated/0/Android/media/$pkg" to externalMediaDir,
            "/sdcard/Android/media/$pkg" to externalMediaDir,
            // 宿主包名兜底：目标必须与 guest 自己的 external 目录**完全相同**
            "/storage/emulated/0/Android/data/$hostPkg/" to "$externalDataDir/",
            "/sdcard/Android/data/$hostPkg/" to "$externalDataDir/",
        )
        assertEquals("规则条数", 16, actual.size)
        assertEquals("规则集合", expected, actual)
    }

    // ---------------- K5：APK / LIB 属于 pathFidelity 入口 ----------------

    @Test
    fun `K5 APK 与 LIB 规则对`() {
        val actual = PathKinds.rulesFor(RedirectOwner.PATH_FIDELITY, pkg, sandbox, apk).toSet()
        val expected = setOf(
            "/data/app/~~VmK8x2==/$pkg-VmK8x2==/base.apk" to apk,
            "/data/app/~~VmK8x2==/$pkg-VmK8x2==/lib/arm64/" to "$sandbox/lib/",
        )
        assertEquals("pathFidelity 规则集合", expected, actual)
        // 缺 apkPath 时跳过该族，不抛
        assertEquals(1, PathKinds.rulesFor(RedirectOwner.PATH_FIDELITY, pkg, sandbox, null).size)
    }

    // ---------------- K6：结构自检（替代 T39 的事后检查） ----------------

    @Test
    fun `K6 每个族都有归属或明确的覆盖关系`() {
        assertEquals("有族既没有宿主前缀也没有 coveredBy", emptyList<PathKind>(), PathKinds.kindsWithoutHostForms())
        for (spec in PathKinds.ALL) {
            val parent = spec.coveredBy ?: continue
            assertTrue(
                "${spec.kind} 的父族 $parent 必须自己有宿主前缀",
                PathKinds.hostForms(parent, pkg).isNotEmpty(),
            )
        }
    }

    // ---------------- K7：共享存储根本身不含包名，独立于族表 ----------------

    @Test
    fun `K7 共享存储根独立维护且形态正确`() {
        assertEquals(
            listOf("/sdcard/", "/storage/emulated/0/", "/mnt/sdcard/", "/storage/self/primary/"),
            PathKinds.EXTERNAL_ROOT_FORMS,
        )
        assertEquals(listOf("/storage/emulated/0", "/sdcard"), PathKinds.EXTERNAL_ROOT_BARE_FORMS)
        // 不含包名 -> 不属于任何族（族的判据是「含包名的私有目录」）
        assertTrue(PathKinds.EXTERNAL_ROOT_FORMS.none { it.contains(pkg) })
    }
}
