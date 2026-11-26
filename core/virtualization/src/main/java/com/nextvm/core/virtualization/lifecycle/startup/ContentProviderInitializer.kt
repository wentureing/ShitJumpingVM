package com.nextvm.core.virtualization.lifecycle.startup

import android.app.Application
import android.content.ContentProvider
import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import com.nextvm.core.binder.proxy.ActivityManagerProxy
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import timber.log.Timber

/**
 * 按 AOSP 的 `installContentProviders` 顺序把 guest 的 ContentProvider attach 起来。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/ContentProviderInitializer.md`
 */
object ContentProviderInitializer {

    private const val TAG = "VAppLifecycle"

    /**
     * ShizukuProvider 需要 INTERACT_ACROSS_USERS_FULL，虚拟环境拿不到；
     * 装它会 SecurityException（`ContentResolver.call()` 里炸）。
     */
    fun isShizukuProvider(className: String, authority: String): Boolean =
        className.contains("shizuku", ignoreCase = true) ||
            className.contains("rikka.shizuku", ignoreCase = true) ||
            authority.contains("shizuku", ignoreCase = true)

    /**
     * @param installedInto 装好的 provider 收进哪个列表（调用方的记录）；null 表示不收集。
     *
     * 用列表而不是函数类型回调：`check-refactor-equivalence.mjs` 按花括号/尖括号配对截函数体，
     * 参数里的 `->` 会破坏配对，导致等价检查失效。
     */
    fun install(
        instanceId: String,
        providers: List<String>,
        authorities: Map<String, String>,
        classLoader: ClassLoader,
        virtualContext: Context,
        application: Application,
        providerMetaData: Map<String, Bundle> = emptyMap(),
        installedInto: MutableList<ContentProvider>? = null,
    ) {
        Timber.tag(TAG).d("Installing ${providers.size} ContentProviders for $instanceId")
        GuestBreadcrumb.log("providers begin n=" + providers.size +
            " list=" + providers.joinToString(","))

        for (providerClassName in providers) {
            val providerAuthority = authorities[providerClassName] ?: ""
            if (isShizukuProvider(providerClassName, providerAuthority)) {
                Timber.tag(TAG).d("Skipping ShizukuProvider: $providerClassName — cross-user permission unavailable")
                continue
            }

            GuestBreadcrumb.log("provider attachInfo enter $providerClassName")
            try {
                val providerClass = classLoader.loadClass(providerClassName)
                val provider = providerClass.getDeclaredConstructor().let { ctor ->
                    ctor.isAccessible = true
                    ctor.newInstance()
                } as ContentProvider

                // Build ProviderInfo for attachInfo()
                val providerInfo = ProviderInfo().apply {
                    name = providerClassName
                    authority = providerAuthority.ifEmpty {
                        "${virtualContext.packageName}.${providerClassName.substringAfterLast(".")}"
                    }
                    applicationInfo = virtualContext.applicationInfo
                    exported = false
                    enabled = true
                    grantUriPermissions = true
                    // v39: manifest meta-data parsed from the guest APK.
                    // Providers reading their meta-data in attachInfo /
                    // onCreate must find it here, as on a real install.
                    metaData = providerMetaData[providerClassName]
                }

                // Call ContentProvider.attachInfo(context, info)
                // This calls both attachInfo internally and then onCreate()
                // v39: pass the guest Application, as AOSP's
                // installContentProviders does — NOT a bare ContextImpl.
                // Providers that cast getContext() to Application (e.g.
                // SoulPermission's InitProvider, which registers the
                // activity-lifecycle tracker behind getTopActivity())
                // died on the cast, silently disabling themselves.
                provider.attachInfo(application, providerInfo)

                installedInto?.add(provider)
                ActivityManagerProxy.activeInstance?.registerVirtualProvider(
                    providerInfo.authority,
                    provider
                )
                Timber.tag(TAG).d("  Provider installed: $providerClassName")
                GuestBreadcrumb.log(
                    "provider ok $providerClassName authority=${providerInfo.authority}"
                )

            } catch (e: ClassNotFoundException) {
                GuestBreadcrumb.logThrowable(
                    "provider NOT FOUND, skipped: $providerClassName", e)
                Timber.tag(TAG).w("ContentProvider class not found: $providerClassName (skipping)")
            } catch (e: SecurityException) {
                GuestBreadcrumb.logThrowable(
                    "provider SecurityException SWALLOWED, skipped: $providerClassName", e)
                Timber.tag(TAG).w("SecurityException skipping provider $providerClassName: ${e.message}")
            } catch (e: IllegalStateException) {
                GuestBreadcrumb.logThrowable(
                    "provider IllegalStateException SWALLOWED, skipped: $providerClassName", e)
                Timber.tag(TAG).w("IllegalStateException skipping provider $providerClassName: ${e.message}")
            } catch (e: Exception) {
                GuestBreadcrumb.logThrowable(
                    "provider FAILED (bind continues): $providerClassName", e)
                Timber.tag(TAG).e(e, "Failed to install ContentProvider: $providerClassName")
                // Don't fail the whole app — some providers are optional
            }
        }
    }
}
