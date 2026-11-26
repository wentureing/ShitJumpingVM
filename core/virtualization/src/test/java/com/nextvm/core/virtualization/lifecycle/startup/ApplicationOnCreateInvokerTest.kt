package com.nextvm.core.virtualization.lifecycle.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ApplicationOnCreateInvoker.isShizukuSecurityException` 的纯逻辑铆钉。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/startup/ApplicationOnCreateInvoker.md
 */
class ApplicationOnCreateInvokerTest {

    @Test
    fun `A1 直接是 ShizukuProvider 的 SecurityException 要吞`() {
        assertTrue(
            ApplicationOnCreateInvoker.isShizukuSecurityException(
                SecurityException("Permission Denial: rikka.shizuku.ShizukuProvider"),
            ),
        )
    }

    @Test
    fun `A2 缺 INTERACT_ACROSS_USERS 的要吞`() {
        assertTrue(
            ApplicationOnCreateInvoker.isShizukuSecurityException(
                SecurityException("requires android.permission.INTERACT_ACROSS_USERS_FULL"),
            ),
        )
    }

    @Test
    fun `A3 消息里小写 shizuku 要吞`() {
        assertTrue(
            ApplicationOnCreateInvoker.isShizukuSecurityException(
                SecurityException("call to shizuku service failed"),
            ),
        )
    }

    @Test
    fun `A4 嵌套两层 cause 里的 SecurityException 也要吞`() {
        val inner = SecurityException("ShizukuProvider not available")
        val mid = RuntimeException(inner)
        val outer = RuntimeException(mid)
        assertTrue(ApplicationOnCreateInvoker.isShizukuSecurityException(outer))
    }

    @Test
    fun `A5 与 shizuku 无关的 SecurityException 不吞`() {
        assertFalse(
            ApplicationOnCreateInvoker.isShizukuSecurityException(
                SecurityException("Permission Denial: reading contacts"),
            ),
        )
    }

    @Test
    fun `A6 非 SecurityException 一律不吞`() {
        // 容器只对"缺跨用户权限"这一种做降级，其他异常必须照常抛出
        assertFalse(ApplicationOnCreateInvoker.isShizukuSecurityException(OutOfMemoryError("boom")))
        assertFalse(ApplicationOnCreateInvoker.isShizukuSecurityException(IllegalStateException("shizuku")))
    }

    @Test
    fun `A7 null 不吞`() {
        assertFalse(ApplicationOnCreateInvoker.isShizukuSecurityException(null))
        assertFalse(ApplicationOnCreateInvoker.isShizukuSecurityException(RuntimeException(null as Throwable?)))
    }

    @Test
    fun `A8 message 为 null 的 SecurityException 不吞`() {
        assertFalse(ApplicationOnCreateInvoker.isShizukuSecurityException(SecurityException()))
    }
}
