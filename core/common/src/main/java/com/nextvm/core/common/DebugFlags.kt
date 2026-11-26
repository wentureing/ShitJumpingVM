package com.nextvm.core.common

import com.nextvm.core.common.diagnostics.DiagOutput

/**
 * 统一调试开关（v145）。
 *
 * 设计目标（按用户要求）：**对 release 与代码管理危害最小**、能用现成工具。
 *
 * 1. **release 全关（编译期）**：总闸是 [ENABLED] = `BuildConfig.DEBUG`（编译期常量）。
 *    release variant 里它是 `false` 字面量，R8 把调试分支整段删除 ——
 *    release 包里不存在开关读取、反射、文件写入，也不会产生任何输出文件。
 * 2. **可运行时切换**：开关走 Android property（`debug.` 前缀不受 property 白名单限制），
 *    无需重新构建即可二分定位：
 *
 *        adb shell setprop debug.nextvm.javaIo 0
 *        adb shell setprop debug.nextvm.javaIoTranslate 0
 *        adb shell setprop debug.nextvm.verbose 1
 *
 * 3. **集中管理**：所有 flag 名字都定义在本文件里（[JAVA_IO] 等），
 *    插桩处只写 `DebugFlags.isOn(DebugFlags.JAVA_IO, true)`，不再散落字符串。
 */
object DebugFlags {

    /**
     * **编译期总闸**。
     *
     * `BuildConfig.DEBUG` 在 release variant 里是 `false` 字面量（AGP 生成，
     * 不是 `parseBoolean`），因此 R8 能把所有 `isDebugBuild()`/`ENABLED` 的 false
     * 分支整段删除：release 包里**不存在**开关读取、反射、文件写入这些代码。
     *
     * debug 包为 true，才按下面的 property 决定各开关。
     *
     * 注：不能用 `const val`（Kotlin 不把跨模块 Java `static final` 视作编译期常量），
     * 但 `val` + 单例 + final 仍能被 R8 内联并折叠。
     */
    @JvmField
    val ENABLED: Boolean = BuildConfig.DEBUG

    private const val PREFIX = "debug.nextvm."
    private const val CACHE_TTL_MS = 1000L

    /** guest 进程是否安装 Java 层文件 I/O hook（默认 true）。 */
    const val JAVA_IO = "javaIo"

    /**
     * 已安装的 Java 层 hook 是否真的翻译路径（默认 true）。
     * 置 0 可把「hook 存在」与「路径翻译」两种影响分离开。
     */
    const val JAVA_IO_TRANSLATE = "javaIoTranslate"

    /** 详细输出（目录方法调用、open 命中/未命中），默认 false。 */
    const val VERBOSE = "verbose"

    /** guest 进程是否注册路径重定向规则（v142 引入，默认 true）。 */
    const val GUEST_REDIRECT = "guestRedirect"

    /**
     * 把 GuestBreadcrumb 的既有埋点镜像到诊断层（checks.log）。
     * 默认开：用户明确要求启用；单进程限流 200 条，避免刷屏。
     */
    const val CRUMB_TO_DIAG = "crumbToDiag"

    /** WebView dataDir 假值（白名单 guest，默认 true）。 */
    const val WEBVIEW_DATA_DIR_FAKE = "webviewDataDirFake"

    /**
     * guest 启动是否走 framework LoadedApk bind（默认 true）。
     *
     * 置 `0` 强制走 legacy bind —— 只用于采集 legacy 路径的启动链 golden
     * （见 `docs/输出/变更/20261126-legacy-bind-golden.md`）。classloader 仍由 framework 建，
     * 只让 VAM 收不到 LoadedApk。
     */
    const val FRAMEWORK_BIND = "frameworkBind"

    private class Cached(val value: Boolean, val at: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    @Volatile
    private var initialized = false

    /**
     * 初始化（只在 debug 包做任何事）：挂文件日志 + 记录开关快照。
     * release 下 [ENABLED] 为 false，本方法直接返回，不会产生文件。
     */
    fun init(context: android.content.Context?) {
        if (!ENABLED) return
        if (initialized) return
        initialized = true
        DebugTrace.init(context?.filesDir)
        DiagOutput.setDir(context?.filesDir)
        DebugTrace.event("flags", "BuildConfig.DEBUG=true, " + snapshot())
    }

    /** 是否为可调试构建（编译期常量，release 下为 false）。 */
    fun isDebugBuild(): Boolean = ENABLED

    /**
     * 读取开关。[name] 用本文件里的常量。
     *
     * release（非 debug 构建）下固定返回 false —— 该分支会被 R8 删除。
     */
    fun isOn(name: String, default: Boolean): Boolean {
        if (!ENABLED) return false
        val now = android.os.SystemClock.elapsedRealtime()
        cache[name]?.let { if (now - it.at < CACHE_TTL_MS) return it.value }
        val raw = readProperty(PREFIX + name)
        val value = when {
            raw == null || raw.isEmpty() -> default
            raw == "0" || raw.equals("false", true) || raw.equals("off", true) -> false
            raw == "1" || raw.equals("true", true) || raw.equals("on", true) -> true
            else -> default
        }
        cache[name] = Cached(value, now)
        return value
    }

    /** 供诊断输出：当前所有已知开关的取值（每项用自己的真实默认值，避免误报）。 */
    fun snapshot(): String = listOf(
        JAVA_IO to false,
        JAVA_IO_TRANSLATE to true,
        VERBOSE to true,
        GUEST_REDIRECT to true,
        WEBVIEW_DATA_DIR_FAKE to true,
        FRAMEWORK_BIND to true
    ).joinToString(", ") { (n, d) -> n + "=" + isOn(n, d) }

    private fun readProperty(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        get.invoke(null, key, null) as? String
    } catch (_: Throwable) {
        null
    }
}
