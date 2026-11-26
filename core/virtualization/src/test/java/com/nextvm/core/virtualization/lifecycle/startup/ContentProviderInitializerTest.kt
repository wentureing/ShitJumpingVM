package com.nextvm.core.virtualization.lifecycle.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ContentProviderInitializer.isShizukuProvider` 的纯逻辑铆钉。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/startup/ContentProviderInitializer.md
 */
class ContentProviderInitializerTest {

    @Test
    fun `P1 类名带 shizuku 的跳过`() {
        assertTrue(ContentProviderInitializer.isShizukuProvider("moe.shizuku.Provider", ""))
    }

    @Test
    fun `P2 类名带 rikka-shizuku 的跳过`() {
        assertTrue(ContentProviderInitializer.isShizukuProvider("rikka.shizuku.ShizukuProvider", ""))
    }

    @Test
    fun `P3 authority 带 shizuku 的跳过`() {
        // 类名看不出、authority 才是线索的情况（原实现第三条判据）
        assertTrue(ContentProviderInitializer.isShizukuProvider("com.foo.Provider", "moe.shizuku.api"))
    }

    @Test
    fun `P4 大小写不敏感`() {
        assertTrue(ContentProviderInitializer.isShizukuProvider("com.foo.SHIZUKUInitProvider", ""))
    }

    @Test
    fun `P5 普通 provider 不跳过`() {
        assertFalse(
            ContentProviderInitializer.isShizukuProvider(
                "androidx.startup.InitializationProvider",
                "mark.via.androidx-startup",
            ),
        )
    }

    @Test
    fun `P6 名字里只有 shiz 片段不算`() {
        assertFalse(ContentProviderInitializer.isShizukuProvider("com.foo.ShizProvider", "com.foo.shiz"))
    }
}
