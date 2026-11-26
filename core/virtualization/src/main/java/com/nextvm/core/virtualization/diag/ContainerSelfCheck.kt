package com.nextvm.core.virtualization.diag

import java.io.File

/**
 * 容器自检：**主动**验证路径重定向、写入隔离、诊断通道与配置，而不是读日志当观察者。
 *
 * 由 `CmdReceiver` 的 `SELF_CHECK` 触发；详见 docs/代码镜像/core/virtualization/diag/ContainerSelfCheck.md
 */
internal interface PathProbe {
    fun add(fromPrefix: String, toPrefix: String)
    fun remove(fromPrefix: String)
    fun translate(path: String): String
    fun redirectionCount(): Int
    fun nativeAvailable(): Boolean
    /** 当前真实规则快照（S7 抽样用）。 */
    fun ruleSnapshot(): Map<String, String>
    /** Java 层 I/O 统计原文（S8 用；未安装 hook 时为 null）。 */
    fun javaIoStats(): String?
    /** Java 层 I/O hook 是否已安装（未安装时 stats 恒为 0，不能据此判定）。 */
    fun javaIoInstalled(): Boolean
}

internal class ContainerSelfCheck(
    private val paths: PathProbe,
    /**
     * 自检写入的目标根（**app 私有**，通常 `context.cacheDir`）。
     *
     * 不能用 `virtualRoot`：那里是用户/容器 Files 页面可见的目录，自检不该在其中留任何痕迹。
     * 目标目录选哪里不影响验证力 —— 只要路径经过 translate()，验证的就是"规则翻译 + 真的能写"。
     */
    private val writeRoot: File,
    private val confText: String?,
    /** 诊断通道文件（null 表示不测，例如 release 构建）。 */
    private val diagFile: File?,
    /** S6 是否强制要求 `path_spoof.conf`（guest 侧读不到主进程配置，传 false）。 */
    private val confRequired: Boolean = true,
) {

    class Check(
        val id: String,
        val name: String,
        val ok: Boolean,
        val detail: String,
    )

    fun run(): List<Check> = listOf(
        ruleHit(),
        ruleRemoved(),
        isolatedWrite(),
        unknownPathUntouched(),
        diagChannel(),
        configSanity(),
        realRuleSample(),
        diagWriteNotCounted(),
    )

    /** 一行汇总：`hookcheck ok=N fail=M S1:ok S2:ok ...` */
    fun report(checks: List<Check>): String {
        val ok = checks.count { it.ok }
        val ids = checks.joinToString(" ") { "${it.id}:${if (it.ok) "ok" else "fail"}" }
        return "hookcheck ok=$ok fail=${checks.size - ok} $ids"
    }

    private fun ruleHit(): Check = guard("S1", "规则命中", {
        paths.add(PREFIX_IN, PREFIX_OUT)
        val got = paths.translate(PREFIX_IN + "x")
        val want = PREFIX_OUT + "x"
        Check("S1", "规则命中", got == want, "translate=$got want=$want")
    }) { paths.remove(PREFIX_IN) }

    private fun ruleRemoved(): Check = guard("S2", "规则移除后不命中", {
        paths.remove(PREFIX_IN)
        val got = paths.translate(PREFIX_IN + "y")
        Check("S2", "规则移除后不命中", got == PREFIX_IN + "y", "translate=$got")
    })

    private fun isolatedWrite(): Check = guard("S3", "规则翻译后可写", {
        val targetRoot = writeRoot.absolutePath.trimEnd('/')
        paths.add(PREFIX_W, "$targetRoot/w/")
        val target = paths.translate(PREFIX_W + "f.txt")
        val f = File(target)
        f.parentFile?.mkdirs()
        f.writeText("selfcheck")
        val inWriteRoot = f.absolutePath.startsWith(targetRoot)
        Check("S3", "规则翻译后可写", inWriteRoot && f.exists(), "target=$target inWriteRoot=$inWriteRoot")
    }) {
        paths.remove(PREFIX_W)
        // 自检不留在任何可见目录里；writeRoot 本身是 app 私有（cacheDir），失败也无害。
        runCatching { File(writeRoot, "w/f.txt").delete() }
        runCatching { File(writeRoot, "w").delete() }
        runCatching { writeRoot.delete() }
    }

    private fun unknownPathUntouched(): Check = guard("S4", "未知路径不误改", {
        val p = "/no/such/rule/path/selfcheck"
        val got = paths.translate(p)
        Check("S4", "未知路径不误改", got == p, "translate=$got")
    })

    private fun diagChannel(): Check {
        val f = diagFile
            ?: return Check("S5", "诊断通道可写", true, "skipped(no diag file)")
        return try {
            val before = if (f.exists()) f.length() else 0L
            f.parentFile?.mkdirs()
            f.appendText("selfcheck probe\n")
            val after = if (f.exists()) f.length() else 0L
            Check("S5", "诊断通道可写", after > before, "delta=${after - before}")
        } catch (t: Throwable) {
            Check("S5", "诊断通道可写", false, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun configSanity(): Check {
        val text = confText
        return when {
            text == null && !confRequired ->
                Check("S6", "路径配置可读", true, "skipped(guest 侧不读主进程配置)")
            text == null -> Check("S6", "路径配置可读", false, "path_spoof.conf 不存在")
            !text.contains("[豁免名单]") -> Check("S6", "路径配置可读", false, "缺 [豁免名单] 段")
            else -> Check("S6", "路径配置可读", true, "len=${text.length}")
        }
    }

    /**
     * S7：从**真实规则表**抽一条做 translate 往返。
     *
     * S1/S2 用的是自造临时规则，只能证明机制可用；真实违规（规则没注册/注册错进程）它是全绿的。
     */
    private fun realRuleSample(): Check = guard("S7", "真实规则抽样", {
        val snapshot = paths.ruleSnapshot()
        if (snapshot.isEmpty()) {
            // 规则在 guest 进程注册（v142 的教训），主进程冷启动时本来就没有 → 不是失败
            return@guard Check("S7", "真实规则抽样", true, "skipped(no guest rules in this process)")
        }
        val sample = snapshot.entries.first()
        val from = sample.key
        val to = sample.value
        val probe = if (from.endsWith("/")) from + PROBE_NAME else "$from/" + PROBE_NAME
        val got = paths.translate(probe)
        val ok = got.startsWith(to.trimEnd('/'))
        Check(
            "S7",
            "真实规则抽样",
            ok,
            "rules=${snapshot.size} from=$from got=$got",
        )
    })

    /**
     * S8：诊断文件写入应计入 `diag`、**不**计入 `total`（否则 Java IO 指标被日志自身污染）。
     *
     * 未安装 javaIo hook（默认 `javaIo=0`）时无从观察 → 记 skipped 而不是 fail。
     */
    private fun diagWriteNotCounted(): Check {
        val f = diagFile
            ?: return Check("S8", "诊断写入不计入 total", true, "skipped(no diag file)")
        if (!paths.javaIoInstalled()) {
            // 未安装时 stats 恒为 0（不是 null），不能据此判定 → 记 skipped
            return Check("S8", "诊断写入不计入 total", true, "skipped(javaIo hook not installed)")
        }
        val before = parseIoStats(paths.javaIoStats())
            ?: return Check("S8", "诊断写入不计入 total", false, "stats 读取失败（hook 已装但读不到）")
        repeat(WRITE_ROUNDS) { runCatching { f.appendText("selfcheck s8\n") } }
        val after = parseIoStats(paths.javaIoStats())
            ?: return Check("S8", "诊断写入不计入 total", false, "stats 读取失败")
        val diagDelta = after.first - before.first
        val totalDelta = after.second - before.second
        return Check(
            "S8",
            "诊断写入不计入 total",
            diagDelta >= WRITE_ROUNDS && totalDelta == 0,
            "diagDelta=$diagDelta totalDelta=$totalDelta",
        )
    }

    /** 解析 `total=N ... diag=N`，返回 (diag, total)；缺字段返回 null。 */
    private fun parseIoStats(s: String?): Pair<Int, Int>? {
        if (s == null) return null
        val diag = Regex("diag=(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val total = Regex("total=(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return diag to total
    }

    private inline fun guard(
        id: String,
        name: String,
        body: () -> Check,
        cleanup: () -> Unit = {},
    ) =
        try {
            body()
        } catch (t: Throwable) {
            Check(id, name, false, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching(cleanup)
        }

    companion object {
        const val PREFIX_IN = "/selfcheck/in/"
        const val PREFIX_OUT = "/selfcheck/out/"
        const val PREFIX_W = "/selfcheck/w/"

        /** S7 抽样用的相对路径片段（拼到真实规则前缀后面）。 */
        private const val PROBE_NAME = "selfcheck.rule"

        /** S8 写入轮数：太少可能被缓存/合并掩盖。 */
        private const val WRITE_ROUNDS = 3
    }
}
