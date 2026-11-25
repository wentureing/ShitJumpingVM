package com.nextvm.core.virtualization.diag.probe

import android.app.Activity
import com.nextvm.core.common.findField
import com.nextvm.core.virtualization.diag.trace.InitTrace
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import java.io.File

/**
 * 登录/认证页面探针（v124/v125/v127/v134/v144/v145）：只读观测 + 两处点击包装。
 *
 * 详见 docs/代码镜像/core/virtualization/diag/probe/LoginProbes.md
 */
internal object LoginProbes {

    /** 上次记录的签名（只在签名变化时写 breadcrumb，避免 timer 刷屏）。 */
    @Volatile
    private var lastSig: String? = null

    /** 已报告过 button-not-found / found-no-listener 的 Activity（identity hash）。 */
    private val reportedActivities = mutableSetOf<Int>()

    // ---------------------------------------------------------------- pure

    /** 纯函数：v144 接收侧一行（只含 key 名，不含值）。 */
    fun formatAuthRecv(
        dataPresent: Boolean,
        scheme: String?,
        host: String?,
        extraKeys: List<String>,
        phoneFound: Boolean,
        phoneEmpty: Boolean?,
    ): String = "v144 recv dataPresent=$dataPresent " +
        "scheme=$scheme host=$host " +
        "extraKeys=${extraKeys.joinToString(",")} " +
        "phoneFound=$phoneFound phoneEmpty=$phoneEmpty"

    /** 纯函数：v145 接收侧一行。 */
    fun formatAuthRecv145(
        bundleKeys: String,
        queryBundleKeys: String,
        routerQueryParams: String,
    ): String = "v145 recv bundleKeys=$bundleKeys " +
        "queryBundleKeys=$queryBundleKeys " +
        "routerQueryParams=$routerQueryParams"

    // ---------------------------------------------------------------- v124/v125

    /**
     * v124：检查「我的」页登录按钮的 click listener 是否真的注册了。
     *
     * 只在签名变化时写 breadcrumb；v125 会把 point listener 包一层（只包一次）。
     */
    fun probeLoginButtonState(a: Activity, source: String) {
        try {
            val decor = try {
                a.window?.decorView
            } catch (_: Throwable) {
                null
            }
            if (decor == null) {
                GuestBreadcrumb.log(
                    "v124-login-probe source=$source activity=${a.javaClass.name} decor=null"
                )
                return
            }
            val idInts = listOf("mine_login_btn", "identity_login_tip").map {
                try {
                    a.resources.getIdentifier(it, "id", a.packageName)
                } catch (_: Throwable) {
                    0
                }
            }.filter { it != 0 }
            var total = 0
            var clickableTotal = 0
            var clickableWithListener = 0
            val idMatches = ArrayList<android.view.View>()
            val textMatches = ArrayList<android.view.View>()
            val stack = ArrayDeque<android.view.View>()
            stack.add(decor)
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                total++
                try {
                    if (v.isClickable) {
                        clickableTotal++
                        if (v.hasOnClickListeners()) {
                            clickableWithListener++
                        }
                    }
                } catch (_: Throwable) {
                }
                if (idInts.contains(v.id)) {
                    idMatches.add(v)
                }
                if (v is android.widget.TextView) {
                    try {
                        val t = v.text?.toString()
                        if (t == "登录" || t == "人脸识别登录") {
                            textMatches.add(v)
                        }
                    } catch (_: Throwable) {
                    }
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
            fun wrapCandidate(v: android.view.View): Boolean {
                return try {
                    v.isClickable && v.hasOnClickListeners()
                } catch (_: Throwable) {
                    false
                }
            }
            val idTarget = idMatches.firstOrNull(::wrapCandidate) ?: idMatches.firstOrNull()
            val textTarget = textMatches.firstOrNull(::wrapCandidate) ?: textMatches.firstOrNull()
            val target = idTarget ?: textTarget
            val via = when {
                idTarget != null -> "id"
                textTarget != null -> "text"
                else -> "-"
            }
            var detail = "found=${target != null} via=$via"
            var listenerCls = "-"
            var parents = "-"
            var v125Info: Any? = null
            var v125Field: java.lang.reflect.Field? = null
            var v125Listener: android.view.View.OnClickListener? = null
            if (target != null) {
                val hasListener = try {
                    target.hasOnClickListeners()
                } catch (_: Throwable) {
                    null
                }
                listenerCls = try {
                    val infoField = findField(target.javaClass, "mListenerInfo")
                    infoField?.isAccessible = true
                    val info = infoField?.get(target)
                    if (info == null) {
                        "none"
                    } else {
                        val lField = findField(info.javaClass, "mOnClickListener")
                        lField?.isAccessible = true
                        val l = lField?.get(info)
                        v125Info = info
                        v125Field = lField
                        v125Listener = l as? android.view.View.OnClickListener
                        l?.javaClass?.name ?: "none"
                    }
                } catch (_: Throwable) {
                    "reflection-unavailable"
                }
                val chain = ArrayList<String>()
                var p: android.view.ViewParent? = target.parent
                while (p != null && chain.size < 4) {
                    chain.add(p.javaClass.name.substringAfterLast('.'))
                    p = try {
                        (p as? android.view.View)?.parent
                    } catch (_: Throwable) {
                        null
                    }
                }
                parents = chain.joinToString("<").ifEmpty { "-" }
                detail += " view=${target.javaClass.name} " +
                    "clickable=${target.isClickable} " +
                    "enabled=${target.isEnabled} " +
                    "shown=${target.isShown} " +
                    "hasListener=$hasListener " +
                    "listener=$listenerCls parents=$parents"
            }
            detail += " control=clickableWithListener" +
                "=$clickableWithListener/$clickableTotal total=$total"
            val sig = "${a.javaClass.name}|$detail"
            if (sig != lastSig) {
                lastSig = sig
                GuestBreadcrumb.log(
                    "v124-login-probe source=$source activity=${a.javaClass.name} $detail"
                )
            }
            val cur = v125Listener
            if (target != null && cur != null &&
                !cur.javaClass.name.contains("V125ClickWrapper")
            ) {
                try {
                    v125Field?.set(v125Info, V125ClickWrapper(cur))
                    GuestBreadcrumb.log(
                        "v125-login-probe listener wrapped view=${target.javaClass.name}"
                    )
                } catch (_: Throwable) {
                    GuestBreadcrumb.log("v125-login-probe wrap failed")
                }
            }
        } catch (_: Throwable) {
            GuestBreadcrumb.log("v124-login-probe FAILED source=$source")
        }
    }

    // ---------------------------------------------------------------- v134

    /**
     * v134：找到人脸认证页的按钮并包装点击（只在 AuthenticationActivity 上）。
     *
     * [onLateDump] 在点击后 15s 调用（原实现里是 `dumpV148ConnectProbe()`），
     * 由调用方注入，避免本类依赖容器启动链。
     */
    fun wrapFaceVerifyButton(a: Activity, onLateDump: () -> Unit = {}) {
        try {
            if (a.javaClass.name !=
                "com.mc10086.cmcc.authentication.common.AuthenticationActivity"
            ) {
                return
            }
            val decor = try {
                a.window?.decorView
            } catch (_: Throwable) {
                null
            } ?: return
            var textView: android.view.View? = null
            val clickableLabels = mutableListOf<String>()
            val stack = ArrayDeque<android.view.View>()
            stack.add(decor)
            while (stack.isNotEmpty() && textView == null) {
                val v = stack.removeLast()
                if (v is android.widget.TextView) {
                    try {
                        val t = v.text?.toString() ?: ""
                        if (t == "面部识别身份认证" || t == "开始验证") {
                            textView = v
                        } else if (clickableLabels.size < 10 &&
                            v !is android.widget.EditText
                        ) {
                            try {
                                if (v.isClickable && t.isNotEmpty()) {
                                    clickableLabels.add(
                                        v.javaClass.name.substringAfterLast('.') + " text=" + t
                                    )
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    } catch (_: Throwable) {
                    }
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
            val actId = System.identityHashCode(a)
            if (textView == null) {
                if (reportedActivities.add(actId)) {
                    GuestBreadcrumb.log(
                        "v134-face-probe button-not-found " +
                            "activity=${a.javaClass.name} " +
                            "clickable=" + clickableLabels.joinToString(";")
                    )
                }
                return
            }
            var candidate: android.view.View? = textView
            while (candidate != null) {
                try {
                    if (candidate.hasOnClickListeners()) break
                } catch (_: Throwable) {
                }
                candidate = try {
                    candidate.parent as? android.view.View
                } catch (_: Throwable) {
                    null
                }
            }
            if (candidate == null) {
                if (reportedActivities.add(actId)) {
                    GuestBreadcrumb.log(
                        "v134-face-probe found-no-listener view=${textView.javaClass.name}"
                    )
                }
                return
            }
            try {
                val infoField = findField(candidate.javaClass, "mListenerInfo")
                infoField?.isAccessible = true
                val info = infoField?.get(candidate)
                if (info == null) return
                val lField = findField(info.javaClass, "mOnClickListener")
                lField?.isAccessible = true
                val cur = lField?.get(info) as? android.view.View.OnClickListener
                if (cur != null && !cur.javaClass.name.contains("V134ClickWrapper")) {
                    lField.set(info, V134ClickWrapper(cur, onLateDump))
                    GuestBreadcrumb.log(
                        "v134-face-probe listener wrapped " +
                            "view=${candidate.javaClass.name} listener=${cur.javaClass.name}"
                    )
                }
            } catch (_: Throwable) {
                GuestBreadcrumb.log("v134-face-probe wrap failed")
            }
        } catch (_: Throwable) {
            // probe only; never break lifecycle
        }
    }

    // ---------------------------------------------------------------- v144/v145

    /**
     * v144 接收侧：只记录 intent key 名、data scheme/host 与 phone 字段是否为空。
     * 绝不记录输入框文本（用户数据）。
     */
    fun probeAuthIntentPhone(a: Activity) {
        try {
            if (a.javaClass.name !=
                "com.mc10086.cmcc.authentication.common.AuthenticationActivity"
            ) {
                return
            }
            val intent = try {
                a.intent
            } catch (_: Throwable) {
                null
            }
            val data = try {
                intent?.data
            } catch (_: Throwable) {
                null
            }
            val keys = try {
                intent?.extras?.keySet()?.toList() ?: emptyList()
            } catch (_: Throwable) {
                listOf("UNREADABLE")
            }
            var phoneFound = false
            var phoneEmpty: Boolean? = null
            try {
                val decor = a.window?.decorView
                val stack = ArrayDeque<android.view.View>()
                if (decor != null) stack.add(decor)
                while (stack.isNotEmpty() && !phoneFound) {
                    val v = stack.removeLast()
                    if (v is android.widget.EditText) {
                        try {
                            val name = try {
                                a.resources.getResourceEntryName(v.id)
                            } catch (_: Throwable) {
                                null
                            }
                            if (name == "phone") {
                                phoneFound = true
                                phoneEmpty = try {
                                    v.text.isNullOrEmpty()
                                } catch (_: Throwable) {
                                    null
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    if (v is android.view.ViewGroup) {
                        for (i in 0 until v.childCount) {
                            try {
                                stack.add(v.getChildAt(i))
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            val dir = try {
                GuestBreadcrumb.filesDir()
            } catch (_: Throwable) {
                null
            } ?: return
            File(dir, "v144-intent-probe.log").appendText(
                formatAuthRecv(data != null, data?.scheme, data?.host, keys, phoneFound, phoneEmpty) + "\n"
            )
        } catch (_: Throwable) {
        }
    }

    /** v145 接收侧：只记录嵌套 bundle 的 key 名（不记录值）。 */
    fun probeAuthIntentPhone145(a: Activity) {
        try {
            if (a.javaClass.name !=
                "com.mc10086.cmcc.authentication.common.AuthenticationActivity"
            ) {
                return
            }
            val intent = try {
                a.intent
            } catch (_: Throwable) {
                null
            } ?: return
            fun nestedKeys(key: String): String {
                return try {
                    if (!intent.hasExtra(key)) {
                        "ABSENT"
                    } else {
                        val b = intent.getBundleExtra(key)
                        if (b == null) {
                            "NULL"
                        } else {
                            try {
                                b.keySet().toList().joinToString(",")
                            } catch (_: Throwable) {
                                "UNREADABLE"
                            }
                        }
                    }
                } catch (_: Throwable) {
                    "UNREADABLE"
                }
            }
            val routerParams = try {
                if (!intent.hasExtra("_componentRouterUri")) {
                    "ABSENT"
                } else {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra("_componentRouterUri") as? android.net.Uri
                    if (uri == null) {
                        "NOT_A_URI"
                    } else {
                        try {
                            uri.queryParameterNames.toList().joinToString(",")
                        } catch (_: Throwable) {
                            "UNREADABLE"
                        }
                    }
                }
            } catch (_: Throwable) {
                "UNREADABLE"
            }
            val dir = try {
                GuestBreadcrumb.filesDir()
            } catch (_: Throwable) {
                null
            } ?: return
            File(dir, "v145-intent-probe.log").appendText(
                formatAuthRecv145(
                    nestedKeys("bundle"),
                    nestedKeys("_componentQueryBundle"),
                    routerParams
                ) + "\n"
            )
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- wrappers

    private class V125ClickWrapper(
        private val original: android.view.View.OnClickListener
    ) : android.view.View.OnClickListener {
        companion object {
            @Volatile var traceCount = 0
        }

        override fun onClick(v: android.view.View) {
            GuestBreadcrumb.log("v125-login-probe CLICKED")
            if (traceCount == 0) {
                try {
                    val dir = GuestBreadcrumb.filesDir()
                    if (dir != null) {
                        traceCount++
                        val traceFile = File(
                            dir,
                            "v127-trace-" + android.os.Process.myPid() + "-1.trace"
                        )
                        InitTrace.stopBeforeStart(dir, "v155-login")
                        android.os.Debug.startMethodTracing(
                            traceFile.absolutePath, 32 * 1024 * 1024
                        )
                        GuestBreadcrumb.log("v127-trace start file=" + traceFile.name)
                        android.os.Handler(
                            android.os.Looper.getMainLooper()
                        ).postDelayed({
                            try {
                                android.os.Debug.stopMethodTracing()
                                GuestBreadcrumb.log(
                                    "v127-trace stop delayed file=" + traceFile.name
                                )
                            } catch (e: Throwable) {
                                GuestBreadcrumb.log(
                                    "v127-trace stop failed " +
                                        "${e.javaClass.simpleName}: " + e.message
                                )
                            }
                        }, 1500L)
                    }
                } catch (e: Throwable) {
                    GuestBreadcrumb.log(
                        "v127-trace start failed ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            try {
                original.onClick(v)
                GuestBreadcrumb.log("v125-login-probe CLICKED returned")
            } catch (e: Throwable) {
                GuestBreadcrumb.log(
                    "v125-login-probe CLICKED threw " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                throw e
            }
        }
    }

    private class V134ClickWrapper(
        private val original: android.view.View.OnClickListener,
        private val onLateDump: () -> Unit,
    ) : android.view.View.OnClickListener {
        companion object {
            @Volatile var traceCount = 0
        }

        override fun onClick(v: android.view.View) {
            GuestBreadcrumb.log("v134-face-probe CLICKED")
            if (traceCount == 0) {
                try {
                    val dir = GuestBreadcrumb.filesDir()
                    if (dir != null) {
                        traceCount++
                        val traceFile = File(
                            dir,
                            "v134-trace-" + android.os.Process.myPid() + "-1.trace"
                        )
                        InitTrace.stopBeforeStart(dir, "v155-click")
                        android.os.Debug.startMethodTracing(
                            traceFile.absolutePath, 64 * 1024 * 1024
                        )
                        GuestBreadcrumb.log("v134-trace start file=" + traceFile.name)
                        android.os.Handler(
                            android.os.Looper.getMainLooper()
                        ).postDelayed({
                            try {
                                android.os.Debug.stopMethodTracing()
                                GuestBreadcrumb.log(
                                    "v134-trace stop delayed file=" + traceFile.name
                                )
                            } catch (e: Throwable) {
                                GuestBreadcrumb.log(
                                    "v134-trace stop failed " +
                                        "${e.javaClass.simpleName}: " + e.message
                                )
                            }
                            Thread({
                                InitTrace.filter(
                                    traceFile, dir,
                                    "v155-click-filtered.log", "v155-click"
                                )
                            }, "v155-click-filter").start()
                        }, 8000L)
                    }
                } catch (e: Throwable) {
                    GuestBreadcrumb.log(
                        "v134-trace start failed ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            android.os.Handler(
                android.os.Looper.getMainLooper()
            ).postDelayed({
                try {
                    onLateDump()
                } catch (_: Throwable) {
                }
            }, 15000L)
            try {
                original.onClick(v)
                GuestBreadcrumb.log("v134-face-probe CLICKED returned")
            } catch (e: Throwable) {
                GuestBreadcrumb.log(
                    "v134-face-probe CLICKED threw " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                throw e
            }
        }
    }
}
