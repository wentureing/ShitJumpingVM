package com.nextvm.core.virtualization.lifecycle.diag

import android.app.Application
import android.os.Build
import com.nextvm.core.virtualization.lifecycle.DiagSuspendGate
import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb

/**
 * guest 侧转储工具：线程栈 / 内存 dex / 小端读取。
 *
 * 从 VirtualApplicationManager 抽出（纯诊断，不含启动链控制流）；
 * 说明见 docs/输出/变更/20261125-B4-VirtualApplicationManager拆包.md
 */
internal object GuestMemoryDump {

    fun dumpGuestStacks(out: java.io.File, label: String) {
        val sb = StringBuilder()
        sb.append("==== ").append(label).append(" ====\n")
        // Main Looper pending messages first: a scheduled-but-never-
        // fired navigation message (or an empty queue) is the fastest
        // discriminator for splash stalls.
        try {
            val queue = android.os.Looper.getMainLooper().queue
            val mf = queue.javaClass.getDeclaredField("mMessages")
            mf.isAccessible = true
            var msg = mf.get(queue)
            var n = 0
            sb.append("main looper queue:\n")
            while (msg != null && n < 20) {
                val cls = msg.javaClass
                val whenF = cls.getDeclaredField("when").apply { isAccessible = true }
                val cbF = cls.getDeclaredField("callback").apply { isAccessible = true }
                val tgtF = cls.getDeclaredField("target").apply { isAccessible = true }
                val whatF = cls.getDeclaredField("what").apply { isAccessible = true }
                val nextF = cls.getDeclaredField("next").apply { isAccessible = true }
                sb.append("  when=+").append(
                    (whenF.getLong(msg) - android.os.SystemClock.uptimeMillis()) / 1000.0
                ).append("s what=").append(whatF.getInt(msg))
                    .append(" callback=").append(cbF.get(msg)?.javaClass?.name)
                    .append(" target=").append(tgtF.get(msg)?.javaClass?.name)
                    .append('\n')
                msg = nextF.get(msg)
                n++
            }
            if (n == 0) sb.append("  (empty)\n")
        } catch (e: Throwable) {
            sb.append("main looper queue: <").append(e.javaClass.simpleName).append(">\n")
        }
        // v42: serialize the suspension on DiagSuspendGate.
        // getAllStackTraces() suspends every thread; in launch58
        // this dump (+8s) and StateProbe's census (+7s) suspended
        // concurrently and ART's suspend watchdog aborted the guest
        // (SuspendThreadByPeer timeout). StateProbe no longer
        // suspends at all; this gate keeps any future diagnostic
        // suspender from overlapping this one.
        val traces = synchronized(DiagSuspendGate.lock) {
            Thread.getAllStackTraces()
        }
        // Main thread first, then the rest (Rx/pool/player threads are
        // the interesting waiters).
        val ordered = traces.keys.sortedWith(
            compareBy({ if (it.name == "main") 0 else 1 }, { it.name })
        )
        for (t in ordered) {
            sb.append("--- \"").append(t.name).append("\" ").append(t.state).append('\n')
            val frames = traces[t] ?: continue
            for ((i, f) in frames.withIndex()) {
                if (i >= 30) break
                sb.append("    at ").append(f.toString()).append('\n')
            }
        }
        try {
            out.appendText(sb.toString())
        } catch (_: Throwable) {
        }
    }

    /**
     * v121 probe (read-only, background thread): dump every dex
     * blob reachable in this process's own address space so the
     * initLoader caller search can run offline with dexdump.
     * Scans only anonymous readable mappings (the shell's decrypted
     * dex is anonymous; file-backed host/framework dex is skipped),
     * skips JIT code caches, and validates each candidate header
     * (magic, header_size 0x70, endian 0x12345678, file_size in
     * range and inside its mapping) before writing it. Caps: 32
     * blobs, 512MB total. A magic split across a 1MB chunk boundary
     * would be missed (accepted: real mmapped dex is page-aligned).
     * VMP caveat: if the caller method itself is VM-protected, its
     * body is VM bytecode, not dex bytecode, and no dump will show
     * the call — that outcome is itself a finding.
     * v150: also scan /memfd: mappings — the shell's MAIN decrypted
     * dex is likely memfd-backed (InMemoryDexClassLoader), which the
     * anonymous-only filter skipped (hence only small dexes were
     * ever dumped). Blob cap raised to 64 so small dexes found
     * first cannot crowd out the main dex; 512MB total cap kept.
     */
    fun dumpMemoryDex(application: Application) {
        val pid = android.os.Process.myPid()
        GuestBreadcrumb.log("v150-memdex scan started pid=$pid memfd=on")
        val dumpedRanges = ArrayList<Pair<Long, Long>>()
        val dumpedKeys = HashSet<String>()
        var count = 0
        var totalBytes = 0L
        val maxBlobs = 64
        val maxTotal = 512L * 1024L * 1024L
        try {
            val maps = java.io.File("/proc/self/maps").readLines()
            val mem = java.io.RandomAccessFile("/proc/self/mem", "r")
            try {
                val chunk = ByteArray(1024 * 1024)
                for (line in maps) {
                    if (count >= maxBlobs || totalBytes >= maxTotal) break
                    val m = Regex(
                        "^([0-9a-f]+)-([0-9a-f]+)\\s+(\\S+)\\s+\\S+\\s+" +
                            "\\S+\\s+\\S+\\s*(.*)$"
                    ).find(line) ?: continue
                    val start = m.groupValues[1].toLong(16)
                    val end = m.groupValues[2].toLong(16)
                    val perms = m.groupValues[3]
                    val path = m.groupValues[4].trim()
                    if (!perms.startsWith("r")) continue
                    if (path.isNotEmpty() && !path.startsWith("[") &&
                        !path.startsWith("/memfd:")
                    ) continue
                    if (path.contains("jit-cache")) continue
                    var pos = start
                    while (pos < end &&
                        count < maxBlobs && totalBytes < maxTotal
                    ) {
                        val want = minOf(
                            chunk.size.toLong(), end - pos
                        ).toInt()
                        val got = try {
                            mem.seek(pos)
                            var off = 0
                            while (off < want) {
                                val r = mem.read(chunk, off, want - off)
                                if (r < 0) break
                                off += r
                            }
                            off
                        } catch (_: Throwable) {
                            0
                        }
                        if (got > 8) {
                            var i = 0
                            while (i + 8 <= got) {
                                if (chunk[i] == 'd'.code.toByte() &&
                                    chunk[i + 1] == 'e'.code.toByte() &&
                                    chunk[i + 2] == 'x'.code.toByte() &&
                                    chunk[i + 3] == '\n'.code.toByte() &&
                                    chunk[i + 4] == '0'.code.toByte() &&
                                    chunk[i + 5] == '3'.code.toByte() &&
                                    (chunk[i + 6] == '5'.code.toByte() ||
                                        chunk[i + 6] == '7'.code.toByte() ||
                                        chunk[i + 6] == '8'.code.toByte() ||
                                        chunk[i + 6] == '9'.code.toByte()) &&
                                    chunk[i + 7] == 0.toByte()
                                ) {
                                    val off = pos + i
                                    val inside = dumpedRanges.any {
                                        off >= it.first && off < it.second
                                    }
                                    if (!inside) {
                                        val hdr = ByteArray(112)
                                        val hgot = try {
                                            mem.seek(off)
                                            var ho = 0
                                            while (ho < 112) {
                                                val r = mem.read(
                                                    hdr, ho, 112 - ho
                                                )
                                                if (r < 0) break
                                                ho += r
                                            }
                                            ho
                                        } catch (_: Throwable) {
                                            0
                                        }
                                        if (hgot == 112) {
                                            val fileSize = le32(hdr, 32)
                                            val headerSize = le32(hdr, 36)
                                            val endian = le32(hdr, 40)
                                            val mapOff = le32(hdr, 52)
                                            val checksum = le32(hdr, 8)
                                            val key =
                                                "$fileSize:$checksum"
                                            if (headerSize == 0x70 &&
                                                endian == 0x12345678 &&
                                                fileSize >= 0x70 &&
                                                fileSize <= 256 * 1024 * 1024 &&
                                                off + fileSize <= end &&
                                                mapOff > 0 &&
                                                mapOff < fileSize &&
                                                !dumpedKeys.contains(key)
                                            ) {
                                                val out = java.io.File(
                                                    application.filesDir,
                                                    "memdex-$pid-$count.dex"
                                                )
                                                var written = 0L
                                                try {
                                                    java.io.FileOutputStream(
                                                        out
                                                    ).use { fos ->
                                                        mem.seek(off)
                                                        var left =
                                                            fileSize.toLong()
                                                        val buf =
                                                            ByteArray(65536)
                                                        while (left > 0) {
                                                            val w = minOf(
                                                                buf.size.toLong(),
                                                                left
                                                            ).toInt()
                                                            var bo = 0
                                                            while (bo < w) {
                                                                val r = mem.read(
                                                                    buf, bo, w - bo
                                                                )
                                                                if (r < 0) break
                                                                bo += r
                                                            }
                                                            if (bo == 0) break
                                                            fos.write(
                                                                buf, 0, bo
                                                            )
                                                            written += bo
                                                            left -= bo
                                                        }
                                                    }
                                                } catch (_: Throwable) {
                                                    out.delete()
                                                    written = 0
                                                }
                                                if (written ==
                                                    fileSize.toLong()
                                                ) {
                                                    dumpedRanges.add(
                                                        off to off + fileSize
                                                    )
                                                    dumpedKeys.add(key)
                                                    count++
                                                    totalBytes += written
                                                    GuestBreadcrumb.log(
                                                        "v121-plugin-probe " +
                                                            "memdex dumped " +
                                                            "idx=${count - 1} " +
                                                            "size=$fileSize " +
                                                            "checksum=" +
                                                            checksum.toString(16) +
                                                            " file=" + out.name
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                i++
                            }
                        }
                        pos += want
                    }
                }
            } finally {
                try {
                    mem.close()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            GuestBreadcrumb.log(
                "v121-plugin-probe memdex scan aborted: " +
                    "${e.javaClass.simpleName}"
            )
        }
        GuestBreadcrumb.log(
            "v121-plugin-probe memdex scan done " +
                "count=$count totalBytes=$totalBytes"
        )
    }

    fun le32(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
