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
}

internal class ContainerSelfCheck(
    private val paths: PathProbe,
    private val sandboxRoot: File,
    private val confText: String?,
    /** 诊断通道文件（null 表示不测，例如 release 构建）。 */
    private val diagFile: File?,
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

    private fun isolatedWrite(): Check = guard("S3", "写入落在沙箱", {
        val sandboxPrefix = sandboxRoot.absolutePath.trimEnd('/') + "/selfcheck/w/"
        paths.add(PREFIX_W, sandboxPrefix)
        val target = paths.translate(PREFIX_W + "f.txt")
        val f = File(target)
        f.parentFile?.mkdirs()
        f.writeText("selfcheck")
        val inSandbox = f.absolutePath.startsWith(sandboxRoot.absolutePath)
        Check("S3", "写入落在沙箱", inSandbox && f.exists(), "target=$target inSandbox=$inSandbox")
    }) {
        paths.remove(PREFIX_W)
        runCatching { File(sandboxRoot, "selfcheck/w/f.txt").delete() }
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
            text == null -> Check("S6", "路径配置可读", false, "path_spoof.conf 不存在")
            !text.contains("[豁免名单]") -> Check("S6", "路径配置可读", false, "缺 [豁免名单] 段")
            else -> Check("S6", "路径配置可读", true, "len=${text.length}")
        }
    }

    private inline fun guard(id: String, name: String, body: () -> Check, cleanup: () -> Unit) =
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
    }
}
