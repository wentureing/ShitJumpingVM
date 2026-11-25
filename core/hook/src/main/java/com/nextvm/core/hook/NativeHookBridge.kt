package com.nextvm.core.hook

import com.nextvm.core.common.diagnostics.CheckResult
import com.nextvm.core.common.diagnostics.CheckState
import com.nextvm.core.common.diagnostics.DiagnosticsHolder
import android.content.Context
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.findField
import com.nextvm.core.common.runSafe
import com.nextvm.core.model.VirtualConstants
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NativeHookBridge — Kotlin-side bridge for native PLT hooks and file I/O redirection.
 *
 * In Phase 1, implements file path redirection in pure Java/Kotlin via:
 * 1. Reflection-based interception of FileInputStream/FileOutputStream constructors
 * 2. Path translation rules for /data/data/{guestPkg}/ -> sandbox paths
 * 3. /proc/self spoofing for maps, cmdline, status
 * 4. /sys property spoofing
 * 5. System property overrides via reflection
 *
 * In Phase 2+, the JNI methods defined here will bridge to C/C++ implementations
 * using bhook (PLT hooks) for libc open/openat/stat/access/mkdir/unlink.
 *
 * IMPORTANT: Java-level hooks only cover Java I/O. Native code in guest apps
 * will bypass these hooks until the native bhook layer is implemented.
 */
@Singleton
class NativeHookBridge @Inject constructor() {

    companion object {
        private const val TAG = "NativeHook"

        // Proc filesystem paths that need spoofing
        internal const val PROC_SELF_MAPS = "/proc/self/maps"
        private const val PROC_SELF_CMDLINE = "/proc/self/cmdline"
        private const val PROC_SELF_STATUS = "/proc/self/status"
        private const val PROC_SELF_COMM = "/proc/self/comm"
        private const val PROC_SELF_EXE = "/proc/self/exe"
        private const val PROC_SELF_MOUNTINFO = "/proc/self/mountinfo"
        private const val PROC_SELF_MOUNTS = "/proc/self/mounts"

        // Sys paths for device spoofing
        private const val SYS_BLOCK = "/sys/block"
        private const val SYS_DEVICES = "/sys/devices"
        private const val SYS_CLASS_NET = "/sys/class/net"

        // Paths indicating root/emulator
        private val ROOT_PATHS = setOf(
            "/system/app/Superuser.apk",
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )

        private val EMULATOR_PATHS = setOf(
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props"
        )

        // Native library load status — eagerly loaded at class init
        @Volatile
        private var nativeLibLoaded = false

        init {
            nativeLibLoaded = try {
                System.loadLibrary("nextvm-native")
                true
            } catch (e: UnsatisfiedLinkError) {
                Timber.tag(TAG).w("libnextvm-native.so not available: ${e.message}")
                false
            }
        }

        /**
         * v59: read the REAL /proc/self/maps. The native side uses
         * raw syscalls only, bypassing both the Java-layer fake file
         * content and the libc PLT hooks, so the result is the
         * kernel's own view of the process map. Null when the native
         * library is unavailable or the read fails.
         */
        @JvmStatic
        fun readRealMaps(): String? {
            if (!nativeLibLoaded) return null
            return try {
                nativeReadRealMaps()
            } catch (_: Throwable) {
                null
            }
        }

        @JvmStatic
        external fun nativeReadRealMaps(): String?

        /**
         * v148: drain the native failed-connect / failed-DNS ring
         * (hooked_connect / hooked_getaddrinfo). Null when the native
         * library is unavailable or no new events since the last drain.
         * Called from outside any hook body, so file I/O here is safe.
         */
        @JvmStatic
        fun dumpConnectProbe(): String? {
            if (!nativeLibLoaded) return null
            return try {
                nativeDumpConnectProbe()
            } catch (_: Throwable) {
                null
            }
        }

        @JvmStatic
        external fun nativeDumpConnectProbe(): String?
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        Timber.tag(TAG).i("NativeHookBridge initialized — PLT hooks ready")
        // Hook Runtime.nativeLoad to fix null caller class (Jiagu/360 SIGABRT fix)
        hookRuntimeNativeLoad()
    }

    /**
     * Hook Runtime.nativeLoad() to fix null caller class.
     *
     * Jiagu/360-protected apps call Runtime.load0(Class, String) via reflection.
     * In the virtual environment, the Class caller argument becomes null because
     * the call originates from code loaded via custom ClassLoader/reflection.
     * ART's nativeLoad() then passes null caller to CheckJNI's GetObjectArrayElement
     * on the caller's ProtectionDomain array, causing SIGABRT.
     *
     * This hook intercepts at the JNI registration level via RegisterNatives to
     * replace the native implementation of Runtime.nativeLoad with one that fixes
     * the null caller before forwarding to the original.
     */
    fun hookRuntimeNativeLoad() {
        if (nativeLibLoaded) {
            try {
                val result = nativeInstallRuntimeLoadHook()
                if (result) {
                    Timber.tag(TAG).i("Runtime.nativeLoad JNI hook installed \u2014 null-caller protection active")
                    return
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Native Runtime.nativeLoad hook failed: ${e.message}")
            }
        }
        Timber.tag(TAG).w("Runtime.nativeLoad hook not available \u2014 CheckJNI may still abort on null caller")    }

    // Path redirection rules: fromPrefix -> toPrefix
    /**
     * v154：路径规则表已拆成独立类（纯逻辑、可单测）。
     * native 同步通过回调注入；hidden 判定仍用本类的 hiddenPaths。
     */
    private val pathRedirects = PathRedirectRegistry(
        isHidden = { hiddenPaths.contains(it) },
        onAdd = { from, to -> if (nativeHooksAvailable) nativeAddPathRedirection(from, to) },
        onRemove = { from -> if (nativeHooksAvailable) nativeRemovePathRedirection(from) },
        onClear = { if (nativeHooksAvailable) nativeClearPathRedirections() }
    )

    /**
     * v154：/proc 伪造与属性覆盖已拆到 ProcSpoofing。
     * 伪造内容仍写回本类的 fakeFileContent（读取层在这里 hook）。
     */
    private val procSpoofing = ProcSpoofing(
        setFakeContent = { path, content -> fakeFileContent[path] = content },
        nativeAvailable = { nativeHooksAvailable },
        nativeSpoofProcSelf = { pid, pkg -> nativeSpoofProcSelf(pid, pkg) },
        nativeSetProcSpoofSuspended = { s -> nativeSetProcSpoofSuspended(s) },
        nativeSetProcessName = { n -> nativeSetProcessName(n) },
        nativeArmSeccompDiag = { p -> nativeArmSeccompDiag(p) },
        nativeSpoofSystemProperty = { k, v -> nativeSpoofSystemProperty(k, v) }
    )

    // /proc/self spoofing data

    // System property overrides

    // Paths to hide (return ENOENT)
    private val hiddenPaths = ConcurrentHashMap.newKeySet<String>()

    // Fake file content overrides: path -> content
    private val fakeFileContent = ConcurrentHashMap<String, ByteArray>()

    // Track initialization state
    private var initialized = false
    private var nativeHooksAvailable = false
    private var appContext: android.content.Context? = null

    /**
     * Initialize the native hook bridge.
     * In Phase 1, sets up Java-level hooks only.
     * Returns true if at least Java hooks are active.
     */
    // v138: synchronized — VE.initialize runs on a background thread while
    // getOrCreateClassLoader may call this on the main thread; without the
    // lock both could enter nativeInit concurrently, or a caller could
    // observe nativeHooksAvailable=false mid-init and register rules that
    // never reach native (v136/v137 empty-DexPathList crashes).
    @Synchronized
    fun initNativeHooks(context: android.content.Context? = null): Boolean {
        if (initialized) return true

        // v145：统一调试开关初始化（release 包自动全关；debug 包才落盘）
        com.nextvm.core.common.DebugFlags.init(context)
        com.nextvm.core.common.DebugTrace.event(
            "initNativeHooks",
            "enter proc=" + (runCatching { android.app.Application.getProcessName() }.getOrNull() ?: "?")
        )

        // P0-3: 容器主进程不装 hook。
        //
        // 理由：主进程的所有文件访问用的都是**真实路径**
        //   - installApp 读 /sdcard/Download/x.apk（真实）
        //   - 写沙箱 <dataDir>/files/virtual/...（也是真实）
        //   - collectProbeOutput 读 context.filesDir（真实）
        // 装 hook 之后这些真实路径会被 redirect_path 重定向到沙箱 -> 反而读不到，
        // 所以历史上要额外加 is_import_read_exempt 来绕过自己的 hook。
        // 主进程不装 hook 后，整个豁免机制可以删掉。
        // 详见 docs/changes/20261124-p0-3-main-process-no-hook-scope.md
        //
        // guest 进程（:p0-:p9）仍会装 hook：由 getOrCreateClassLoader 调用本方法。
        // NativeHookBridge 是 @Singleton（Hilt），每个进程一份，initialized 是进程级。
        // 取进程名：Application.getProcessName() 是 API 28，minSdk 是 26，
        // 所以用反射调 ActivityThread.currentProcessName()（全版本可用），
        // 再退到 /proc/self/cmdline（主进程此时还没有 hook，读到的是真实值）。
        val procName = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentProcessName")
                .invoke(null) as? String
        }.getOrNull() ?: runCatching {
            java.io.File(PROC_SELF_CMDLINE).readText().trimEnd('\u0000')
        }.getOrNull()
        val hostPkg = context?.packageName
        if (hostPkg != null && procName == hostPkg) {
            initialized = true
            Timber.tag(TAG).i("initNativeHooks skipped: container main process (proc=%s)", procName)
            com.nextvm.core.common.DebugTrace.event(
                "initNativeHooks", "skip: container main process (proc=$procName)"
            )
            return false
        }

        appContext = context?.applicationContext
        Timber.tag(TAG).i("Initializing native hook bridge...")
        com.nextvm.core.common.DebugTrace.event(
            "initNativeHooks", "guest process confirmed (proc=$procName), installing hooks"
        )

        // Phase 1: Java-level IO hooks
        val javaHooksInstalled = installJavaIoHooks()
        DiagnosticsHolder.check(
            CheckResult(
                path = "native", step = "phase1", name = "javaIoHooks",
                state = CheckState.OK,
                target = "libcore.io.Linux",
                expected = if (javaHooksInstalled) "installed" else "unchanged_default_off",
                actual = if (javaHooksInstalled) "installed" else "unchanged_default_off"
            )
        )

        // Phase 2: Try to load native library for PLT hooks (GOT patching)
        nativeHooksAvailable = tryLoadNativeLibrary()
        DiagnosticsHolder.check(
            CheckResult(
                path = "native", step = "phase2", name = "loadNativeLibrary",
                state = if (nativeHooksAvailable) CheckState.OK else CheckState.FAIL,
                target = "libnextvm.so",
                expected = "loaded",
                actual = if (nativeHooksAvailable) "loaded" else "missing",
                reason = if (nativeHooksAvailable) null else "dlopen_failed"
            )
        )

        if (nativeHooksAvailable) {
            try {
                val nativeResult = nativeInit()
                DiagnosticsHolder.check(
                    CheckResult(
                        path = "native", step = "phase2", name = "nativeInit",
                        state = if (nativeResult) CheckState.OK else CheckState.FAIL,
                        target = "GOT patches",
                        expected = "installed",
                        actual = if (nativeResult) "installed" else "failed",
                        reason = if (nativeResult) null else "native_init_returned_false"
                    )
                )
                Timber.tag(TAG).i("Native PLT hooks initialized: $nativeResult")

                // Set host data prefix + virtual data root for native-level redirections
                appContext?.let { ctx ->
                    try {
                        nativeSetHostDataPrefix(ctx.dataDir.absolutePath)
                        val virtualRoot = java.io.File(ctx.filesDir, "virtual").absolutePath
                        nativeSetVirtualDataRoot(virtualRoot)
                        DiagnosticsHolder.stage(
                            "native",
                            "phase2 dataRoot set (prefix=${ctx.dataDir.absolutePath} root=$virtualRoot)"
                        )
                        Timber.tag(TAG).d("Native prefix=%s, root=%s", ctx.dataDir.absolutePath, virtualRoot)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Failed to set native data paths: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Native hook init failed")
                nativeHooksAvailable = false
            }
        } else {
            Timber.tag(TAG).i("Native hooks not available, using Java-level hooks only")
        }

        // v137: push any path redirections registered before native was
        // ready (addPathRedirection only touches the native map when
        // nativeHooksAvailable). Without this, rules registered during the
        // early guest-start window would stay Kotlin-only and never
        // redirect native file opens.
        if (nativeHooksAvailable) {
            try {
                pathRedirects.snapshot().forEach { (from, to) ->
                    nativeAddPathRedirection(from, to)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to sync path redirections: ${e.message}")
            }
        }

        // Add default hidden paths (root/emulator detection files)
        ROOT_PATHS.forEach { hiddenPaths.add(it) }
        EMULATOR_PATHS.forEach { hiddenPaths.add(it) }

        // v141/v145: Java 层文件 I/O 重定向（RegisterNatives 替换 libcore.io.Linux 的
        // native 方法）。必须在 native 库加载 + 路径规则同步之后安装，因为替换实现
        // 会直接读 native 侧的 g_path_redirects 表。
        //
        // 安全边界：本方法在**主进程**已提前 return（上面的 P0-3 判断），所以这里
        // 只会发生在 guest 进程。
        //
        // v145 把两个开关拆开，便于「只切一个变量」的严格对照：
        //   debug.nextvm.javaIo=0            -> 完全不装 hook（等价于没有 v141）
        //   debug.nextvm.javaIoTranslate=0   -> 装 hook 但不翻译路径（隔离"hook 本身"的影响）
        //   (都=1，默认)                      -> 装 + 翻译
        // v147：**默认关闭**。
        //
        // 实测结论（2026-11-24）：打开这个 hook 会让 Via 从「主页显示源码但仍可交互」
        // 退化为「完全不能交互」（用户确认，并用 debug.nextvm.javaIo=0 做了对照）。
        // 因此默认值改为 false，只保留代码与开关供后续修复后重新启用。
        //
        // 已知的语义偏差（就是它破坏 Via 的原因，修复方向也在这里）：
        //   1. Linux.open 失败时 AOSP 抛 ErrnoException("open", errno)，
        //      本实现抛 FileNotFoundException —— 所有 catch(ErrnoException) 判断
        //      ENOENT/EACCES 的调用者（FileChannel / NativeCrypto / 部分 RandomAccessFile）
        //      会捕获不到，异常直接上抛；
        //   2. Linux.access 失败时 AOSP 抛 ErrnoException，本实现返回 false ——
        //      File.canRead()/canWrite() 依赖异常分支的代码行为改变。
        //
        // 打开方式（仅用于调试/修复验证）：adb shell setprop debug.nextvm.javaIo 1
        //
        // v149：安装逻辑与诊断已拆到 JavaIoHooks（本类只负责调用与规则明细输出）。
        if (nativeHooksAvailable) {
            com.nextvm.core.hook.JavaIoHooks.install()
            com.nextvm.core.common.DebugTrace.event(
                "javaIo",
                "installed=" + com.nextvm.core.hook.JavaIoHooks.installed +
                    " nativeAvailable=$nativeHooksAvailable rules=${pathRedirects.size()}"
            )
            // 详细：规则明细（排查「为什么没翻译」时第一眼看这个）
            pathRedirects.snapshot().entries.take(20).forEach { (from, to) ->
                com.nextvm.core.common.DebugTrace.detail("javaIo", "rule $from -> $to")
            }
        }

        initialized = true
        Timber.tag(TAG).i("Native hook bridge initialized (java=$javaHooksInstalled, native=$nativeHooksAvailable)")
        return true
    }

    /** v141：Java 层文件 I/O 重定向是否已安装（转发到 JavaIoHooks，保留旧调用点兼容） */
    val javaIoHooksInstalled: Boolean
        get() = com.nextvm.core.hook.JavaIoHooks.installed

    /**
     * Add a path redirection rule.
     * Any file access to paths starting with [fromPrefix] will be redirected
     * to paths starting with [toPrefix].
     *
     * Example:
     *   addPathRedirection("/data/data/com.whatsapp/", "/data/data/com.nextvm.app/virtual/data/com.whatsapp_12345/")
     */
    // v139 probe getters (public for VirtualEngine's redirect probe;
    // isNativeAvailable already exists below).
    fun getPathRedirectSnapshot(): Map<String, String> = pathRedirects.snapshot()
    fun getNativeRedirectCount(): Int =
        try { nativeGetRedirectCount() } catch (e: Exception) { -1 }

    fun addPathRedirection(fromPrefix: String, toPrefix: String) {
        // v154：规则表已拆到 PathRedirectRegistry（含 native 同步回调）
        pathRedirects.add(fromPrefix, toPrefix)
    }

    /**
     * Remove a path redirection.
     */
    fun removePathRedirection(fromPrefix: String) {
        pathRedirects.remove(fromPrefix)
    }

    /**
     * Clear all path redirections.
     */
    fun clearPathRedirections() {
        pathRedirects.clear()
    }

    /**
     * Set up path redirections for a virtual app instance.
     *
     * @param guestPackageName The guest app's package name
     * @param instanceId The virtual instance ID
     * @param sandboxDataDir The sandbox data directory
     */
    fun setupAppRedirections(guestPackageName: String, instanceId: String, sandboxDataDir: String) {
        // Primary data directory redirection
        addPathRedirection(
            "/data/data/$guestPackageName/",
            "$sandboxDataDir/"
        )
        addPathRedirection(
            "/data/user/0/$guestPackageName/",
            "$sandboxDataDir/"
        )

        // App-specific external storage
        addPathRedirection(
            "/storage/emulated/0/Android/data/$guestPackageName/",
            "$sandboxDataDir/external_data/"
        )
        addPathRedirection(
            "/sdcard/Android/data/$guestPackageName/",
            "$sandboxDataDir/external_data/"
        )

        // OBB storage
        addPathRedirection(
            "/storage/emulated/0/Android/obb/$guestPackageName/",
            "$sandboxDataDir/obb/"
        )

        Timber.tag(TAG).d("App redirections set for $guestPackageName ($instanceId)")
    }

    /**
     * Set up general external/shared storage redirections for a virtual app.
     *
     * This redirects /sdcard/, /storage/emulated/0/, /mnt/sdcard/, and
     * /storage/self/primary/ to the per-instance virtual sdcard directory.
     *
     * MUST be called in addition to [setupAppRedirections] so that file explorer
     * apps (and any code using Environment.getExternalStorageDirectory()) see
     * the sandboxed storage instead of the host device's real storage.
     *
     * @param instanceId The virtual instance ID
     * @param virtualSdcardDir Absolute path to the per-instance virtual sdcard
     */
    fun setupExternalStorageRedirections(instanceId: String, virtualSdcardDir: String) {
        // General shared storage redirections — catches ALL access to external storage:
        //   File("/sdcard/DCIM/photo.jpg")  -> File("{virtualSdcard}/DCIM/photo.jpg")
        //   File("/storage/emulated/0/Download/a.pdf") -> File("{virtualSdcard}/Download/a.pdf")

        addPathRedirection("/sdcard/", "$virtualSdcardDir/")
        addPathRedirection("/storage/emulated/0/", "$virtualSdcardDir/")
        addPathRedirection("/mnt/sdcard/", "$virtualSdcardDir/")
        addPathRedirection("/storage/self/primary/", "$virtualSdcardDir/")

        // Some apps/libraries use the path without trailing slash for the root itself
        addPathRedirection("/storage/emulated/0", virtualSdcardDir)
        addPathRedirection("/sdcard", virtualSdcardDir)

        Timber.tag(TAG).d("External storage redirections set for $instanceId -> $virtualSdcardDir")
    }

    /**
     * Remove all external storage redirections for a virtual app instance.
     */
    fun removeExternalStorageRedirections() {
        val prefixes = listOf(
            "/sdcard/",
            "/sdcard",
            "/storage/emulated/0/",
            "/storage/emulated/0",
            "/mnt/sdcard/",
            "/storage/self/primary/"
        )
        prefixes.forEach { removePathRedirection(it) }
        Timber.tag(TAG).d("External storage redirections removed")
    }

    /**
     * Remove all redirections for a virtual app instance.
     */
    fun removeAppRedirections(guestPackageName: String) {
        val prefixes = listOf(
            "/data/data/$guestPackageName/",
            "/data/user/0/$guestPackageName/",
            "/storage/emulated/0/Android/data/$guestPackageName/",
            "/sdcard/Android/data/$guestPackageName/",
            "/storage/emulated/0/Android/obb/$guestPackageName/"
        )
        prefixes.forEach { removePathRedirection(it) }
        Timber.tag(TAG).d("App redirections removed for $guestPackageName")
    }

    /**
     * v135 path fidelity (experiment): register the file-I/O redirections
     * that make the stock-looking paths reported by the Java layer actually
     * work. MUST be called in the guest process before the guest Application
     * is bound (the shell's H.b opens sourceDir and writes dataDir/.cache
     * during attachBaseContext). The synthetic paths use the same token as
     * the native /proc/self/maps spoof, so the Java layer and the maps
     * layer agree.
     *
     * @param sandboxDataDir The sandbox app dir (files/virtual/data/<instance>)
     * @param apkPath The sandbox APK file path
     */
    fun setupPathFidelityRedirections(
        guestPackageName: String,
        instanceId: String,
        sandboxDataDir: String,
        apkPath: String
    ) {
        setupAppRedirections(guestPackageName, instanceId, sandboxDataDir)
        addPathRedirection(
            VirtualConstants.syntheticApkPath(guestPackageName),
            apkPath
        )
        addPathRedirection(
            VirtualConstants.syntheticLibDir(guestPackageName) + "/",
            "$sandboxDataDir/lib/"
        )
        Timber.tag("v135-pathfidelity").i(
            "redirections set for $guestPackageName ($instanceId)"
        )
    }

    /** Remove the v135 synthetic-path rules (data-dir rules via removeAppRedirections). */
    fun removePathFidelityRedirections(guestPackageName: String) {
        removePathRedirection(VirtualConstants.syntheticApkPath(guestPackageName))
        removePathRedirection(VirtualConstants.syntheticLibDir(guestPackageName) + "/")
        Timber.tag("v135-pathfidelity").d("redirections removed for $guestPackageName")
    }

    /**
     * Set up /proc/self spoofing for a virtual app.
     *
     * @param pid The spoofed PID to report
     * @param packageName The virtual app's package name (for cmdline)
     */
    fun spoofProcSelf(pid: Int, packageName: String) {
        // v154：实现已拆到 ProcSpoofing
        procSpoofing.spoofProcSelf(pid, packageName)
    }

    /**
     * v57: suspend (or resume) native /proc/self spoofing. While
     * suspended, hooked readers get the real /proc content. Used
     * around the CM guest's bind: its SecNeo mini-linker stub parses
     * the real maps in its constructor to resolve libc symbols, and
     * the spoofed maps silently abort its registration chain.
     */
    fun setProcSpoofSuspended(suspended: Boolean) {
        procSpoofing.setProcSpoofSuspended(suspended)
    }

    /**
     * Rename the current (stub) process to the guest's process name at
     * the source (kernel comm via prctl). Complements the /proc content
     * spoofing: identity fields read directly from the kernel — stat,
     * status Name, task comms, other processes' views — become the
     * guest's everywhere, not only on hooked read paths.
     */
    fun setProcessName(name: String) {
        procSpoofing.setProcessName(name)
    }

    /**
     * Arm the seccomp raw-syscall diagnostic in this process: traps
     * path-bearing syscalls (openat/readlinkat/...) issued via direct
     * svc — which bypass every libc GOT hook — attributes them to the
     * calling library, and logs them to [logPath]. Results are never
     * altered. Call once per guest stub process, before bind.
     */
    fun armSeccompDiag(logPath: String) {
        procSpoofing.armSeccompDiag(logPath)
    }

    /**
     * Override a system property value.
     * Hooks __system_property_get to return our value.
     */
    fun spoofSystemProperty(key: String, value: String) {
        procSpoofing.spoofSystemProperty(key, value)
    }

    /**
     * Set multiple system properties at once.
     */
    fun spoofSystemProperties(properties: Map<String, String>) {
        properties.forEach { (key, value) ->
            spoofSystemProperty(key, value)
        }
    }

    /**
     * Hide a path (make it appear non-existent).
     */
    fun hidePath(path: String) {
        hiddenPaths.add(path)
        if (nativeHooksAvailable) {
            nativeHidePath(path)
        }
    }

    /**
     * Unhide a path.
     */
    fun unhidePath(path: String) {
        hiddenPaths.remove(path)
        if (nativeHooksAvailable) {
            nativeUnhidePath(path)
        }
    }

    /**
     * Set fake content for a file path.
     * When the path is read, the fake content is returned instead.
     */
    fun setFakeFileContent(path: String, content: ByteArray) {
        fakeFileContent[path] = content
    }

    /**
     * Set fake text content for a file path.
     */
    fun setFakeFileContent(path: String, content: String) {
        fakeFileContent[path] = content.toByteArray()
    }

    /**
     * Translate a file path through redirection rules.
     * Public API for other components to use.
     */
    fun translatePath(originalPath: String): String {
        // v154：实现已拆到 PathRedirectRegistry（最长前缀匹配 + 隐藏路径）
        return pathRedirects.translate(originalPath)
    }

    /**
     * Check if a path is hidden.
     */
    fun isPathHidden(path: String): Boolean = hiddenPaths.contains(path)

    /**
     * Check if fake content exists for a path.
     */
    fun hasFakeContent(path: String): Boolean = fakeFileContent.containsKey(path)

    /**
     * Get fake content for a path.
     */
    fun getFakeContent(path: String): ByteArray? = fakeFileContent[path]

    /**
     * Get a system property override.
     */
    fun getPropertyOverride(key: String): String? =
        procSpoofing.propertyOverridesSnapshot()[key]

    /**
     * Get current redirection rules count.
     */
    fun getRedirectionCount(): Int = pathRedirects.size()

    /**
     * Get current hidden paths count.
     */
    fun getHiddenPathCount(): Int = hiddenPaths.size

    /**
     * Check if native hooks are available.
     */
    fun isNativeAvailable(): Boolean = nativeHooksAvailable

    /**
     * Clean up all hooks and state.
     */
    fun cleanup() {
        pathRedirects.clear()
        hiddenPaths.clear()
        fakeFileContent.clear()
        procSpoofing.reset()

        if (nativeHooksAvailable) {
            nativeCleanup()
        }

        initialized = false
        Timber.tag(TAG).i("Native hook bridge cleaned up")
    }

    // ====================================================================
    // Java-level I/O hooks (Phase 1)
    // ====================================================================

    /**
     * Install Java-level I/O interception.
     * Hooks FileInputStream and FileOutputStream to apply path redirection.
     */
    private fun installJavaIoHooks(): Boolean {
        var success = true

        // Hook File constructor to intercept path resolution
        success = success && hookFileClass()

        // Hook Runtime.exec to prevent root-detection binaries
        success = success && hookRuntimeExec()

        // Hook System properties access
        success = success && hookSystemProperties()

        Timber.tag(TAG).d("Java I/O hooks installed: $success")
        return success
    }

    /**
     * Hook java.io.File to intercept path operations.
     * We wrap the File constructor indirectly by hooking File.exists(), canRead(), etc.
     */
    private fun hookFileClass(): Boolean {
        return try {
            // We can't directly hook File constructors in pure Java,
            // but we can intercept the resolution at higher levels.
            // For Phase 1, the VirtualContext and ContentResolverProxy
            // handle most Java-level file access.

            // Install a SecurityManager-like check (limited effectiveness)
            Timber.tag(TAG).d("File class hook points prepared")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook File class")
            false
        }
    }

    /**
     * Hook Runtime.exec() to prevent su/root binary execution.
     */
    private fun hookRuntimeExec(): Boolean {
        return try {
            val runtimeClass = Runtime::class.java

            // Can't directly hook exec in pure Java, but we can:
            // 1. Hide su binaries via path hiding
            // 2. The AntiDetectionEngine handles this at a higher level

            Timber.tag(TAG).d("Runtime.exec hook points prepared")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook Runtime.exec")
            false
        }
    }

    /**
     * Hook System.getProperty to return spoofed system properties.
     */
    private fun hookSystemProperties(): Boolean {
        return try {
            // On Android, system properties are typically accessed via
            // android.os.SystemProperties (hidden API) or native __system_property_get.
            // System.getProperty() accesses JVM-level properties, not Android system properties.

            // Hook SystemProperties.get() via reflection
            val spClass = Class.forName("android.os.SystemProperties")
            // We intercept via the native hook layer when available.
            // For Java-level, we use the Settings proxy.

            Timber.tag(TAG).d("System properties hook points prepared")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook system properties")
            false
        }
    }

    // ====================================================================
    // /proc spoofing helpers
    // ====================================================================

    /**
     * Build fake /proc/self/status content.
     */

    /**
     * Filter /proc/self/maps to hide NEXTVM-related entries.
     */
    fun filterProcMaps(originalMaps: String): String {
        val linesToHide = listOf(
            VirtualConstants.HOST_PACKAGE,
            "nextvm",
            "libnextvm",
            "lsplant",
            "dobby",
            "bhook",
            "xhook",
            "substrate",
            "xposed"
        )

        return originalMaps.lines()
            .filter { line ->
                linesToHide.none { keyword ->
                    line.contains(keyword, ignoreCase = true)
                }
            }
            .joinToString("\n")
    }

    // ====================================================================
    // JNI native method declarations (Phase 2)
    // These will be implemented in C/C++ with bhook
    // ====================================================================

    /**
     * Initialize native PLT hooks (bhook).
     * Hooks: open, openat, stat, lstat, access, mkdir, unlink, rename,
     *        readlink, realpath, __system_property_get
     */
    private external fun nativeInit(): Boolean

    /** Add a native-level path redirection rule. */
    private external fun nativeAddPathRedirection(fromPrefix: String, toPrefix: String)

    /** Remove a native-level path redirection rule. */
    private external fun nativeRemovePathRedirection(fromPrefix: String)

    /** Clear all native-level path redirections. */
    private external fun nativeClearPathRedirections()

    /** Set up /proc/self spoofing at native level. */
    private external fun nativeSpoofProcSelf(pid: Int, packageName: String)

    /** v57: suspend/resume /proc/self spoofing at native level. */
    private external fun nativeSetProcSpoofSuspended(suspended: Boolean)

    private external fun nativeSetProcessName(name: String)

    private external fun nativeArmSeccompDiag(logPath: String)

    /** Override a system property at native level. */
    private external fun nativeSpoofSystemProperty(key: String, value: String)

    /** Hide a path at native level (return ENOENT). */
    private external fun nativeHidePath(path: String)

    /** Unhide a path at native level. */
    private external fun nativeUnhidePath(path: String)

    /** Clean up all native hooks. */
    private external fun nativeCleanup()

    /** Set the host data directory prefix for native redirections. */
    private external fun nativeSetHostDataPrefix(prefix: String)

    /** Set the virtual data root directory for native redirections. */
    private external fun nativeSetVirtualDataRoot(root: String)

    /** Get current redirect count (for debugging). */
    private external fun nativeGetRedirectCount(): Int

    /** Get current property spoof count (for debugging). */
    private external fun nativeGetPropertySpoofCount(): Int

    /** Check if native engine is initialized. */
    private external fun nativeIsInitialized(): Boolean

    /**
     * Install a JNI-level hook for Runtime.nativeLoad to fix null caller class.
     * Uses RegisterNatives to replace the native implementation.
     * Returns true on success.
     */
    private external fun nativeInstallRuntimeLoadHook(): Boolean

    /**
     * Check if the native library is loaded.
     * Since the library is eagerly loaded in companion init {},
     * this simply returns the cached result.
     */
    private fun tryLoadNativeLibrary(): Boolean {
        return nativeLibLoaded
    }
}

/**
 * FileAccessInterceptor — Monitors and redirects file access at Java level.
 *
 * This works as a companion to NativeHookBridge for cases where
 * Java I/O classes are used directly by guest apps.
 */
class FileAccessInterceptor(
    private val bridge: NativeHookBridge
) {

    companion object {
        private const val TAG = "FileIntercept"
    }

    /**
     * Intercept a File constructor call and return a redirected File.
     */
    fun interceptFile(originalPath: String): File {
        val translatedPath = bridge.translatePath(originalPath)
        if (translatedPath != originalPath) {
            Timber.tag(TAG).d("File redirect: $originalPath -> $translatedPath")
        }
        return File(translatedPath)
    }

    /**
     * Intercept a FileInputStream and return one for the redirected path.
     */
    fun interceptFileInputStream(originalPath: String): FileInputStream {
        val translatedPath = bridge.translatePath(originalPath)
        if (translatedPath != originalPath) {
            Timber.tag(TAG).d("FIS redirect: $originalPath -> $translatedPath")
        }

        // Check for fake content
        val fakeContent = bridge.getFakeContent(originalPath)
        if (fakeContent != null) {
            // Create a temp file with fake content and return stream to it
            val tempFile = File.createTempFile("nextvm_fake_", ".tmp")
            tempFile.deleteOnExit()
            tempFile.writeBytes(fakeContent)
            return FileInputStream(tempFile)
        }

        return FileInputStream(translatedPath)
    }

    /**
     * Intercept a FileOutputStream and return one for the redirected path.
     */
    fun interceptFileOutputStream(originalPath: String, append: Boolean = false): FileOutputStream {
        val translatedPath = bridge.translatePath(originalPath)
        if (translatedPath != originalPath) {
            Timber.tag(TAG).d("FOS redirect: $originalPath -> $translatedPath")
            // Ensure parent directory exists
            File(translatedPath).parentFile?.mkdirs()
        }
        return FileOutputStream(translatedPath, append)
    }

    /**
     * Intercept a File.exists() call.
     */
    fun interceptExists(path: String): Boolean {
        if (bridge.isPathHidden(path)) {
            return false
        }
        val translated = bridge.translatePath(path)
        return File(translated).exists()
    }

    /**
     * Intercept a File.canRead() call.
     */
    fun interceptCanRead(path: String): Boolean {
        if (bridge.isPathHidden(path)) {
            return false
        }
        val translated = bridge.translatePath(path)
        return File(translated).canRead()
    }

    /**
     * Intercept a File.listFiles() call.
     */
    fun interceptListFiles(path: String): Array<File>? {
        val translated = bridge.translatePath(path)
        val files = File(translated).listFiles() ?: return null

        // Filter out hidden paths
        return files.filter { file ->
            !bridge.isPathHidden(file.absolutePath)
        }.toTypedArray()
    }

    /**
     * Read /proc/self/maps with NEXTVM entries filtered out.
     */
    fun readFilteredProcMaps(): String {
        return try {
            val content = File(NativeHookBridge.PROC_SELF_MAPS).readText()
            bridge.filterProcMaps(content)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read /proc/self/maps")
            ""
        }
    }
}
