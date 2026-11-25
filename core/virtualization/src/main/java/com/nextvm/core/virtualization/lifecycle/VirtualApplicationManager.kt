package com.nextvm.core.virtualization.lifecycle

import android.app.Application
import android.app.Instrumentation
import android.content.ContentProvider
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.findField
import com.nextvm.core.common.findMethod
import com.nextvm.core.common.runSafe
import com.nextvm.core.binder.proxy.ActivityManagerProxy
import com.nextvm.core.hook.NativeHookBridge
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.nextvm.core.virtualization.diag.dump.GuestMemoryDump
import com.nextvm.core.virtualization.diag.fieldOf
import com.nextvm.core.virtualization.diag.probe.HijackProbes
import com.nextvm.core.virtualization.diag.probe.LoginProbes
import com.nextvm.core.virtualization.diag.probe.PluginProbes
import com.nextvm.core.virtualization.diag.suppress.AutofillSuppressor
import com.nextvm.core.virtualization.diag.dump.MapsSnapshot
import com.nextvm.core.virtualization.diag.trace.InitTrace
import com.nextvm.core.virtualization.diag.dump.RealSnap

/**
 * VirtualApplicationManager — Manages guest app Application lifecycle.
 *
 * This is the MOST critical missing piece. It handles:
 * - Instantiating guest app's custom Application subclass via DexClassLoader
 * - Calling Application.attachBaseContext(virtualContext)
 * - Calling Application.onCreate()
 * - Managing Application lifecycle per guest app instance
 * - ContentProvider auto-init (AndroidX Startup, WorkManager, Firebase via ContentProvider.onCreate())
 * - AppComponentFactory support (Android 9+)
 * - handleBindApplication() mirror — simulating what real Android does when binding an app
 *
 * Mirrors the flow from:
 *   ActivityThread.handleBindApplication() in frameworks/base/core/java/android/app/ActivityThread.java
 *
 * Flow:
 *   1. Create LoadedApk equivalent (set up ClassLoader, Resources)
 *   2. Create AppComponentFactory (Android 9+)
 *   3. Instantiate Application class
 *   4. Install ContentProviders (BEFORE Application.onCreate)
 *   5. Call Application.attachBaseContext()
 *   6. Call Application.onCreate()
 */
@Singleton
/**
 * v148: drain the native failed-connect / failed-DNS ring and append
 * new events to v148-connect-probe.log in the guest files dir.
 * File-private top-level so both the VAM timer and the nested
 * V134ClickWrapper can call it. Runs outside any native hook body,
 * so file I/O here is safe. No-op when there are no new events.
 */
private fun dumpV148ConnectProbe() {
    try {
        val ev = NativeHookBridge.dumpConnectProbe()
        if (ev.isNullOrEmpty()) return
        val dir = try {
            GuestBreadcrumb.filesDir()
        } catch (_: Throwable) {
            null
        } ?: return
        java.io.File(dir, "v148-connect-probe.log").appendText(ev)
    } catch (_: Throwable) {
    }
}


class VirtualApplicationManager @Inject constructor(
    private val resourceManager: VirtualResourceManager,
    private val nativeHookBridge: com.nextvm.core.hook.NativeHookBridge,
    private val sandboxManager: com.nextvm.core.sandbox.SandboxManager
) {

    companion object {
        private const val TAG = "VAppLifecycle"
    }

    private var initialized = false
    private lateinit var appContext: android.content.Context

    fun initialize(context: android.content.Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        Timber.tag(TAG).i("VirtualApplicationManager initialized — Application lifecycle ready")
    }

    /**
     * Holds state for a living guest Application.
     */
    data class ApplicationRecord(
        val instanceId: String,
        val packageName: String,
        val application: Application,
        val classLoader: ClassLoader,
        val applicationClassName: String?,
        val contentProviders: MutableList<ContentProvider> = mutableListOf(),
        val startTime: Long = System.currentTimeMillis(),
        var state: ApplicationState = ApplicationState.CREATED
    )

    enum class ApplicationState {
        CREATED,
        ATTACHED,
        PROVIDERS_INSTALLED,
        RUNNING,
        TERMINATED
    }

    // instanceId -> ApplicationRecord
    private var guestDiagnosticsInstalled = false

    private val applications = ConcurrentHashMap<String, ApplicationRecord>()

    /**
     * Full bind-application flow — mirrors ActivityThread.handleBindApplication().
     *
     * Order (from real Android source):
     *   1. Set up ApplicationInfo
     *   2. Create ClassLoader (already done by VirtualEngine)
     *   3. Set up Resources (handled by VirtualResourceManager)
     *   4. Create Instrumentation
     *   5. Create Application instance via AppComponentFactory or reflection
     *   6. Install ContentProviders (BEFORE onCreate!)
     *   7. Call Instrumentation.callApplicationOnCreate()
     *
     * @param instanceId Unique instance identifier
     * @param packageName Guest app package name
     * @param applicationClassName Fully qualified Application class (null = android.app.Application)
     * @param apkPath Path to the guest APK
     * @param classLoader DexClassLoader for the guest app
     * @param virtualContext Sandboxed context wrapping the guest identity
     * @param providerNames List of ContentProvider class names to initialize
     * @param providerAuthorities Map of provider class name -> authority string
     * @return The created Application or null on failure
     */
    fun bindApplication(
        instanceId: String,
        packageName: String,
        applicationClassName: String?,
        apkPath: String,
        classLoader: ClassLoader,
        virtualContext: Context,
        providerNames: List<String> = emptyList(),
        providerAuthorities: Map<String, String> = emptyMap(),
        providerMetaData: Map<String, Bundle> = emptyMap(),
        frameworkLoadedApk: Any? = null
    ): Application? {
        // v145：guest 进程在这里能拿到可靠可写的 Context（VirtualContext → 沙箱 files），
        // 补一次调试初始化。initNativeHooks 那处传的 context 可能为 null
        // （getOrCreateClassLoader 调用），否则 guest 侧永远没有 debug-trace.log。
        com.nextvm.core.common.DebugFlags.init(virtualContext)
        com.nextvm.core.common.DebugTrace.event(
            "bindApplication", "enter pkg=$packageName instance=$instanceId"
        )

        // Check if already bound
        val existing = applications[instanceId]
        if (existing != null && existing.state != ApplicationState.TERMINATED) {
            Timber.tag(TAG).d("Application already bound for $instanceId, returning existing")
            com.nextvm.core.common.DebugTrace.event(
                "bindApplication", "already bound, return existing"
            )
            return existing.application
        }

        Timber.tag(TAG).i("bindApplication: $packageName ($instanceId)")
        Timber.tag(TAG).d("  applicationClass: ${applicationClassName ?: "android.app.Application"}")
        Timber.tag(TAG).d("  providers: ${providerNames.size}")
        GuestBreadcrumb.begin(packageName)
        GuestBreadcrumb.log("bind start instance=$instanceId appClass=" +
            (applicationClassName ?: "android.app.Application") +
            " providers=" + providerNames.size +
            " frameworkLoadedApk=" + (frameworkLoadedApk != null))
        // v47: start the WebView witness HERE, at bind entry — v46
        // started it at bind complete and missed the guest startup
        // band where the early WebView touch lives (fork+1.3s).
        // virtualContext.filesDir is the guest files dir. The
        // bind-complete schedule() calls below become no-ops.
        WebViewWatch.schedule(packageName, virtualContext.filesDir)

        // v155: init-time method trace, CM-gated, once per process.
        // Starts at bind entry (before the framework bind creates
        // the Application), so Application.onCreate + provider init
        // + SDK inits are inside the window. Full tracing (v153's
        // 1ms sampling froze the guest — reverted), 64MB buffer,
        // 20s delayed stop. After
        // stop, a background filter pass extracts only
        // device-identity reads into v155-init-filtered.log, so
        // the small file — not the 64MB trace — is what gets
        // pulled. The full trace stays on-device as backup. A
        // failed start/stop/filter never touches the bind.
        if (packageName == "com.greenpoint.android.mc10086.activity") {
            InitTrace.startInit()
        }

        var legacyPrevInstr: Instrumentation? = null
        try {
            // B2: framework-native bind — when a framework LoadedApk is
            // available for this guest, the Application is created and
            // attached by the framework itself instead of being
            // hand-assembled onto the host's (patched) LoadedApk.
            if (frameworkLoadedApk != null) {
                val fwApp = bindViaFrameworkLoadedApk(
                    instanceId = instanceId,
                    packageName = packageName,
                    loadedApk = frameworkLoadedApk,
                    virtualContext = virtualContext,
                    providerNames = providerNames,
                    providerAuthorities = providerAuthorities,
                    providerMetaData = providerMetaData
                )
                if (fwApp != null) {
                    GuestBreadcrumb.attachFile(fwApp.filesDir)
                    // v138-appctx：必须早于 registerBreadcrumbCallbacks —— 那里的
                    // WebViewWarmup 会主动预热 WebViewFactory，provider 一旦初始化，
                    // 它看到的 context 就固化了。
                    patchActivityThreadApplication(fwApp)
                    // v142：规则注册点与生效进程不一致的修复 —— guest 进程补注册
                    registerGuestRedirections(fwApp, instanceId, packageName)
                    // v144：预建目录树，消除启动早期的懒创建竞态
                    sandboxManager.ensureAppDirsReady(instanceId, packageName)
                    // v143：假 dataDir（白名单 + 开关），必须早于 WebViewWarmup
                    patchFrameworkDataDir(fwApp, packageName)
                    registerBreadcrumbCallbacks(fwApp)
                    GuestBreadcrumb.log("bind complete (framework path)")
                    // v146：把 native 侧 Java I/O 统计落盘（guest 的 native 日志会被吞）
                    com.nextvm.core.common.DebugTrace.event(
                        "javaIo",
                        "stats after bind: " + (com.nextvm.core.hook.JavaIoHooks.stats() ?: "n/a")
                    )
                    // v148：最近「失败 / 发生翻译」的 open|access 明细
                    com.nextvm.core.common.DebugTrace.event(
                        "javaIo",
                        "recent opens after bind:\n" +
                            (com.nextvm.core.hook.JavaIoHooks.recent() ?: "n/a")
                    )
                    scheduleGuestStackDumps(packageName)
                    scheduleHijackTrace(packageName)
                    DexMemDump.schedule(packageName, fwApp.filesDir)
                    StateProbe.schedule(packageName, fwApp.filesDir)
                    WebViewWatch.schedule(packageName, fwApp.filesDir)
                    WebViewWarmup.schedule(packageName, fwApp.filesDir)
                    return fwApp
                }
                GuestBreadcrumb.log("framework path returned null, falling back to legacy bind")
                // fall through to the legacy bind path
            }

            // Step 1: Prepare ApplicationInfo on the virtual context
            val appInfo = buildApplicationInfo(packageName, apkPath, virtualContext)

            // Step 2: Create Application instance
            val application = createApplication(
                instanceId = instanceId,
                packageName = packageName,
                applicationClassName = applicationClassName,
                classLoader = classLoader,
                virtualContext = virtualContext,
                appInfo = appInfo
            ) ?: return null

            // Step 3: Create ApplicationRecord
            val record = ApplicationRecord(
                instanceId = instanceId,
                packageName = packageName,
                application = application,
                classLoader = classLoader,
                applicationClassName = applicationClassName
            )
            applications[instanceId] = record

            // Step 3.5: Set the guest Application on VirtualContext so that
            // getApplicationContext() returns the real Application instance.
            // This is CRITICAL for AndroidX libraries (ProcessLifecycleInitializer,
            // WorkManager, Firebase) which cast getApplicationContext() to Application.
            if (virtualContext is com.nextvm.core.sandbox.VirtualContext) {
                virtualContext.setGuestApplication(application)
            }

            // Step 4: Attach base context
            // v35: same stock-init cover as the framework path —
            // hijack's initailise runs inside this attach.
            legacyPrevInstr = swapInStockInstrumentation(packageName)
            GuestBreadcrumb.log("legacy attachBaseContext enter")
            attachBaseContext(application, virtualContext)
            GuestBreadcrumb.log("legacy attachBaseContext exit")
            record.state = ApplicationState.ATTACHED
            Timber.tag(TAG).d("Application.attachBaseContext() complete")

            // Step 4.5: Patch LoadedApk.mApplication to the guest Application.
            // ContextImpl.getApplicationContext() returns LoadedApk.getApplication()
            // which normally returns the HOST Application. By patching mApplication,
            // even code that bypasses our ContextWrapper chain (e.g., framework-internal
            // calls to ContextImpl directly) will get the guest Application.
            patchLoadedApkApplication(virtualContext, application)

            // Step 5: Install ContentProviders BEFORE Application.onCreate()
            // This is critical — AndroidX Startup, WorkManager, Firebase all use
            // ContentProviders that must be initialized before the Application.
            if (providerNames.isNotEmpty()) {
                initContentProviders(
                    instanceId = instanceId,
                    providers = providerNames,
                    authorities = providerAuthorities,
                    classLoader = classLoader,
                    virtualContext = virtualContext,
                    application = application,
                    providerMetaData = providerMetaData
                )
                record.state = ApplicationState.PROVIDERS_INSTALLED
                Timber.tag(TAG).d("ContentProviders installed: ${providerNames.size}")
            }

            // Step 6: Call Application.onCreate()
            callApplicationOnCreate(application)
            PluginProbes.probePluginLoaderState(application, record)
            record.state = ApplicationState.RUNNING
            Timber.tag(TAG).i("Application.onCreate() complete for $packageName")
            restoreInstrumentation(legacyPrevInstr)

            GuestBreadcrumb.attachFile(application.filesDir)
            // v138-appctx：同上，必须早于 registerBreadcrumbCallbacks（WebViewWarmup 会预热）
            patchActivityThreadApplication(application)
            // v142：同上
            registerGuestRedirections(application, instanceId, packageName)
            sandboxManager.ensureAppDirsReady(instanceId, packageName)
            patchFrameworkDataDir(application, packageName)
            registerBreadcrumbCallbacks(application)
            GuestBreadcrumb.log("bind complete (legacy path)")
            scheduleGuestStackDumps(packageName)
            scheduleHijackTrace(packageName)
            DexMemDump.schedule(packageName, application.filesDir)
            StateProbe.schedule(packageName, application.filesDir)
            WebViewWatch.schedule(packageName, application.filesDir)
            WebViewWarmup.schedule(packageName, application.filesDir)
            return application

        } catch (e: Exception) {
            restoreInstrumentation(legacyPrevInstr)
            GuestBreadcrumb.logThrowable("bind FAILED (legacy/framework outer catch)", e)
            Timber.tag(TAG).e(e, "Failed to bind application for $packageName ($instanceId)")
            applications.remove(instanceId)
            return null
        }
    }


    private fun registerBreadcrumbCallbacks(app: Application) {
        // ===== guest 侧诊断（sandboxsensor）=====
        // guest 进程用的是 guest 自己的 Application（如 mark.via.BrowserApp），
        // NextVmApplication.onCreate 在这里不执行 -> 必须在 bind 路径上装。
        // 路径用 guest 的 filesDir（已被 VirtualContext 重定向到沙箱）。
        runCatching {
            if (!guestDiagnosticsInstalled) {
                guestDiagnosticsInstalled = true
                com.nextvm.core.common.diagnostics.DiagnosticsHolder.install(
                    com.nextvm.core.common.diagnostics.DiagnosticsRouter(
                        listOf(
                            com.nextvm.core.common.diagnostics.FileSink(
                                java.io.File(app.filesDir, "checks.log")
                            )
                        )
                    )
                )
                com.nextvm.core.virtualization.engine.LifecycleRecorder.install(app)
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
                            val rec = applications.values.firstOrNull {
                                it.application === app
                            }
                            PluginProbes.probePluginStatePhase(app, rec, "firstActivity")
                        }
                    }

                    override fun onActivityStarted(a: android.app.Activity) =
                        GuestBreadcrumb.log("cb onActivityStarted " + a.javaClass.name)

                    override fun onActivityResumed(a: android.app.Activity) {
                        GuestBreadcrumb.log("cb onActivityResumed " + a.javaClass.name)
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

    // ==================== Guest stack diagnostics ====================
    // One-shot per process: after a guest Application binds, dump all
    // thread stacks (+ the main Looper's pending messages) at
    // +0.3/+0.7/+1.2/+2/+3/+8/+20s into a stackdump-<pid>.log under the
    // Application's filesDir (for a framework-bound guest that resolves
    // to the guest sandbox files dir; readable via run-as).
    // Exists to answer "the guest UI is up but never advances — what
    // is it waiting on?" with the actual Java frames instead of
    // black-box guessing.

    @Volatile private var stackDumpsScheduled = false

    // v68: which guest the scheduled dumps belong to (drives the
    // late-marks extension inside the dumper thread).
    @Volatile private var stackDumpPackageName: String? = null

    private fun scheduleGuestStackDumps(packageName: String) {
        if (stackDumpsScheduled) return
        stackDumpsScheduled = true
        stackDumpPackageName = packageName
        Thread {
            try {
                val hostApp = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                    as? android.app.Application ?: return@Thread
                val out = java.io.File(
                    hostApp.filesDir, "stackdump-${android.os.Process.myPid()}.log"
                )
                // Cadence (v29): logcat comparison against a real
                // device localized the guest's init-pipeline stall to
                // the first ~1.5s after process start (bind lands at
                // ~+0.45s), so sample densely across bind+0.3..+3s
                // to catch threads mid-flight at the stall moment;
                // +8s/+20s remain as steady-state anchors.
                val baseMarks = listOf(
                    300L to "t+0.3s",
                    700L to "t+0.7s",
                    1_200L to "t+1.2s",
                    2_000L to "t+2s",
                    3_000L to "t+3s",
                    8_000L to "t+8s",
                    20_000L to "t+20s"
                )
                // v68: China Mobile's post-Agree stall sits INSIDE
                // ZWRisk.setup's native enumeration (launch90: main
                // RUNNABLE in Feinnhap.v at both t+8s and t+20s,
                // hundreds of supervisor-served readlinkat/fstatat
                // calls). Late marks answer the one open question —
                // guest-slow-but-finite vs never-returns — and show
                // what main does if setup ever completes.
                val marks = if (stackDumpPackageName ==
                    "com.greenpoint.android.mc10086.activity") {
                    baseMarks + listOf(
                        40_000L to "t+40s",
                        80_000L to "t+80s",
                        160_000L to "t+160s"
                    )
                } else {
                    baseMarks
                }
                var elapsed = 0L
                for ((at, label) in marks) {
                    val wait = at - elapsed
                    if (wait > 0) Thread.sleep(wait)
                    elapsed = at
                    GuestMemoryDump.dumpGuestStacks(out, label)
                }
            } catch (_: Throwable) {
                // diagnostics must never disturb the guest
            }
        }.apply { isDaemon = true; name = "nv-stackdump" }.start()
    }


    // ==================== Hijack SDK completion trace (v33) ====================
    // v34 adds the discriminating experiment: after each sample, if
    // ActivityThread.mInstrumentation is hijack's ProxyInstrumentation,
    // swap it (and every existing Activity's mInstrumentation) back to
    // the proxy's oriInstrumentation (the engine's NextVmInstrumentation).
    // v33 proved the proxy sits in its "hijack suspected" state
    // permanently in the sandbox (oriClassType=0); if the splash then
    // advances to MainActivity, the proxy's extracted callbacks were
    // the gate. If nothing changes, the hijack line is closed.
    // One-shot per process, unicom only. Static analysis showed the
    // guest's App.attachBaseContext() calls
    // com.sdk.hijack.api.HijackSDK.initailise(Application) first thing,
    // which (a) sets hidden-API exemptions via reflection, (b) wraps
    // ActivityThread.mInstrumentation in its ProxyInstrumentation,
    // (c) registers ActivityLifecycleCallbacks, then sets its own
    // isRegisterActivityLifecycleCallbacks flag as the last step.
    // Whether all of that actually completes inside the sandbox is
    // invisible from outside (registerContext swallows failures), so
    // at +1/+4/+12s after bind we reflectively read back the end
    // state: the flag, ProxyInstrumentation.instance, the live
    // ActivityThread/Application instrumentation objects, the
    // registered lifecycle callbacks, and each running Activity's
    // mInstrumentation. Log tag "HijackTrace" + a
    // hijacktrace-<pid>.log next to the stackdumps.

    @Volatile private var hijackTraceScheduled = false

    // ==================== v35: stock-init experiment ====================
    // v34 only removed the hijack proxy AFTER bind; it never changed
    // what the proxy wrapped. On a real device ActivityThread's
    // instrumentation is a stock android.app.Instrumentation, so
    // hijack's ProxyInstrumentation classifies its ori as type 1
    // ("not hijacked"). In the sandbox it wraps the engine's
    // NextVmInstrumentation (type 0) and any verdict latched at
    // ProxyInstrumentation.<init> / register time — during
    // App.attachBaseContext, long before any post-bind unwrap — is
    // already fixed. v35 therefore swaps a stock Instrumentation into
    // ActivityThread.mInstrumentation for the whole guest early-init
    // window (attachBaseContext -> ContentProviders ->
    // Application.onCreate) and restores the previous instance
    // afterwards: NextVmInstrumentation is load-bearing for activity
    // creation (newActivity classloader resolution, pre-create
    // injection), so it cannot stay replaced — but the classification
    // moment and everything guest init reads from ActivityThread see
    // the faithful real-device configuration. Validity check built
    // in: at restore time AT is expected to hold hijack's
    // ProxyInstrumentation (registerContext wrapped the stock
    // instance); the sampler then reads oriInstrumentation /
    // oriClassType from ProxyInstrumentation.instance — expect
    // android.app.Instrumentation / 1 (v33/v34 read
    // NextVmInstrumentation / 0). Unicom only.

    @Volatile private var stockInitExperiment = false
    @Volatile private var stockInitNote = "not-run"

    // v36: both discriminating mutations are concluded experiments
    // (v34 unwrap: falsified launch50; v35 stock-init: falsified
    // launch52). v36 is observe-only — a clean baseline plus the
    // breadcrumb — so both default OFF. Flip the const to re-run one.
    private val STOCK_INIT_ENABLED = false
    private val UNWRAP_ENABLED = false

    private fun swapInStockInstrumentation(packageName: String): Instrumentation? {
        if (!STOCK_INIT_ENABLED) {
            stockInitNote = "disabled (v36 observe-only baseline)"
            return null
        }
        if (packageName != "com.sinovatech.unicom.ui") return null
        return try {
            val atCls = Class.forName("android.app.ActivityThread")
            val at = atCls.getMethod("currentActivityThread").invoke(null)
                ?: return null
            val f = fieldOf(atCls, "mInstrumentation")
            val prev = f.get(at) as? Instrumentation
            f.set(at, Instrumentation())
            stockInitExperiment = true
            stockInitNote = "swapped prev=" + (prev?.javaClass?.name ?: "null") +
                " -> android.app.Instrumentation (stock)"
            Timber.tag("HijackTrace").i("v35 stock-init: %s", stockInitNote)
            prev
        } catch (e: Throwable) {
            stockInitNote = "swap FAILED <" + e.javaClass.simpleName + "> " + e.message
            Timber.tag("HijackTrace").w("v35 stock-init: %s", stockInitNote)
            null
        }
    }

    private fun restoreInstrumentation(prev: Instrumentation?) {
        if (prev == null) return
        try {
            val atCls = Class.forName("android.app.ActivityThread")
            val at = atCls.getMethod("currentActivityThread").invoke(null)
                ?: return
            val f = fieldOf(atCls, "mInstrumentation")
            val during = f.get(at)
            f.set(at, prev)
            stockInitNote += "; at restore AT was " +
                (during?.javaClass?.name ?: "null") + ", prev restored"
            Timber.tag("HijackTrace").i(
                "v35 stock-init restore: AT during init=%s",
                during?.javaClass?.name)
        } catch (e: Throwable) {
            stockInitNote += "; restore FAILED <" + e.javaClass.simpleName + ">"
        }
    }

    private fun scheduleHijackTrace(packageName: String) {
        if (packageName != "com.sinovatech.unicom.ui") return
        if (hijackTraceScheduled) return
        hijackTraceScheduled = true
        Thread {
            try {
                val hostApp = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                    as? android.app.Application ?: return@Thread
                val out = java.io.File(
                    hostApp.filesDir, "hijacktrace-${android.os.Process.myPid()}.log"
                )
                val marks = listOf(
                    1_000L to "t+1s", 4_000L to "t+4s",
                    12_000L to "t+12s", 25_000L to "t+25s"
                )
                var elapsed = 0L
                for ((at, label) in marks) {
                    val wait = at - elapsed
                    if (wait > 0) Thread.sleep(wait)
                    elapsed = at
                    HijackProbes.sample(out, label, stockInitNote)
                    HijackProbes.unwrap(out, label, stockInitExperiment, UNWRAP_ENABLED)
                }
            } catch (_: Throwable) {
                // diagnostics must never disturb the guest
            }
        }.apply { isDaemon = true; name = "nv-hijacktrace" }.start()
    }

    /**
     * v138-appctx：把 ActivityThread 记录的「进程 Application」指向 guest。
     *
     * ## 为什么
     *
     * WebView 的两处入口都从它取 context（framework.jar 反汇编实测）：
     * ```
     * android.webkit.WebViewFactory    <- AppGlobals.getInitialApplication()
     * android.webkit.WebViewDelegate   <- ActivityThread.currentApplication()
     * ```
     * 而 `AppGlobals.getInitialApplication()` = `currentActivityThread().mInitialApplication`。
     *
     * 容器里这个字段是**容器的** Application，于是 WebView 拿到的
     * packageName / applicationInfo / assets 全是容器的：
     * - `<guest APK>/assets` 打不开 → 探针 W3 `EMPTY` + `Unable to open asset URL`
     * - 普通 `file://` 拿不到流 → 落到 `guessContentTypeFromStream` 内容嗅探
     *   → HTML 没有魔数 → `text/plain` → 探针 W2 显示源码（Via 主页现象）
     *
     * 改成 guest Application 后，WebView 自己就能按扩展名判 MIME
     * （同一设备 Gaia 内实测 W2/W3 都正常，它也是靠这一层而非包装 client）。
     *
     * ## 时机
     *
     * 必须早于第一次 `WebViewFactory.getProvider()`。本函数在
     * `registerBreadcrumbCallbacks()` **之前**调用 —— 后者里的 `WebViewWarmup`
     * 会主动预热 provider（一旦预热，context 就固化了）。
     *
     * ## 影响面（需要回归验证）
     *
     * framework.jar 里实测 **96 个类**调用 `currentApplication()`/`getInitialApplication()`，
     * 包括 `Environment`、`StorageManager`、`Settings.*`、`ContentResolver`、
     * `AppOpsManager`、`ResourcesManager`、`PackageInstaller`、`MediaPlayer` 等。
     * 它们都会开始看到 guest 的 context —— 这是虚拟化本来的目标，但属于
     * 「一次改动影响很多模块」，必须按 Via 做回归（启动/主页/长按/保存网页）。
     *
     * 开关：`System.setProperty("nextvm.webviewAppContext", "0")` 可关（fork 前设置）。
     */
    /**
     * v142：在 **guest 进程** 里注册本实例的路径重定向规则。
     *
     * 为什么需要：规则目前只在 `VirtualEngine.launch()`（**主进程**）注册，而
     * `NativeHookBridge` 的规则表是**进程级**的（@Singleton，每进程一份）。
     * 探针实测（v141-javaio.log）：guest 进程 `rules=0` —— 所以即使 Java 层 hook
     * 装成功（installed=true），翻译也永远命中不了任何规则。
     *
     * 这是「Java 层重定向装上但无效」的直接原因，也是路径机制里一处真正的架构缺口：
     * 规则的注册点与生效进程不一致。
     */
    private fun registerGuestRedirections(
        application: android.app.Application,
        instanceId: String,
        guestPkg: String
    ) {
        try {
            if (!com.nextvm.core.common.DebugFlags.isOn(
                    com.nextvm.core.common.DebugFlags.GUEST_REDIRECT, true
                )
            ) {
                GuestBreadcrumb.log("v142-redirect skipped by debug.nextvm.guestRedirect=0")
                com.nextvm.core.common.DebugTrace.event("v142", "SKIPPED by flag")
                return
            }
            val appDir = application.filesDir?.parentFile
            if (appDir == null) {
                GuestBreadcrumb.log("v142-redirect skip: filesDir=null")
                com.nextvm.core.common.DebugTrace.fail("v142", "filesDir=null")
                return
            }
            val sdcardDir = java.io.File(appDir, "sdcard")
            nativeHookBridge.setupExternalStorageRedirections(
                instanceId, sdcardDir.absolutePath
            )
            nativeHookBridge.setupAppRedirections(
                guestPkg, instanceId, appDir.absolutePath
            )
            com.nextvm.core.common.DebugTrace.event(
                "v142",
                "registered rules=" + nativeHookBridge.getPathRedirectSnapshot().size +
                    " appDir=" + appDir.absolutePath + " sdcard=" + sdcardDir.absolutePath
            )
            GuestBreadcrumb.log(
                "v142-redirect registered rules=" +
                    nativeHookBridge.getPathRedirectSnapshot().size +
                    " appDir=" + appDir.absolutePath +
                    " sdcard=" + sdcardDir.absolutePath
            )
        } catch (t: Throwable) {
            GuestBreadcrumb.log(
                "v142-redirect FAILED: " + t.javaClass.simpleName + ": " + t.message
            )
        }
    }

    /**
     * v143：把 LoadedApk 的 dataDir 注入为 guest **官方路径**（`/data/user/0/<guest>`）。
     *
     * 目的：让 WebView 认为 `file://` 的文件**不在自己的 dataDir 内**，从而不落进
     * 「dataDir 内文件走特殊 MIME 分支」，改用标准 `FileURLLoader`（按扩展名判 MIME）。
     *
     * 两个前提（都已具备）：
     *   1. `VirtualContext.getApplicationInfo()` 对该 guest 返回**副本**（否则会把本方法
     *      注入的值原地改回沙箱路径 —— v140 之前实测过）；
     *   2. `/data/user/0/<guest>/` → 沙箱 的重定向规则已在本进程注册（v142 的 11 条规则）。
     *
     * 影响面：白名单（先只 `com.sandbox.probe`）+ 开关 `nextvm.webviewDataDirFake`。
     */
    private fun patchFrameworkDataDir(
        application: android.app.Application,
        guestPkg: String
    ) {
        try {
            val enabled = com.nextvm.core.common.DebugFlags.isOn(
                com.nextvm.core.common.DebugFlags.WEBVIEW_DATA_DIR_FAKE, true
            )
            if (!enabled) {
                GuestBreadcrumb.log("v143-datadir disabled by debug.nextvm.webviewDataDirFake=0")
                com.nextvm.core.common.DebugTrace.event("v143", "SKIPPED by flag")
                return
            }
            if (guestPkg != "com.sandbox.probe") {
                GuestBreadcrumb.log("v143-datadir skip (whitelist) pkg=" + guestPkg)
                com.nextvm.core.common.DebugTrace.detail("v143", "skip whitelist pkg=$guestPkg")
                return
            }
            val fake = com.nextvm.core.model.VirtualConstants.syntheticDataDir(guestPkg)
            val loadedApk = try {
                fieldOf(application.javaClass, "mLoadedApk").get(application)
            } catch (_: Throwable) {
                null
            }
            if (loadedApk == null) {
                GuestBreadcrumb.log("v143-datadir FAIL: mLoadedApk=null")
                return
            }
            val parts = StringBuilder()
            try {
                val ai = fieldOf(loadedApk.javaClass, "mApplicationInfo")
                    .get(loadedApk) as? android.content.pm.ApplicationInfo
                if (ai != null) {
                    val before = ai.dataDir
                    ai.dataDir = fake
                    parts.append(" appInfo ").append(before).append(" -> ").append(ai.dataDir)
                }
            } catch (t: Throwable) {
                parts.append(" appInfo:").append(t.javaClass.simpleName)
            }
            try {
                fieldOf(loadedApk.javaClass, "mDataDir").set(loadedApk, fake)
                parts.append(" ; mDataDir -> ").append(fake)
            } catch (t: Throwable) {
                parts.append(" ; mDataDir:").append(t.javaClass.simpleName)
            }
            com.nextvm.core.common.DebugTrace.event("v143", "datadir pkg=" + guestPkg + parts)
            GuestBreadcrumb.log("v143-datadir pkg=" + guestPkg + parts)
        } catch (t: Throwable) {
            GuestBreadcrumb.log(
                "v143-datadir FAILED: " + t.javaClass.simpleName + ": " + t.message
            )
        }
    }

    private fun patchActivityThreadApplication(application: android.app.Application) {
        val enabled = try {
            System.getProperty("nextvm.webviewAppContext", "1") != "0"
        } catch (_: Throwable) {
            true
        }
        if (!enabled) {
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


    /**
     * Framework-native bind (B2): build the guest's app ContextImpl
     * from its framework LoadedApk and let LoadedApk.makeApplication()
     * create + attach the Application — the same construction a real
     * app goes through in ActivityThread.handleBindApplication().
     *
     * The legacy path instead instantiates the Application by
     * reflection and attaches it to a VirtualContext whose underlying
     * LoadedApk is the HOST's (with guest paths patched into it). Any
     * code inspecting the Application's LoadedApk/ContextImpl object
     * graph (packers do) sees the seam. Here mLoadedApk, the resources,
     * the data dirs and the class loader are all genuinely the guest's
     * own framework objects.
     *
     * Providers and onCreate keep the same ordering as the legacy path.
     * Returns null if the framework construction fails; the caller
     * then falls back to the legacy bind.
     */
    private fun bindViaFrameworkLoadedApk(
        instanceId: String,
        packageName: String,
        loadedApk: Any,
        virtualContext: Context,
        providerNames: List<String>,
        providerAuthorities: Map<String, String>,
        providerMetaData: Map<String, Bundle> = emptyMap()
    ): Application? {
        var prevInstr: Instrumentation? = null
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val activityThread = atClass.getMethod("currentActivityThread").invoke(null)
                ?: return null
            val loadedApkClass = Class.forName("android.app.LoadedApk")

            // Identity toward the real system must be the HOST's.
            // The framework LoadedApk carries the guest package in
            // mPackageName, and every ContextImpl created from it
            // derives mOpPackageName + mAttributionSource from that
            // field — so the guest's first Settings/DeviceConfig
            // provider call reaches the system with the guest package
            // as calling identity and dies on
            // AppOpsManager.checkPackage (guest pkg does not belong
            // to the host uid) with a SecurityException. Mirror the
            // LoadedApk-clone path's defense (see ActivityThreadHook):
            // patch mPackageName to the host package BEFORE any
            // ContextImpl is created from this LoadedApk. The
            // LoadedApk stays registered under the guest name in
            // ActivityThread.mPackages, and guest code keeps seeing
            // the guest package through the VirtualContext wrapper.
            val hostPkg = (virtualContext as? android.content.ContextWrapper)
                ?.baseContext?.packageName
                ?: runCatching {
                    atClass.getMethod("currentApplication").invoke(null)
                        as? android.app.Application
                }.getOrNull()?.packageName
            if (hostPkg != null && hostPkg != packageName) {
                runCatching {
                    val pkgField = loadedApkClass.getDeclaredField("mPackageName")
                    pkgField.isAccessible = true
                    pkgField.set(loadedApk, hostPkg)
                    Timber.tag(TAG).i(
                        "Patched framework LoadedApk mPackageName " +
                            "$packageName -> $hostPkg (system-facing identity)"
                    )
                }.onFailure {
                    Timber.tag(TAG).w(it, "Failed to patch LoadedApk mPackageName")
                }
            }

            val contextImplClass = Class.forName("android.app.ContextImpl")
            val createAppContext = contextImplClass.getDeclaredMethod(
                "createAppContext", atClass, loadedApkClass
            )
            createAppContext.isAccessible = true
            val appContext = createAppContext.invoke(null, activityThread, loadedApk) as Context
            val guestResources = (virtualContext as? com.nextvm.core.sandbox.VirtualContext)
                ?.resources
            if (guestResources != null) {
                val before = appContext.resources
                if (guestResources !== before) {
                    resourceManager.injectResourcesIntoContext(appContext, guestResources)
                }
                // Keep the framework registry pointing at the same guest resource
                // object. This covers ContextImpls created after Application attach.
                resourceManager.injectResourcesIntoActivityThread(packageName, guestResources)
            }

            val guestClassLoader = loadedApkClass.getMethod("getClassLoader")
                .invoke(loadedApk) as? ClassLoader ?: return null

            // Same CheckJNI guard as the legacy attach path.
            disableCheckJniForGuest()

            // v35: cover the guest early-init window with a stock
            // Instrumentation (see swapInStockInstrumentation). The
            // window runs from here (makeApplication triggers
            // App.attachBaseContext, where hijack's initailise reads
            // AT.mInstrumentation) through initContentProviders and
            // callApplicationOnCreate below; restored after that.
            prevInstr = swapInStockInstrumentation(packageName)

            GuestBreadcrumb.log("framework makeApplication enter")
            GuestBreadcrumb.log("framework bind identity " + loadedApkWitness(loadedApk))
            secNeoLoadProbe(packageName, loadedApk, guestClassLoader)
            val application = try {
                loadedApkClass.getMethod(
                    "makeApplication",
                    Boolean::class.javaPrimitiveType,
                    Instrumentation::class.java
                ).invoke(loadedApk, false, null) as? Application
            } catch (e: NoSuchMethodException) {
                loadedApkClass.getMethod(
                    "makeApplicationInner",
                    Boolean::class.javaPrimitiveType,
                    Instrumentation::class.java,
                    Boolean::class.javaPrimitiveType
                ).invoke(loadedApk, false, null, false) as? Application
            }
            if (application == null) {
                GuestBreadcrumb.log("framework makeApplication returned null")
                restoreInstrumentation(prevInstr)
                return null
            }
            GuestBreadcrumb.log("framework makeApplication exit app=" +
                application.javaClass.name)

            // LoadedApk.makeApplication() creates and attaches the guest Application.
            // Rebind its actual base Context as well; otherwise Application code can
            // retain a framework-created ContextImpl with host resources.
            if (guestResources != null) {
                application.baseContext?.let { base ->
                    resourceManager.injectResourcesIntoContext(base, guestResources)
                }
            }

            val record = ApplicationRecord(
                instanceId = instanceId,
                packageName = packageName,
                application = application,
                classLoader = guestClassLoader,
                applicationClassName = application.javaClass.name
            )
            applications[instanceId] = record

            if (virtualContext is com.nextvm.core.sandbox.VirtualContext) {
                virtualContext.setGuestApplication(application)
            }
            record.state = ApplicationState.ATTACHED

            if (providerNames.isNotEmpty()) {
                initContentProviders(
                    instanceId = instanceId,
                    providers = providerNames,
                    authorities = providerAuthorities,
                    classLoader = guestClassLoader,
                    virtualContext = appContext,
                    application = application,
                    providerMetaData = providerMetaData
                )
                record.state = ApplicationState.PROVIDERS_INSTALLED
            }

            callApplicationOnCreate(application)
            PluginProbes.probePluginLoaderState(application, record)
            record.state = ApplicationState.RUNNING
            Timber.tag(TAG).i(
                "Framework LoadedApk bind complete for $packageName " +
                    "(app=${application.javaClass.name}, base=${application.baseContext?.javaClass?.simpleName})"
            )
            restoreInstrumentation(prevInstr)
            application
        } catch (e: Exception) {
            restoreInstrumentation(prevInstr)
            GuestBreadcrumb.logThrowable("framework bind FAILED", e)
            Timber.tag(TAG).w(e, "Framework LoadedApk bind failed for $packageName, falling back to legacy bind")
            applications.remove(instanceId)
            null
        }
    }

    /**
     * Create an Application instance using the guest app's ClassLoader.
     *
     * Mirrors the logic in:
     *   ActivityThread.java → LoadedApk.makeApplicationInner()
     *   AppComponentFactory.instantiateApplication() (Android 9+)
     */
    fun createApplication(
        instanceId: String,
        packageName: String,
        applicationClassName: String?,
        classLoader: ClassLoader,
        virtualContext: Context,
        appInfo: ApplicationInfo? = null
    ): Application? {
        val className = applicationClassName ?: "android.app.Application"

        Timber.tag(TAG).d("Creating Application: $className for $packageName")

        return try {
            // Try AppComponentFactory on Android 9+
            if (AndroidCompat.isAtLeastP) {
                val factoryApp = createViaAppComponentFactory(className, classLoader)
                if (factoryApp != null) {
                    Timber.tag(TAG).d("Application created via AppComponentFactory")
                    return factoryApp
                }
            }

            // Standard reflection path
            val appClass = if (className == "android.app.Application") {
                Application::class.java
            } else {
                classLoader.loadClass(className)
            }

            val app = appClass.getDeclaredConstructor().let { ctor ->
                ctor.isAccessible = true
                ctor.newInstance()
            } as Application

            Timber.tag(TAG).d("Application created via reflection: $className")
            app

        } catch (e: ClassNotFoundException) {
            Timber.tag(TAG).w("Application class not found: $className, falling back to base Application")
            try {
                Application::class.java.getDeclaredConstructor().newInstance()
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Failed to create fallback Application")
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create Application: $className")
            null
        }
    }

    /**
     * Try to create Application via AppComponentFactory (Android 9+).
     *
     * Real Android uses AppComponentFactory to allow apps to customize
     * instantiation of Application, Activity, Service, etc.
     */
    private fun createViaAppComponentFactory(
        className: String,
        classLoader: ClassLoader
    ): Application? {
        return runSafe(TAG) {
            val factoryClass = Class.forName("android.app.AppComponentFactory")
            val factory = factoryClass.getDeclaredConstructor().newInstance()

            val instantiateMethod = findMethod(
                factoryClass,
                "instantiateApplication",
                arrayOf(ClassLoader::class.java, String::class.java)
            )
            instantiateMethod?.isAccessible = true
            instantiateMethod?.invoke(factory, classLoader, className) as? Application
        }
    }

    /**
     * Attach base context to Application.
     *
     * This calls Application.attach(context), which internally calls:
     *   - ContextWrapper.attachBaseContext(context)
     *   - Application.attachBaseContext(context) [overridable by app]
     *
     * We use the hidden Application.attach() method rather than just
     * attachBaseContext() because attach() also sets up internal fields
     * like mLoadedApk.
     */
    private fun attachBaseContext(application: Application, virtualContext: Context) {
        // CRITICAL: Before Application.attach(), inject guest app paths into the
        // LoadedApk that the ContextImpl holds. Application.attach() does:
        //   mLoadedApk = ContextImpl.getImpl(context).mPackageInfo
        // If we don't patch this, the LoadedApk has HOST paths (nativeLibraryDir etc.),
        // and when the guest app's attachBaseContext() calls System.load(), the runtime
        // looks up nativeLibraryDir from the LoadedApk's ApplicationInfo and fails.
        injectGuestPathsIntoLoadedApk(virtualContext)

        // FIX 1: Disable CheckJNI before guest Application.attach().
        // Jiagu/360-protected apps use reflection to call Runtime.load0(Class, String)
        // directly. In the virtual environment, the 'Class caller' argument becomes null
        // because the call originates from code loaded via custom ClassLoader/reflection.
        // CheckJNI catches the null caller's protection domains array (jarray == NULL)
        // in GetObjectArrayElement and triggers SIGABRT. Disabling CheckJNI prevents
        // this hard-abort — the null caller is harmless for actual library loading.
        disableCheckJniForGuest()

        // FIX 2: Patch Jiagu/packer stub Application fields before attach.
        // Packers like Jiagu store native lib extraction paths in static/instance String
        // fields. In the virtual environment these may be null, causing load failures.
        patchJiaguStubBeforeAttach(application, virtualContext)

        // FIX 3: Install Runtime.load0 caller fix to handle null caller class.
        installRuntimeLoadCallerFix(application, virtualContext)

        try {
            // Try the full attach() method first (mirrors real Android flow)
            val attachMethod = findMethod(
                Application::class.java,
                "attach",
                arrayOf(Context::class.java)
            )
            if (attachMethod != null) {
                attachMethod.isAccessible = true
                attachMethod.invoke(application, virtualContext)
                Timber.tag(TAG).d("Used Application.attach(context)")
                return
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Application.attach() failed, using attachBaseContext fallback")
        }

        // Fallback: directly call attachBaseContext via reflection
        try {
            val attachBaseMethod = findMethod(
                android.content.ContextWrapper::class.java,
                "attachBaseContext",
                arrayOf(Context::class.java)
            )
            attachBaseMethod?.isAccessible = true
            attachBaseMethod?.invoke(application, virtualContext)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "attachBaseContext fallback also failed")
            // Last resort: set mBase field directly
            try {
                val mBaseField = findField(android.content.ContextWrapper::class.java, "mBase")
                mBaseField?.isAccessible = true
                mBaseField?.set(application, virtualContext)
                Timber.tag(TAG).d("Set mBase field directly as last resort")
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "All attach methods failed")
                throw RuntimeException("Cannot attach context to Application", e2)
            }
        }
    }

    /**
     * FIX 1: Disable CheckJNI in the current (guest) process.
     *
     * NOTE: VMRuntime.setCheckJni(boolean) does NOT exist on Android API 35+.
     * This method is kept as a best-effort attempt for older API levels.
     * The real fix for null-caller SIGABRT is the native Runtime.nativeLoad hook
     * installed by NativeHookBridge.hookRuntimeNativeLoad().
     */
    private fun disableCheckJniForGuest() {
        if (Build.VERSION.SDK_INT >= 35) {
            Timber.tag(TAG).d("Skipping disableCheckJniForGuest — VMRuntime.setCheckJni not available on API 35+")
            return
        }
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntimeMethod.isAccessible = true
            val vmRuntime = getRuntimeMethod.invoke(null)

            val setCheckJniMethod = vmRuntimeClass.getDeclaredMethod(
                "setCheckJni", Boolean::class.javaPrimitiveType
            )
            setCheckJniMethod.isAccessible = true
            setCheckJniMethod.invoke(vmRuntime, false)
            Timber.tag(TAG).i("CheckJNI disabled for guest process")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to disable CheckJNI: ${e.message} (non-fatal — native hook handles this)")
        }
    }

    /**
     * FIX 2: Patch Jiagu/packer stub Application fields before attach.
     *
     * Packers like Jiagu (com.stub.StubApp) store native library extraction paths
     * and data directory paths in static/instance String fields. In the virtual
     * environment, these fields may be null because the packer's initialization
     * code hasn't run yet, or the paths it computed are wrong. This causes the
     * packer's attachBaseContext() to pass null/invalid paths to System.load().
     *
     * We scan the stub Application class for null String fields and set them to
     * the guest app's nativeLibraryDir, which is the most commonly needed path.
     */
    private fun patchJiaguStubBeforeAttach(application: Application, virtualContext: Context) {
        try {
            val appClass = application.javaClass
            val className = appClass.name

            // Only patch if it looks like a packer stub class
            if (!className.contains("stub", ignoreCase = true) &&
                !className.contains("jiagu", ignoreCase = true) &&
                !className.contains("wrapper", ignoreCase = true) &&
                !className.contains("Shell", ignoreCase = true) &&
                !className.contains("Qihoo", ignoreCase = true)
            ) {
                return
            }

            Timber.tag(TAG).d("Detected packer stub class: $className — patching fields")

            val nativeLibDir = virtualContext.applicationInfo?.nativeLibraryDir ?: return
            val dataDir = virtualContext.applicationInfo?.dataDir ?: return
            val sourceDir = virtualContext.applicationInfo?.sourceDir ?: return

            // Patch instance fields
            for (field in appClass.declaredFields) {
                try {
                    field.isAccessible = true
                    if (field.type == String::class.java) {
                        val value = field.get(application) as? String
                        if (value == null) {
                            // Null String field — likely a path that needs to be set
                            field.set(application, nativeLibDir)
                            Timber.tag(TAG).d("  Patched null field: ${field.name} → $nativeLibDir")
                        } else if (value.contains("/data/data/") &&
                            !value.contains("com.nextvm.app")
                        ) {
                            // Contains a real package data path — redirect to virtual sandbox
                            val redirected = value.replace(
                                Regex("/data/(data|user/0)/[^/]+"),
                                dataDir
                            )
                            if (redirected != value) {
                                field.set(application, redirected)
                                Timber.tag(TAG).d("  Redirected field: ${field.name} → $redirected")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip inaccessible fields
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "patchJiaguStubBeforeAttach failed (non-fatal)")
        }
    }

    /**
     * FIX 3: Install a caller-class fix for Runtime.load0 / Runtime.nativeLoad.
     *
     * When Jiagu calls Runtime.load0(Class<?> callerClass, String filename) via
     * reflection, callerClass can be null in the virtual environment. The ART runtime's
     * nativeLoad implementation uses the caller's ClassLoader to determine the linker
     * namespace, and a null caller causes CheckJNI to abort when accessing the caller's
     * protection domains array.
     *
     * This method:
     * 1. Sets Thread contextClassLoader to the guest's ClassLoader
     * 2. Ensures the guest Application class has a valid ProtectionDomain with
     *    CodeSource pointing to the guest APK path
     * 3. The actual JNI-level fix is in NativeHookBridge.hookRuntimeNativeLoad()
     */
    private fun installRuntimeLoadCallerFix(application: Application, virtualContext: Context) {
        try {
            // Ensure the current thread's contextClassLoader is the guest's ClassLoader.
            val guestClassLoader = application.javaClass.classLoader
            if (guestClassLoader != null) {
                Thread.currentThread().contextClassLoader = guestClassLoader
                Timber.tag(TAG).d("Set thread contextClassLoader to guest ClassLoader: ${guestClassLoader.javaClass.name}")
            }

            // Ensure the guest's Application class has a valid ProtectionDomain
            // with a CodeSource pointing to the guest APK. This prevents NPE/SIGABRT
            // when ART/CheckJNI tries to access the caller's ProtectionDomain array.
            try {
                val pd = application.javaClass.protectionDomain
                if (pd == null || pd.codeSource == null) {
                    // Inject a valid ProtectionDomain with CodeSource pointing to APK
                    val apkPath = virtualContext.applicationInfo?.sourceDir
                    if (apkPath != null) {
                        val codeSource = java.security.CodeSource(
                            java.io.File(apkPath).toURI().toURL(),
                            arrayOfNulls<java.security.cert.Certificate>(0)
                        )
                        val newPd = java.security.ProtectionDomain(codeSource, null)
                        // Set via reflection on the Class object
                        val pdField = findField(Class::class.java, "protectionDomain")
                            ?: findField(Class::class.java, "pd")
                        if (pdField != null) {
                            pdField.isAccessible = true
                            pdField.set(application.javaClass, newPd)
                            Timber.tag(TAG).d("Injected ProtectionDomain with CodeSource: $apkPath")
                        }
                    }
                } else {
                    Timber.tag(TAG).d("Guest Application ProtectionDomain already valid: $pd")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("ProtectionDomain fix: ${e.message} (non-fatal)")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "installRuntimeLoadCallerFix failed (non-fatal)")
        }
    }

    /**
     * Inject guest app paths into the LoadedApk's ApplicationInfo.
     *
     * When Application.attach(context) is called, it sets:
     *   mLoadedApk = ContextImpl.getImpl(context).mPackageInfo
     *
     * Since VirtualContext wraps the HOST ContextImpl, mPackageInfo is the HOST's
     * LoadedApk with HOST paths. We patch its mApplicationInfo to have guest paths
     * so that Runtime.nativeLoad() and other framework code finds the right
     * nativeLibraryDir, sourceDir, and dataDir.
     */
    private fun injectGuestPathsIntoLoadedApk(virtualContext: Context) {
        try {
            // Walk the ContextWrapper chain to find the underlying ContextImpl
            var ctx: Context = virtualContext
            while (ctx is android.content.ContextWrapper) {
                val base = ctx.baseContext ?: break
                ctx = base
            }

            // Get mPackageInfo (LoadedApk) from ContextImpl
            val mPackageInfoField = findField(ctx::class.java, "mPackageInfo")
            mPackageInfoField?.isAccessible = true
            val loadedApk = mPackageInfoField?.get(ctx) ?: run {
                Timber.tag(TAG).w("Could not find mPackageInfo on ContextImpl")
                return
            }

            // Get mApplicationInfo from LoadedApk
            val appInfoField = findField(loadedApk::class.java, "mApplicationInfo")
            appInfoField?.isAccessible = true
            val loadedApkAppInfo = appInfoField?.get(loadedApk) as? ApplicationInfo ?: run {
                Timber.tag(TAG).w("Could not find mApplicationInfo on LoadedApk")
                return
            }

            // Read guest paths from VirtualContext.getApplicationInfo()
            val guestAppInfo = virtualContext.applicationInfo

            // Patch the LoadedApk's ApplicationInfo with guest paths
            loadedApkAppInfo.nativeLibraryDir = guestAppInfo.nativeLibraryDir
            loadedApkAppInfo.sourceDir = guestAppInfo.sourceDir
            loadedApkAppInfo.publicSourceDir = guestAppInfo.sourceDir
            loadedApkAppInfo.dataDir = guestAppInfo.dataDir

            // CRITICAL: Also patch LoadedApk.mLibDir which is used by
            // Runtime.nativeLoad() to construct the library search path.
            // Without this, the linker namespace has wrong paths and .so loading fails.
            val mLibDirField = findField(loadedApk::class.java, "mLibDir")
            if (mLibDirField != null) {
                mLibDirField.isAccessible = true
                mLibDirField.set(loadedApk, guestAppInfo.nativeLibraryDir)
                Timber.tag(TAG).d("Injected mLibDir into LoadedApk: ${guestAppInfo.nativeLibraryDir}")
            }

            Timber.tag(TAG).d(
                "Injected guest paths into LoadedApk: nativeLibDir=${loadedApkAppInfo.nativeLibraryDir}"
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to inject guest paths into LoadedApk (non-fatal)")
        }
    }

    /**
     * Patch LoadedApk.mApplication to point to the guest Application.
     *
     * Without this, ContextImpl.getApplicationContext() returns the HOST Application
     * (set when the process started). Framework code that calls getApplicationContext()
     * directly on ContextImpl (bypassing our ContextWrapper) would get NextVmApplication
     * instead of the guest Application — causing Hilt/Dagger cast failures and GMS
     * identity mismatches.
     */
    private fun patchLoadedApkApplication(virtualContext: Context, guestApplication: Application) {
        try {
            // Walk the ContextWrapper chain to find the underlying ContextImpl
            var ctx: Context = virtualContext
            while (ctx is android.content.ContextWrapper) {
                val base = ctx.baseContext ?: break
                ctx = base
            }

            // Get mPackageInfo (LoadedApk) from ContextImpl
            val mPackageInfoField = findField(ctx::class.java, "mPackageInfo")
            mPackageInfoField?.isAccessible = true
            val loadedApk = mPackageInfoField?.get(ctx) ?: run {
                Timber.tag(TAG).w("Could not find mPackageInfo for mApplication patch")
                return
            }

            // Patch LoadedApk.mApplication
            val mApplicationField = findField(loadedApk::class.java, "mApplication")
            if (mApplicationField != null) {
                mApplicationField.isAccessible = true
                mApplicationField.set(loadedApk, guestApplication)
                Timber.tag(TAG).d("Patched LoadedApk.mApplication to guest Application")
            } else {
                Timber.tag(TAG).w("Could not find mApplication field on LoadedApk")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to patch LoadedApk.mApplication (non-fatal)")
        }
    }

    /**
     * Initialize ContentProviders before Application.onCreate().
     *
     * This mirrors ActivityThread.installContentProviders() which is called
     * BEFORE Instrumentation.callApplicationOnCreate().
     *
     * Critical providers that depend on this ordering:
     * - androidx.startup.InitializationProvider (AndroidX Startup)
     * - androidx.work.impl.WorkManagerInitializer (WorkManager)
     * - com.google.firebase.provider.FirebaseInitProvider (Firebase)
     * - androidx.lifecycle.ProcessLifecycleOwnerInitializer (Lifecycle)
     */
    fun initContentProviders(
        instanceId: String,
        providers: List<String>,
        authorities: Map<String, String>,
        classLoader: ClassLoader,
        virtualContext: Context,
        application: Application,
        providerMetaData: Map<String, Bundle> = emptyMap()
    ) {
        Timber.tag(TAG).d("Installing ${providers.size} ContentProviders for $instanceId")
        GuestBreadcrumb.log("providers begin n=" + providers.size +
            " list=" + providers.joinToString(","))

        val record = applications[instanceId]

        for (providerClassName in providers) {
            // Skip ShizukuProvider — requires INTERACT_ACROSS_USERS_FULL which
            // is not available in the virtual environment. Attempting to install
            // it causes SecurityException in ContentResolver.call().
            val providerAuthority = authorities[providerClassName] ?: ""
            if (providerClassName.contains("shizuku", ignoreCase = true) ||
                providerClassName.contains("rikka.shizuku", ignoreCase = true) ||
                providerAuthority.contains("shizuku", ignoreCase = true)
            ) {
                Timber.tag(TAG).d("Skipping ShizukuProvider: $providerClassName — cross-user permission unavailable")
                continue
            }

            GuestBreadcrumb.log("provider attachInfo enter $providerClassName")
            try {
                val providerClass = classLoader.loadClass(providerClassName)
                val provider = providerClass.getDeclaredConstructor().let { ctor ->
                    ctor.isAccessible = true
                    ctor.newInstance()
                } as ContentProvider

                // Build ProviderInfo for attachInfo()
                val providerInfo = ProviderInfo().apply {
                    name = providerClassName
                    authority = providerAuthority.ifEmpty {
                        "${virtualContext.packageName}.${providerClassName.substringAfterLast(".")}"
                    }
                    applicationInfo = virtualContext.applicationInfo
                    exported = false
                    enabled = true
                    grantUriPermissions = true
                    // v39: manifest meta-data parsed from the guest APK.
                    // Providers reading their meta-data in attachInfo /
                    // onCreate must find it here, as on a real install.
                    metaData = providerMetaData[providerClassName]
                }

                // Call ContentProvider.attachInfo(context, info)
                // This calls both attachInfo internally and then onCreate()
                // v39: pass the guest Application, as AOSP's
                // installContentProviders does — NOT a bare ContextImpl.
                // Providers that cast getContext() to Application (e.g.
                // SoulPermission's InitProvider, which registers the
                // activity-lifecycle tracker behind getTopActivity())
                // died on the cast, silently disabling themselves.
                provider.attachInfo(application, providerInfo)

                record?.contentProviders?.add(provider)
                ActivityManagerProxy.activeInstance?.registerVirtualProvider(
                    providerInfo.authority,
                    provider
                )
                Timber.tag(TAG).d("  Provider installed: $providerClassName")
                GuestBreadcrumb.log(
                    "provider ok $providerClassName authority=${providerInfo.authority}"
                )

            } catch (e: ClassNotFoundException) {
                GuestBreadcrumb.logThrowable(
                    "provider NOT FOUND, skipped: $providerClassName", e)
                Timber.tag(TAG).w("ContentProvider class not found: $providerClassName (skipping)")
            } catch (e: SecurityException) {
                GuestBreadcrumb.logThrowable(
                    "provider SecurityException SWALLOWED, skipped: $providerClassName", e)
                Timber.tag(TAG).w("SecurityException skipping provider $providerClassName: ${e.message}")
            } catch (e: IllegalStateException) {
                GuestBreadcrumb.logThrowable(
                    "provider IllegalStateException SWALLOWED, skipped: $providerClassName", e)
                Timber.tag(TAG).w("IllegalStateException skipping provider $providerClassName: ${e.message}")
            } catch (e: Exception) {
                GuestBreadcrumb.logThrowable(
                    "provider FAILED (bind continues): $providerClassName", e)
                Timber.tag(TAG).e(e, "Failed to install ContentProvider: $providerClassName")
                // Don't fail the whole app — some providers are optional
            }
        }
    }

    /**
     * Call Application.onCreate() using Instrumentation pattern.
     *
     * Mirrors:
     *   Instrumentation.callApplicationOnCreate(app) in ActivityThread.handleBindApplication()
     */


    @Volatile private var loginProbeLastActivity: android.app.Activity? = null
    @Volatile private var loginProbeTimerStarted = false

    // v130-watchdog: main-looper stall sampler state.
    private var watchdogStarted = false
    @Volatile private var watchdogLastBeat = 0L
    @Volatile private var watchdogStallReported = false
    private var watchdogStallCount = 0
    private var watchdogStallStart = 0L

    /**
     * v130-watchdog: sample the main thread's stack whenever the
     * main looper stops responding. A heartbeat runnable on the
     * main looper refreshes watchdogLastBeat every 1s; a daemon
     * thread checks it every 1s and, when main has not beaten for
     * >3s, dumps main's stack into the breadcrumb file (once per
     * stall, plus a RECOVERED line when main resumes). Read-only
     * w.r.t. the guest: no behaviour change, bounded output (max
     * 20 stalls per process). Started once per guest process.
     * Motivation: launch157 ANRs persist on v129 but the ANR-time
     * stack is unobtainable through system channels on this device
     * (DropBox returns no new entries, /data/anr unreadable), so
     * the engine must capture the stall itself, in its own file.
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        try {
            val mainLooper = android.os.Looper.getMainLooper() ?: return
            val mainHandler = android.os.Handler(mainLooper)
            watchdogLastBeat = android.os.SystemClock.uptimeMillis()
            val beat = object : Runnable {
                override fun run() {
                    watchdogLastBeat =
                        android.os.SystemClock.uptimeMillis()
                    try {
                        mainHandler.postDelayed(this, 1000L)
                    } catch (_: Throwable) {
                    }
                }
            }
            mainHandler.post(beat)
            val t = Thread({
                while (true) {
                    try {
                        Thread.sleep(1000L)
                    } catch (_: Throwable) {
                    }
                    try {
                        val now =
                            android.os.SystemClock.uptimeMillis()
                        val stuckFor = now - watchdogLastBeat
                        if (stuckFor > 3000L) {
                            if (!watchdogStallReported) {
                                watchdogStallReported = true
                                watchdogStallStart = now - stuckFor
                                if (watchdogStallCount < 20) {
                                    watchdogStallCount++
                                    val frames = try {
                                        mainLooper.thread?.stackTrace
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    val top = frames?.take(25)
                                        ?.joinToString(" | ") {
                                            it.className + "." +
                                                it.methodName
                                        } ?: "<no stack>"
                                    GuestBreadcrumb.log(
                                        "v130-watchdog STUCK " +
                                            stuckFor + "ms main=" + top
                                    )
                                }
                            }
                        } else {
                            if (watchdogStallReported) {
                                watchdogStallReported = false
                                GuestBreadcrumb.log(
                                    "v130-watchdog RECOVERED after " +
                                        (now - watchdogStallStart) + "ms"
                                )
                            }
                        }
                    } catch (_: Throwable) {
                        // watchdog never dies on its own errors
                    }
                }
            }, "v130-watchdog")
            t.isDaemon = true
            t.start()
            GuestBreadcrumb.log("v130-watchdog started")
        } catch (_: Throwable) {
            // probe only; never break bind
        }
    }



    private fun callApplicationOnCreate(application: Application) {
        GuestBreadcrumb.log("Application.onCreate enter " + application.javaClass.name)
        try {
            // Use Instrumentation if available (more accurate mirroring)
            val instrumentation = Instrumentation()
            instrumentation.callApplicationOnCreate(application)
            Timber.tag(TAG).d("Application.onCreate() called via Instrumentation")
            GuestBreadcrumb.log("Application.onCreate exit ok")
        } catch (e: RuntimeException) {
            // Check if this is a ShizukuProvider SecurityException — swallow it.
            // ZArchiver calls ShizukuProvider.a() in onCreate() which triggers
            // ContentResolver.call() to rikka.shizuku.ShizukuProvider. The system
            // throws SecurityException because INTERACT_ACROSS_USERS_FULL is missing.
            // ZArchiver can run without Shizuku root access.
            val cause = e.cause?.cause ?: e.cause ?: e
            if (cause is SecurityException &&
                (cause.message?.contains("ShizukuProvider", ignoreCase = true) == true ||
                 cause.message?.contains("INTERACT_ACROSS_USERS", ignoreCase = true) == true ||
                 cause.message?.contains("shizuku", ignoreCase = true) == true)
            ) {
                GuestBreadcrumb.logThrowable(
                    "Application.onCreate SecurityException SWALLOWED (shizuku rule)", e)
                Timber.tag(TAG).w("Shizuku SecurityException in onCreate swallowed — continuing without Shizuku")
                return
            }
            // Not Shizuku-related — try direct call fallback
            Timber.tag(TAG).w(e, "Instrumentation.callApplicationOnCreate() failed, trying direct")
            try {
                application.onCreate()
            } catch (e2: RuntimeException) {
                val cause2 = e2.cause?.cause ?: e2.cause ?: e2
                if (cause2 is SecurityException &&
                    (cause2.message?.contains("ShizukuProvider", ignoreCase = true) == true ||
                     cause2.message?.contains("INTERACT_ACROSS_USERS", ignoreCase = true) == true ||
                     cause2.message?.contains("shizuku", ignoreCase = true) == true)
                ) {
                    GuestBreadcrumb.logThrowable(
                        "Application.onCreate (direct) SecurityException SWALLOWED (shizuku rule)", e2)
                    Timber.tag(TAG).w("Shizuku SecurityException in direct onCreate swallowed")
                    return
                }
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            }
        } catch (e: Exception) {
            GuestBreadcrumb.logThrowable(
                "Application.onCreate via Instrumentation FAILED, calling directly", e)
            Timber.tag(TAG).w(e, "Instrumentation.callApplicationOnCreate() failed, calling directly")
            try {
                application.onCreate()
                GuestBreadcrumb.log("Application.onCreate exit ok (direct)")
            } catch (e2: Exception) {
                GuestBreadcrumb.logThrowable("Application.onCreate direct call FAILED", e2)
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            }
        }
    }

    /**
     * Build an ApplicationInfo suitable for the guest app.
     */
    private fun buildApplicationInfo(
        packageName: String,
        apkPath: String,
        virtualContext: Context
    ): ApplicationInfo {
        return ApplicationInfo().apply {
            this.packageName = packageName
            this.sourceDir = apkPath
            this.publicSourceDir = apkPath
            this.dataDir = virtualContext.dataDir.absolutePath
            this.nativeLibraryDir = virtualContext.applicationInfo.nativeLibraryDir
            this.processName = packageName
            this.enabled = true
            this.flags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA
            // longVersionCode defaults to 0 on new ApplicationInfo

            // Populate metaData from the guest APK so SDKs (AppLovin, Firebase,
            // Facebook, CronetProvider, etc.) can read their <meta-data> tags.
            // Also inject essential GMS meta-data if not already present.
            try {
                val pm = virtualContext.packageManager
                val pkgInfo = pm.getPackageArchiveInfo(
                    apkPath,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                pkgInfo?.applicationInfo?.let { it2 ->
                    it2.sourceDir = apkPath
                    it2.publicSourceDir = apkPath
                }
                val bundle = pkgInfo?.applicationInfo?.metaData ?: android.os.Bundle()

                // Inject GMS version meta-data if the guest APK doesn't already have it.
                // Many SDKs (AppLovin, Firebase Analytics, CronetProvider) crash without this.
                if (!bundle.containsKey("com.google.android.gms.version")) {
                    bundle.putInt("com.google.android.gms.version", 243431006)
                }

                this.metaData = bundle
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse metaData from APK: $apkPath")
                // At minimum provide GMS version to avoid common SDK crashes
                this.metaData = android.os.Bundle().apply {
                    putInt("com.google.android.gms.version", 243431006)
                }
            }

            // v135 path fidelity (CM-gated experiment): report
            // stock-install paths. I/O on these is redirected by
            // v135-pathfidelity rules registered at guest bind.
            if (com.nextvm.core.model.VirtualConstants.isPathFidelityGuest(packageName)) {
                this.dataDir = com.nextvm.core.model.VirtualConstants.syntheticDataDir(packageName)
                this.nativeLibraryDir = com.nextvm.core.model.VirtualConstants.syntheticLibDir(packageName)
                val synthApk = com.nextvm.core.model.VirtualConstants.syntheticApkPath(packageName)
                this.sourceDir = synthApk
                this.publicSourceDir = synthApk
            }
        }
    }

    /**
     * Terminate a guest app's Application instance.
     * Calls Application.onTerminate() and cleans up all ContentProviders.
     */
    fun terminateApplication(instanceId: String) {
        val record = applications[instanceId] ?: run {
            Timber.tag(TAG).w("No Application record found for $instanceId")
            return
        }

        Timber.tag(TAG).i("Terminating application for ${record.packageName} ($instanceId)")

        try {
            // Shut down ContentProviders first
            for (provider in record.contentProviders) {
                try {
                    provider.shutdown()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error shutting down provider: ${provider.javaClass.name}")
                }
            }
            record.contentProviders.clear()

            // Call Application.onTerminate()
            try {
                record.application.onTerminate()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Application.onTerminate() threw exception")
            }

            record.state = ApplicationState.TERMINATED
            Timber.tag(TAG).i("Application terminated for ${record.packageName}")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error terminating application for $instanceId")
        } finally {
            applications.remove(instanceId)
        }
    }

    /**
     * Propagate configuration change to a running Application.
     */
    fun onConfigurationChanged(instanceId: String, newConfig: Configuration) {
        val record = applications[instanceId] ?: return
        if (record.state != ApplicationState.RUNNING) return

        try {
            record.application.onConfigurationChanged(newConfig)
            Timber.tag(TAG).d("Configuration changed for ${record.packageName}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error dispatching config change to ${record.packageName}")
        }
    }

    /**
     * Propagate low memory signal to a running Application.
     */
    fun onLowMemory(instanceId: String) {
        val record = applications[instanceId] ?: return
        if (record.state != ApplicationState.RUNNING) return

        try {
            record.application.onLowMemory()
            Timber.tag(TAG).d("onLowMemory dispatched to ${record.packageName}")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error dispatching onLowMemory")
        }
    }

    /**
     * Propagate trim memory signal to a running Application.
     */
    fun onTrimMemory(instanceId: String, level: Int) {
        val record = applications[instanceId] ?: return
        if (record.state != ApplicationState.RUNNING) return

        try {
            record.application.onTrimMemory(level)
            Timber.tag(TAG).d("onTrimMemory($level) dispatched to ${record.packageName}")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error dispatching onTrimMemory")
        }
    }

    // ---------- Query Methods ----------

    /** Get the Application instance for a guest app */
    fun getApplication(instanceId: String): Application? =
        applications[instanceId]?.application

    /** Get the ApplicationRecord for a guest app */
    fun getApplicationRecord(instanceId: String): ApplicationRecord? =
        applications[instanceId]

    /** Check if an application is bound and running */
    fun isApplicationRunning(instanceId: String): Boolean {
        val record = applications[instanceId] ?: return false
        return record.state == ApplicationState.RUNNING
    }

    /** Get all running application instance IDs */
    fun getRunningInstanceIds(): Set<String> {
        return applications.entries
            .filter { it.value.state == ApplicationState.RUNNING }
            .map { it.key }
            .toSet()
    }

    /** Get the number of running applications */
    fun getRunningCount(): Int =
        applications.values.count { it.state == ApplicationState.RUNNING }

    /** Terminate all running applications */
    fun terminateAll() {
        Timber.tag(TAG).i("Terminating all ${applications.size} applications")
        val instanceIds = applications.keys.toList()
        for (id in instanceIds) {
            terminateApplication(id)
        }
    }
}

/**
 * v52 diagnostic: compact identity summary of a framework LoadedApk
 * (identity hash + package fields + whether an Application is
 * already attached). Never throws. The LoadedApk fields read here
 * are all declared on android.app.LoadedApk itself.
 */
private fun loadedApkWitness(loadedApk: Any): String {
    fun field(name: String): Any? = try {
        val f = loadedApk.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.get(loadedApk)
    } catch (e: Exception) {
        null
    }
    val ai = field("mApplicationInfo") as? android.content.pm.ApplicationInfo
    val app = field("mApplication")
    val appDesc = if (app == null) {
        "null"
    } else {
        app.javaClass.name + "#" + System.identityHashCode(app)
    }
    return "loadedApk=#" + System.identityHashCode(loadedApk) +
        " mPackageName=" + field("mPackageName") +
        " ai.packageName=" + ai?.packageName +
        " ai.sourceDir=" + ai?.sourceDir +
        " mApplication=" + appDesc
}

/**
 * v53 diagnostic probe: a SecNeo guest's AW.attachBaseContext calls
 * System.loadLibrary("DexHelper") inside H.a(ApplicationInfo), which
 * swallows any Throwable — the failure only surfaces later as an
 * UnsatisfiedLinkError from H.is(). This probe runs just before the
 * framework LoadedApk.makeApplication() and records what the packer
 * hides: the guest class loader's full DexPathList (incl. native
 * library directories), whether that loader can resolve libDexHelper
 * at all, and — for the China Mobile guest only this round — the
 * verbatim result of preloading the library against the guest class
 * loader via Runtime.loadLibrary0. Never throws.
 */
private fun secNeoLoadProbe(
    packageName: String,
    loadedApk: Any,
    guestClassLoader: ClassLoader
) {
    try {
        GuestBreadcrumb.log("v53 probe cl=" + guestClassLoader.toString())
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v53 probe cl toString failed", t)
    }
    try {
        val bdl = guestClassLoader as? dalvik.system.BaseDexClassLoader
        val found = bdl?.findLibrary("DexHelper")
        GuestBreadcrumb.log("v53 probe findLibrary(DexHelper)=" + (found ?: "null"))
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v53 probe findLibrary failed", t)
    }
    if (packageName != "com.greenpoint.android.mc10086.activity") return
    val anchor = try {
        Class.forName("com.secneo.apkwrapper.H", false, guestClassLoader)
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v55 probe anchor load failed", t)
        return
    }
    GuestBreadcrumb.log(
        "v55 probe anchor clId=" +
            System.identityHashCode(anchor.classLoader) +
            " guestClId=" + System.identityHashCode(guestClassLoader) +
            " sameCl=" + (anchor.classLoader === guestClassLoader)
    )
    fun testIs(tag: String) {
        try {
            val m = anchor.getDeclaredMethod(
                "is", Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            val r = m.invoke(null, 0)
            GuestBreadcrumb.log("v55 probe H.is(0) $tag -> $r")
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)
                ?.cause ?: t
            GuestBreadcrumb.logThrowable("v55 probe H.is(0) $tag FAILED", cause)
        }
    }
    // v56: discriminator. Snapshot /proc/self/maps, then run H.a with
    // the thread context classloader pointed at the guest CL, so the
    // stub->payload chain sees a guest-flavoured context when it goes
    // looking for a classloader. Maps snapshots before/after tell us
    // whether the ~1.2MB payload image ever lands in memory.
    fun mapsSnap(): Triple<Int, Int, Set<String>> {
        val ws = Regex("\\s+")
        val lines = java.io.File("/proc/self/maps").readLines()
        var anonExec = 0
        val paths = HashSet<String>()
        for (ln in lines) {
            val parts = ln.trim().split(ws, 6)
            if (parts.size < 2) continue
            val path = if (parts.size >= 6) parts[5].trim() else ""
            if (path.isNotEmpty()) {
                paths.add(path)
            } else if (parts[1].contains('x')) {
                anonExec++
            }
        }
        return Triple(lines.size, anonExec, paths)
    }
    fun bigAnonExec(): List<String> {
        val ws = Regex("\\s+")
        val out = ArrayList<String>()
        for (ln in java.io.File("/proc/self/maps").readLines()) {
            val parts = ln.trim().split(ws, 6)
            if (parts.size < 2 || !parts[1].contains('x')) continue
            if (parts.size >= 6 && parts[5].trim().isNotEmpty()) continue
            val se = parts[0].split("-")
            if (se.size != 2) continue
            val start = se[0].toLongOrNull(16) ?: continue
            val end = se[1].toLongOrNull(16) ?: continue
            val sz = end - start
            if (sz in 0x80000L..0x180000L) {
                out.add("0x" + sz.toString(16) + "/" + parts[1])
            }
        }
        return out
    }
    // v59: REAL maps via the raw-syscall native channel
    // (NativeHookBridge.readRealMaps). The v56 snapshots above go
    // through java.io and are served by the engine's own Java-layer
    // /proc fake (proven in launch77: identical readings while the
    // native spoof was suspended), so they cannot say whether the
    // SecNeo payload was ever really mapped. This channel can: it
    // reads the kernel's own view, below both fake layers.
    fun realSnap(): RealSnap? =
        NativeHookBridge.readRealMaps()?.let(MapsSnapshot::parse)
    fun logRealSnap(tag: String, snap: RealSnap?, prev: RealSnap?) {
        GuestBreadcrumb.log(MapsSnapshot.formatLine(tag, snap, prev))
    }
    var realBefore: RealSnap? = null
    var realAfter: RealSnap? = null
    var v59DataDir: String? = null
    fun dumpRealMaps(tag: String, snap: RealSnap?) {
        val dir = v59DataDir ?: return
        val s = snap ?: return
        try {
            val name = MapsSnapshot.dump(java.io.File(dir, "files"), android.os.Process.myPid(), tag, s)
            GuestBreadcrumb.log(
                "v59 realmaps dump " + tag + " -> " + name +
                    " bytes=" + s.text.length
            )
        } catch (t: Throwable) {
            GuestBreadcrumb.logThrowable("v59 realmaps dump $tag failed", t)
        }
    }
    var mapsBefore: Triple<Int, Int, Set<String>>? = null
    try {
        mapsBefore = mapsSnap()
        GuestBreadcrumb.log(
            "v56 probe maps before lines=" + mapsBefore.first +
                " anonExec=" + mapsBefore.second +
                " paths=" + mapsBefore.third.size
        )
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v56 probe maps before failed", t)
    }
    try {
        realBefore = realSnap()
        logRealSnap("before", realBefore, null)
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v59 realmaps before failed", t)
    }
    val probeThread = Thread.currentThread()
    val oldCtxCl = probeThread.contextClassLoader
    GuestBreadcrumb.log(
        "v56 probe ctxCl was=" + oldCtxCl + " setting to guestCl"
    )
    probeThread.contextClassLoader = guestClassLoader
    // v58 plant state (see the plant block inside the try below):
    // declared here so the restore after the maps-after snapshot can
    // still see them.
    var v58AtObj: Any? = null
    var v58InitAppField: java.lang.reflect.Field? = null
    var v58OldInitApp: Any? = null
    var v58BoundData: Any? = null
    var v58BoundAiField: java.lang.reflect.Field? = null
    var v58OldBoundAi: Any? = null
    var v58Planted = false
    var v60StubBase = 0L
    try {
        fun apkField(name: String): Any? {
            var cls: Class<*>? = loadedApk.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(loadedApk)
                } catch (e: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            return null
        }
        val ai = apkField("mApplicationInfo")
            as? android.content.pm.ApplicationInfo
        GuestBreadcrumb.log(
            "v55 probe ai packageName=" + ai?.packageName +
                " dataDir=" + ai?.dataDir +
                " nativeLibraryDir=" + ai?.nativeLibraryDir +
                " sourceDir=" + ai?.sourceDir
        )
        v59DataDir = ai?.dataDir
        // v60 note: the early discriminant block that used to sit
        // here read the BEFORE snapshot, but the stub is only
        // loaded during this probe's own H.a — the before maps
        // never contain it (launch81: dexHelper=[] before,
        // 5 segments after). All v60 checks moved to the single
        // post-load block after the after-snapshot (below).
        // v58 plant: the payload's registration must locate H
        // through an ambient application identity. The thread ctxCl
        // is already the guest CL (v56), so the remaining anchor is
        // ActivityThread.mInitialApplication — in a stub process
        // that is the HOST Application, whose CL has no H. Plant a
        // pre-created guest AW instance (loaded by the guest CL)
        // there, plus the guest ai into mBoundApplication.appInfo,
        // for the H.a window; restored after the maps-after
        // snapshot (see below) to also cover a short async tail.
        try {
            fun fieldOf(obj: Any, name: String):
                java.lang.reflect.Field? {
                var cls: Class<*>? = obj.javaClass
                while (cls != null) {
                    try {
                        val f = cls.getDeclaredField(name)
                        f.isAccessible = true
                        return f
                    } catch (e: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }
                return null
            }
            val atCls = Class.forName("android.app.ActivityThread")
            val cur = atCls.getDeclaredMethod("currentActivityThread")
            cur.isAccessible = true
            v58AtObj = cur.invoke(null)
            val at = v58AtObj
            if (at != null) {
                v58InitAppField = fieldOf(at, "mInitialApplication")
                v58OldInitApp = v58InitAppField?.get(at)
                val oldCl = (v58OldInitApp as? android.app.Application)
                    ?.javaClass?.classLoader
                GuestBreadcrumb.log(
                    "v58 plant before mInitialApplication=" +
                        v58OldInitApp?.javaClass?.name +
                        " clId=" + System.identityHashCode(oldCl)
                )
                val bdField = fieldOf(at, "mBoundApplication")
                v58BoundData = bdField?.get(at)
                val bd = v58BoundData
                if (bd != null) {
                    v58BoundAiField = fieldOf(bd, "appInfo")
                    v58OldBoundAi = v58BoundAiField?.get(bd)
                    GuestBreadcrumb.log(
                        "v58 plant before bound appInfo pkg=" +
                            (v58OldBoundAi as?
                                android.content.pm.ApplicationInfo)
                                ?.packageName
                    )
                }
                val awCls = guestClassLoader.loadClass(
                    "com.secneo.apkwrapper.AW"
                )
                val awCtor = awCls.getDeclaredConstructor()
                awCtor.isAccessible = true
                val plant = awCtor.newInstance()
                GuestBreadcrumb.log(
                    "v58 plant AW created clId=" +
                        System.identityHashCode(
                            plant.javaClass.classLoader
                        ) +
                        " guestClId=" +
                        System.identityHashCode(guestClassLoader)
                )
                v58InitAppField?.set(at, plant)
                if (bd != null && ai != null) {
                    v58BoundAiField?.set(bd, ai)
                }
                v58Planted = v58InitAppField != null
                GuestBreadcrumb.log("v58 plant planted=" + v58Planted)
            }
        } catch (t: Throwable) {
            GuestBreadcrumb.logThrowable("v58 plant FAILED", t)
        }
        // v63: the probe must NOT trigger the DexHelper load itself.
        // launch83 verdict: with F3 (v62) the load now completes and
        // the payload's JNI_OnLoad runs — and it reads H.sApp /
        // H.sAppInfo, which AW.attachBaseContext only populates
        // immediately before calling H.a in the shell's own
        // sequence. Invoking H.a here, before makeApplication, made
        // JNI_OnLoad read null statics and die on
        // GetObjectClass(NULL) under CheckJNI, aborting the whole
        // guest before the real bind ever ran. Let the natural
        // sequence (attachBaseContext sets the statics, then H.a)
        // perform the load during makeApplication instead.
        GuestBreadcrumb.log(
            "v63 probe: early H.a trigger removed " +
                "(v58plant=" + v58Planted + ")"
        )
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)
            ?.cause ?: t
        GuestBreadcrumb.logThrowable("v55 probe H.a(ai) FAILED", cause)
    }
    try {
        val after = mapsSnap()
        val newPaths = mapsBefore?.let { after.third - it.third }
            ?: after.third
        GuestBreadcrumb.log(
            "v56 probe maps after lines=" + after.first +
                " anonExec=" + after.second +
                " newPaths=" + newPaths.take(12) +
                " bigAnonExec=" + bigAnonExec()
        )
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v56 probe maps after failed", t)
    }
    try {
        realAfter = realSnap()
        logRealSnap("after", realAfter, realBefore)
        dumpRealMaps("before", realBefore)
        dumpRealMaps("after", realAfter)
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v59 realmaps after failed", t)
    }
    // v61 post-load discriminant (single block; v60's split
    // blocks read the pre-load snapshot and found no stub).
    // From the AFTER maps: compute the stub base, replicate
    // S1's extraction (range containing the base, field 7
    // verbatim) and S2's open of that path (control: the
    // guest's real lib path), then read the stub's bss slots
    // through /proc/self/mem. C0 stored the self base at
    // +0x15048, C1 the sscanf pointer at +0x15038, C2 config
    // (payload offset 0x8000 at +0x15040, tail offset
    // 0x12c23d at +0x1507c); S3 would have copied the 16-byte
    // RC4 key to +0x15068 and the XOR dword to +0x15080. An
    // all-zero key buffer means S3 never landed (bad fd):
    // the failure is at the S2/S3 boundary.
    try {
        val afterText = realAfter?.text ?: realSnap()?.text
        if (afterText != null) {
            val dexLines = afterText.lines()
                .filter { it.contains("libDexHelper.so") }
            var foundBase = 0L
            for (ln in dexLines) {
                val st = ln.substringBefore('-').trim()
                    .toLongOrNull(16) ?: continue
                if (foundBase == 0L || st < foundBase) {
                    foundBase = st
                }
            }
            v60StubBase = foundBase
            GuestBreadcrumb.log(
                "v61 stub base=0x" + foundBase.toString(16) +
                    " dexLines=" + dexLines.size
            )
            if (foundBase != 0L) {
                var hitPath: String? = null
                for (ln in afterText.lines()) {
                    val rng = ln.substringBefore(' ')
                    val st = rng.substringBefore('-')
                        .toLongOrNull(16) ?: continue
                    val en = rng.substringAfter('-')
                        .toLongOrNull(16) ?: continue
                    if (st <= foundBase && foundBase < en) {
                        val f = ln.trim().split(Regex("\\s+"))
                        hitPath = f.getOrNull(6)
                        GuestBreadcrumb.log(
                            "v61 s1 replica hit line=" + ln
                        )
                        break
                    }
                }
                GuestBreadcrumb.log(
                    "v61 s1 replica extractedPath=" + hitPath
                )
                fun replicaOpen(tag: String, p: String?) {
                    if (p == null) {
                        GuestBreadcrumb.log(
                            "v61 open $tag skipped (null path)"
                        )
                        return
                    }
                    try {
                        val fd = android.system.Os.open(
                            p,
                            android.system.OsConstants.O_RDONLY,
                            0
                        )
                        GuestBreadcrumb.log(
                            "v61 open $tag OK path=" + p
                        )
                        android.system.Os.close(fd)
                    } catch (e: android.system.ErrnoException) {
                        GuestBreadcrumb.log(
                            "v61 open $tag FAIL errno=" + e.errno +
                                " msg=" + e.message +
                                " path=" + p
                        )
                    } catch (t: Throwable) {
                        GuestBreadcrumb.logThrowable(
                            "v61 open $tag threw", t
                        )
                    }
                }
                replicaOpen("s1path", hitPath)
                replicaOpen(
                    "control",
                    (v59DataDir ?: "?") + "/lib/libDexHelper.so"
                )
            }
        } else {
            GuestBreadcrumb.log("v61 no after maps text")
        }
        val base = v60StubBase
        if (base != 0L) {
            val memFd = android.system.Os.open(
                "/proc/self/mem",
                android.system.OsConstants.O_RDONLY, 0
            )
            fun readAt(addr: Long, n: Int): ByteArray {
                val buf = java.nio.ByteBuffer.allocate(n)
                val got = android.system.Os.pread(memFd, buf, addr)
                if (got < n) {
                    throw IllegalStateException(
                        "short pread got=" + got
                    )
                }
                return buf.array()
            }
            fun hexOf(b: ByteArray): String {
                val sb = StringBuilder()
                for (x in b) {
                    sb.append("%02x".format(x.toInt() and 0xff))
                }
                return sb.toString()
            }
            fun longAt(off: Long): Long {
                val bb = java.nio.ByteBuffer.wrap(
                    readAt(base + off, 8)
                )
                bb.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                return bb.getLong(0)
            }
            GuestBreadcrumb.log(
                "v61 bss sscanfPtr=0x" +
                    longAt(0x15038).toString(16) +
                    " payloadOffCfg=0x" +
                    longAt(0x15040).toString(16) +
                    " selfBaseSlot=0x" +
                    longAt(0x15048).toString(16) +
                    " (base=0x" + base.toString(16) + ")" +
                    " tailOffCfg=0x" +
                    longAt(0x1507c).toString(16) +
                    " rc4key=" +
                    hexOf(readAt(base + 0x15068, 16)) +
                    " xorKey=" + hexOf(readAt(base + 0x15080, 4))
            )
            android.system.Os.close(memFd)
        } else {
            GuestBreadcrumb.log("v61 bss skipped: stub base unknown")
        }
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v61 discriminant failed", t)
    }
    // v58 plant restore (window covered H.a + the maps-after
    // snapshot, in case the payload registers on a short async tail)
    if (v58Planted) {
        try {
            v58InitAppField?.set(v58AtObj, v58OldInitApp)
            val bd = v58BoundData
            if (bd != null) {
                v58BoundAiField?.set(bd, v58OldBoundAi)
            }
            GuestBreadcrumb.log("v58 plant restored")
        } catch (t: Throwable) {
            GuestBreadcrumb.logThrowable("v58 plant restore FAILED", t)
        }
    }
    probeThread.contextClassLoader = oldCtxCl
    GuestBreadcrumb.log("v56 probe ctxCl restored")
    // v63: the v54 loadLibrary0 preload block is removed for the
    // same reason as the H.a trigger above — it loads DexHelper
    // (stub ctor -> payload JNI_OnLoad) at probe time, before
    // AW.attachBaseContext has populated H.sApp / H.sAppInfo.
    GuestBreadcrumb.log("v63 probe: loadLibrary0 preload removed")
    try {
        val fin = mapsSnap()
        val newPaths = mapsBefore?.let { fin.third - it.third }
            ?: fin.third
        GuestBreadcrumb.log(
            "v56 probe maps final lines=" + fin.first +
                " anonExec=" + fin.second +
                " newPaths=" + newPaths.take(12) +
                " bigAnonExec=" + bigAnonExec()
        )
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v56 probe maps final failed", t)
    }
    try {
        val realFin = realSnap()
        logRealSnap("final", realFin, realAfter ?: realBefore)
        dumpRealMaps("final", realFin)
    } catch (t: Throwable) {
        GuestBreadcrumb.logThrowable("v59 realmaps final failed", t)
    }
}
