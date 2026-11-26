package com.nextvm.core.virtualization.lifecycle.startup

import android.app.Application
import android.app.Instrumentation
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import timber.log.Timber

/**
 * 触发 guest 的 `Application.onCreate()`：Instrumentation 优先，失败直调，Shizuku 异常吞掉。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/ApplicationOnCreateInvoker.md`
 */
object ApplicationOnCreateInvoker {

    private const val TAG = "VAppLifecycle"

    /**
     * 判断是否是 Shizuku 触发的 SecurityException。
     *
     * 容器不需要跨用户权限，`INTERACT_ACROSS_USERS_FULL` 拿不到 —— 这类异常必须吞掉，
     * 否则 ZArchiver 这类 guest 会在 onCreate 里直接死于 Shizuku 探测。
     */
    fun isShizukuSecurityException(t: Throwable?): Boolean {
        val cause = t?.cause?.cause ?: t?.cause ?: t ?: return false
        if (cause !is SecurityException) return false
        val msg = cause.message ?: return false
        return msg.contains("ShizukuProvider", ignoreCase = true) ||
            msg.contains("INTERACT_ACROSS_USERS", ignoreCase = true) ||
            msg.contains("shizuku", ignoreCase = true)
    }

    fun invoke(application: Application) {
        GuestBreadcrumb.log("Application.onCreate enter " + application.javaClass.name)
        try {
            // Use Instrumentation if available (more accurate mirroring)
            val instrumentation = Instrumentation()
            instrumentation.callApplicationOnCreate(application)
            Timber.tag(TAG).d("Application.onCreate() called via Instrumentation")
            GuestBreadcrumb.log("Application.onCreate exit ok")
        } catch (e: RuntimeException) {
            // Check if this is a ShizukuProvider SecurityException — swallow it.
            // ZArchiver calls ShizukuProvider.a() in onCreate() which triggers
            // ContentResolver.call() to rikka.shizuku.ShizukuProvider. The system
            // throws SecurityException because INTERACT_ACROSS_USERS_FULL is missing.
            // ZArchiver can run without Shizuku root access.
            if (isShizukuSecurityException(e)) {
                GuestBreadcrumb.logThrowable(
                    "Application.onCreate SecurityException SWALLOWED (shizuku rule)", e)
                Timber.tag(TAG).w("Shizuku SecurityException in onCreate swallowed — continuing without Shizuku")
                return
            }
            // Not Shizuku-related — try direct call fallback
            Timber.tag(TAG).w(e, "Instrumentation.callApplicationOnCreate() failed, trying direct")
            try {
                application.onCreate()
            } catch (e2: RuntimeException) {
                if (isShizukuSecurityException(e2)) {
                    GuestBreadcrumb.logThrowable(
                        "Application.onCreate (direct) SecurityException SWALLOWED (shizuku rule)", e2)
                    Timber.tag(TAG).w("Shizuku SecurityException in direct onCreate swallowed")
                    return
                }
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            }
        } catch (e: Exception) {
            GuestBreadcrumb.logThrowable(
                "Application.onCreate via Instrumentation FAILED, calling directly", e)
            Timber.tag(TAG).w(e, "Instrumentation.callApplicationOnCreate() failed, calling directly")
            try {
                application.onCreate()
                GuestBreadcrumb.log("Application.onCreate exit ok (direct)")
            } catch (e2: Exception) {
                GuestBreadcrumb.logThrowable("Application.onCreate direct call FAILED", e2)
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            }
        }
    }
}
