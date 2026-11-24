package com.nextvm.core.hook

/**
 * /proc 伪造（cmdline/comm/status）与系统属性覆盖。
 * 详见 `docs/代码镜像/core/hook/ProcSpoofing.md`。
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
