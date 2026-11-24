package com.nextvm.core.virtualization.lifecycle

import com.nextvm.core.common.DebugFlags
import com.nextvm.core.common.DebugTrace
import com.nextvm.core.model.VirtualConstants
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb

/**
 * guest 进程的路径 / 目录准备（v152 从 `VirtualApplicationManager` 拆出）。
 *
 * ## 职责
 * 在 guest 进程 bind 时，把"让路径能以 guest 身份工作"的三件事做掉：
 * 1. **注册重定向规则**（v142）：`/sdcard/…`、`/data/user/0/<guest>/…` → 沙箱。
 *    规则是**进程级**的（`NativeHookBridge` 每进程一份），所以必须在 guest 进程里注册；
 *    早期只在主进程 `launch()` 注册，导致 guest `rules=0`（实测）。
 * 2. **假 dataDir**（v143）：把 `LoadedApk` 与 `VirtualContext.getDataDir()` 报成
 *    guest 官方路径 `/data/user/0/<pkg>`。
 * 3. 两者的**开关与诊断**（`debug.nextvm.guestRedirect` / `webviewDataDirFake`）。
 *
 * ## 不负责
 * - ❌ ActivityThread 字段改写（v138 → 留在 `VirtualApplicationManager` / 未来的 `GuestBootSupport`）
 * - ❌ 目录树的实际创建（`SandboxManager.ensureAppDirsReady`）
 * - ❌ native 侧 GOT patch（`NativeHookBridge`）
 *
 * ## 偏差登记
 *
 * | # | 偏差 | 类型 | 说明 |
 * | --- | --- | --- | --- |
 * | 1 | 在 guest 进程重复注册路径规则 | ✅ **必要偏差** | 规则表是进程级状态；主进程注册的规则传不到 guest。这是 `rules=0` 那次故障的根因 |
 * | 2 | 把 dataDir 报成 guest 官方路径 | 🟡 **待评估** | 曾假设它能改变 WebView 的 MIME 判定 —— **已被否证**（v146 实测：dataDir 生效但 MIME 仍为 text/plain）。目前仅白名单 guest 使用，价值待重新确认 |
 * | 3 | 只对 `com.sandbox.probe` 启用假 dataDir | ✅ 保守 | 避免影响真实应用；白名单写死在 `VirtualContext.detachedAppInfoEnabled()` |
 *
 * ## 约束与坑
 * 1. **必须在 native 库加载之后**调用（规则要写进 native 的 `g_path_redirects`），
 *    即 `bindApplication` 里 `initNativeHooks` 之后。
 * 2. **规则注册失败不会抛**：全部 try/catch 并写 `DebugTrace.fail`，避免阻断启动。
 * 3. `registerGuestRedirections` 的 `appDir` 取自 `application.filesDir.parentFile`，
 *    依赖 `VirtualContext.getFilesDir()` 已经指向沙箱（不是 host）。
 * 4. 两个函数都是**扩展函数**，依赖 `VirtualApplicationManager` 把
 *    `nativeHookBridge` / `sandboxManager` / `fieldOf` 暴露为 `internal`（v152 改）。
 * 5. debug：命中/跳过/失败都写 `debug-trace.log`（tag = `v142` / `v143`）。
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
