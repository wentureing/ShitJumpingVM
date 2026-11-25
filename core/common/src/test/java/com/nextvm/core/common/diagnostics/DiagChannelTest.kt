package com.nextvm.core.common.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 诊断通道与命名规范的行为铆钉（纯 JVM）。
 *
 * 为什么重要：诊断是排查唯一可靠通道（设备会吞 guest 日志）。
 * 通道行为（超限清空、失败静默）与命名规范一旦变化，会让**已有的排查脚本/探针静默失效**。
 *
 * 说明：docs/输出/变更/20261125-common职责化与探针接入.md
 */
class DiagChannelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `C1 追加不覆盖`() {
        val f = File(tmp.root, "a.log")
        val ch = DiagChannel(f)
        ch.append("line1\n")
        ch.append("line2\n")
        assertEquals("line1\nline2\n", f.readText())
        assertEquals(f.length(), ch.size())
    }

    @Test
    fun `C2 超过阈值先清空再写`() {
        val f = File(tmp.root, "b.log")
        val ch = DiagChannel(f, maxBytes = 8)
        ch.append("0123456789") // 10 字节 > 8
        ch.append("x")
        assertEquals("超限后应清空只留新内容", "x", f.readText())
    }

    @Test
    fun `C3 写失败不抛（父路径是普通文件）`() {
        val blocker = File(tmp.root, "blocker")
        blocker.writeText("x")
        val f = File(blocker, "child.log") // 父不是目录 -> 必然失败
        DiagChannel(f).append("y")
        assertTrue("失败应静默，不产生文件", !f.exists())
    }

    @Test
    fun `C4 命名规范`() {
        assertEquals("debug-trace.log", DiagFiles.DEBUG_TRACE)
        assertEquals("checks.log", DiagFiles.CHECKS)
        assertEquals("breadcrumb-123.log", DiagFiles.breadcrumb(123))
        assertEquals("stackdump-123.log", DiagFiles.stackDump(123))
        assertEquals("hijacktrace-123.log", DiagFiles.hijackTrace(123))
        assertEquals("startactivity-trace-123.log", DiagFiles.startActivityTrace(123))
        assertEquals("webviewwarm-123.log", DiagFiles.webViewWarm(123))
        assertEquals("webviewwatch-123.log", DiagFiles.webViewWatch(123))
    }

    @Test
    fun `C5 清单只含诊断文件且可统计总量`() {
        val dir = tmp.newFolder("files")
        File(dir, "checks.log").writeText("abc")
        File(dir, "breadcrumb-1.log").writeText("de")
        File(dir, "memdex-1-0.dex").writeText("f")
        File(dir, "not-diag.txt").writeText("ignored")
        dir.mkdirs()

        val listed = DiagFiles.list(dir).map { it.name }
        assertEquals(listOf("breadcrumb-1.log", "checks.log", "memdex-1-0.dex"), listed)
        assertEquals(3L + 2L + 1L, DiagFiles.totalBytes(dir))
    }

    @Test
    fun `C6 目录不存在时不抛`() {
        val missing = File(tmp.root, "nope")
        assertEquals(0, DiagFiles.list(missing).size)
        assertEquals(0L, DiagFiles.totalBytes(missing))
    }
}
