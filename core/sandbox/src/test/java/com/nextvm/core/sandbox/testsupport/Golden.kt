package com.nextvm.core.sandbox.testsupport

import java.io.File
import org.junit.Assert.fail

/**
 * Golden（批准文件）支持：逐字比对输出。
 * 与 core:virtualization 的同名工具一致；详见 `docs/设计/设计-重构行为等价性验证.md`。
 */
object Golden {

    private const val DIR = "src/test/resources/golden"

    /** 比对输出；`UPDATE_GOLDEN=1` 时重录。文件缺失时生成并失败（提示需人工 review）。 */
    fun verify(name: String, actual: String) {
        val file = File(System.getProperty("user.dir"), "$DIR/$name/approved.txt")
        val expected = if (file.exists()) file.readText().replace("\r\n", "\n") else null

        val update = System.getenv("UPDATE_GOLDEN") == "1"
        if (update || expected == null) {
            file.parentFile?.mkdirs()
            file.writeText(actual)
            if (expected == null) {
                fail("golden 不存在，已生成：$file\n请 review 内容后提交。\n--- 内容 ---\n$actual")
            }
            return
        }
        if (expected != actual) fail(diff(expected, actual))
    }

    private fun diff(expected: String, actual: String): String {
        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until maxOf(e.size, a.size)) {
            if (e.getOrNull(i) != a.getOrNull(i)) {
                return "golden 不匹配（第 ${i + 1} 行）\n" +
                    "  - 期望: ${e.getOrNull(i) ?: "<缺行>"}\n" +
                    "  + 实际: ${a.getOrNull(i) ?: "<缺行>"}"
            }
        }
        return "golden 不匹配（长度不同）"
    }
}
