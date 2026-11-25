package com.nextvm.core.hook.path

import com.nextvm.core.hook.NativeHookBridge
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路径翻译的 golden 向量（Kotlin 侧）。
 *
 * **与 C++ 侧 `core/hook/src/test/cpp/path_rules_test.cpp` 共读同一份**
 * `golden/path_vectors.tsv` —— 这是防止 Java 与 C++ 语义分叉的机制
 * （本仓库曾出现 Kotlin 用 String.replace 全局替换、C++ 用 substr 正确的前车之鉴）。
 *
 * 说明：docs/架构/架构-C++测试夹具设计.md、docs/计划/计划-黄金测试与覆盖率提升.md（T7）
 */
class PathGoldenTest {

    private val pkg = "mark.via"
    private val sandbox = "/sandbox/i1"

    /** 与 C++ 侧 makeApp()/makeExternal()/makeHiddenSu() 同规格的构造器。 */
    private fun buildBridge(ruleSet: String): NativeHookBridge {
        val bridge = NativeHookBridge()
        when (ruleSet) {
            "app_i1" -> {
                val ext = "$sandbox/sdcard/Android/data/$pkg"
                bridge.addPathRedirection("/data/data/$pkg/", "$sandbox/")
                bridge.addPathRedirection("/data/user/0/$pkg/", "$sandbox/")
                bridge.addPathRedirection("/storage/emulated/0/Android/data/$pkg/", "$ext/")
                bridge.addPathRedirection("/sdcard/Android/data/$pkg/", "$ext/")
                bridge.addPathRedirection("/storage/emulated/0/Android/data/$pkg", ext)
                bridge.addPathRedirection("/sdcard/Android/data/$pkg", ext)
            }
            "external" -> {
                val root = "$sandbox/sdcard"
                bridge.addPathRedirection("/sdcard/", "$root/")
                bridge.addPathRedirection("/storage/emulated/0/", "$root/")
                bridge.addPathRedirection("/mnt/sdcard/", "$root/")
                bridge.addPathRedirection("/storage/self/primary/", "$root/")
                bridge.addPathRedirection("/storage/emulated/0", root)
                bridge.addPathRedirection("/sdcard", root)
            }
            "hidden_su" -> {
                bridge.hidePath("/system/bin/su")
                bridge.addPathRedirection("/system/", "$sandbox/system/")
            }
            else -> throw IllegalArgumentException("未知 rule_set: $ruleSet")
        }
        return bridge
    }

    @Test
    fun `golden 向量全部通过（与 C++ 共用同一份 TSV）`() {
        val stream = javaClass.classLoader?.getResourceAsStream("golden/path_vectors.tsv")
            ?: error("找不到 golden/path_vectors.tsv（测试资源未打包）")
        val lines = stream.bufferedReader().use { it.readLines() }

        var total = 0
        val failures = ArrayList<String>()
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = line.split("\t")
            if (cols.size < 4) continue
            val id = cols[0]
            val ruleSet = cols[1]
            val path = cols[2]
            val expected = if (cols[3] == "-") path else cols[3].replace("<sandbox>", sandbox)

            val actual = buildBridge(ruleSet).translatePath(path)
            total++
            if (actual != expected) {
                failures += "$id [$ruleSet] $path\n  期望=$expected\n  实际=$actual"
            }
        }

        assertTrue("golden 向量为空", total > 0)
        assertTrue("失败 ${failures.size}/$total:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
