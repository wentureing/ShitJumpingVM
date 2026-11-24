package com.nextvm.core.hook

/**
 * `/proc` 伪造与系统属性覆盖（v154 从 `NativeHookBridge` 拆出）。
 *
 * ## 职责
 * 让 guest 在 `/proc` 与系统属性层面"看起来像自己":
 * - `spoofProcSelf(pid, pkg)`：注册 `/proc/self/{cmdline,comm,status}` 的伪造内容
 * - `setProcSpoofSuspended(b)`：临时关闭 /proc 伪造（某些客户端的 mini-linker 要在
 *   构造时解析**真实** maps，见 CM/SecNeo 案例）
 * - `setProcessName(name)`：用 `prctl` 把内核 comm 改成 guest 名（补足"内核视角"）
 * - `armSeccompDiag(path)`：启用 raw-syscall 诊断（不改结果，只记录）
 * - `spoofSystemProperty` / `spoofSystemProperties`：`__system_property_get` 覆盖
 *
 * ## 不负责
 * - ❌ 伪造内容的**存储**（通过 [setFakeContent] 回写到 `NativeHookBridge.fakeFileContent`）
 * - ❌ native 实现（全部通过回调注入，native 未就绪时为空操作）
 * - ❌ 路径规则 / 隐藏路径（`PathRedirectRegistry` / `NativeHookBridge`）
 *
 * ## 偏差登记
 *
 * | # | 偏差 | 相对谁 | 类型 | 说明 |
 * | --- | --- | --- | --- | --- |
 * | 1 | `/proc/self/*` 返回伪造内容 | AOSP 返回真实内核信息 | ✅ **必要偏差** | 否则 guest 能直接看到容器包名/进程名；这是容器隐匿的基础能力 |
 * | 2 | `setProcSpoofSuspended` 可临时关闭 | — | ✅ **必要偏差** | 实测：CM 客户的 SecNeo mini-linker 在构造时解析真实 maps 来解析 libc 符号；伪造的 maps 会让其注册链静默失败 |
 * | 3 | 属性覆盖只对 `__system_property_get` 生效 | — | ⚠️ **能力边界** | 直接读 `/dev/__properties__` 或 `__system_property_foreach` 的代码看不到覆盖。若客户用这些 API，需要扩展 hook |
 * | 4 | `spoofedPid` 只影响伪造内容与日志 | — | ✅ | 不改变真实 pid（Binder 里仍是真实值） |
 *
 * ## 约束与坑
 * 1. **必须在 guest 进程调用**（`/proc/self` 是进程级的）；主进程不需要。
 * 2. `comm` 有 **15 字符上限**（Linux 内核限制），超长会被截断。
 * 3. 伪造内容写进 `fakeFileContent` 后，**读取路径必须已被 hook**（`open` 等）才会生效；
 *    直接 `syscall` 读的仍然拿到真实内容（这是能力边界，见偏差 #3）。
 * 4. native 未就绪时所有方法只更新 Kotlin 侧状态，不报错（保证 bind 早期可调用）。
 */
internal class ProcSpoofing(
    /** 把伪造内容写入读取层（通常是 `NativeHookBridge.fakeFileContent`）。 */
    private val setFakeContent: (String, ByteArray) -> Unit,
    /** native 层是否可用。 */
    private val nativeAvailable: () -> Boolean,
    private val nativeSpoofProcSelf: (Int, String) -> Unit,
    private val nativeSetProcSpoofSuspended: (Boolean) -> Unit,
    private val nativeSetProcessName: (String) -> Unit,
    private val nativeArmSeccompDiag: (String) -> Unit,
    private val nativeSpoofSystemProperty: (String, String) -> Unit
) {

    companion object {
        const val PROC_SELF_CMDLINE = "/proc/self/cmdline"
        const val PROC_SELF_COMM = "/proc/self/comm"
        const val PROC_SELF_STATUS = "/proc/self/status"

        private const val TAG = "ProcSpoof"
    }

    var spoofedPid: Int = -1
        private set

    var spoofedPackageName: String? = null
        private set

    var spoofedProcessName: String? = null
        private set

    private val propertyOverrides = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 当前属性覆盖快照（供诊断）。 */
    fun propertyOverridesSnapshot(): Map<String, String> = propertyOverrides.toMap()

    fun spoofProcSelf(pid: Int, packageName: String) {
        spoofedPid = pid
        spoofedPackageName = packageName
        spoofedProcessName = packageName

        // /proc/self/cmdline：以 NUL 结尾
        setFakeContent(PROC_SELF_CMDLINE, packageName.toByteArray() + byteArrayOf(0))
        // /proc/self/comm：Linux 上限 15 字符
        val commName = packageName.take(15)
        setFakeContent(PROC_SELF_COMM, "$commName\n".toByteArray())
        // /proc/self/status：伪造 Name/Pid 等
        setFakeContent(PROC_SELF_STATUS, buildFakeProcStatus(pid, packageName))

        if (nativeAvailable()) {
            nativeSpoofProcSelf(pid, packageName)
        }
        timber.log.Timber.tag(TAG).d("/proc/self spoofed: pid=$pid, pkg=$packageName")
    }

    fun setProcSpoofSuspended(suspended: Boolean) {
        if (nativeAvailable()) {
            nativeSetProcSpoofSuspended(suspended)
        }
        timber.log.Timber.tag(TAG).d("/proc spoof suspended=$suspended")
    }

    fun setProcessName(name: String) {
        if (nativeAvailable()) {
            nativeSetProcessName(name)
        }
        timber.log.Timber.tag(TAG).d("Process renamed to guest: $name")
    }

    fun armSeccompDiag(logPath: String) {
        if (nativeAvailable()) {
            nativeArmSeccompDiag(logPath)
        }
    }

    fun spoofSystemProperty(key: String, value: String) {
        propertyOverrides[key] = value
        if (nativeAvailable()) {
            nativeSpoofSystemProperty(key, value)
        }
        timber.log.Timber.tag(TAG).d("System property override: $key=$value")
    }

    fun spoofSystemProperties(properties: Map<String, String>) {
        properties.forEach { (key, value) -> spoofSystemProperty(key, value) }
    }

    /** 清空所有伪造状态（`NativeHookBridge.cleanup()` 调用）。 */
    fun reset() {
        propertyOverrides.clear()
        spoofedPackageName = null
        spoofedPid = -1
        spoofedProcessName = null
    }

    /**
     * 生成伪造的 `/proc/self/status`（纯函数，可单测）。
     *
     * 只保留 guest 会读的字段，并统一成"像普通应用进程"的样子。
     */
    fun buildFakeProcStatus(pid: Int, packageName: String): ByteArray {
        val name = packageName.take(15)
        return """
            |Name:	$name
            |State:	S (sleeping)
            |Tgid:	$pid
            |Pid:	$pid
            |PPid:	1
            |TracerPid:	0
            |Uid:	10123	10123	10123	10123
            |Gid:	10123	10123	10123	10123
            |Threads:	42
            |SigPnd:	0000000000000000
            |ShdPnd:	0000000000000000
        """.trimMargin().toByteArray()
    }
}
