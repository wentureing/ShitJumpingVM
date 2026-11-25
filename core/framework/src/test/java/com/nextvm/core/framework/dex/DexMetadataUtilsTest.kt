package com.nextvm.core.framework.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * dex metadata（.dm）路径推导的行为铆钉（golden）。
 *
 * 语义来源：AOSP `DexMetadataHelper`。**只铆钉，不重构**。
 * 只覆盖纯字符串/File 逻辑；`ZipFile` 相关部分不在此类（需要真实 zip）。
 */
class DexMetadataUtilsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `apk 路径换成 dm 路径`() {
        assertEquals(
            "/data/app/x/base.dm",
            DexMetadataUtils.buildDexMetadataPathForApk("/data/app/x/base.apk"),
        )
    }

    @Test
    fun `没有 apk 后缀时直接加 dm`() {
        assertEquals(
            "/data/app/x/base.dm",
            DexMetadataUtils.buildDexMetadataPathForApk("/data/app/x/base"),
        )
    }

    @Test
    fun `只去掉最后一个 apk 后缀`() {
        // removeSuffix 只作用于结尾一次：base.apk.apk -> base.apk.dm
        assertEquals(
            "/data/app/x/base.apk.dm",
            DexMetadataUtils.buildDexMetadataPathForApk("/data/app/x/base.apk.apk"),
        )
    }

    @Test
    fun `后缀判定大小写敏感`() {
        assertTrue(DexMetadataUtils.isDexMetadataFile(File("a.dm")))
        assertFalse(DexMetadataUtils.isDexMetadataFile(File("a.apk")))
        assertFalse(DexMetadataUtils.isDexMetadataFile(File("a.DM")))
    }

    @Test
    fun `存在 dm 时能找到 否则返回 null`() {
        val dir = tmp.newFolder("apkdir")
        val apk = File(dir, "base.apk").apply { writeText("x") }
        assertNull("没有 .dm 时应返回 null", DexMetadataUtils.findDexMetadataForFile(apk))

        val dm = File(dir, "base.dm").apply { writeText("y") }
        val found = DexMetadataUtils.findDexMetadataForFile(apk)
        assertNotNull("有 .dm 时应返回它", found)
        assertEquals(dm.absolutePath, found!!.absolutePath)
    }
}
