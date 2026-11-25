package com.nextvm.core.virtualization.diag.suppress

import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb

/**
 * Autofill 抑制探针（v129/v131）：阻止 guest 窗口触发 Autofill 会话，并报告视图的 autofill 模式。
 *
 * 详见 docs/代码镜像/core/virtualization/lifecycle/diag/AutofillSuppressor.md
 */
internal object AutofillSuppressor {

    /** 纯函数：把 `mode -> count` 渲染成日志里的 `8:1,0:3`。 */
    fun formatModes(modes: Map<Int, Int>): String =
        modes.entries.joinToString(",") { it.key.toString() + ":" + it.value }

    /** 纯函数：`explicit` 列表渲染（最多保留 10 条由调用方控制）。 */
    fun formatExplicit(items: List<String>): String = items.joinToString(";")

    /** v129：整窗关闭 Autofill（decorView 上的 flag 会被子 View 继承）。 */
    fun disableForActivity(a: android.app.Activity) {
        try {
            val decor = a.window?.decorView ?: return
            decor.importantForAutofill =
                android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            GuestBreadcrumb.log("v129-autofill disabled " + a.javaClass.name)
        } catch (_: Throwable) {
            // never break activity lifecycle over autofill
        }
    }

    /**
     * v131：遍历视图树，逐 View 关闭 Autofill，并报告改动前的模式分布与带 hints 的视图。
     *
     * 为什么需要逐 View：v129 的 decor 级 flag 在实测里仍会 startSession（launch158）。
     */
    fun disablePerView(a: android.app.Activity) {
        try {
            val decor = try {
                a.window?.decorView
            } catch (_: Throwable) {
                null
            } ?: return
            var total = 0
            var changed = 0
            val modes = mutableMapOf<Int, Int>()
            val explicit = mutableListOf<String>()
            val stack = ArrayDeque<android.view.View>()
            stack.add(decor)
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                total++
                try {
                    val mode = v.importantForAutofill
                    modes[mode] = (modes[mode] ?: 0) + 1
                    val hints = try {
                        v.autofillHints
                    } catch (_: Throwable) {
                        null
                    }
                    if (mode == android.view.View.IMPORTANT_FOR_AUTOFILL_YES ||
                        mode == android.view.View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS ||
                        (hints != null && hints.isNotEmpty())
                    ) {
                        if (explicit.size < 10) {
                            explicit.add(
                                v.javaClass.name + " mode=" + mode +
                                    " hints=" + (hints?.joinToString(",") ?: "-")
                            )
                        }
                    }
                    if (mode != android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS) {
                        v.importantForAutofill =
                            android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        changed++
                    }
                } catch (_: Throwable) {
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) {
                        try {
                            v.getChildAt(i)?.let { stack.add(it) }
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            GuestBreadcrumb.log(
                "v131-autofill per-view " + a.javaClass.name +
                    " total=" + total + " changed=" + changed +
                    " modes=" + formatModes(modes) +
                    " explicit=" + formatExplicit(explicit)
            )
        } catch (_: Throwable) {
            // never break lifecycle over autofill
        }
    }
}
