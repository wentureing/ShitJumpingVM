package com.nextvm.core.virtualization.lifecycle.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MapsSnapshot 的纯逻辑铆钉（G1–G7）。
 *
 * parse/formatLine 是纯函数（只吃文本），dump 只依赖 File；
 * 真正读内核 maps 的入口 `realSnap()` 留在启动链，由真机 breadcrumb 验证。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/diag/MapsSnapshot.md
 */
class MapsSnapshotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 一行 maps：range perms offset dev inode [path] */
    private fun line(range: String, perms: String, path: String? = null) =
        if (path == null) "$range $perms 00000000 00:00 0" else "$range $perms 00000000 00:00 0 $path"

    @Test
    fun `G1 只累加可执行的匿名段`() {
        val text = listOf(
            line("1000-2000", "r-xp"),                       // 匿名可执行 0x1000
            line("3000-4000", "rw-p"),                       // 匿名不可执行，不算
            line("5000-7000", "r-xp", "/system/lib/libc.so"), // file-backed，只进 execRanges
            line("8000-9000", "r--p"),                       // 不可执行
        ).joinToString("\n")

        val s = MapsSnapshot.parse(text)
        assertEquals(4, s.lines)
        assertEquals(2, s.execRanges.size)        // 匿名 + file-backed 都算"可执行区间"
        assertEquals(0x1000L, s.anonExecBytes)    // 只有匿名那一段计入
        assertTrue(s.bigAnon.isEmpty())
    }

    @Test
    fun `G2 DexHelper 与 memfd 行无论是否可执行都收集`() {
        val text = listOf(
            line("1000-2000", "r--p", "/data/dalvik-cache/x@classes.dex"),
            line("2000-3000", "rw-p", "/memfd:jit-cache (deleted)"),
            line("3000-4000", "r--p", "[anon:DexHelper]"),
        ).joinToString("\n")

        val s = MapsSnapshot.parse(text)
        assertEquals(0, s.execRanges.size)
        assertEquals(1, s.dexHelper.size)
        assertTrue(s.dexHelper[0].contains("DexHelper"))
        assertEquals(1, s.memfd.size)
        assertTrue(s.memfd[0].contains("memfd"))
    }

    @Test
    fun `G3 大匿名段按尺寸区间判定`() {
        val text = listOf(
            line("100000-180000", "rwxp"),          // 0x80000 边界内
            line("200000-480000", "rwxp"),          // 0x280000 超上限
            line("500000-9FF000", "rwxp"),          // 0x4FF000 超上限
        ).joinToString("\n")

        val s = MapsSnapshot.parse(text)
        assertEquals(1, s.bigAnon.size)
        assertTrue(s.bigAnon[0].startsWith("0x80000/rwxp@100000-180000"))
        assertEquals(0x80000L + 0x280000L + 0x4FF000L, s.anonExecBytes)
    }

    @Test
    fun `G4 畸形输入不抛且被跳过`() {
        val text = listOf(
            "garbage",                     // 只有 1 段 -> 跳过
            "1000",                        // 只有 1 段 -> 跳过
            "zzzz-yyyy rw-p 0 0 0",        // 不可执行 -> 不进统计
            "",
            "   ",
        ).joinToString("\n")

        val s = MapsSnapshot.parse(text)
        assertEquals(0, s.execRanges.size)
        assertEquals(0L, s.anonExecBytes)
    }

    @Test
    fun `G5 空文本`() {
        val s = MapsSnapshot.parse("")
        assertEquals(0, s.lines)
        assertEquals(0L, s.anonExecBytes)
        assertEquals("", s.text)
    }

    @Test
    fun `G6 formatLine 首次快照：全部算新增`() {
        val s = MapsSnapshot.parse(line("100000-180000", "rwxp"))
        val out = MapsSnapshot.formatLine("before", s, null)
        assertTrue(out.startsWith("v59 realmaps before lines=1 anonExecBytes=0x80000"))
        assertTrue(out.contains("newExecRanges=1"))
        assertTrue(out.contains("newBigAnon=[0x80000/rwxp@100000-180000]"))
    }

    @Test
    fun `G7 formatLine 有 prev：只算新增`() {
        val prev = MapsSnapshot.parse(line("100000-180000", "rwxp"))
        val now = MapsSnapshot.parse(
            listOf(
                line("100000-180000", "rwxp"),  // 同地址，不算新增
                line("200000-280000", "rwxp"),  // 新地址，算新增
            ).joinToString("\n")
        )
        val out = MapsSnapshot.formatLine("after", now, prev)
        assertTrue(out.contains("newExecRanges=1"))
        assertTrue(out.contains("newBigAnon=[0x80000/rwxp@200000-280000]"))
    }

    @Test
    fun `G8 snap 为 null 时输出 UNAVAILABLE`() {
        assertEquals("v59 realmaps before UNAVAILABLE", MapsSnapshot.formatLine("before", null, null))
    }

    @Test
    fun `G9 dump 写 realmaps-pid-tag 且内容为原文`() {
        val dir = tmp.newFolder("files")
        val s = MapsSnapshot.parse(line("100000-180000", "rwxp"))
        val name = MapsSnapshot.dump(dir, 4321, "before", s)
        assertEquals("realmaps-4321-before.txt", name)
        assertEquals(s.text, java.io.File(dir, name).readText())
    }
}
