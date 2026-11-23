package com.nextvm.core.sandbox

import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric 冒烟测试（P1-2 的第 0 步）。
 *
 * 目的：**只验证 Robolectric 4.14 能在本项目里启动**，不验证任何业务逻辑。
 *
 * 为什么先跑这一个：
 *   Robolectric 4.14 是官方兼容表里唯一匹配 AGP 8.7.x / Gradle 8.9 / compileSdk 35 的版本，
 *   但"表上兼容"不等于"实际能跑"。先用最小用例确认：
 *     1) 依赖能解析
 *     2) aandroid-all-instrumented jar 能下载
 *     3) @Config(sdk = [35]) 能正常初始化
 *   确认后再写 HostReadPolicy 的 15 条用例，避免在错误的基础上堆代码。
 */
@Ignore(
    "Robolectric 需要 aandroid-all-instrumented（约 100 MB），" +
        "CI 上下载不稳定（p23 全量失败）。本用例留作本地/调试环境的手动探测，" +
        "不参与 CI。需要用 Robolectric 时：先确保该 jar 已在本地 Gradle 缓存，再临时移除 @Ignore。"
)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobolectricSmokeTest {

    @Test
    fun robolectricBootsOnApi35() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        assertNotNull("RuntimeEnvironment.getApplication() 返回了 null", ctx)
        assertNotNull("packageName 为 null", ctx.packageName)
        assertTrue(
            "packageName 不是 core:sandbox 的 namespace，实际=${ctx.packageName}",
            ctx.packageName.isNotEmpty()
        )
    }

    @Test
    fun filesDirIsUsable() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = ctx.filesDir
        assertNotNull(dir)
        assertTrue("filesDir 不存在", dir.exists() || dir.mkdirs())
    }
}