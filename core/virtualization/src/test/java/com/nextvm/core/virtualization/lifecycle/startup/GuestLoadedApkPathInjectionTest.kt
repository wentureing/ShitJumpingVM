package com.nextvm.core.virtualization.lifecycle.startup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `GuestLoadedApkPathInjection.APP_INFO_FIELDS` 的契约铆钉（I 系列）。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/startup/GuestLoadedApkPathInjection.md
 */
class GuestLoadedApkPathInjectionTest {

    @Test
    fun `I1 注入字段清单与顺序固定（漏字段 = 静默失灵）`() {
        assertEquals(
            listOf("nativeLibraryDir", "sourceDir", "publicSourceDir", "dataDir"),
            GuestLoadedApkPathInjection.APP_INFO_FIELDS,
        )
    }
}
