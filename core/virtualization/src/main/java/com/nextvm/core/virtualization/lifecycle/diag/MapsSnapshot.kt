package com.nextvm.core.virtualization.lifecycle.diag

import java.io.File

/**
 * 内核视角的 maps 快照（v59）：解析 /proc/self/maps 文本并由纯函数产出统计。
 *
 * 详见 docs/代码镜像/core/virtualization/lifecycle/diag/MapsSnapshot.md
 */
internal class RealSnap(
    val lines: Int,
    val execRanges: Set<String>,
    val anonExecBytes: Long,
    val bigAnon: List<String>,
    val dexHelper: List<String>,
    val memfd: List<String>,
    val text: String,
)

internal object MapsSnapshot {

    /**
     * 解析 maps 文本（纯函数，不读文件）。
     *
     * 只统计：可执行段的匿名区间（无 path）、0x80000..0x200000 的"大匿名段"、
     * 含 DexHelper 的行、含 memfd 的行。file-backed 的可执行段只进 execRanges。
     */
    fun parse(text: String): RealSnap {
        val ws = Regex("\\s+")
        var anonBytes = 0L
        val exec = HashSet<String>()
        val big = ArrayList<String>()
        val dex = ArrayList<String>()
        val memfd = ArrayList<String>()
        val lines = text.split('\n').filter { it.isNotBlank() }
        for (ln in lines) {
            val parts = ln.trim().split(ws, 6)
            if (parts.size < 2) continue
            val range = parts[0]
            val perms = parts[1]
            val path = if (parts.size >= 6) parts[5].trim() else ""
            if (ln.contains("DexHelper")) dex.add(ln.trim())
            if (path.contains("memfd")) memfd.add(ln.trim())
            if (!perms.contains('x')) continue
            exec.add(range)
            if (path.isNotEmpty()) continue
            val se = range.split("-")
            if (se.size != 2) continue
            val start = se[0].toLongOrNull(16) ?: continue
            val end = se[1].toLongOrNull(16) ?: continue
            val sz = end - start
            anonBytes += sz
            if (sz in 0x80000L..0x200000L) {
                big.add("0x" + sz.toString(16) + "/" + perms + "@" + range)
            }
        }
        return RealSnap(lines.size, exec, anonBytes, big, dex, memfd, text)
    }

    /**
     * 生成 v59 realmaps 日志行（纯函数）。
     *
     * `prev` 为 null 时按"首次快照"计；否则只统计新增的可执行区间与大匿名段
     * （大匿名段按 `@` 之后的地址区间比较，大小变化不算新增）。
     */
    fun formatLine(tag: String, snap: RealSnap?, prev: RealSnap?): String {
        if (snap == null) return "v59 realmaps $tag UNAVAILABLE"
        val newBig = if (prev != null) {
            snap.bigAnon.filter { b ->
                prev.bigAnon.none {
                    it.substringAfter('@') == b.substringAfter('@')
                }
            }
        } else {
            snap.bigAnon
        }
        val newExec = if (prev != null) {
            (snap.execRanges - prev.execRanges).size
        } else {
            snap.execRanges.size
        }
        return "v59 realmaps " + tag + " lines=" + snap.lines +
            " anonExecBytes=0x" + snap.anonExecBytes.toString(16) +
            " newExecRanges=" + newExec +
            " bigAnon=" + snap.bigAnon +
            " newBigAnon=" + newBig +
            " dexHelper=" + snap.dexHelper +
            " memfd=" + snap.memfd
    }

    /** 写 `realmaps-<pid>-<tag>.txt` 到给定 directories；返回文件名。失败抛出，由调用方记日志。 */
    fun dump(filesDir: File, pid: Int, tag: String, snap: RealSnap): String {
        val f = File(filesDir, "realmaps-$pid-$tag.txt")
        f.writeText(snap.text)
        return f.name
    }
}
