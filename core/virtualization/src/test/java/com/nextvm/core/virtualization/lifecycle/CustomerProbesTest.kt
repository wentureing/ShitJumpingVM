package com.nextvm.core.virtualization.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 客户探测的入口判断（纯函数部分）。
 * 完整说明见 `docs/代码镜像/core/virtualization/lifecycle/CustomerProbes.md`。
 */
class CustomerProbesTest {

    @Test
    fun `只认 CMCC 认证 Activity`() {
        assertTrue(
            isCmccAuthActivity(
                "com.mc10086.cmcc.authentication.common.AuthenticationActivity"
            )
        )
    }

    @Test
    fun `其它类名一律不认`() {
        assertFalse(isCmccAuthActivity("com.mc10086.cmcc.OtherActivity"))
        assertFalse(isCmccAuthActivity("com.example.authentication.common.AuthenticationActivity"))
        assertFalse(isCmccAuthActivity(""))
    }

    @Test
    fun `前缀相同但不同的类名不认（必须完全相等）`() {
        // 避免 "com.mc10086.cmcc.authentication.common.AuthenticationActivity2" 被误判
        assertFalse(
            isCmccAuthActivity(
                "com.mc10086.cmcc.authentication.common.AuthenticationActivity2"
            )
        )
    }
}
