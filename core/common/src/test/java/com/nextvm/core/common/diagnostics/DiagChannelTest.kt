package com.nextvm.core.common.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 诊断通道与命名规范的行为铆钉（纯 JVM）。
 *
 * 为什么重要：诊断是排查唯一可靠通道（设备吞 D/V 级日志）。
 * 通道行为（环形保留 / 失败静默）与命名规范一旦变化，会让**已有的排查脚本/探针静默失效**。
 *
 * 说明：docs/输出/变更/20261126-l1-诊断输出统一与环形.md
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

    /** 旧语义保留：`checks.log` 是结构化结果，写满即清空（`FileSink` 显式传 keepTail=false）。 */
    @Test
    fun `C2 keepTail=false 时超过阈值先清空再写`() {
        val f = File(tmp.root, "b.log")
        val ch = DiagChannel(f, maxBytes = 8, keepTail = false)
        ch.append("0123456789")
        ch.append("x")
        assertEquals("超限后应清空只留新内容", "x", f.readText())
        assertFalse("清空模式不产生轮转标记", f.readText().contains(DiagChannel.ROTATE_MARK))
    }

    @Test
    fun `C3 写失败不抛（父路径是普通文件）`() {
        val blocker = File(tmp.root, "blocker")
        blocker.writeText("x")
        val f = File(blocker, "child.log") // 父不是目录 -> 必然失败
        assertFalse(DiagChannel(f).append("y"))
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
        // L1 补齐的通道名（原本硬编码在各调用点）
        assertEquals("stateprobe-123.log", DiagFiles.stateProbe(123))
        assertEquals("dexmem-123.log", DiagFiles.dexMem(123))
        assertEquals("shortcut-probe-123.log", DiagFiles.shortcutProbe(123))
        assertEquals("memdex-123-0.dex", DiagFiles.memDexDump(123, 0))
        assertEquals("v139-probe.log", DiagFiles.v139Probe())
        assertEquals("v144-intent-probe.log", DiagFiles.v144IntentProbe())
        assertEquals("v145-intent-probe.log", DiagFiles.v145IntentProbe())
        assertEquals("v148-connect-probe.log", DiagFiles.v148ConnectProbe())
        assertEquals("v155-init-filtered.log", DiagFiles.filtered("v155-init"))
        assertEquals("v155-trace-123-1.trace", DiagFiles.trace("v155-trace", 123))
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

    // ==================== R 系列：环形保留（L1 新增） ====================

    /** 显式写满：轮转后必须留住「最后写进去的那条」，丢掉最早的。 */
    @Test
    fun `R1 写满后环形保留尾部并打轮转标记`() {
        val f = File(tmp.root, "ring.log")
        val maxBytes = 1_000L
        val ch = DiagChannel(f, maxBytes)
        val pad = "x".repeat(40) // 每行 47 字节

        repeat(20) { ch.append("FIRST-$pad\n") } // 940 字节，未满
        assertFalse("写满前不应轮转", f.readText().contains(DiagChannel.ROTATE_MARK))

        repeat(40) { ch.append("MID-$it-$pad\n") } // 触发轮转
        ch.append("LAST-$pad\n")                   // 收尾标记

        val text = f.readText()
        assertTrue("应出现轮转标记", text.contains(DiagChannel.ROTATE_MARK))
        assertTrue("必须保留最后一条", text.contains("LAST-"))
        assertFalse("最早的内容应被丢弃", text.contains("FIRST-"))
        assertTrue("末行不被撕裂（以换行结尾）", text.endsWith("\n"))
        assertTrue("轮转后大小受控（<= maxBytes + 单条）", f.length() <= maxBytes + 64)
    }

    /** 连续写满多次也要能收敛（不能每次写都轮转、也不能无限增长）。 */
    @Test
    fun `R2 反复写满多次仍受控且保留最新内容`() {
        val f = File(tmp.root, "ring2.log")
        val maxBytes = 512L
        val ch = DiagChannel(f, maxBytes)
        val pad = "y".repeat(30)
        repeat(200) { i ->
            ch.append("N$i-$pad\n")
        }
        val text = f.readText()
        assertTrue("最终仍保留最后一条", text.contains("N199-"))
        assertTrue("大小受控", f.length() <= maxBytes + 64)
        assertTrue("标记存在", text.contains(DiagChannel.ROTATE_MARK))
    }

    /** 单条就超过上限：不做轮转（轮转也留不下），内容必须留下。 */
    @Test
    fun `R3 单条超过阈值不丢内容`() {
        val f = File(tmp.root, "big.log")
        val ch = DiagChannel(f, maxBytes = 16)
        val big = "B".repeat(100) + "\n"
        assertTrue(ch.append(big))
        assertTrue("单条超限时必须原样保留", f.readText().endsWith(big))
    }
}
