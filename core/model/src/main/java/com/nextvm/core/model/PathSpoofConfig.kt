package com.nextvm.core.model

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 路径欺骗配置：豁免名单 + 欺骗名单 + 每个路径族的开关。
 *
 * 持久化在 `<containerDataDir>/files/path_spoof.conf`，行式 `key=value` + `[节]` + `#` 注释，
 * 可被 CmdReceiver 增删（`SPOOF_EXEMPT_ADD` / `REMOVE` / `LIST`）。
 *
 * **不实时生效**：`load()` 在 Application.onCreate 调一次，改完需重启容器/guest。
 *
 * 语义（见 docs/设计/设计-澄清-欺骗与隔离与合成路径.md）：
 *   - 豁免 = 关掉「路径形态欺骗」（不产生合成路径），**保留沙箱隔离**（规则仍注册）
 *   - 欺骗名单 = 允许产生合成路径的包（原 `PATH_FIDELITY_WHITELIST`，改为配置驱动）
 *   - 两份名单都可通过配置文件修改，代码里不写死具体包
 */
object PathSpoofConfig {

    const val FILE_NAME = "path_spoof.conf"

    private const val SECTION_EXEMPT = "豁免名单"
    private const val SECTION_SPOOF = "欺骗名单"
    private const val SECTION_KINDS = "各族开关"

    /** 豁免的包名（不做任何路径形态欺骗） */
    private val exemptPkgs = ConcurrentHashMap.newKeySet<String>()

    /** 允许产生合成路径的包名（pathFidelity 名单） */
    private val spoofPkgs = ConcurrentHashMap.newKeySet<String>()

    /** 显式关闭的路径族（缺省 = 开） */
    private val kindOff = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var storeFile: File? = null

    @Volatile
    private var loaded: Boolean = false

    /** 在 Application.onCreate（容器主进程）与 guest bind 时各调一次。读失败时保持空集合 = 回到旧行为。 */
    @Synchronized
    fun load(dir: File) {
        val f = File(dir, FILE_NAME)
        storeFile = f
        exemptPkgs.clear()
        spoofPkgs.clear()
        kindOff.clear()
        if (!f.exists()) {
            writeTemplate(f)
            loaded = true
            return
        }
        try {
            var section = ""
            for (raw in f.readLines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim()
                    continue
                }
                when (section) {
                    SECTION_EXEMPT -> exemptPkgs.add(line)
                    SECTION_SPOOF -> spoofPkgs.add(line)
                    SECTION_KINDS -> {
                        val eq = line.indexOf('=')
                        if (eq > 0) {
                            val k = line.substring(0, eq).trim()
                            val v = line.substring(eq + 1).trim()
                            if (v.equals("off", ignoreCase = true)) kindOff.add(k)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // 读失败：保持空集合（全部不豁免、全部族开启）= 旧行为
        }
        loaded = true
    }

    fun isLoaded(): Boolean = loaded

    fun isGuestExempt(packageName: String): Boolean = exemptPkgs.contains(packageName)

    fun exemptList(): List<String> = exemptPkgs.sorted()

    fun setGuestExempt(packageName: String, exempt: Boolean): Boolean {
        val changed = if (exempt) exemptPkgs.add(packageName) else exemptPkgs.remove(packageName)
        if (changed) persist()
        return changed
    }

    /** 是否在欺骗名单里（未加载时由 [VirtualConstants.isPathFidelityGuest] 回落内置默认）。 */
    fun isSpoofListed(packageName: String): Boolean = spoofPkgs.contains(packageName)

    fun spoofList(): List<String> = spoofPkgs.sorted()

    fun setSpoofListed(packageName: String, listed: Boolean): Boolean {
        val changed = if (listed) spoofPkgs.add(packageName) else spoofPkgs.remove(packageName)
        if (changed) persist()
        return changed
    }

    fun isKindEnabled(kindName: String): Boolean = !kindOff.contains(kindName)

    fun setKindEnabled(kindName: String, enabled: Boolean): Boolean {
        val changed = if (enabled) kindOff.remove(kindName) else kindOff.add(kindName)
        if (changed) persist()
        return changed
    }

    /** 把当前内存状态写回文件（保持人类可读格式）。 */
    @Synchronized
    fun persist() {
        val f = storeFile ?: return
        try {
            f.writeText(render())
        } catch (t: Throwable) {
            // 写失败不影响内存态；下次启动会回落到文件里的旧值
        }
    }

    private fun render(): String = buildString {
        appendLine("# 路径欺骗配置 —— 改完需重启容器/guest 生效")
        appendLine("# 说明见 docs/设计/设计-澄清-欺骗与隔离与合成路径.md")
        appendLine()
        appendLine("[${SECTION_EXEMPT}]")
        appendLine("# 这些包不做任何路径形态欺骗（不产生合成路径），但仍隔离到沙箱")
        appendLine("# 每行一个包名")
        exemptPkgs.sorted().forEach { appendLine(it) }
        appendLine()
        appendLine("[${SECTION_SPOOF}]")
        appendLine("# 允许产生合成路径的包（反双开）；不在本段也不在豁免段 = 直接用沙箱路径")
        appendLine("# 每行一个包名；与豁免名单冲突时豁免优先")
        spoofPkgs.sorted().forEach { appendLine(it) }
        appendLine()
        appendLine("[${SECTION_KINDS}]")
        appendLine("# 缺省 = on；off = 该族不产生合成路径（但规则仍注册，隔离不受影响）")
        appendLine("# 可选值：on / off")
        for (kind in KNOWN_KINDS) {
            appendLine("$kind=${if (isKindEnabled(kind)) "on" else "off"}")
        }
    }

    private fun writeTemplate(f: File) {
        // 首次生成：via 进豁免名单（不欺骗、只隔离）；CM 进欺骗名单（反双开定向目标）。
        // 两者都只是**默认值**，运行时改本文件或走 CmdReceiver 即可，不需要改代码。
        exemptPkgs.add(DEFAULT_EXEMPT_PKG)
        spoofPkgs.add(VirtualConstants.CM_PACKAGE)
        storeFile = f
        try {
            f.parentFile?.mkdirs()
            f.writeText(render())
        } catch (t: Throwable) {
            // 生成失败也保留内存态
        }
    }

    /** 默认豁免包：via 依赖 WebView 加载本地 HTML，任何假路径都会让它读不到。 */
    const val DEFAULT_EXEMPT_PKG = "mark.via"

    /** 路径族清单：由枚举派生，避免与 [PathKind] 两处维护。 */
    private val KNOWN_KINDS: List<String> = PathKind.entries.map { it.name }
}
