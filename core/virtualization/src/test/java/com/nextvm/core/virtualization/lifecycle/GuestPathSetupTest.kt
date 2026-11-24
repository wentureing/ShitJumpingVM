package com.nextvm.core.virtualization.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * guest 路径准备的白名单判断（纯函数部分）。
 * 完整说明见 `docs/代码镜像/core/virtualization/lifecycle/GuestPathSetup.md`。
 */
class GuestPathSetupTest {

    @Test
    fun `只有探针在白名单内`() {
        assertTrue(isFakeDataDirWhitelisted("com.sandbox.probe"))
    }

    @Test
    fun `真实应用不在白名单内`() {
        assertFalse(isFakeDataDirWhitelisted("mark.via"))
        assertFalse(isFakeDataDirWhitelisted("com.tencent.mm"))
        assertFalse(isFakeDataDirWhitelisted(""))
    }

    @Test
    fun `近似包名不匹配（必须完全相等）`() {
        assertFalse(isFakeDataDirWhitelisted("com.sandbox.probe2"))
        assertFalse(isFakeDataDirWhitelisted("com.sandbox"))
    }
}
