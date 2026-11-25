package com.nextvm.core.model

/**
 * Represents a process slot in the virtual engine.
 * Each slot corresponds to one guest app process (:p0, :p1, etc.)
 */
data class ProcessSlot(
    val slotIndex: Int,
    val processName: String,
    val assignedInstanceId: String? = null,
    val isOccupied: Boolean = false,
    val pid: Int = -1
)

/**
 * Stub component mapping for a process slot.
 * Each slot has pre-declared stub Activities, Services, Providers, Receivers.
 */
data class StubMapping(
    val processSlot: Int,
    val stubActivityStandard: List<String>,
    val stubActivitySingleTop: List<String>,
    val stubActivitySingleTask: List<String>,
    val stubActivitySingleInstance: List<String>,
    val stubServices: List<String>,
    val stubProviders: List<String>,
    val stubReceivers: List<String>
) {
    /** Get all stub activity class names for this slot */
    fun allActivities(): List<String> =
        stubActivityStandard + stubActivitySingleTop + stubActivitySingleTask + stubActivitySingleInstance

    /** Get all stub component class names for this slot */
    fun allComponents(): List<String> =
        allActivities() + stubServices + stubProviders + stubReceivers
}

/**
 * Intent extras keys used for stub-to-guest mapping.
 */
object VirtualIntentExtras {
    const val TARGET_PACKAGE = "_nextvm_target_pkg"
    const val TARGET_ACTIVITY = "_nextvm_target_activity"
    const val TARGET_SERVICE = "_nextvm_target_service"
    const val INSTANCE_ID = "_nextvm_instance_id"
    const val APK_PATH = "_nextvm_apk_path"
    const val PROCESS_SLOT = "_nextvm_process_slot"
    const val LAUNCH_MODE = "_nextvm_launch_mode"
    // Caller identity added by VirtualContext before an AMS start/bind.
    // Kept separate from stub-target metadata: a guest can construct an
    // explicit Intent whose component package is still the host package.
    const val CALLER_INSTANCE = "_nextvm_caller_instance"
    const val CALLER_PACKAGE = "_nextvm_caller_pkg"
}

/**
 * GMS service routing interface.
 * Implemented by VirtualGmsManager, used by ActivityManagerProxy
 * to route GMS bindService/startService calls through the Hybrid GMS bridge.
 *
 * This interface lives in core:model so both core:binder and core:services
 * can reference it without circular dependencies.
 */
interface GmsServiceRouter {
    /** Check if this intent targets a GMS or Play Store service */
    fun isGmsServiceIntent(intent: android.content.Intent): Boolean

    /** Route a GMS bind intent and return a proxied IBinder, or null if not handled */
    fun routeGmsBindIntent(
        intent: android.content.Intent,
        instanceId: String,
        guestPackageName: String
    ): android.os.IBinder?

    /** Intercept and route a GMS auth intent, returns modified intent or null */
    fun interceptAuthIntent(
        intent: android.content.Intent,
        instanceId: String
    ): android.content.Intent?

    /** Check if a package name belongs to GMS ecosystem */
    fun isGmsPackage(packageName: String): Boolean

    /** Get synthesized PackageInfo for a GMS package (gms, gsf, vending) */
    fun synthesizeGmsPackageInfo(packageName: String): android.content.pm.PackageInfo?

    /**
     * Get the isolated Google accounts for a specific virtual instance.
     * Returns null if the instance has no virtual account yet (guest hasn't signed in).
     * Returns an empty list to mean "no accounts" (hide host accounts from guest).
     *
     * Used by AccountManagerProxy to enforce per-instance account isolation:
     * - If the instance has signed in → return only that instance's account
     * - If not yet signed in → return null (fall back to host accounts for sign-in flow)
     */
    fun getVirtualGoogleAccounts(instanceId: String): List<android.accounts.Account>?
}

/**
 * Constants for the virtual engine.
 */
object VirtualConstants {
    /** Maximum number of simultaneous virtual app processes */
    const val MAX_PROCESS_SLOTS = 10

    /** Number of standard stub Activities per process slot */
    const val STUB_ACTIVITIES_STANDARD = 5

    /** Number of singleTop stub Activities per process slot */
    const val STUB_ACTIVITIES_SINGLE_TOP = 2

    /** Number of singleTask stub Activities per process slot */
    const val STUB_ACTIVITIES_SINGLE_TASK = 2

    /** Number of singleInstance stub Activities per process slot */
    const val STUB_ACTIVITIES_SINGLE_INSTANCE = 1

    /** Number of stub Services per process slot */
    const val STUB_SERVICES = 5

    /** Number of stub ContentProviders per process slot */
    const val STUB_PROVIDERS = 3

    /** Number of stub BroadcastReceivers per process slot */
    const val STUB_RECEIVERS = 2

    /** Base directory name for virtual storage */
    const val VIRTUAL_DIR = "virtual"

    /** APK storage subdirectory */
    const val VIRTUAL_APKS_DIR = "apks"

    /** Per-app data subdirectory */
    const val VIRTUAL_DATA_DIR = "data"

    /** Snapshots subdirectory */
    const val VIRTUAL_SNAPSHOTS_DIR = "snapshots"

    /** Host app package name */
    const val HOST_PACKAGE = "com.nextvm.app"

    /** Stub class prefix */
    const val STUB_PREFIX = "com.nextvm.app.stub"

    // ============ v135 path fidelity (experiment, CM-gated) ============
    // Synthetic stock-install token. MUST match native-hook.cpp's
    // synthetic_apk_path()/synthetic_lib_path() token (VmK8x2) so the
    // Java-layer paths agree with what /proc/self/maps already shows.
    const val CM_PACKAGE = "com.greenpoint.android.mc10086.activity"
    const val SYNTHETIC_INSTALL_TOKEN = "VmK8x2"

    /**
     * 走 pathFidelity 的包名白名单 —— **可编辑**。
     *
     * 在白名单里 = 该 guest 的 data 目录走"合成路径"（看起来像真机安装，
     * 实际 I/O 由 v135 规则映射回沙箱）：
     *   getFilesDir()  -> /data/user/0/<pkg>/files        （假路径，实际不存在）
     *   getDataDir()   -> /data/user/0/<pkg>
     *   NativeHookBridge 把 /data/user/0/<pkg>/ 映射回 <sandbox>/
     *
     * 不在白名单 = 直接用沙箱路径（会暴露 <hostPkg>/files/virtual/...）。
     *
     * 已知缺陷（重要）：合成路径只含包名、**不含 instanceId**。
     * 同一个 app 装两份时，两个实例的合成路径相同 -> 规则表是
     * ConcurrentHashMap<from, to>，后注册的覆盖前者 -> 先注册的实例所有 I/O
     * 跑到另一个实例的沙箱（数据损坏级）。
     * **当前只支持单实例**。多实例需要改成 /data/user/<10+slot>/<pkg> 之类的按槽位映射。
     *
     * 已知缺口：external 目录（getExternalFilesDir/getObbDir/getExternalMediaDirs）
     * **没有** pathFidelity 分支，仍返回 <sandbox>/sdcard/Android/data/<pkg>/...，
     * 所以即使在本白名单里，外部存储仍然暴露沙箱。
     */
    private val PATH_FIDELITY_WHITELIST: Set<String> = setOf(
        // 原始定向对抗目标（com.greenpoint.android.mc10086.activity）
        CM_PACKAGE,
        // via：验证 file:// 与 WebView MIME 行为（原先 pathFidelity 只对 CM_PACKAGE 开）
        "mark.via",
    )

    /**
     * 是否对 packageName 启用 pathFidelity（= 是否产生合成路径）。
     *
     * **豁免优先**：PathSpoofConfig 里的豁免名单优先级最高，命中则一律返回 false，
     * 不产生任何合成路径（但仍隔离到沙箱，规则由 NativeHookBridge 照常注册）。
     */
    fun isPathFidelityGuest(packageName: String): Boolean {
        if (PathSpoofConfig.isGuestExempt(packageName)) return false
        return packageName in PATH_FIDELITY_WHITELIST
    }


    /** 合成 data 根（模板唯一来源：[PathKinds]）。 */
    fun syntheticDataDir(packageName: String): String =
        PathKinds.synthetic(PathKind.DATA_ROOT, packageName)

    /** 合成 APK 路径（token 必须与 native maps 伪造一致）。 */
    fun syntheticApkPath(packageName: String): String =
        PathKinds.synthetic(PathKind.APK, packageName)

    /** 合成 external data 根：/storage/emulated/0/Android/data/<pkg>（真机形态，实际不存在） */
    fun syntheticExternalDir(packageName: String): String =
        PathKinds.synthetic(PathKind.EXTERNAL_DATA, packageName)

    /** 合成 external cache 目录（与 files 同级） */
    fun syntheticExternalCacheDir(packageName: String): String =
        PathKinds.synthetic(PathKind.EXTERNAL_CACHE, packageName)

    /** 合成 media 目录（getExternalMediaDirs） */
    fun syntheticMediaDir(packageName: String): String =
        PathKinds.synthetic(PathKind.MEDIA, packageName)

    /** 合成 OBB 目录 */
    fun syntheticObbDir(packageName: String): String =
        PathKinds.synthetic(PathKind.OBB, packageName)

    /** 合成 native lib 目录。 */
    fun syntheticLibDir(packageName: String): String =
        PathKinds.synthetic(PathKind.NATIVE_LIB, packageName)
}
