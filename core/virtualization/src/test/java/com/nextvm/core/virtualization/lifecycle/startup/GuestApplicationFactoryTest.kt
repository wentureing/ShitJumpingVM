package com.nextvm.core.virtualization.lifecycle.startup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `GuestApplicationFactory.classNameOf` 的纯逻辑铆钉。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/startup/GuestApplicationFactory.md
 */
class GuestApplicationFactoryTest {

    @Test
    fun `G1 指定了类名就原样使用`() {
        assertEquals("mark.via.BrowserApp", GuestApplicationFactory.classNameOf("mark.via.BrowserApp"))
    }

    @Test
    fun `G2 类名为 null 时回退到 AOSP 默认 Application`() {
        assertEquals("android.app.Application", GuestApplicationFactory.classNameOf(null))
    }

    @Test
    fun `G3 空字符串不回退（只有 null 才回退）`() {
        // 固定既有行为：原实现是 `applicationClassName ?: "android.app.Application"`，
        // 对 "" 不生效 —— 空串会走到 loadClass("") 抛异常再回退 base Application。
        assertEquals("", GuestApplicationFactory.classNameOf(""))
    }
}
