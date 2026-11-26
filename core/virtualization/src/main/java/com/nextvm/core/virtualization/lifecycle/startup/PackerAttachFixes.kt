package com.nextvm.core.virtualization.lifecycle.startup

import android.app.Application
import android.content.Context
import com.nextvm.core.common.findField
import timber.log.Timber

/**
 * 打包壳（Jiagu / SecNeo / 360 等）在 `attach` 前的两段修正：stub 字段、ProtectionDomain。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/PackerAttachFixes.md`
 */
object PackerAttachFixes {

    private const val TAG = "VAppLifecycle"

    /** 壳类名判据（原为内联 5 个 contains，ignoreCase）。 */
    internal fun isPackerStubClass(className: String): Boolean =
        className.contains("stub", ignoreCase = true) ||
            className.contains("jiagu", ignoreCase = true) ||
            className.contains("wrapper", ignoreCase = true) ||
            className.contains("Shell", ignoreCase = true) ||
            className.contains("Qihoo", ignoreCase = true)

    /**
     * 字段值的路径重定向；返回 null 表示**不改**（原判据 `redirected != value`）。
     *
     * 只处理含 `/data/data/` 且不含容器自身包名的值（原实现语义，逐字保留）。
     */
    internal fun redirectedPath(value: String, dataDir: String): String? {
        if (!value.contains("/data/data/")) return null
        if (value.contains("com.nextvm.app")) return null
        val redirected = value.replace(Regex("/data/(data|user/0)/[^/]+"), dataDir)
        return redirected.takeIf { it != value }
    }

    /** FIX 2：把 stub 类里的空/错误路径字段改成 guest 的 nativeLibraryDir / dataDir。 */
    fun applyBeforeAttach(application: Application, virtualContext: Context) {
        try {
            val appClass = application.javaClass
            val className = appClass.name

            if (!isPackerStubClass(className)) {
                return
            }

            Timber.tag(TAG).d("Detected packer stub class: $className — patching fields")

            val nativeLibDir = virtualContext.applicationInfo?.nativeLibraryDir ?: return
            val dataDir = virtualContext.applicationInfo?.dataDir ?: return
            val sourceDir = virtualContext.applicationInfo?.sourceDir ?: return

            // Patch instance fields
            for (field in appClass.declaredFields) {
                try {
                    field.isAccessible = true
                    if (field.type == String::class.java) {
                        val value = field.get(application) as? String
                        if (value == null) {
                            field.set(application, nativeLibDir)
                            Timber.tag(TAG).d("  Patched null field: ${field.name} → $nativeLibDir")
                        } else {
                            val redirected = redirectedPath(value, dataDir)
                            if (redirected != null) {
                                field.set(application, redirected)
                                Timber.tag(TAG).d("  Redirected field: ${field.name} → $redirected")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip inaccessible fields
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "patchJiaguStubBeforeAttach failed (non-fatal)")
        }
    }

    /**
     * FIX 3：给 guest 线程/类装齐 caller 信息，避免 ART 的 null-caller SIGABRT。
     *
     * 失败只打 W，不抛。
     */
    fun applyRuntimeLoadCallerFix(application: Application, virtualContext: Context) {
        try {
            // Ensure the current thread's contextClassLoader is the guest's ClassLoader.
            val guestClassLoader = application.javaClass.classLoader
            if (guestClassLoader != null) {
                Thread.currentThread().contextClassLoader = guestClassLoader
                Timber.tag(TAG).d("Set thread contextClassLoader to guest ClassLoader: ${guestClassLoader.javaClass.name}")
            }

            // Ensure the guest's Application class has a valid ProtectionDomain
            // with a CodeSource pointing to the guest APK.
            try {
                val pd = application.javaClass.protectionDomain
                if (pd == null || pd.codeSource == null) {
                    val apkPath = virtualContext.applicationInfo?.sourceDir
                    if (apkPath != null) {
                        val codeSource = java.security.CodeSource(
                            java.io.File(apkPath).toURI().toURL(),
                            arrayOfNulls<java.security.cert.Certificate>(0)
                        )
                        val newPd = java.security.ProtectionDomain(codeSource, null)
                        val pdField = findField(Class::class.java, "protectionDomain")
                            ?: findField(Class::class.java, "pd")
                        if (pdField != null) {
                            pdField.isAccessible = true
                            pdField.set(application.javaClass, newPd)
                            Timber.tag(TAG).d("Injected ProtectionDomain with CodeSource: $apkPath")
                        }
                    }
                } else {
                    Timber.tag(TAG).d("Guest Application ProtectionDomain already valid: $pd")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("ProtectionDomain fix: ${e.message} (non-fatal)")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "installRuntimeLoadCallerFix failed (non-fatal)")
        }
    }
}
