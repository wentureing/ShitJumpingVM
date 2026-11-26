package com.nextvm.core.virtualization.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `SelfCheckRequest` 的协议铆钉（纯 JVM）：一次请求只能跑一次自检。
 *
 * 说明：docs/输出/变更/20261126-selfcheck-guest.md
 */
class SelfCheckRequestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `R1 写请求后 pending 为真`() {
        val dir = tmp.newFolder("guest")
        assertFalse(SelfCheckRequest.isPending(dir))
        assertTrue(SelfCheckRequest.request(dir))
        assertTrue(SelfCheckRequest.isPending(dir))
    }

    @Test
    fun `R2 consume 删除文件且只成功一次`() {
        val dir = tmp.newFolder("guest")
        SelfCheckRequest.request(dir)
        assertTrue("首次消费应成功", SelfCheckRequest.consume(dir))
        assertFalse("文件应被删除", SelfCheckRequest.isPending(dir))
        assertFalse("二次消费必须失败（一次请求只跑一次）", SelfCheckRequest.consume(dir))
    }

    @Test
    fun `R3 未写请求时 consume 为假`() {
        val dir = tmp.newFolder("guest")
        assertFalse(SelfCheckRequest.consume(dir))
    }

    @Test
    fun `R4 目标不可写时写失败但不抛`() {
        val blocker = File(tmp.root, "blocker").apply { writeText("x") }
        val dir = File(blocker, "child") // 父是普通文件 -> 必然失败
        assertFalse(SelfCheckRequest.request(dir))
        assertFalse(SelfCheckRequest.isPending(dir))
    }
}
