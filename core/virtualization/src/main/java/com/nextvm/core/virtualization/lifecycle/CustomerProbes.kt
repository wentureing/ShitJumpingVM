package com.nextvm.core.virtualization.lifecycle

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.nextvm.core.common.DebugTrace
import com.nextvm.core.common.findField
import timber.log.Timber

/**
 * 客户定制探测（CMCC 认证 / 人脸核验 / 登录按钮采样），只观测不改行为。
 * 详见 `docs/代码镜像/core/virtualization/lifecycle/CustomerProbes.md`。
 */

    internal val v134ReportedActivities = mutableSetOf<Int>()

    /**
     * v134: find the face page's button and wrap its click
     * listener with V134ClickWrapper (same reflection mechanism
     * as v125). Only AuthenticationActivity, only once per
     * listener instance (class-name check, as v125). The label
     * is "面部识别身份认证" (v133's "开始验证" never matched);
     * the clickable view is the TextView's ViewGroup ancestor,
     * so walk up from the text match to the nearest view with
     * a listener. Runs on resume and on the v124 timer, so
     * late-inflated buttons get wrapped too. When no match is
     * found, log the clickable views' labels (first 10, input
     * fields excluded so no user-entered text ever lands in a
     * log) so a future mismatch is visible instead of blind.
     */
    /**
     * v144 phone-pass probe, receive side (PII-safe by design: intent key
     * names, data scheme/host and a phone-field empty boolean only — the
     * EditText text itself is user-entered data and is never logged).
     * Appends to the same v144-intent-probe.log the rewrite side writes,
     * so send-in / send-out / recv for one navigation sit together.
     */
    internal fun VirtualApplicationManager.probeAuthIntentPhone(a: android.app.Activity) {
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
            java.io.File(dir, "v144-intent-probe.log").appendText(
                "v144 recv dataPresent=${data != null} " +
                    "scheme=${data?.scheme} host=${data?.host} " +
                    "extraKeys=${keys.joinToString(",")} " +
                    "phoneFound=$phoneFound phoneEmpty=$phoneEmpty\n"
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * v145 receive side: nested key names only (same contract as
     * logV145Intent in ActivityManagerProxy — names, never values).
     */
    internal fun VirtualApplicationManager.probeAuthIntentPhone145(a: android.app.Activity) {
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
                    val uri = intent.getParcelableExtra(
                        "_componentRouterUri"
                    ) as? android.net.Uri
                    if (uri == null) {
                        "NOT_A_URI"
                    } else {
                        try {
                            uri.queryParameterNames.toList()
                                .joinToString(",")
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
            java.io.File(dir, "v145-intent-probe.log").appendText(
                "v145 recv bundleKeys=${nestedKeys("bundle")} " +
                    "queryBundleKeys=" +
                    nestedKeys("_componentQueryBundle") + " " +
                    "routerQueryParams=$routerParams\n"
            )
        } catch (_: Throwable) {
        }
    }

    internal fun VirtualApplicationManager.wrapFaceVerifyButton(a: android.app.Activity) {
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
            // Diagnostic for the not-found branch: labels of
            // clickable views, input fields excluded (their text
            // is user-entered data and must never land in a log).
            val clickableLabels = mutableListOf<String>()
            val stack = ArrayDeque<android.view.View>()
            stack.add(decor)
            while (stack.isNotEmpty() && textView == null) {
                val v = stack.removeLast()
                if (v is android.widget.TextView) {
                    try {
                        val t = v.text?.toString() ?: ""
                        if (t == "面部识别身份认证" ||
                            t == "开始验证"
                        ) {
                            textView = v
                        } else if (clickableLabels.size < 10 &&
                            v !is android.widget.EditText
                        ) {
                            try {
                                if (v.isClickable && t.isNotEmpty()) {
                                    clickableLabels.add(
                                        v.javaClass.name
                                            .substringAfterLast('.') +
                                            " text=" + t
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
                if (v134ReportedActivities.add(actId)) {
                    GuestBreadcrumb.log(
                        "v134-face-probe button-not-found " +
                            "activity=${a.javaClass.name} " +
                            "clickable=" +
                            clickableLabels.joinToString(";")
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
                if (v134ReportedActivities.add(actId)) {
                    GuestBreadcrumb.log(
                        "v134-face-probe found-no-listener " +
                            "view=${textView.javaClass.name}"
                    )
                }
                return
            }
            try {
                val infoField =
                    findField(candidate.javaClass, "mListenerInfo")
                infoField?.isAccessible = true
                val info = infoField?.get(candidate)
                if (info == null) return
                val lField =
                    findField(info.javaClass, "mOnClickListener")
                lField?.isAccessible = true
                val cur =
                    lField?.get(info) as?
                        android.view.View.OnClickListener
                if (cur != null &&
                    !cur.javaClass.name.contains("V134ClickWrapper")
                ) {
                    lField.set(info, newV134ClickWrapper(cur))
                    GuestBreadcrumb.log(
                        "v134-face-probe listener wrapped " +
                            "view=${candidate.javaClass.name} " +
                            "listener=${cur.javaClass.name}"
                    )
                }
            } catch (_: Throwable) {
                GuestBreadcrumb.log("v134-face-probe wrap failed")
            }
        } catch (_: Throwable) {
            // probe only; never break lifecycle
        }
    }

    /**
     * v124 probe (read-only, main thread only): is the 我的 tab
     * login button's click listener actually registered in the
     * guest? Walks the resumed Activity's view hierarchy once
     * per call and reports: the mine_login_btn view itself
     * (found by resource id, falling back to a Button with text
     * "登录"), its clickable/enabled/shown state, whether it has
     * an OnClickListener (public hasOnClickListeners() plus, best
     * effort, the listener's class via mListenerInfo reflection),
     * its parent chain, and a control count (clickable views with
     * vs without listeners in the same hierarchy). The control
     * count is the point: if NO view has a listener, listener
     * registration is globally broken; if others have listeners
     * but this button does not, the gap is specific to this
     * button's binding. Logs only when the signature changes, so
     * the 20s timer does not spam the breadcrumb.
     */
    internal fun VirtualApplicationManager.probeLoginButtonState(
        a: android.app.Activity,
        source: String
    ) {
        try {
            val decor = try {
                a.window?.decorView
            } catch (_: Throwable) {
                null
            }
            if (decor == null) {
                GuestBreadcrumb.log(
                    "v124-login-probe source=$source " +
                        "activity=${a.javaClass.name} decor=null"
                )
                return
            }
            // v155: two id rules (mine_login_btn on the 我的 page,
            // identity_login_tip = 人脸识别登录 on the login page)
            // and two text rules ("登录", "人脸识别登录").
            // Candidates are collected, not first-won: the old code
            // stopped at the first "登录" TextView, which on the
            // login page is the non-clickable one_key_login_tv
            // label, and never wrapped the real button.
            val idInts = listOf(
                "mine_login_btn", "identity_login_tip"
            ).map {
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
            // Prefer a candidate that is clickable AND has a
            // listener (the wrappable button); fall back to any id
            // match, then any text match. A non-clickable label
            // must never win over a real button elsewhere in the
            // tree.
            fun wrapCandidate(v: android.view.View): Boolean {
                return try {
                    v.isClickable && v.hasOnClickListeners()
                } catch (_: Throwable) {
                    false
                }
            }
            val idTarget = idMatches.firstOrNull(::wrapCandidate)
                ?: idMatches.firstOrNull()
            val textTarget = textMatches.firstOrNull(::wrapCandidate)
                ?: textMatches.firstOrNull()
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
                    val infoField =
                        findField(target.javaClass, "mListenerInfo")
                    infoField?.isAccessible = true
                    val info = infoField?.get(target)
                    if (info == null) {
                        "none"
                    } else {
                        val lField =
                            findField(info.javaClass, "mOnClickListener")
                        lField?.isAccessible = true
                        val l = lField?.get(info)
                        v125Info = info
                        v125Field = lField
                        v125Listener =
                            l as? android.view.View.OnClickListener
                        l?.javaClass?.name ?: "none"
                    }
                } catch (_: Throwable) {
                    "reflection-unavailable"
                }
                val chain = ArrayList<String>()
                var p: android.view.ViewParent? = target.parent
                while (p != null && chain.size < 4) {
                    chain.add(
                        p.javaClass.name.substringAfterLast('.')
                    )
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
            if (sig != loginProbeLastSig) {
                loginProbeLastSig = sig
                GuestBreadcrumb.log(
                    "v124-login-probe source=$source " +
                        "activity=${a.javaClass.name} $detail"
                )
            }
            // v125: wrap the login button's listener with a logging
            // delegator, so a tap is observable even when the
            // original body is silent or its exception is swallowed
            // upstream. Only this button (id match preferred), only
            // once per listener instance (re-created views get
            // re-wrapped on a later probe run). The wrapper logs
            // entry, normal return, and thrown exceptions (rethrowing
            // to preserve behavior exactly); a failed set leaves the
            // original listener untouched.
            val cur = v125Listener
            if (target != null && cur != null &&
                !cur.javaClass.name.contains("V125ClickWrapper")
            ) {
                try {
                    v125Field?.set(v125Info, V125ClickWrapper(cur))
                    GuestBreadcrumb.log(
                        "v125-login-probe listener wrapped " +
                            "view=${target.javaClass.name}"
                    )
                } catch (_: Throwable) {
                    GuestBreadcrumb.log("v125-login-probe wrap failed")
                }
            }
        } catch (_: Throwable) {
            GuestBreadcrumb.log(
                "v124-login-probe FAILED source=$source"
            )
        }
    }
