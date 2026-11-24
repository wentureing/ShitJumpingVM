package com.nextvm.core.virtualization.engine.activitythread

import android.os.Handler
import android.os.Looper
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import timber.log.Timber

/**
 * 主 looper 崩溃守卫：只吞 Toast UID mismatch 与 GMS SecurityException，其余照常崩。
 * 详见 `docs/代码镜像/core/virtualization/engine/MainLooperGuard.md`。
 */
internal object MainLooperGuard {

    private const val TAG = "ATHook"

    fun install() {
        try {
            Handler(Looper.getMainLooper()).post {
                while (true) {
                    try {
                        Looper.loop()
                    } catch (e: Throwable) {
                        when (classifyLooperThrowable(e)) {
                            LooperSuppression.TOAST_UID_MISMATCH -> {
                                GuestBreadcrumb.logThrowable(
                                    "main-looper guard SUPPRESSED guest Toast UID mismatch", e
                                )
                                Timber.tag(TAG).w(e, "Suppressed guest Toast UID mismatch")
                            }
                            LooperSuppression.GMS_SECURITY -> {
                                GuestBreadcrumb.logThrowable(
                                    "main-looper guard SUPPRESSED exception (message consumed)", e
                                )
                                Timber.tag(TAG).w(e, "Suppressed GMS SecurityException in main looper")
                                // 继续循环：被吞掉的那条消息已被消费
                            }
                            LooperSuppression.NONE -> {
                                // 关键：保留 AOSP 的崩溃行为
                                throw e
                            }
                        }
                    }
                }
            }
            Timber.tag(TAG).i("Main looper crash guard installed")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to install main looper crash guard")
        }
    }

    /**
     * 纯函数：判定一个主线程异常是否属于「可吞掉的两类」。
     *
     * 抽成独立函数是为了**可测试**（JVM 单测不需要 Looper/Android 运行时）。
     * 规则见类注释；判定顺序：Toast UID mismatch 优先于 GMS SecurityException。
     */
    internal fun classifyLooperThrowable(e: Throwable): LooperSuppression {
        var root: Throwable = e
        while (root.cause != null) root = root.cause!!

        var isToastWindowFailure = false
        var uidMismatch: SecurityException? = null
        var cursor: Throwable? = e
        while (cursor != null) {
            if (cursor.message?.contains("Adding window failed") == true ||
                cursor.stackTrace.any {
                    it.className == "android.widget.ToastPresenter" ||
                        it.className.startsWith("android.widget.Toast")
                }
            ) {
                isToastWindowFailure = true
            }
            if (cursor is SecurityException && cursor.message?.contains("not in UID") == true) {
                uidMismatch = cursor
            }
            cursor = cursor.cause
        }

        if (uidMismatch != null && isToastWindowFailure) {
            return LooperSuppression.TOAST_UID_MISMATCH
        }
        if (root is SecurityException &&
            (root.message?.contains("Unknown calling package name") == true ||
                root.message?.contains("does not belong to") == true)
        ) {
            return LooperSuppression.GMS_SECURITY
        }
        return LooperSuppression.NONE
    }
}

/** 主线程异常的分类结果（可吞 / 必须崩）。 */
internal enum class LooperSuppression {
    /** 不是可吞的两类 → 必须按 AOSP 行为崩溃。 */
    NONE,

    /** Toast 窗口的 UID mismatch（虚拟包名 vs 宿主 UID）。 */
    TOAST_UID_MISMATCH,

    /** GMS 的 SecurityException（包名归属校验）。 */
    GMS_SECURITY
}
