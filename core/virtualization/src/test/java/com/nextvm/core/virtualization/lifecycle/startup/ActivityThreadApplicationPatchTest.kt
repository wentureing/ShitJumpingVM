package com.nextvm.core.virtualization.lifecycle.startup

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ActivityThreadApplicationPatch.isEnabled` 的纯逻辑铆钉。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/startup/ActivityThreadApplicationPatch.md
 */
class ActivityThreadApplicationPatchTest {

    private val key = "nextvm.webviewAppContext"

    @After
    fun clear() {
        System.clearProperty(key)
    }

    @Test
    fun `B1 未设置属性时默认启用`() {
        System.clearProperty(key)
        assertTrue(ActivityThreadApplicationPatch.isEnabled())
    }

    @Test
    fun `B2 属性为 0 时禁用`() {
        System.setProperty(key, "0")
        assertFalse(ActivityThreadApplicationPatch.isEnabled())
    }

    @Test
    fun `B3 属性为 1 时启用`() {
        System.setProperty(key, "1")
        assertTrue(ActivityThreadApplicationPatch.isEnabled())
    }

    @Test
    fun `B4 除 0 以外的任何值都算启用`() {
        // 原判据是 `!= "0"`，不是 parseBoolean —— 固定这个既有行为
        System.setProperty(key, "false")
        assertTrue(ActivityThreadApplicationPatch.isEnabled())
        System.setProperty(key, "")
        assertTrue(ActivityThreadApplicationPatch.isEnabled())
    }
}
