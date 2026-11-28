package com.nextvm.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B 态（内容目录外部化）的**准入守卫**：只有白名单里的应用才允许外部化。
 *
 * 固定的是"默认只含 mark.via、未列出的包一律 false"这条行为——
 * 目录外部化会改变 guest 看到的路径形状，绝不能对非白名单应用生效。
 *
 * 说明见 docs/设计/设计-内容目录三态与真假取舍.md
 */
class PathSpoofConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun freshLoad(): java.io.File {
        val dir = tmp.newFolder("cfg")
        PathSpoofConfig.load(dir)
        return dir
    }

    @Test
    fun `首次生成只把 via 放进外部化名单`() {
        freshLoad()
        assertEquals(listOf("mark.via"), PathSpoofConfig.externalContentList())
    }

    @Test
    fun `未列出的包一律不外化`() {
        freshLoad()
        assertFalse(PathSpoofConfig.isExternalContentListed("com.example.other"))
        assertFalse(PathSpoofConfig.isExternalContentListed("com.sandbox.probe"))
        assertTrue(PathSpoofConfig.isExternalContentListed("mark.via"))
    }

    @Test
    fun `显式增删生效且持久化`() {
        val dir = freshLoad()
        assertTrue(PathSpoofConfig.setExternalContentListed("com.example.tmp", true))
        assertTrue(PathSpoofConfig.isExternalContentListed("com.example.tmp"))

        // 重新从文件加载：新增项应保留
        PathSpoofConfig.load(dir)
        assertTrue(PathSpoofConfig.isExternalContentListed("com.example.tmp"))

        assertTrue(PathSpoofConfig.setExternalContentListed("com.example.tmp", false))
        PathSpoofConfig.load(dir)
        assertFalse(PathSpoofConfig.isExternalContentListed("com.example.tmp"))
    }

    @Test
    fun `豁免名单与外部化名单相互独立`() {
        freshLoad()
        // via 同时在两个名单里：豁免=不做形态欺骗，外部化=内容目录放 vsandbox
        assertTrue(PathSpoofConfig.isGuestExempt("mark.via"))
        assertTrue(PathSpoofConfig.isExternalContentListed("mark.via"))
        // 手工加一个只外部化、不豁免的包
        assertTrue(PathSpoofConfig.setExternalContentListed("com.example.tmp", true))
        assertFalse(PathSpoofConfig.isGuestExempt("com.example.tmp"))
    }
}
