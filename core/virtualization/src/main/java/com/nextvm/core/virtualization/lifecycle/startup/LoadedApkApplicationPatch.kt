package com.nextvm.core.virtualization.lifecycle.startup

import android.app.Application
import android.content.Context
import com.nextvm.core.common.findField
import timber.log.Timber

/**
 * 把宿主 `LoadedApk.mApplication` 指向 guest Application（绕过 ContextWrapper 链也能拿到 guest）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/LoadedApkApplicationPatch.md`
 */
object LoadedApkApplicationPatch {

    private const val TAG = "VAppLifecycle"

    fun apply(virtualContext: Context, guestApplication: Application) {
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
                Timber.tag(TAG).w("Could not find mPackageInfo for mApplication patch")
                return
            }

            // Patch LoadedApk.mApplication
            val mApplicationField = findField(loadedApk::class.java, "mApplication")
            if (mApplicationField != null) {
                mApplicationField.isAccessible = true
                mApplicationField.set(loadedApk, guestApplication)
                Timber.tag(TAG).d("Patched LoadedApk.mApplication to guest Application")
            } else {
                Timber.tag(TAG).w("Could not find mApplication field on LoadedApk")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to patch LoadedApk.mApplication (non-fatal)")
        }
    }
}
