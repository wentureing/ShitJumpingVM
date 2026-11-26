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

    /** 契约（C++ 白名单下发用）：markers() 必须覆盖 DiagFiles 里所有通道名。 */
    @Test
    fun `O7 markers 覆盖全部通道名`() {
        val markers = DiagFiles.markers()
        val names = channelNames()
        assertTrue("应至少有一个通道", names.isNotEmpty())
        val missing = names.filter { n -> markers.none { mk -> n.contains(mk) } }
        assertEquals("markers() 漏了这些通道，C++ 白名单下发会漏", emptyList<String>(), missing)
    }

    @Test
    fun `O8 markers 无重复且非空`() {
        val markers = DiagFiles.markers()
        assertEquals("marker 不得重复", markers.size, markers.toSet().size)
        assertTrue("marker 不得为空", markers.none { it.isBlank() })
    }

    /** 反射收集 `DiagFiles` 所有返回 String 的通道（const val + 无参/有参函数）。 */
    private fun channelNames(): List<String> {
        val out = mutableListOf<String>()
        val cls = DiagFiles::class.java
        for (f in cls.declaredFields) {
            if (f.type != String::class.java) continue
            if (f.name == "CONFIG_RESOURCE") continue // 资源路径，不是通道名
            f.isAccessible = true
            (try { f.get(null) } catch (_: Throwable) { f.get(DiagFiles) } as? String)
                ?.takeIf { !it.startsWith("/") }
                ?.let { out.add(it) }
        }
        for (m in cls.declaredMethods) {
            if (m.returnType != String::class.java) continue
            if (m.name == "markers") continue
            val args = m.parameterTypes.map { t ->
                if (t == Int::class.javaPrimitiveType || t == Int::class.javaObjectType) 0 else "x"
            }.toTypedArray()
            m.isAccessible = true
            (m.invoke(DiagFiles, *args) as? String)?.let { out.add(it) }
        }
        return out
    }
}
