package com.nextvm.core.virtualization.lifecycle.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PluginProbes 的纯逻辑铆钉（P1–P5）。
 *
 * 两个探针入口依赖 Application / 反射，不可纯 JVM 测（见镜像文档「约束与坑」）；
 * 这里只锁定从它们里抽出的两个纯函数：字段渲染与 meta-data 分类。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/diag/PluginProbes.md
 */
class PluginProbesTest {

    @Test
    fun `P1 字段渲染：null 与基础类型`() {
        assertEquals("null", PluginProbes.renderFieldValue(null))
        assertEquals("true", PluginProbes.renderFieldValue(true))
        assertEquals("42", PluginProbes.renderFieldValue(42))
        assertEquals("hi", PluginProbes.renderFieldValue("hi"))
    }

    @Test
    fun `P2 字段渲染：数组只给长度，不展开`() {
        assertEquals("Array(len=3)", PluginProbes.renderFieldValue(arrayOf("a", "b", "c")))
        assertEquals("Array(len=0)", PluginProbes.renderFieldValue(emptyArray<String>()))
    }

    @Test
    fun `P3 字段渲染：其它对象只给类名（不调 toString）`() {
        val o = object {
            override fun toString(): String = "SHOULD_NOT_APPEAR"
        }
        val out = PluginProbes.renderFieldValue(o)
        assertTrue(out.startsWith("obj("))
        assertTrue(!out.contains("SHOULD_NOT_APPEAR"))
    }

    @Test
    fun `P4 meta-data 分类：App Startup 与 plugin 键`() {
        val c = PluginProbes.classifyMetaData(
            listOf(
                "androidx.startup.FooInitializer" to "androidx.startup",
                "com.mc10086.plugin.KEY" to "x",
                "android.app.PLAIN" to "y",
                "some.plugin.lower" to null,
            )
        )
        assertEquals(listOf("androidx.startup.FooInitializer"), c.startups)
        assertEquals(
            listOf("com.mc10086.plugin.KEY", "some.plugin.lower"),
            c.pluginKeys
        )
    }

    @Test
    fun `P5 meta-data 分类：空输入与大小写不敏感`() {
        val empty = PluginProbes.classifyMetaData(emptyList())
        assertEquals(0, empty.startups.size)
        assertEquals(0, empty.pluginKeys.size)

        val c = PluginProbes.classifyMetaData(listOf("A.PLUGIN.KEY" to "androidx.startup"))
        assertEquals(listOf("A.PLUGIN.KEY"), c.startups)
        assertEquals(listOf("A.PLUGIN.KEY"), c.pluginKeys)
    }
}
