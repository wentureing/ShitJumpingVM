package com.nextvm.core.virtualization.diag.probe

import android.app.Application
import com.nextvm.core.common.findField
import com.nextvm.core.virtualization.diag.dump.GuestMemoryDump
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import com.nextvm.core.virtualization.lifecycle.VirtualApplicationManager
import com.nextvm.core.virtualization.lifecycle.VirtualApplicationManager.ApplicationRecord

/**
 * guest 插件框架探针（v116-v123）：只读反射观测 PluginLoader 状态。
 *
 * 详见 docs/代码镜像/core/virtualization/lifecycle/diag/PluginProbes.md
 */
internal object PluginProbes {

    /** meta-data 分类结果：App Startup 入口键 + 含 plugin 的键。 */
    class MetaDataClasses(val startups: List<String>, val pluginKeys: List<String>)

    /** 静态字段值渲染：只按类型，绝不调 toString（避免执行 guest 代码）。 */
    fun renderFieldValue(v: Any?): String = when (v) {
        null -> "null"
        is Application -> "Application(${v.javaClass.name})"
        is Boolean, is Number, is String -> v.toString()
        is Array<*> -> "Array(len=${v.size})"
        else -> "obj(${(v as Any).javaClass.name})"
    }

    /** 把 meta-data 的 (key, value) 分成 App Startup 入口与含 plugin 的键。 */
    fun classifyMetaData(entries: List<Pair<String, String?>>): MetaDataClasses {
        val startups = ArrayList<String>()
        val pluginKeys = ArrayList<String>()
        for ((k, v) in entries) {
            if (v == "androidx.startup") startups.add(k)
            if (k.contains("plugin", ignoreCase = true)) pluginKeys.add(k)
        }
        return MetaDataClasses(startups, pluginKeys)
    }

    /**
     * v116 probe：Application.onCreate 之后检查 PluginLoader 是否初始化、
     * PluginManagerProvider 是否还在。绝不调用 initLoader（会改变被测量状态）。
     */
    fun probePluginLoaderState(
        application: Application,
        record: ApplicationRecord
    ) {
        try {
            val providerRegistered = record.contentProviders.any {
                it.javaClass.name.contains("PluginManagerProvider")
            }
            GuestBreadcrumb.log(
                "v116-plugin-probe PluginManagerProvider registered=" +
                    "$providerRegistered (contentProviders=${record.contentProviders.size})"
            )
            val loaderCls = try {
                Class.forName(
                    "com.mc10086.plugin.core.PluginLoader",
                    false,
                    application.classLoader
                )
            } catch (e: Throwable) {
                GuestBreadcrumb.log(
                    "v116-plugin-probe PluginLoader class not found: " +
                        "${e.javaClass.simpleName}"
                )
                return
            }
            val hasInitLoader = loaderCls.methods.any { it.name == "initLoader" } ||
                loaderCls.declaredMethods.any { it.name == "initLoader" }
            // v117: 只记录签名，不调用。
            val initLoaderSigs = (loaderCls.methods + loaderCls.declaredMethods)
                .filter { it.name == "initLoader" }
                .distinctBy { it.toString() }
                .joinToString(" | ") { m ->
                    java.lang.reflect.Modifier.toString(m.modifiers) +
                        " " + m.returnType.name + "(" +
                        m.parameterTypes.joinToString(",") { it.name } + ")"
                }
                .ifEmpty { "none" }
            GuestBreadcrumb.log(
                "v117-plugin-probe initLoader signatures=$initLoaderSigs"
            )
            val getApp = try {
                loaderCls.getMethod("getApplication")
            } catch (_: Throwable) {
                loaderCls.declaredMethods.firstOrNull { it.name == "getApplication" }
            }
            if (getApp == null) {
                GuestBreadcrumb.log(
                    "v116-plugin-probe initLoader present=$hasInitLoader, " +
                        "getApplication method not found"
                )
                return
            }
            getApp.isAccessible = true
            val state = try {
                val app = if (java.lang.reflect.Modifier.isStatic(getApp.modifiers)) {
                    getApp.invoke(null)
                } else {
                    "getApplication is not static, skipped"
                }
                if (app is Application) {
                    "INITIALIZED (application=${app.javaClass.name})"
                } else {
                    "NOT_INITIALIZED (returned=$app)"
                }
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                "NOT_INITIALIZED (${cause.javaClass.simpleName}: ${cause.message})"
            }
            GuestBreadcrumb.log(
                "v116-plugin-probe initLoader present=$hasInitLoader, " +
                    "getApplication state=$state"
            )
            // v123: 列出 PluginLoader 的静态字段（只按类型渲染值）。
            try {
                val seen = HashSet<String>()
                val parts = ArrayList<String>()
                var c: Class<*>? = loaderCls
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) {
                        if (!java.lang.reflect.Modifier.isStatic(
                                f.modifiers
                            )
                        ) continue
                        if (!seen.add(f.name)) continue
                        val v = try {
                            f.isAccessible = true
                            f.get(null)
                        } catch (e: Throwable) {
                            "read-failed:${e.javaClass.simpleName}"
                        }
                        parts.add("${f.name}=${renderFieldValue(v)}")
                    }
                    c = c.superclass
                }
                GuestBreadcrumb.log(
                    "v123-plugin-probe PluginLoader static fields: " +
                        parts.joinToString(", ").ifEmpty { "none" }
                )
            } catch (_: Throwable) {
                GuestBreadcrumb.log(
                    "v123-plugin-probe static fields read failed"
                )
            }
            // v118: App Startup 发现检查（application meta-data）。
            try {
                val md = application.applicationInfo?.metaData
                if (md == null) {
                    GuestBreadcrumb.log("v118-plugin-probe app metaData=null")
                } else {
                    val classes = classifyMetaData(
                        md.keySet().map { k ->
                            k to try {
                                md.get(k)?.toString()
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    )
                    GuestBreadcrumb.log(
                        "v118-plugin-probe startup initializers=" +
                            classes.startups.joinToString(",").ifEmpty { "none" }
                    )
                    GuestBreadcrumb.log(
                        "v118-plugin-probe plugin meta keys=" +
                            classes.pluginKeys.joinToString(",").ifEmpty { "none" } +
                            " (metaData keys=${md.size()})"
                    )
                }
            } catch (_: Throwable) {
                GuestBreadcrumb.log("v118-plugin-probe app metaData read failed")
            }
            // v119: 复现 AppInitializer.discoverAndInitialize 看到的 provider meta-data。
            try {
                val cn = android.content.ComponentName(
                    application.packageName,
                    "androidx.startup.InitializationProvider"
                )
                val pi = application.packageManager.getProviderInfo(
                    cn,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                val pmd = pi?.metaData
                if (pmd == null) {
                    GuestBreadcrumb.log(
                        "v119-plugin-probe startup providerInfo metaData=null " +
                            "(providerInfo found, authority=${pi?.authority})"
                    )
                } else {
                    val keys = ArrayList<String>()
                    for (k in pmd.keySet()) {
                        val v = try {
                            pmd.get(k)?.toString()
                        } catch (_: Throwable) {
                            "?"
                        }
                        keys.add("$k=$v")
                    }
                    GuestBreadcrumb.log(
                        "v119-plugin-probe startup provider metaData keys=" +
                            keys.joinToString(",").ifEmpty { "none" } +
                            " (n=${pmd.size()})"
                    )
                }
            } catch (e: Throwable) {
                GuestBreadcrumb.log(
                    "v119-plugin-probe startup getProviderInfo FAILED: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            // v120: 定时重查 lazy init（只 post 主线程延迟任务）。
            try {
                val handler = android.os.Handler(
                    android.os.Looper.getMainLooper()
                )
                val delays = listOf(
                    30_000L to "t30s",
                    60_000L to "t60s",
                    120_000L to "t120s"
                )
                for ((delay, phase) in delays) {
                    handler.postDelayed({
                        try {
                            probePluginStatePhase(application, record, phase)
                        } catch (_: Throwable) {
                        }
                    }, delay)
                }
                GuestBreadcrumb.log(
                    "v120-plugin-probe timed re-checks scheduled t30s/t60s/t120s"
                )
            } catch (_: Throwable) {
                GuestBreadcrumb.log("v120-plugin-probe schedule failed")
            }
            // v121/v122: AW delegate 状态 + 内存 dex dump（仅 SecNeo 壳）。
            try {
                if (application.javaClass.name ==
                    "com.secneo.apkwrapper.AW"
                ) {
                    val readStaticApp = { name: String ->
                        try {
                            val f = findField(application.javaClass, name)
                            f?.isAccessible = true
                            f?.get(null)
                        } catch (_: Throwable) {
                            "field-read-failed"
                        }
                    }
                    val aApp = readStaticApp("a")
                    val aDesc = when (aApp) {
                        null -> "null"
                        is Application -> aApp.javaClass.name
                        else -> "unexpected=$aApp"
                    }
                    val realApp = readStaticApp("realApplication")
                    val realDesc = when (realApp) {
                        null -> "null (delegate never created)"
                        is Application -> {
                            val base = try {
                                realApp.baseContext
                            } catch (_: Throwable) {
                                null
                            }
                            "class=${realApp.javaClass.name} " +
                                "baseContext=" + if (base == null) {
                                    "null (attachBaseContext never ran)"
                                } else {
                                    "attached (${base.javaClass.name})"
                                }
                        }
                        else -> "unexpected=$realApp"
                    }
                    GuestBreadcrumb.log(
                        "v122-plugin-probe AW a=$aDesc " +
                            "realApplication: $realDesc"
                    )
                    Thread({
                        try {
                            GuestMemoryDump.dumpMemoryDex(application)
                        } catch (_: Throwable) {
                            GuestBreadcrumb.log(
                                "v121-plugin-probe memdex dump failed"
                            )
                        }
                    }, "v121-memdex-dump").start()
                } else {
                    GuestBreadcrumb.log(
                        "v121-plugin-probe not AW " +
                            "(${application.javaClass.name}), skipped"
                    )
                }
            } catch (_: Throwable) {
                GuestBreadcrumb.log("v121-plugin-probe AW state failed")
            }
            try {
                val pmAi = application.packageManager.getApplicationInfo(
                    application.packageName,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                val pmMd = pmAi?.metaData
                GuestBreadcrumb.log(
                    "v119-plugin-probe PM ApplicationInfo metaData=" +
                        if (pmMd == null) {
                            "null"
                        } else {
                            "keys=${pmMd.size()}"
                        }
                )
            } catch (e: Throwable) {
                GuestBreadcrumb.log(
                    "v119-plugin-probe PM getApplicationInfo FAILED: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        } catch (_: Throwable) {
            // 探针绝不能影响 bind。
        }
    }

    /**
     * v118 probe：第一个 Activity 创建后重查插件框架状态（主线程延迟调用）。
     */
    fun probePluginStatePhase(
        application: Application,
        record: ApplicationRecord?,
        phase: String
    ) {
        try {
            val registered = record?.contentProviders?.any {
                it.javaClass.name.contains("PluginManagerProvider")
            }
            GuestBreadcrumb.log(
                "v118-plugin-probe phase=$phase " +
                    "PluginManagerProvider registered=$registered " +
                    "(contentProviders=${record?.contentProviders?.size})"
            )
            val loaderCls = try {
                Class.forName(
                    "com.mc10086.plugin.core.PluginLoader",
                    false,
                    application.classLoader
                )
            } catch (e: Throwable) {
                GuestBreadcrumb.log(
                    "v118-plugin-probe phase=$phase " +
                        "PluginLoader class not found: ${e.javaClass.simpleName}"
                )
                return
            }
            val getApp = try {
                loaderCls.getMethod("getApplication")
            } catch (_: Throwable) {
                loaderCls.declaredMethods.firstOrNull { it.name == "getApplication" }
            }
            if (getApp == null) {
                GuestBreadcrumb.log(
                    "v118-plugin-probe phase=$phase getApplication method not found"
                )
                return
            }
            getApp.isAccessible = true
            val state = try {
                val app = if (java.lang.reflect.Modifier.isStatic(getApp.modifiers)) {
                    getApp.invoke(null)
                } else {
                    "getApplication is not static, skipped"
                }
                if (app is Application) {
                    "INITIALIZED (application=${app.javaClass.name})"
                } else {
                    "NOT_INITIALIZED (returned=$app)"
                }
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                "NOT_INITIALIZED (${cause.javaClass.simpleName}: ${cause.message})"
            }
            GuestBreadcrumb.log(
                "v118-plugin-probe phase=$phase getApplication state=$state"
            )
        } catch (_: Throwable) {
            // 探针绝不能影响 guest。
        }
    }
}
