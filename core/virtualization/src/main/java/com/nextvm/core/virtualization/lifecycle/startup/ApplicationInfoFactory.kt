package com.nextvm.core.virtualization.lifecycle.startup

import android.content.Context
import android.content.pm.ApplicationInfo
import timber.log.Timber

/**
 * 构造 guest 的 `ApplicationInfo`（沙箱路径 + APK metaData + `v135` 路径保真分支）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/ApplicationInfoFactory.md`
 */
object ApplicationInfoFactory {

    private const val TAG = "VAppLifecycle"

    fun build(packageName: String, apkPath: String, virtualContext: Context): ApplicationInfo {
        return ApplicationInfo().apply {
            this.packageName = packageName
            this.sourceDir = apkPath
            this.publicSourceDir = apkPath
            this.dataDir = virtualContext.dataDir.absolutePath
            this.nativeLibraryDir = virtualContext.applicationInfo.nativeLibraryDir
            this.processName = packageName
            this.enabled = true
            this.flags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA
            // longVersionCode defaults to 0 on new ApplicationInfo

            // Populate metaData from the guest APK so SDKs (AppLovin, Firebase,
            // Facebook, CronetProvider, etc.) can read their <meta-data> tags.
            // Also inject essential GMS meta-data if not already present.
            try {
                val pm = virtualContext.packageManager
                val pkgInfo = pm.getPackageArchiveInfo(
                    apkPath,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                pkgInfo?.applicationInfo?.let { it2 ->
                    it2.sourceDir = apkPath
                    it2.publicSourceDir = apkPath
                }
                val bundle = pkgInfo?.applicationInfo?.metaData ?: android.os.Bundle()

                // Inject GMS version meta-data if the guest APK doesn't already have it.
                // Many SDKs (AppLovin, Firebase Analytics, CronetProvider) crash without this.
                if (!bundle.containsKey("com.google.android.gms.version")) {
                    bundle.putInt("com.google.android.gms.version", 243431006)
                }

                this.metaData = bundle
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse metaData from APK: $apkPath")
                // At minimum provide GMS version to avoid common SDK crashes
                this.metaData = android.os.Bundle().apply {
                    putInt("com.google.android.gms.version", 243431006)
                }
            }

            // v135 path fidelity (CM-gated experiment): report
            // stock-install paths. I/O on these is redirected by
            // v135-pathfidelity rules registered at guest bind.
            if (com.nextvm.core.model.VirtualConstants.isPathFidelityGuest(packageName)) {
                this.dataDir = com.nextvm.core.model.VirtualConstants.syntheticDataDir(packageName)
                this.nativeLibraryDir = com.nextvm.core.model.VirtualConstants.syntheticLibDir(packageName)
                val synthApk = com.nextvm.core.model.VirtualConstants.syntheticApkPath(packageName)
                this.sourceDir = synthApk
                this.publicSourceDir = synthApk
            }
        }
    }
}
