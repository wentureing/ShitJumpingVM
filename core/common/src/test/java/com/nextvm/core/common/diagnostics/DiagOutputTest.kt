package com.nextvm.core.common.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 统一落盘出口（`DiagOutput`）的行为铆钉（纯 JVM）。
 *
 * 设计目标：业务代码只给「通道名 + 内容」，不关心目录、上限、锁、失败。
 * 说明：docs/输出/变更/20261126-l1-诊断输出统一与环形.md
 */
class DiagOutputTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        DiagOutput.reset()
    }

    @Test
    fun `O1 未绑定目录时丢弃且不建文件`() {
        DiagOutput.reset()
        val dir = File(tmp.root, "files")
        assertFalse(DiagOutput.append("x.log", "hello\n"))
        assertFalse(DiagOutput.isReady())
        assertFalse("未绑定时不得产生文件", File(dir, "x.log").exists())
    }

    @Test
    fun `O2 同名通道复用同一文件且顺序正确`() {
        val dir = tmp.newFolder("files")
        DiagOutput.setDir(dir)
        assertTrue(DiagOutput.line("breadcrumb-1.log", "a"))
        assertTrue(DiagOutput.line("breadcrumb-1.log", "b"))
        assertEquals("a\nb\n", File(dir, "breadcrumb-1.log").readText())
        assertEquals(4L, DiagOutput.size("breadcrumb-1.log"))
    }

    @Test
    fun `O3 不同通道互不影响`() {
        val dir = tmp.newFolder("files")
        DiagOutput.setDir(dir)
        DiagOutput.line("a.log", "A")
        DiagOutput.line("b.log", "B")
        assertEquals("A\n", File(dir, "a.log").readText())
        assertEquals("B\n", File(dir, "b.log").readText())
    }

    @Test
    fun `O4 切换目录后写入落新目录`() {
        val d1 = tmp.newFolder("one")
        val d2 = tmp.newFolder("two")
        DiagOutput.setDir(d1)
        DiagOutput.line("k.log", "first")
        DiagOutput.setDir(d2)
        DiagOutput.line("k.log", "second")
        assertEquals("first\n", File(d1, "k.log").readText())
        assertEquals("second\n", File(d2, "k.log").readText())
    }

    @Test
    fun `O5 并发写同一通道不丢行`() {
        val dir = tmp.newFolder("files")
        DiagOutput.setDir(dir)
        val threads = 8
        val perThread = 100
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> DiagOutput.line("c.log", "t$t-$i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("并发写应在超时内完成", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val lines = File(dir, "c.log").readLines().filter { it.isNotEmpty() }
        assertEquals("行数不得丢失", threads * perThread, lines.size)
        assertTrue("每条都得是完整行", lines.all { it.matches(Regex("t\\d+-\\d+")) })
    }

    @Test
    fun `O6 overwrite 用于一次性快照`() {
        val dir = tmp.newFolder("files")
        DiagOutput.setDir(dir)
        assertTrue(DiagOutput.overwrite("maps.txt", "v1"))
        assertTrue(DiagOutput.overwrite("maps.txt", "v2"))
        assertEquals("v2", File(dir, "maps.txt").readText())
    }
}
