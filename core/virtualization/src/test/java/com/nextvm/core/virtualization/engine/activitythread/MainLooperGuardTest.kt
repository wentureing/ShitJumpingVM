package com.nextvm.core.virtualization.engine.activitythread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块级单测：主线程异常分类（v151）。
 *
 * 这个测试存在的意义：`MainLooperGuard` 的"吞哪两类异常"是**必要偏差**的核心，
 * 一旦规则被无意改宽（变成"全部吞"），就会让 guest 的崩溃变成静默卡死。
 * 这里把每条规则都钉住。
 *
 * 纯 JVM 测试：`classifyLooperThrowable` 不依赖 Looper / Android 运行时。
 */
class MainLooperGuardTest {

    @Test
    fun `普通异常必须按崩溃处理`() {
        val e = RuntimeException("something broke")
        assertEquals(LooperSuppression.NONE, MainLooperGuard.classifyLooperThrowable(e))
    }

    @Test
    fun `GMS Unknown calling package 被吞`() {
        val e = SecurityException("Unknown calling package name 'com.google.android.gms'")
        assertEquals(LooperSuppression.GMS_SECURITY, MainLooperGuard.classifyLooperThrowable(e))
    }

    @Test
    fun `GMS does not belong to 被吞`() {
        val e = SecurityException("Package com.foo does not belong to 10351")
        assertEquals(LooperSuppression.GMS_SECURITY, MainLooperGuard.classifyLooperThrowable(e))
    }

    @Test
    fun `Toast 窗口的 UID mismatch 被吞（cause 链中间）`() {
        // 实战结构：RuntimeException("Adding window failed") -> SecurityException("... not in UID ...")
        val sec = SecurityException("Package com.foo not in UID 10351")
        val e = RuntimeException("Adding window failed", sec)
        assertEquals(
            LooperSuppression.TOAST_UID_MISMATCH,
            MainLooperGuard.classifyLooperThrowable(e)
        )
    }

    @Test
    fun `只有 UID mismatch 但非 Toast 窗口时不吞`() {
        val e = SecurityException("Package com.foo not in UID 10351")
        assertEquals(LooperSuppression.NONE, MainLooperGuard.classifyLooperThrowable(e))
    }

    @Test
    fun `深层嵌套的 cause 链也能识别`() {
        val sec = SecurityException("not in UID 10351")
        val middle = IllegalStateException("Adding window failed", sec)
        val top = RuntimeException("wrapped", middle)
        assertEquals(
            LooperSuppression.TOAST_UID_MISMATCH,
            MainLooperGuard.classifyLooperThrowable(top)
        )
    }

    @Test
    fun `无关的 SecurityException 不吞`() {
        val e = SecurityException("Permission Denial: reading contacts")
        assertEquals(LooperSuppression.NONE, MainLooperGuard.classifyLooperThrowable(e))
    }
}
