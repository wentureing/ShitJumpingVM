package com.nextvm.core.virtualization.engine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.nextvm.core.common.diagnostics.DiagnosticsHolder

/**
 * 标准行为记录器：用 Android **官方**生命周期回调记录启动编排顺序。
 * 零侵入 —— 不改容器逻辑，只作为旁观者写 checks.log。
 * 详见 `docs/设计/设计-启动编排测法与标准行为来源.md`。
 */
class LifecycleRecorder : Application.ActivityLifecycleCallbacks {

    private fun rec(name: String, activity: Activity) {
        DiagnosticsHolder.stage("lifecycle", "$name:${activity.javaClass.name}")
    }

    // API 29+ 的 pre 回调：容器 STEP 0-5 必须全部早于 pre_created
    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        rec("pre_created", activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        rec("created", activity)
    }

    override fun onActivityPreStarted(activity: Activity) {
        rec("pre_started", activity)
    }

    override fun onActivityStarted(activity: Activity) {
        rec("started", activity)
    }

    override fun onActivityPreResumed(activity: Activity) {
        rec("pre_resumed", activity)
    }

    override fun onActivityResumed(activity: Activity) {
        rec("resumed", activity)
    }

    override fun onActivityPaused(activity: Activity) {
        rec("paused", activity)
    }

    override fun onActivityStopped(activity: Activity) {
        rec("stopped", activity)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        rec("destroyed", activity)
    }

    companion object {
        /** 在 Application.onCreate 里调用一次。 */
        fun install(app: Application) {
            runCatching {
                app.registerActivityLifecycleCallbacks(LifecycleRecorder())
                DiagnosticsHolder.stage("lifecycle", "recorder_installed")
            }
        }
    }
}
