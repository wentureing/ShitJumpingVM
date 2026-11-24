package com.nextvm.core.virtualization.lifecycle

import com.nextvm.core.common.DebugFlags
import com.nextvm.core.common.DebugTrace
import com.nextvm.core.model.VirtualConstants
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb

/**
 * guest 进程路径准备：注册重定向规则（v142）+ 假 dataDir（v143）。
 * 详见 `docs/代码镜像/core/virtualization/lifecycle/GuestPathSetup.md`。
 */

/** v142：在 guest 进程注册本实例的路径重定向规则。 */
internal fun VirtualApplicationManager.registerGuestRedirections(
    application: android.app.Application,
    instanceId: String,
    guestPkg: String
) {
    try {
        if (!DebugFlags.isOn(DebugFlags.GUEST_REDIRECT, true)) {
            GuestBreadcrumb.log("v142-redirect skipped by debug.nextvm.guestRedirect=0")
            DebugTrace.event("v142", "SKIPPED by flag")
            return
        }
        val appDir = application.filesDir?.parentFile
        if (appDir == null) {
            GuestBreadcrumb.log("v142-redirect skip: filesDir=null")
            DebugTrace.fail("v142", "filesDir=null")
            return
        }
        val sdcardDir = java.io.File(appDir, "sdcard")
        nativeHookBridge.setupExternalStorageRedirections(instanceId, sdcardDir.absolutePath)
        nativeHookBridge.setupAppRedirections(guestPkg, instanceId, appDir.absolutePath)

        val rules = nativeHookBridge.getPathRedirectSnapshot().size
        DebugTrace.event(
            "v142",
            "registered rules=$rules appDir=${appDir.absolutePath} sdcard=${sdcardDir.absolutePath}"
        )
        GuestBreadcrumb.log(
            "v142-redirect registered rules=$rules appDir=${appDir.absolutePath} " +
                "sdcard=${sdcardDir.absolutePath}"
        )
    } catch (t: Throwable) {
        GuestBreadcrumb.log("v142-redirect FAILED: ${t.javaClass.simpleName}: ${t.message}")
        DebugTrace.fail("v142", "register failed", t)
    }
}

/**
 * v143：把 LoadedApk 的 dataDir 注入为 guest **官方路径**。
 *
 * 背景：曾假设让 WebView 认为 `file://` 文件不在自己 dataDir 内，就会走标准 MIME 判定 ——
 * **已被否证**（v146）。当前保留该能力仅供实验，且只对白名单 guest 生效。
 */
internal fun VirtualApplicationManager.patchFrameworkDataDir(
    application: android.app.Application,
    guestPkg: String
) {
    try {
        val enabled = DebugFlags.isOn(DebugFlags.WEBVIEW_DATA_DIR_FAKE, true)
        if (!enabled) {
            GuestBreadcrumb.log("v143-datadir disabled by debug.nextvm.webviewDataDirFake=0")
            DebugTrace.event("v143", "SKIPPED by flag")
            return
        }
        if (guestPkg != "com.sandbox.probe") {
            GuestBreadcrumb.log("v143-datadir skip (whitelist) pkg=$guestPkg")
            DebugTrace.detail("v143", "skip whitelist pkg=$guestPkg")
            return
        }
        val fake = VirtualConstants.syntheticDataDir(guestPkg)
        val loadedApk = try {
            fieldOf(application.javaClass, "mLoadedApk").get(application)
        } catch (_: Throwable) {
            null
        }
        if (loadedApk == null) {
            GuestBreadcrumb.log("v143-datadir FAIL: mLoadedApk=null")
            DebugTrace.fail("v143", "mLoadedApk=null")
            return
        }
        val parts = StringBuilder()
        try {
            val ai = fieldOf(loadedApk.javaClass, "mApplicationInfo")
                .get(loadedApk) as? android.content.pm.ApplicationInfo
            if (ai != null) {
                val before = ai.dataDir
                ai.dataDir = fake
                parts.append(" appInfo ").append(before).append(" -> ").append(ai.dataDir)
            }
        } catch (t: Throwable) {
            parts.append(" appInfo:").append(t.javaClass.simpleName)
        }
        try {
            fieldOf(loadedApk.javaClass, "mDataDir").set(loadedApk, fake)
            parts.append(" ; mDataDir -> ").append(fake)
        } catch (t: Throwable) {
            parts.append(" ; mDataDir:").append(t.javaClass.simpleName)
        }
        DebugTrace.event("v143", "datadir pkg=$guestPkg$parts")
        GuestBreadcrumb.log("v143-datadir pkg=$guestPkg$parts")
    } catch (t: Throwable) {
        GuestBreadcrumb.log("v143-datadir FAILED: ${t.javaClass.simpleName}: ${t.message}")
        DebugTrace.fail("v143", "patch failed", t)
    }
}
