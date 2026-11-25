package com.nextvm.core.virtualization.diag.probe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LoginProbes 的纯逻辑铆钉（L1–L3）。
 *
 * 四个入口依赖 Activity/View/Intent，不可纯 JVM 测（见镜像文档）；
 * 这里锁定 v144/v145 的日志行格式（排查脚本按字段名 grep）。
 *
 * 说明：docs/代码镜像/core/virtualization/diag/probe/LoginProbes.md
 */
class LoginProbesTest {

    @Test
    fun `L1 formatAuthRecv 含 data scheme host keys 与 phone 状态`() {
        val out = LoginProbes.formatAuthRecv(
            dataPresent = true,
            scheme = "https",
            host = "login.example.com",
            extraKeys = listOf("a", "b"),
            phoneFound = true,
            phoneEmpty = false,
        )
        assertEquals(
            "v144 recv dataPresent=true scheme=https host=login.example.com " +
                "extraKeys=a,b phoneFound=true phoneEmpty=false",
            out
        )
    }

    @Test
    fun `L2 formatAuthRecv 无 data 与空 keys`() {
        val out = LoginProbes.formatAuthRecv(
            dataPresent = false,
            scheme = null,
            host = null,
            extraKeys = emptyList(),
            phoneFound = false,
            phoneEmpty = null,
        )
        assertEquals(
            "v144 recv dataPresent=false scheme=null host=null " +
                "extraKeys= phoneFound=false phoneEmpty=null",
            out
        )
    }

    @Test
    fun `L3 formatAuthRecv145 三个字段`() {
        assertEquals(
            "v145 recv bundleKeys=k1,k2 queryBundleKeys=ABSENT routerQueryParams=q1,q2",
            LoginProbes.formatAuthRecv145("k1,k2", "ABSENT", "q1,q2")
        )
    }
}
