package com.nextvm.core.common

/**
 * 统一调试开关（v145）。
 *
 * 设计目标（按用户要求）：**对 release 与代码管理危害最小**、能用现成工具。
 *
 * 1. **release 自动全关**：以 `ApplicationInfo.FLAG_DEBUGGABLE` 为总闸。
 *    release 包该位为 0 -> [isOn] 一律返回 false -> 不写文件、不读 property、不反射。
 *    因此 release 里只剩「一次 volatile 读」，没有可观测开销，也不会产生任何输出文件。
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

    /** WebView dataDir 假值（白名单 guest，默认 true）。 */
    const val WEBVIEW_DATA_DIR_FAKE = "webviewDataDirFake"

    private class Cached(val value: Boolean, val at: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    @Volatile
    private var debuggable = false

    @Volatile
    private var initialized = false

    /**
     * 初始化：判断是否 debuggable 包，并（仅当 debuggable 时）挂上文件日志。
     * 可在任意进程调用；重复调用无副作用。
     */
    fun init(context: android.content.Context?) {
        if (initialized) return
        initialized = true
        debuggable = try {
            val flags = context?.applicationInfo?.flags ?: 0
            (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            false
        }
        // 只有可调试包才落盘，release 不产生任何文件
        DebugTrace.init(if (debuggable) context?.filesDir else null)
        if (debuggable) {
            DebugTrace.event(
                "flags",
                "debuggable=true, " + snapshot()
            )
        }
    }

    /** 是否为可调试包（总闸）。 */
    fun isDebugBuild(): Boolean = debuggable

    /**
     * 读取开关。[name] 用本文件里的常量。
     *
     * release（非 debuggable）下固定返回 false —— 调用点因此成为死分支。
     */
    fun isOn(name: String, default: Boolean): Boolean {
        if (!debuggable) return false
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

    /** 供诊断输出：当前所有已知开关的取值。 */
    fun snapshot(): String = listOf(
        JAVA_IO, JAVA_IO_TRANSLATE, VERBOSE, GUEST_REDIRECT, WEBVIEW_DATA_DIR_FAKE
    ).joinToString(", ") { n -> n + "=" + isOn(n, true) }

    private fun readProperty(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        get.invoke(null, key, null) as? String
    } catch (_: Throwable) {
        null
    }
}
