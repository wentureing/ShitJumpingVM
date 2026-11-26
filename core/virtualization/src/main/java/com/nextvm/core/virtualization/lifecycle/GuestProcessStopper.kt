package com.nextvm.core.virtualization.lifecycle

import android.os.Process
import java.io.File

/**
 * 真正停掉 guest 进程（`terminateApplication` 只做逻辑终止，进程仍在）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/GuestProcessStopper.md`
 */
internal object GuestProcessStopper {

    /** 一条 /proc 快照（纯数据，便于 JVM 测试）。 */
    data class ProcEntry(val pid: Int, val cmdline: String, val uid: Int)

    data class StopResult(val pids: List<Int>, val killed: Int) {
        val ok: Boolean get() = pids.isNotEmpty() && killed == pids.size
    }

    /**
     * 从快照里找出属于 [uid]、进程名为 [pkg] 或 `pkg:xxx` 的 pid。
     *
     * 只在同 uid 内匹配：容器与 guest 同 uid，跨 uid 的 `kill` 本来也没权限。
     */
    fun findPids(entries: List<ProcEntry>, pkg: String, uid: Int): List<Int> {
        if (pkg.isEmpty()) return emptyList()
        return entries
            .filter { it.uid == uid && (it.cmdline == pkg || it.cmdline.startsWith("$pkg:")) }
            .map { it.pid }
            .distinct()
            .sorted()
    }

    /** 读 `/proc` 快照（跳过自身与读不到的进程）。 */
    fun readProcEntries(): List<ProcEntry> {
        val out = ArrayList<ProcEntry>()
        val self = Process.myPid()
        val procDir = File("/proc")
        val dirs = runCatching { procDir.listFiles() }.getOrNull() ?: return out
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == self) continue
            val cmdline = runCatching { File(d, "cmdline").readText() }
                .getOrDefault("")
                .substringBefore('\u0000')
                .trim()
            if (cmdline.isEmpty()) continue
            val uid = runCatching {
                File(d, "status").readLines()
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.split('\t')?.getOrNull(1)?.trim()?.toIntOrNull()
            }.getOrNull() ?: continue
            out.add(ProcEntry(pid, cmdline, uid))
        }
        return out
    }

    /**
     * 停掉 [pkg] 的全部进程（SIGKILL）。
     *
     * 调用前应先做逻辑终止（provider.shutdown / onTerminate），否则拿不到优雅清理。
     */
    fun stop(pkg: String): StopResult {
        val pids = findPids(readProcEntries(), pkg, Process.myUid())
        var killed = 0
        for (pid in pids) {
            val done = runCatching {
                Process.sendSignal(pid, SIGNAL_KILL)
                true
            }.getOrDefault(false)
            if (done) killed++
        }
        return StopResult(pids, killed)
    }

    private const val SIGNAL_KILL = 9
}
