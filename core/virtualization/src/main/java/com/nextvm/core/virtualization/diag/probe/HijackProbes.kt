package com.nextvm.core.virtualization.diag.probe

import com.nextvm.core.virtualization.diag.fieldOf
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import timber.log.Timber
import java.io.File

/**
 * 劫持框架观测（v35/v36）：只读采样 ActivityThread / Instrumentation / SDK 静态字段。
 *
 * 详见 docs/代码镜像/core/virtualization/diag/probe/HijackProbes.md
 */
internal object HijackProbes {

    fun describe(name: String?): String = name ?: "null"

    /**
     * 采样当前劫持状态写入 [out]。
     *
     * 只读：不修改 Instrumentation、不调用 SDK 方法。
     */
    fun sample(out: File, label: String, stockInitNote: String) {
        val sb = StringBuilder()
        sb.append("==== ").append(label).append(" ====\n")
        fun line(s: String) {
            sb.append(s).append('\n')
            Timber.tag("HijackTrace").i("%s %s", label, s)
        }
        try {
            val atCls = Class.forName("android.app.ActivityThread")
            val at = atCls.getMethod("currentActivityThread").invoke(null)
            if (at == null) {
                line("ActivityThread: null")
                out.appendText(sb.toString())
                return
            }
            val atInstr = fieldOf(atCls, "mInstrumentation").get(at)
            line("ActivityThread.mInstrumentation = " + describe(atInstr?.javaClass?.name))
            line("v35 stock-init: " + stockInitNote)
            val app = atCls.getMethod("getApplication").invoke(at)
                as? android.app.Application
            line("Application = " + describe(app?.javaClass?.name))
            val guestCl = app?.javaClass?.classLoader
            if (app != null) {
                try {
                    val cbs = fieldOf(
                        Class.forName("android.app.Application"),
                        "mActivityLifecycleCallbacks"
                    ).get(app) as? java.util.ArrayList<*>
                    if (cbs == null) {
                        line("lifecycleCallbacks = null")
                    } else {
                        line("lifecycleCallbacks.size = " + cbs.size)
                        for (cb in cbs) line("  cb: " + describe(cb?.javaClass?.name))
                    }
                } catch (e: Throwable) {
                    line("lifecycleCallbacks: <" + e.javaClass.simpleName + ">")
                }
            }
            if (guestCl != null) {
                try {
                    val sdk = Class.forName("com.sdk.hijack.api.HijackSDK", false, guestCl)
                    val done = fieldOf(sdk, "isRegisterActivityLifecycleCallbacks")
                        .getBoolean(null)
                    line("HijackSDK.isRegisterActivityLifecycleCallbacks = " + done)
                } catch (e: Throwable) {
                    line("HijackSDK flag: <" + e.javaClass.simpleName + ">")
                }
                try {
                    val pi = Class.forName(
                        "com.sdk.hijack.base.ProxyInstrumentation", false, guestCl
                    )
                    val inst = fieldOf(pi, "instance").get(null)
                    line(
                        "ProxyInstrumentation.instance = " +
                            (if (inst == null) "null" else "set(sameAsAT=" + (inst === atInstr) + ")")
                    )
                    if (inst != null) {
                        try {
                            val ori = fieldOf(pi, "oriInstrumentation").get(inst)
                            val typ = fieldOf(pi, "oriClassType").getInt(inst)
                            line(
                                "  oriInstrumentation = " +
                                    describe(ori?.javaClass?.name) + " oriClassType = " + typ
                            )
                        } catch (e: Throwable) {
                            line("  ori: <" + e.javaClass.simpleName + ">")
                        }
                    }
                } catch (e: Throwable) {
                    line("ProxyInstrumentation.instance: <" + e.javaClass.simpleName + ">")
                }
            }
            try {
                val actsAny = fieldOf(atCls, "mActivities").get(at)
                if (actsAny == null) {
                    line("mActivities = null")
                } else {
                    val entries = actsAny.javaClass.getMethod("entrySet")
                        .invoke(actsAny) as java.util.Set<*>
                    line("mActivities.size = " + entries.size)
                    for (entry in entries) {
                        if (entry == null) continue
                        val rec = entry.javaClass.getMethod("getValue")
                            .invoke(entry) ?: continue
                        val act = fieldOf(rec.javaClass, "activity").get(rec) ?: continue
                        val ai = try {
                            fieldOf(Class.forName("android.app.Activity"), "mInstrumentation")
                                .get(act)
                        } catch (_: Throwable) {
                            null
                        }
                        line(
                            "  activity " + act.javaClass.name +
                                " mInstrumentation = " + describe(ai?.javaClass?.name)
                        )
                    }
                }
            } catch (e: Throwable) {
                line("mActivities: <" + e.javaClass.simpleName + ">")
            }
            try {
                val pmAny = fieldOf(atCls, "mProviderMap").get(at)
                if (pmAny == null) {
                    line("mProviderMap = null")
                } else {
                    val entries = pmAny.javaClass.getMethod("entrySet")
                        .invoke(pmAny) as java.util.Set<*>
                    line("mProviderMap.size = " + entries.size)
                    for (entry in entries) {
                        if (entry == null) continue
                        val rec = entry.javaClass.getMethod("getValue")
                            .invoke(entry) ?: continue
                        val local = try {
                            fieldOf(rec.javaClass, "mLocalProvider").get(rec)
                        } catch (_: Throwable) {
                            null
                        }
                        line("  provider local=" + describe(local?.javaClass?.name))
                    }
                }
            } catch (e: Throwable) {
                line("mProviderMap: <" + e.javaClass.simpleName + ">")
            }
            try {
                val maps = File("/proc/self/maps").readText()
                line("maps libUnicomFPSDK.so loaded = " + maps.contains("libUnicomFPSDK"))
                line("maps libDexHelper.so loaded = " + maps.contains("libDexHelper"))
            } catch (e: Throwable) {
                line("maps: <" + e.javaClass.simpleName + ">")
            }
        } catch (e: Throwable) {
            line("sample failed: <" + e.javaClass.simpleName + "> " + e.message)
        }
        try {
            out.appendText(sb.toString())
        } catch (_: Throwable) {
        }
    }

    /**
     * 把 ActivityThread / 各 Activity 的 `mInstrumentation` 从劫持代理还原成原始实例。
     *
     * 默认关闭（observe-only baseline）：只有 [unwrapEnabled] 且未处于 stock-init 实验时才执行。
     */
    fun unwrap(
        out: File,
        label: String,
        stockInitExperiment: Boolean,
        unwrapEnabled: Boolean,
    ) {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s).append('\n')
            Timber.tag("HijackTrace").i("%s unwrap: %s", label, s)
        }
        if (stockInitExperiment) {
            line("skipped, v35 stock-init experiment active")
            try { out.appendText(sb.toString()) } catch (_: Throwable) { }
            return
        }
        if (!unwrapEnabled) {
            line("unwrap disabled (v36 observe-only baseline)")
            try { out.appendText(sb.toString()) } catch (_: Throwable) { }
            return
        }
        try {
            val atCls = Class.forName("android.app.ActivityThread")
            val at = atCls.getMethod("currentActivityThread").invoke(null)
            if (at == null) {
                line("skip, ActivityThread null")
                out.appendText(sb.toString())
                return
            }
            val instrF = fieldOf(atCls, "mInstrumentation")
            val cur = instrF.get(at)
            if (cur == null ||
                cur.javaClass.name != "com.sdk.hijack.base.ProxyInstrumentation"
            ) {
                line("skip, AT.mInstrumentation = " + describe(cur?.javaClass?.name))
                out.appendText(sb.toString())
                return
            }
            val ori = fieldOf(cur.javaClass, "oriInstrumentation").get(cur)
            if (ori == null) {
                line("abort, oriInstrumentation is null")
                out.appendText(sb.toString())
                return
            }
            instrF.set(at, ori)
            line("AT.mInstrumentation ProxyInstrumentation -> " + ori.javaClass.name)
            try {
                val actsAny = fieldOf(atCls, "mActivities").get(at)
                val entries = actsAny.javaClass.getMethod("entrySet")
                    .invoke(actsAny) as java.util.Set<*>
                val aiF = fieldOf(Class.forName("android.app.Activity"), "mInstrumentation")
                for (entry in entries) {
                    if (entry == null) continue
                    val rec = entry.javaClass.getMethod("getValue")
                        .invoke(entry) ?: continue
                    val act = fieldOf(rec.javaClass, "activity").get(rec) ?: continue
                    val ai = aiF.get(act)
                    if (ai != null && ai.javaClass.name ==
                        "com.sdk.hijack.base.ProxyInstrumentation"
                    ) {
                        aiF.set(act, ori)
                        line("activity " + act.javaClass.name + " mInstrumentation -> ori")
                    }
                }
            } catch (e: Throwable) {
                line("activities swap: <" + e.javaClass.simpleName + ">")
            }
        } catch (e: Throwable) {
            line("unwrap failed: <" + e.javaClass.simpleName + "> " + e.message)
        }
        try {
            out.appendText(sb.toString())
        } catch (_: Throwable) {
        }
    }
}
