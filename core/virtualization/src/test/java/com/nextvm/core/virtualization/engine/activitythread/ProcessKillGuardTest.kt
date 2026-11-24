package com.nextvm.core.virtualization.engine.activitythread

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块级单测：进程自杀守卫（v151）。
 *
 * 钉住两件事：
 * 1. `install()` 后 `isActive()` 为真（守卫真的挂上了标志）；
 * 2. `uninstall()` 后 `isActive()` 为假（不会泄漏到后续测试/后续生命周期）。
 *
 * 注意：JVM 环境下 `System.setSecurityManager` 可能被拒绝（JDK 17+），
 * 本守卫对此是 try/catch 降级，因此这里**只测标志位**，不测 SecurityManager 是否装成。
 */
class ProcessKillGuardTest {

    @Test
    fun `install 之后处于激活态`() {
        val guard = ProcessKillGuard.install()
        try {
            assertTrue("install() 后 isActive 必须为 true", ProcessKillGuard.isActive())
        } finally {
            guard.uninstall()
        }
    }

    @Test
    fun `uninstall 之后不再激活`() {
        val guard = ProcessKillGuard.install()
        guard.uninstall()
        assertFalse("uninstall() 后 isActive 必须为 false", ProcessKillGuard.isActive())
    }

    @Test
    fun `重复 install 不会破坏状态`() {
        val g1 = ProcessKillGuard.install()
        val g2 = ProcessKillGuard.install()
        try {
            assertTrue(ProcessKillGuard.isActive())
        } finally {
            g2.uninstall()
            g1.uninstall()
        }
    }
}
