package com.nextvm.core.common.diagnostics

import java.io.File

/**
 * 诊断文件名与目录的**唯一规范**（代码侧）。探针（独立 APK）按同样规则扫描，见 KDoc。
 *
 * 规则：
 * - 全部落在 `<filesDir>` 下，平铺，不建子目录；
 * - 带进程区分的文件用 `-<pid>.log` 后缀（breadcrumb / stackdump / hijacktrace / webviewwarm / webviewwatch）；
 * - 固定名通道：`debug-trace.log`、`checks.log`、`seccomp-diag.log`、各 `v1xx-*-probe.log`。
 *
 * 探针侧对应实现：`probe/src/main/java/com/sandbox/probe/ProbeRunner.java` 的 D 系列（按同规则匹配）。
 */
object DiagFiles {

    const val DEBUG_TRACE = "debug-trace.log"
    const val CHECKS = "checks.log"
    const val SECCOMP_DIAG = "seccomp-diag.log"

    fun breadcrumb(pid: Int): String = "breadcrumb-$pid.log"
    fun stackDump(pid: Int): String = "stackdump-$pid.log"
    fun hijackTrace(pid: Int): String = "hijacktrace-$pid.log"
    fun startActivityTrace(pid: Int): String = "startactivity-trace-$pid.log"
    fun webViewWarm(pid: Int): String = "webviewwarm-$pid.log"
    fun webViewWatch(pid: Int): String = "webviewwatch-$pid.log"

    fun stateProbe(pid: Int): String = "stateprobe-$pid.log"
    fun dexMem(pid: Int): String = "dexmem-$pid.log"
    fun shortcutProbe(pid: Int): String = "shortcut-probe-$pid.log"
    fun memDexDump(pid: Int, index: Int): String = "memdex-$pid-$index.dex"

    fun v139Probe(): String = "v139-probe.log"
    fun v144IntentProbe(): String = "v144-intent-probe.log"
    fun v145IntentProbe(): String = "v145-intent-probe.log"
    fun v148ConnectProbe(): String = "v148-connect-probe.log"

    /** `v155-init` / `v156-load` / `v155-click` 这类过滤结果。 */
    fun filtered(prefix: String): String = "$prefix-filtered.log"

    /** API trace 原始文件（非 `.log`）。 */
    fun trace(prefix: String, pid: Int): String = "$prefix-$pid-1.trace"

    /**
     * 给 C++ `is_container_diag_path` 的标记（**唯一来源**：
     * `core/common/src/main/resources/diag_channels.tsv`，见 `tools/gen/gen-diag-channels.mjs`）。
     * 读不到配置时回退内置表，保证 release/异常环境行为不变。
     */
    fun markers(): Array<String> = markersFromConfig() ?: FALLBACK_MARKERS

    private fun markersFromConfig(): Array<String>? = try {
        DiagFiles::class.java.getResourceAsStream(CONFIG_RESOURCE)?.bufferedReader()?.use { r ->
            val out = ArrayList<String>()
            r.forEachLine { raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    val cols = line.split('\t')
                    if (cols.size >= 2) cols[1].trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            }
            out.toTypedArray().takeIf { it.isNotEmpty() }
        }
    } catch (_: Throwable) {
        null
    }

    private const val CONFIG_RESOURCE = "/diag_channels.tsv"

    private val FALLBACK_MARKERS: Array<String> = arrayOf(
        DEBUG_TRACE, CHECKS, SECCOMP_DIAG,
        "breadcrumb-", "stackdump-", "hijacktrace-", "startactivity-trace-",
        "webviewwarm-", "webviewwatch-", "stateprobe-", "dexmem-",
        "shortcut-probe-", "memdex-",
        "v139-probe.log", "v144-intent-probe.log", "v145-intent-probe.log",
        "v148-connect-probe.log", "-filtered.log", "-1.trace",
    )

    /** 扫 `<filesDir>` 下所有诊断文件（按名字排序）。 */
    fun list(filesDir: File): List<File> = try {
        filesDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".dex")) }
            ?.sortedBy { it.name }
            ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** 诊断文件总大小（字节），用于判断"是否爆盘"。 */
    fun totalBytes(filesDir: File): Long = list(filesDir).sumOf { it.length() }
}
