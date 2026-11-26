package com.nextvm.core.virtualization.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * guest 启动链 breadcrumb 契约（golden 驱动，纯 JVM）。
 *
 * 契约清单、golden 来源与归一化规则见 `docs/架构/架构-启动链契约.md`。
 */
class BreadcrumbGoldenContractTest {

    /** 一个采集样本：2 个 guest × 2 次启动 */
    private data class Sample(
        val tag: String,
        val guest: String,
        val appClass: String,
        val activity: String,
        val providers: Int,
    )

    private val samples = listOf(
        Sample("via-1", "mark.via", "mark.via.BrowserApp", "mark.via.Shell", 2),
        Sample("via-2", "mark.via", "mark.via.BrowserApp", "mark.via.Shell", 2),
        Sample("probe-1", "com.sandbox.probe", "com.sandbox.probe.ProbeApplication", "com.sandbox.probe.ProbeActivity", 1),
        Sample("probe-2", "com.sandbox.probe", "com.sandbox.probe.ProbeApplication", "com.sandbox.probe.ProbeActivity", 1),
    )

    // ---------------------------------------------------------------- 解析

    private fun events(tag: String): List<String> {
        val stream = javaClass.classLoader!!.getResourceAsStream("breadcrumb-golden/$tag.log")
            ?: error("缺少 golden 资源 breadcrumb-golden/$tag.log")
        return stream.bufferedReader().readLines().filter { it.isNotBlank() }
    }

    private fun List<String>.indexOfPrefix(prefix: String): Int = indexOfFirst { it.startsWith(prefix) }

    private fun List<String>.countPrefix(prefix: String): Int = count { it.startsWith(prefix) }

    /** 断言 [prefixes] 按给定顺序出现（每次都要出现，且位置严格递增） */
    private fun assertOrder(
        tag: String,
        lines: List<String>,
        vararg prefixes: String,
    ) {
        var last = -1
        for (p in prefixes) {
            val i = lines.indexOfPrefix(p)
            assertTrue("$tag: 缺少事件「$p」", i >= 0)
            assertTrue(
                "$tag: 事件顺序错「$p」应排在上一条之后（实际 index=$i, 上一条=$last）",
                i > last,
            )
            last = i
        }
    }

    // ---------------------------------------------------------------- 契约

    @Test
    fun `C1 每次启动以本进程的 begin 开头且只出现一次`() {
        for (s in samples) {
            val lines = events(s.tag)
            val begin = lines.firstOrNull { it.startsWith("breadcrumb begin pkg=") }
            assertEquals(
                "C1 ${s.tag}: 首行必须是本进程的 begin（收到：${lines.first()}）",
                "breadcrumb begin pkg=${s.guest} pid=<PID> wall=<WALL>",
                begin,
            )
            assertEquals("C1 ${s.tag}: 本进程 begin 只能出现一次", 1, lines.countPrefix("breadcrumb begin pkg="))
        }
    }

    @Test
    fun `C2 换 ClassLoader 先于 bind`() {
        for (s in samples) {
            assertOrder(s.tag, events(s.tag), "replaceClassLoader done ", "bind start ")
        }
    }

    @Test
    fun `C3 provider 元数据先读后 attach 且条数与 bind 声明一致`() {
        for (s in samples) {
            val lines = events(s.tag)
            assertEquals(
                "C3 ${s.tag}: provider 元数据条数",
                s.providers,
                lines.countPrefix("v118-provider-fields "),
            )
            val lastField = lines.indexOfLast { it.startsWith("v118-provider-fields ") }
            assertTrue(
                "C3 ${s.tag}: provider 元数据必须在 bind start 之前读完",
                lastField < lines.indexOfPrefix("bind start "),
            )
            assertTrue(
                "C3 ${s.tag}: bind start 声明的 providers 数要与元数据一致",
                lines.first { it.startsWith("bind start ") }.contains("providers=${s.providers}"),
            )
        }
    }

    @Test
    fun `C4 Application 只构造一次且出口类名与入参一致`() {
        for (s in samples) {
            val lines = events(s.tag)
            assertOrder(
                s.tag, lines,
                "framework makeApplication enter",
                "instr newApplication ${s.appClass}",
                "framework makeApplication exit app=${s.appClass}",
            )
            assertEquals("C4 ${s.tag}: makeApplication 只能进入一次", 1, lines.countPrefix("framework makeApplication enter"))
            assertEquals(
                "C4 ${s.tag}: newApplication 只能一次",
                1,
                lines.countPrefix("instr newApplication "),
            )
        }
    }

    @Test
    fun `C5 provider 全部 attach 完才进 onCreate，失败不中止 bind`() {
        for (s in samples) {
            val lines = events(s.tag)
            val onCreate = lines.indexOfPrefix("Application.onCreate enter")
            assertTrue("C5 ${s.tag}: 缺 Application.onCreate enter", onCreate >= 0)
            val attach = lines.withIndex().filter {
                it.value.startsWith("provider attachInfo enter ") ||
                    it.value.startsWith("provider ok ") ||
                    it.value.startsWith("provider FAILED ")
            }
            assertEquals("C5 ${s.tag}: 每个 provider 一行 enter + 一行结果", s.providers * 2, attach.size)
            assertTrue(
                "C5 ${s.tag}: provider attach 必须全部早于 Application.onCreate enter",
                attach.all { it.index < onCreate },
            )
            if (lines.any { it.startsWith("provider FAILED ") }) {
                assertTrue(
                    "C5 ${s.tag}: provider 失败后 bind 必须继续（FAILED 行要写明 bind continues）",
                    lines.any { it.startsWith("provider FAILED (bind continues):") },
                )
            }
        }
    }

    @Test
    fun `C6 规则注册与诊断装配都在 onCreate 之后、watchdog 之前`() {
        for (s in samples) {
            assertOrder(
                s.tag, events(s.tag),
                "Application.onCreate exit ok",
                "v142-redirect registered rules=",
                "guest diagnostics installed",
                "v130-watchdog started",
            )
        }
    }

    @Test
    fun `C7 bind 段以唯一的 bind complete 收尾，随后才起首个 Activity`() {
        for (s in samples) {
            val lines = events(s.tag)
            assertOrder(
                s.tag, lines,
                "engine breadcrumb callbacks registered",
                "bind complete (framework path)",
                "instr newActivity ${s.activity}",
            )
            assertEquals("C7 ${s.tag}: bind complete 只能出现一次", 1, lines.countPrefix("bind complete"))
        }
    }

    @Test
    fun `C8 首个 Activity 的生命周期顺序`() {
        for (s in samples) {
            assertOrder(
                s.tag, events(s.tag),
                "instr newActivity ${s.activity}",
                "instr callActivityOnCreate enter ${s.activity}",
                "preCreate enter ${s.activity}",
                "cb onActivityCreated ${s.activity}",
                "instr callActivityOnCreate exit ok ${s.activity}",
                "cb onActivityStarted ${s.activity}",
                "cb onActivityResumed ${s.activity}",
            )
        }
    }

    @Test
    fun `C9 豁免 guest 跳过 datadir patch，非豁免 guest 必须 patch`() {
        for (s in samples) {
            val lines = events(s.tag)
            if (s.guest == "mark.via") {
                assertTrue(
                    "C9 ${s.tag}: mark.via 在豁免名单内，必须 skip datadir patch",
                    lines.any { it.startsWith("v143-datadir skip (whitelist) pkg=mark.via") },
                )
                assertTrue(
                    "C9 ${s.tag}: mark.via 不允许出现实际 datadir patch",
                    lines.none { it.startsWith("v143-datadir pkg=mark.via") },
                )
            } else {
                assertTrue(
                    "C9 ${s.tag}: 非豁免 guest 必须 patch 到 guest 自己的 dataDir",
                    lines.any {
                        it.startsWith("v143-datadir pkg=${s.guest} ") &&
                            it.contains("-> /data/user/0/${s.guest} ;")
                    },
                )
                assertTrue(
                    "C9 ${s.tag}: 非豁免 guest 不允许出现 skip 分支",
                    lines.none { it.startsWith("v143-datadir skip") },
                )
            }
        }
    }

    @Test
    fun `C10 启动链各阶段只执行一次`() {
        val once = listOf(
            "bind start ",
            "framework makeApplication enter",
            "framework makeApplication exit app=",
            "bind complete",
            "instr newActivity ",
            "Application.onCreate enter",
        )
        for (s in samples) {
            val lines = events(s.tag)
            for (p in once) {
                assertEquals("C10 ${s.tag}: 「$p」应恰好 1 次", 1, lines.countPrefix(p))
            }
        }
    }

    @Test
    fun `C11 同一 guest 的两次启动逐行一致`() {
        for ((a, b) in listOf("via-1" to "via-2", "probe-1" to "probe-2")) {
            val la = events(a)
            val lb = events(b)
            assertEquals("C11 $a/$b: 行数", la.size, lb.size)
            for (i in la.indices) {
                assertEquals("C11 $a/$b: 第 ${i + 1} 行", la[i], lb[i])
            }
        }
    }
}
