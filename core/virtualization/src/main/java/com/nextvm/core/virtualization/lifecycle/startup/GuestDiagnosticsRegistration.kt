package com.nextvm.core.virtualization.lifecycle.startup

import android.app.Application
import com.nextvm.core.common.DebugFlags
import com.nextvm.core.common.diagnostics.DiagFiles
import com.nextvm.core.common.diagnostics.DiagOutput
import com.nextvm.core.common.diagnostics.DiagnosticsHolder
import com.nextvm.core.common.diagnostics.DiagnosticsRouter
import com.nextvm.core.common.diagnostics.FileSink
import com.nextvm.core.hook.NativeHookBridge
import com.nextvm.core.virtualization.diag.GuestSelfCheck
import com.nextvm.core.virtualization.diag.SelfCheckRequest
import com.nextvm.core.virtualization.diag.probe.LoginProbes
import com.nextvm.core.virtualization.diag.probe.PluginProbes
import com.nextvm.core.virtualization.diag.suppress.AutofillSuppressor
import com.nextvm.core.virtualization.diag.trace.InitTrace
import com.nextvm.core.virtualization.engine.LifecycleRecorder
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import com.nextvm.core.virtualization.lifecycle.VirtualApplicationManager.ApplicationRecord

/**
 * guest 侧诊断装配 + Activity 生命周期回调注册（每进程一次）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/GuestDiagnosticsRegistration.md`
 */
object GuestDiagnosticsRegistration {

    @Volatile
    private var diagnosticsInstalled = false

    /** guest 自检：每进程只消费一次请求（debug 包才有意义）。 */
    @Volatile
    private var selfCheckDone = false

    @Volatile
    private var loginProbeLastActivity: android.app.Activity? = null
    @Volatile
    private var loginProbeTimerStarted = false

    /**
     * 是否该在本进程消费一次 guest 自检请求：只有 debug 包、且每进程一次。
     */
    fun shouldConsumeSelfCheck(isDebugBuild: Boolean, alreadyDone: Boolean): Boolean =
        isDebugBuild && !alreadyDone

    /**
     * @param startWatchdog VAM 的 watchdog 启动（仍属 VAM 的职责）
     * @param existingRecord 按 Application 反查实例记录（替代直接读 VAM 的 applications 表）
     */
    fun install(
        app: Application,
        nativeHookBridge: NativeHookBridge,
        startWatchdog: () -> Unit,
        existingRecord: (Application) -> ApplicationRecord?,
    ) {
        // ===== guest 侧诊断（sandboxsensor）=====
        // guest 进程用的是 guest 自己的 Application（如 mark.via.BrowserApp），
        // NextVmApplication.onCreate 在这里不执行 -> 必须在 bind 路径上装。
        // 路径用 guest 的 filesDir（已被 VirtualContext 重定向到沙箱）。
        runCatching {
            if (!diagnosticsInstalled) {
                diagnosticsInstalled = true
                DiagnosticsHolder.install(
                    DiagnosticsRouter(
                        listOf(
                            FileSink(
                                java.io.File(app.filesDir, "checks.log")
                            )
                        )
                    )
                )
                LifecycleRecorder.install(app)
                GuestBreadcrumb.log("guest diagnostics installed")
                // Holder 就绪后再 dump —— 规则注册(71ms)早于 Holder install(78ms)，
                // 在 setupAppRedirections 里 dump 会被 NoOp 静默丢弃。
                runCatching { nativeHookBridge.dumpFsStats("bind:" + app.packageName) }
            }
        }.onFailure {
            GuestBreadcrumb.log("guest diagnostics install failed: " + it.message)
        }
        startWatchdog()
        try {
            app.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    // v118: first Activity only, once per process.
                    private var firstActivityProbed = false
                    override fun onActivityCreated(
                        a: android.app.Activity, b: android.os.Bundle?
                    ) {
                        GuestBreadcrumb.log("cb onActivityCreated " + a.javaClass.name)
                        AutofillSuppressor.disableForActivity(a)
                        if (!firstActivityProbed) {
                            firstActivityProbed = true
                            val rec = existingRecord(app)
                            PluginProbes.probePluginStatePhase(app, rec, "firstActivity")
                        }
                    }

                    override fun onActivityStarted(a: android.app.Activity) =
                        GuestBreadcrumb.log("cb onActivityStarted " + a.javaClass.name)

                    override fun onActivityResumed(a: android.app.Activity) {
                        GuestBreadcrumb.log("cb onActivityResumed " + a.javaClass.name)
                        // 自检请求：**仅 debug 包**、每进程一次（CmdReceiver SELF_CHECK --es instance 写入）
                        if (shouldConsumeSelfCheck(DebugFlags.isDebugBuild(), selfCheckDone)) {
                            selfCheckDone = true
                            runCatching {
                                val dir = a.applicationContext?.filesDir ?: return@runCatching
                                if (SelfCheckRequest.consume(dir)) {
                                    GuestSelfCheck.run(nativeHookBridge, a.application)
                                }
                            }
                        }
                        // v156: trace the login page's load window.
                        // First resume of LoginActivity, once per
                        // process. The init trace never covers this
                        // window (9626's init table has no
                        // LoginActivity at all — buffer filled in
                        // the Application burst).
                        if (a.javaClass.name ==
                            "com.cmccit.loginmodule.ac.LoginActivity"
                        ) {
                            InitTrace.startLoad()
                        }
                        // v124: every resume re-checks the login button
                        // (fragment tab switches do not resume a new
                        // Activity, so the timer below covers those).
                        loginProbeLastActivity = a
                        LoginProbes.probeLoginButtonState(a, "resumed")
                        AutofillSuppressor.disableForActivity(a)
                        AutofillSuppressor.disablePerView(a)
                        LoginProbes.wrapFaceVerifyButton(a) { dumpV148ConnectProbe() }
                        LoginProbes.probeAuthIntentPhone(a)
                        LoginProbes.probeAuthIntentPhone145(a)
                    }

                    override fun onActivityPaused(a: android.app.Activity) =
                        GuestBreadcrumb.log("cb onActivityPaused " + a.javaClass.name)

                    override fun onActivityStopped(a: android.app.Activity) =
                        GuestBreadcrumb.log("cb onActivityStopped " + a.javaClass.name)

                    override fun onActivitySaveInstanceState(
                        a: android.app.Activity, b: android.os.Bundle
                    ) = GuestBreadcrumb.log("cb onActivitySaveInstanceState " + a.javaClass.name)

                    override fun onActivityDestroyed(a: android.app.Activity) =
                        GuestBreadcrumb.log("cb onActivityDestroyed " + a.javaClass.name)
                })
            GuestBreadcrumb.log("engine breadcrumb callbacks registered on " + app.javaClass.name)
            // 运行期二次采样：maps/spoof 计数发生在 bind 之后（探针 N10 读 /proc/self/maps 时）。
            // 只打两条（+5s / +20s），见 docs/架构/架构-native模块关系与技术路线.md §3。
            Thread {
                var elapsed = 0L
                for ((atMs, label) in listOf(5_000L to "+5s", 20_000L to "+20s")) {
                    val wait = atMs - elapsed
                    if (wait > 0) Thread.sleep(wait)
                    elapsed = atMs
                    try {
                        // N2-C 第 2 步：先主动触发一次延迟 patch（L6 —— bind 之后
                        // System.loadLibrary 加载的 app 库本来永远补不上），再取统计。
                        val patched = nativeHookBridge.patchNewlyLoaded()
                        com.nextvm.core.common.DebugTrace.event(
                            "native",
                            "hook stats($label): patchedNewly=$patched " +
                                nativeHookBridge.hookStats()
                        )
                    } catch (_: Throwable) {
                    }
                }
            }.apply { isDaemon = true; name = "nv-native-stats" }.start()
            // v124: repeating login-button check on the main looper.
            // Tab switches inside one Activity never resume a new
            // Activity, so resume alone cannot see the 我的 tab's
            // button appearing later. v155: the 20s timer missed
            // the user's few-seconds visit to the 我的 tab, so the
            // timer is 5s for 10 minutes now (120 runs) — a full
            // hierarchy walk is milliseconds, and the breadcrumb
            // only logs on signature change, so the finer tick
            // costs no log spam. Read-only hierarchy walk, main
            // thread only.
            if (!loginProbeTimerStarted) {
                loginProbeTimerStarted = true
                val handler = android.os.Handler(
                    android.os.Looper.getMainLooper()
                )
                var runs = 0
                val r = object : Runnable {
                    override fun run() {
                        try {
                            loginProbeLastActivity?.let {
                                LoginProbes.probeLoginButtonState(it, "timer")
                                AutofillSuppressor.disablePerView(it)
                                LoginProbes.wrapFaceVerifyButton(it) { dumpV148ConnectProbe() }
                                LoginProbes.probeAuthIntentPhone(it)
                                LoginProbes.probeAuthIntentPhone145(it)
                                dumpV148ConnectProbe()
                            }
                        } catch (_: Throwable) {
                        }
                        runs++
                        if (runs < 120) handler.postDelayed(this, 5_000L)
                    }
                }
                handler.postDelayed(r, 5_000L)
            }
        } catch (e: Throwable) {
            GuestBreadcrumb.logThrowable("register breadcrumb callbacks FAILED", e)
        }
    }

    /**
     * v148: drain the native failed-connect / failed-DNS ring and append
     * new events to v148-connect-probe.log in the guest files dir.
     * Runs outside any native hook body, so file I/O here is safe.
     * No-op when there are no new events.
     */
    private fun dumpV148ConnectProbe() {
        try {
            val ev = NativeHookBridge.dumpConnectProbe()
            if (ev.isNullOrEmpty()) return
            if (GuestBreadcrumb.filesDir() == null) return
            DiagOutput.append(DiagFiles.v148ConnectProbe(), ev)
        } catch (_: Throwable) {
        }
    }
}
