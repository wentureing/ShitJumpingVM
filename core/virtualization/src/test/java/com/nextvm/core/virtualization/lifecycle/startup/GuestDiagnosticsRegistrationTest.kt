package com.nextvm.core.virtualization.lifecycle.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GuestDiagnosticsRegistration.shouldConsumeSelfCheck` 的纯逻辑铆钉。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/startup/GuestDiagnosticsRegistration.md
 */
class GuestDiagnosticsRegistrationTest {

    @Test
    fun `S1 debug 包且本进程还没消费过才消费`() {
        assertTrue(GuestDiagnosticsRegistration.shouldConsumeSelfCheck(isDebugBuild = true, alreadyDone = false))
    }

    @Test
    fun `S2 本进程已消费过就不再消费（每进程一次）`() {
        assertFalse(GuestDiagnosticsRegistration.shouldConsumeSelfCheck(isDebugBuild = true, alreadyDone = true))
    }

    @Test
    fun `S3 release 包永不消费（自检只在 debug 有意义）`() {
        assertFalse(GuestDiagnosticsRegistration.shouldConsumeSelfCheck(isDebugBuild = false, alreadyDone = false))
        assertFalse(GuestDiagnosticsRegistration.shouldConsumeSelfCheck(isDebugBuild = false, alreadyDone = true))
    }
}
