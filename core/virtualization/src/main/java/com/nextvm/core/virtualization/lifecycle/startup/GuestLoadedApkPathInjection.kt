package com.nextvm.core.virtualization.lifecycle.startup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.nextvm.core.common.findField
import timber.log.Timber

/**
 * 把 guest 的路径写进宿主 `LoadedApk`（ApplicationInfo 的 4 个路径 + `mLibDir`）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/GuestLoadedApkPathInjection.md`
 */
object GuestLoadedApkPathInjection {

    private const val TAG = "VAppLifecycle"

    /** 要注入的 ApplicationInfo 路径字段清单（顺序即代码顺序，供审查/测试固定）。 */
    internal val APP_INFO_FIELDS = listOf(
        "nativeLibraryDir",
        "sourceDir",
        "publicSourceDir",
        "dataDir",
    )

    /**
     * 在 `Application.attach()` 之前调用：让 Runtime.nativeLoad 与框架看到 guest 的路径。
     *
     * 失败只打 W，不抛（attach 流程不能因为注入失败而中断）。
     */
    fun apply(virtualContext: Context) {
        try {
            // Walk the ContextWrapper chain to find the underlying ContextImpl
            var ctx: Context = virtualContext
            while (ctx is android.content.ContextWrapper) {
                val base = ctx.baseContext ?: break
                ctx = base
            }

            // Get mPackageInfo (LoadedApk) from ContextImpl
            val mPackageInfoField = findField(ctx::class.java, "mPackageInfo")
            mPackageInfoField?.isAccessible = true
            val loadedApk = mPackageInfoField?.get(ctx) ?: run {
                Timber.tag(TAG).w("Could not find mPackageInfo on ContextImpl")
                return
            }

            // Get mApplicationInfo from LoadedApk
            val appInfoField = findField(loadedApk::class.java, "mApplicationInfo")
            appInfoField?.isAccessible = true
            val loadedApkAppInfo = appInfoField?.get(loadedApk) as? ApplicationInfo
                ?: run {
                    Timber.tag(TAG).w("Could not find mApplicationInfo on LoadedApk")
                    return
                }

            // Read guest paths from VirtualContext.getApplicationInfo()
            val guestAppInfo = virtualContext.applicationInfo

            // Patch the LoadedApk's ApplicationInfo with guest paths
            loadedApkAppInfo.nativeLibraryDir = guestAppInfo.nativeLibraryDir
            loadedApkAppInfo.sourceDir = guestAppInfo.sourceDir
            loadedApkAppInfo.publicSourceDir = guestAppInfo.sourceDir
            loadedApkAppInfo.dataDir = guestAppInfo.dataDir

            // CRITICAL: Also patch LoadedApk.mLibDir which is used by
            // Runtime.nativeLoad() to construct the library search path.
            // Without this, the linker namespace has wrong paths and .so loading fails.
            val mLibDirField = findField(loadedApk::class.java, "mLibDir")
            if (mLibDirField != null) {
                mLibDirField.isAccessible = true
                mLibDirField.set(loadedApk, guestAppInfo.nativeLibraryDir)
                Timber.tag(TAG).d("Injected mLibDir into LoadedApk: ${guestAppInfo.nativeLibraryDir}")
            }

            Timber.tag(TAG).d(
                "Injected guest paths into LoadedApk: nativeLibDir=${loadedApkAppInfo.nativeLibraryDir}"
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to inject guest paths into LoadedApk (non-fatal)")
        }
    }
}
