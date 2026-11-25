package com.nextvm.core.virtualization.lifecycle.diag

import com.nextvm.core.virtualization.lifecycle.GuestBreadcrumb
import java.io.File

/**
 * v155/v156 方法追踪：启停、停止竞争处理、以及把二进制 trace 过滤成可读日志。
 *
 * 详见 docs/代码镜像/core/virtualization/lifecycle/diag/InitTrace.md
 */
internal object InitTrace {

    @Volatile var started = false
    @Volatile var stoppedByClick = false
    @Volatile var loadStarted = false
    @Volatile var loadStoppedByClick = false
    @Volatile var loadFiltered = false

    /** 过滤关注的方法名前缀（CM 风控/设备信息路径）。 */
    val KEYS = arrayOf(
        "zw/mobile/risk", "TelephonyManager",
        "Settings\$Secure", "WifiManager", "LocationManager",
        "DeviceInfoUtils", "RequestForBridge",
        "getPackageInfo", "getPackageManager", "android.os.Build",
        "ResponseProcess", "ErrorForBridge", "callBackError",
        "onHttpFailure", "onHttpSuccess"
    )

    /**
     * 点击追踪开始前先停掉正在进行的 init/load trace。
     *
     * 同一设备上二次 `startMethodTracing` 是静默 no-op（v152 实测 0 字节），
     * 所以必须先把前一个停掉，并在这里完成它的过滤。
     */
    fun stopBeforeStart(dir: File, tag: String) {
        try {
            android.os.Debug.stopMethodTracing()
            GuestBreadcrumb.log("$tag stop-before-start")
            stoppedByClick = true
            if (loadStarted) {
                loadStoppedByClick = true
            }
            val initFile = File(
                dir, "v155-trace-" + android.os.Process.myPid() + "-1.trace"
            )
            Thread({
                filter(initFile, dir)
            }, "v155-trace-filter").start()
            if (loadStarted && !loadFiltered) {
                loadFiltered = true
                val loadFile = File(
                    dir,
                    "v156-load-trace-" + android.os.Process.myPid() +
                        "-1.trace"
                )
                Thread({
                    filter(
                        loadFile, dir,
                        "v156-load-filtered.log", "v156-load"
                    )
                }, "v156-load-filter").start()
            }
        } catch (e: Throwable) {
            GuestBreadcrumb.log(
                "$tag stop-before-start none " +
                    "${e.javaClass.simpleName}: " + e.message
            )
        }
    }

    /** 启动 20s 的 init trace（仅 CM 包）。 */
    fun startInit() {
        if (started) return
        try {
            val dir = GuestBreadcrumb.filesDir() ?: return
            started = true
            val traceFile = File(
                dir, "v155-trace-" + android.os.Process.myPid() + "-1.trace"
            )
            android.os.Debug.startMethodTracing(
                traceFile.absolutePath, 64 * 1024 * 1024
            )
            GuestBreadcrumb.log("v155-trace start file=" + traceFile.name)
            android.os.Handler(
                android.os.Looper.getMainLooper()
            ).postDelayed({
                if (stoppedByClick) {
                    GuestBreadcrumb.log(
                        "v155-trace stop skipped already stopped by click"
                    )
                } else {
                    try {
                        android.os.Debug.stopMethodTracing()
                        GuestBreadcrumb.log(
                            "v155-trace stop delayed file=" + traceFile.name
                        )
                    } catch (e: Throwable) {
                        GuestBreadcrumb.log(
                            "v155-trace stop failed " +
                                "${e.javaClass.simpleName}: " + e.message
                        )
                    }
                    Thread({
                        filter(traceFile, dir)
                    }, "v155-trace-filter").start()
                }
            }, 20000L)
        } catch (e: Throwable) {
            GuestBreadcrumb.log(
                "v155-trace start failed " +
                    "${e.javaClass.simpleName}: " + e.message
            )
        }
    }

    /** 启动 20s 的 load trace（LoginActivity resume）。 */
    fun startLoad() {
        if (loadStarted) return
        try {
            val dir = GuestBreadcrumb.filesDir() ?: return
            // 先停 init trace；loadStarted 要在 stopBeforeStart 之后置位，
            // 否则 stopBeforeStart 会把它自己标成 stopped-by-click。
            stopBeforeStart(dir, "v156-load")
            loadStarted = true
            val traceFile = File(
                dir,
                "v156-load-trace-" + android.os.Process.myPid() + "-1.trace"
            )
            android.os.Debug.startMethodTracing(
                traceFile.absolutePath, 256 * 1024 * 1024
            )
            GuestBreadcrumb.log("v156-load-trace start file=" + traceFile.name)
            android.os.Handler(
                android.os.Looper.getMainLooper()
            ).postDelayed({
                if (loadStoppedByClick) {
                    GuestBreadcrumb.log(
                        "v156-load-trace stop skipped already stopped by click"
                    )
                } else {
                    try {
                        android.os.Debug.stopMethodTracing()
                        GuestBreadcrumb.log(
                            "v156-load-trace stop delayed file=" + traceFile.name
                        )
                    } catch (e: Throwable) {
                        GuestBreadcrumb.log(
                            "v156-load-trace stop failed " +
                                "${e.javaClass.simpleName}: " + e.message
                        )
                    }
                    if (!loadFiltered) {
                        loadFiltered = true
                        Thread({
                            filter(
                                traceFile, dir,
                                "v156-load-filtered.log", "v156-load"
                            )
                        }, "v156-load-filter").start()
                    }
                }
            }, 20000L)
        } catch (e: Throwable) {
            GuestBreadcrumb.log(
                "v156-load-trace start failed " +
                    "${e.javaClass.simpleName}: " + e.message
            )
        }
    }

    /** 读 trace、过滤关注方法、写可读日志。 */
    fun filter(
        traceFile: File, dir: File,
        outName: String = "v155-init-filtered.log",
        tag: String = "v155",
        sampled: Boolean = false
    ) {
        try {
            val data = traceFile.readBytes()
            val textEnd = textEnd(data)
            if (textEnd < 0) {
                GuestBreadcrumb.log("$tag-filter failed no *end")
                return
            }
            val methods = parseMethods(String(data, 0, textEnd, Charsets.UTF_8))
            val blobOff = textEnd + 5
            if (blobOff + 32 > data.size || !isSlow(data, blobOff)) {
                GuestBreadcrumb.log("$tag-filter failed no SLOW")
                return
            }
            val recSize = u32(data, blobOff + 16)
            if (recSize < 14) {
                GuestBreadcrumb.log("$tag-filter failed recSize=$recSize")
                return
            }
            val result = collectRecords(
                data, blobOff + 32, recSize, methods, KEYS, sampled
            )
            val out = File(dir, outName)
            out.bufferedWriter().use { w ->
                for (line in result.lines) w.write(line + "\n")
            }
            GuestBreadcrumb.log(
                "$tag-filter done matched=${result.lines.size} total=${result.total} " +
                    "file=" + out.name + " traceBytes=" + data.size
            )
        } catch (e: Throwable) {
            GuestBreadcrumb.log(
                "$tag-filter failed " +
                    "${e.javaClass.simpleName}: " + e.message
            )
        }
    }

    /** 纯函数：找文本头结束位置（`*end\n` 的 `*` 下标），找不到返回 -1。 */
    fun textEnd(data: ByteArray): Int {
        var i = 0
        while (i + 4 < data.size) {
            if (data[i] == '*'.code.toByte() &&
                data[i + 1] == 'e'.code.toByte() &&
                data[i + 2] == 'n'.code.toByte() &&
                data[i + 3] == 'd'.code.toByte() &&
                data[i + 4] == '\n'.code.toByte()
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /** 纯函数：解析 `*methods` 段，方法 id -> "类.方法"。 */
    fun parseMethods(text: String): Map<Int, String> {
        val methods = HashMap<Int, String>()
        var inMethods = false
        for (line in text.split("\n")) {
            if (line == "*methods") {
                inMethods = true
                continue
            }
            if (line.startsWith("*")) {
                inMethods = false
                continue
            }
            if (!inMethods) continue
            val parts = line.split("\t")
            if (parts.size < 3) continue
            val id = try {
                parts[0].removePrefix("0x").toLong(16).toInt()
            } catch (_: Throwable) {
                continue
            }
            methods[id] = parts[1] + "." + parts[2]
        }
        return methods
    }

    /** 纯函数：二进制 trace 的记录块头是否为 "SLOW"。 */
    fun isSlow(data: ByteArray, off: Int): Boolean =
        off + 3 < data.size &&
            data[off] == 'S'.code.toByte() &&
            data[off + 1] == 'L'.code.toByte() &&
            data[off + 2] == 'O'.code.toByte() &&
            data[off + 3] == 'W'.code.toByte()

    /** 纯函数：小端 u32。 */
    fun u32(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or
            ((data[off + 3].toInt() and 0xFF) shl 24)

    /** 纯函数：方法名是否命中任一关注前缀。 */
    fun matchesAny(name: String, keys: Array<String>): Boolean {
        for (k in keys) {
            if (name.contains(k)) return true
        }
        return false
    }

    /** 过滤结果：可读行 + 总记录数。 */
    class FilterResult(val lines: List<String>, val total: Int)

    /**
     * 纯函数：按固定记录大小遍历，产出 `t2=.. tid=.. name` 行。
     *
     * `sampled=false` 时跳过 `mid & 1 != 0` 的记录（方法追踪的采样位）。
     */
    fun collectRecords(
        data: ByteArray,
        startOff: Int,
        recSize: Int,
        methods: Map<Int, String>,
        keys: Array<String>,
        sampled: Boolean
    ): FilterResult {
        // recSize<=0 会让下面的偏移递增失效（死循环）——损坏/伪造 trace 必须先拒绝。
        if (recSize <= 0) return FilterResult(ArrayList(), 0)
        val lines = ArrayList<String>()
        var total = 0
        var off = startOff
        while (off + recSize <= data.size) {
            total++
            val tid = (data[off].toInt() and 0xFF) or
                ((data[off + 1].toInt() and 0xFF) shl 8)
            val mid = u32(data, off + 2)
            val t2 = u32(data, off + 10)
            off += recSize
            if (!sampled && mid and 1 != 0) continue
            val name = methods[mid and -4] ?: continue
            if (!matchesAny(name, keys)) continue
            lines.add("t2=$t2 tid=$tid $name")
        }
        return FilterResult(lines, total)
    }
}
