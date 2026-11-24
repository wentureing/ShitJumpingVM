package com.nextvm.core.virtualization.engine

import android.os.Handler
import android.os.Looper
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import timber.log.Timber

/**
 * 主 looper 的崩溃守卫（v150 从 `ActivityThreadHook` 拆出）。
 *
 * ## 职责
 * 在 guest 进程的主 looper 外层包一个守卫，**只吞掉两类可证明是虚拟化导致的异常**：
 * 1. Toast 窗口的 UID mismatch（`Adding window failed` + `SecurityException("... not in UID ...")`）
 * 2. GMS 的 `SecurityException("Unknown calling package name" / "does not belong to")`
 *
 * 其余异常一律 `throw` 回去，保持 AOSP 的原生崩溃行为（可从 crash buffer 读到真实栈）。
 *
 * ## 不负责
 * - 不修改消息内容、不做 Activity 拦截（那是 `TransactionInterceptor` 的职责）。
 * - 不做崩溃上报（只写 breadcrumb，见 [GuestBreadcrumb.logThrowable]）。
 *
 * ## 约束与坑
 * 1. **必须保留 `throw e` 分支**：历史上有过"无条件吞异常"的版本，导致应用进入未知状态、
 *    表现为"白屏/点不动"而不是崩溃，排查成本极高。
 * 2. **判断依据是异常 message 的字符串**（`"Adding window failed"` / `"not in UID"`），
 *    属于**脆弱耦合**：OS 升级后文案变化会让白名单失效 —— 失效时的行为是"照常崩溃"，
 *    这是安全的默认（宁可崩，不可静默）。
 * 3. **必须在整个 cause 链上扫描**，不能只看 `root`：实战中出现过
 *    `RuntimeException("Adding window failed") -> SecurityException("not in UID") -> RemoteException`
 *    的链式结构，只看链尾会漏判（v115 修复）。
 * 4. 本对象**只允许安装一次**（由 ActivityThreadHook.install 的 `installed` 保证）。
 *
 * ## 已知的架构风险（待重构，见 docs/计划/计划-ActivityThreadHook与Binder语义重构.md §1.2）
 * 这里在 `Handler.post` 的 Runnable 里**再次调用 `Looper.loop()`**，形成嵌套 looper；
 * 异常传播路径与 AOSP 原生不同（会穿过两层 loop 才到 uncaughtExceptionHandler）。
 * 治本方向是改用 `Thread.setDefaultUncaughtExceptionHandler`。
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
