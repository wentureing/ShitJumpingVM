package com.nextvm.core.virtualization.lifecycle.startup

import android.app.Application
import com.nextvm.core.virtualization.diag.fieldOf
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb

/**
 * 把 `ActivityThread.mInitialApplication` / `mBoundApplication.appContext` 指向 guest Application。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/ActivityThreadApplicationPatch.md`
 */
object ActivityThreadApplicationPatch {

    /** 属性开关：`nextvm.webviewAppContext=0` 时整体跳过。 */
    fun isEnabled(): Boolean = try {
        System.getProperty("nextvm.webviewAppContext", "1") != "0"
    } catch (_: Throwable) {
        true
    }

    fun apply(application: Application) {
        if (!isEnabled()) {
            GuestBreadcrumb.log("v138-appctx disabled by property")
            return
        }
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = try {
                atClass.getMethod("currentActivityThread").invoke(null)
            } catch (_: Throwable) {
                null
            }
            if (thread == null) {
                GuestBreadcrumb.log("v138-appctx skip: currentActivityThread()=null")
                return
            }

            val initialField = fieldOf(atClass, "mInitialApplication")
            val before = (initialField.get(thread) as? android.content.Context)?.packageName
            initialField.set(thread, application)

            var bindInfo = "none"
            try {
                val bindField = fieldOf(atClass, "mBoundApplication")
                val bindData = bindField.get(thread)
                if (bindData != null) {
                    val ctxField = fieldOf(bindData.javaClass, "appContext")
                    val beforeCtx =
                        (ctxField.get(bindData) as? android.content.Context)?.packageName
                    ctxField.set(bindData, application)
                    bindInfo = "$beforeCtx -> ${application.packageName}"
                }
            } catch (t: Throwable) {
                bindInfo = "failed: " + t.javaClass.simpleName
            }

            GuestBreadcrumb.log(
                "v138-appctx mInitialApplication " + before + " -> " +
                    application.packageName + " ; mBoundApplication.appContext " + bindInfo
            )
        } catch (t: Throwable) {
            GuestBreadcrumb.log(
                "v138-appctx FAILED: " + t.javaClass.simpleName + ": " + t.message
            )
        }
    }
}
