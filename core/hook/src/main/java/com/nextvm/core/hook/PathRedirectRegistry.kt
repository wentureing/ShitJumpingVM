package com.nextvm.core.hook

import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * 路径重定向规则表（v154 从 `NativeHookBridge` 拆出）。
 *
 * ## 职责
 * 维护「前缀 → 目标前缀」的映射，并提供路径翻译：
 * - 增删查：`add` / `remove` / `clear` / `snapshot`
 * - 翻译：[translate]（**最长前缀匹配**，与 native 侧 `redirect_path()` 语义一致）
 *
 * ## 不负责
 * - ❌ 与 native 层同步（由调用方 [onAdd]/[onRemove]/[onClear] 回调注入，
 *   `NativeHookBridge` 用它把规则推给 `nativeAddPathRedirection` 等）
 * - ❌ 具体规则内容（`setupAppRedirections` 等"规则组合"仍留在 `NativeHookBridge`）
 * - ❌ 隐藏路径判定（`hiddenPaths` 在 `NativeHookBridge`；[translate] 通过 [isHidden] 回调查询）
 * - ❌ 进程判断 / 开关（调用方负责）
 *
 * ## 偏差登记
 *
 * | # | 偏差 | 相对谁 | 类型 | 说明 |
 * | --- | --- | --- | --- | --- |
 * | 1 | 最长前缀匹配（不是精确匹配） | POSIX 路径语义 | ✅ **必要偏差** | 重定向按"目录前缀"生效；`/data/data/pkg/` 命中后，其下所有路径都映射到沙箱 |
 * | 2 | 用 `String.startsWith` 判前缀（不检查目录边界） | — | 🟡 **已知简化** | `/data/data/pkg2/...` 不会被 `/data/data/pkg/` 命中（因为带尾斜杠），但 `/data/data/pkg_backup/` 也不会命中（同样靠尾斜杠）。**约定：规则的前缀必须带尾斜杠** |
 * | 3 | 翻译只做一次替换 | 可能多次映射 | ✅ 设计如此 | 与 native `redirect_path()` 一致：不做链式翻译，避免规则互相递归 |
 * | 4 | 线程安全用 `ConcurrentHashMap` | — | ✅ | 主线程注册、guest 线程查询，必须并发安全 |
 *
 * ## 约束与坑
 * 1. **前缀必须带尾斜杠**（如 `/data/data/com.foo/`），否则 `/data/data/com.foo2/` 会被误命中。
 * 2. [translate] 与 native 侧 `redirect_path()` **必须保持同一语义**（最长前缀 + 单次替换），
 *    否则会出现"Kotlin 说是 A、native 实际做 B"的双轨不一致。
 * 3. native 侧未就绪时（[onAdd] 为 null）只维护 Kotlin 侧的表，等 native 可用后再由
 *    调用方补推（`NativeHookBridge.initNativeHooks` 里已有"同步已有规则"的逻辑）。
 */
internal class PathRedirectRegistry(
    /** 查询某个路径是否应被隐藏（命中则翻译成 `/dev/null`）。 */
    private val isHidden: (String) -> Boolean = { false },
    /** 规则新增时同步给 native（native 未就绪时应为空操作）。 */
    private val onAdd: ((String, String) -> Unit)? = null,
    /** 规则删除时同步给 native。 */
    private val onRemove: ((String) -> Unit)? = null,
    /** 清空时同步给 native。 */
    private val onClear: (() -> Unit)? = null
) {

    private val rules = ConcurrentHashMap<String, String>()

    /** 当前规则快照（供诊断）。 */
    fun snapshot(): Map<String, String> = rules.toMap()

    /** 规则数量。 */
    fun size(): Int = rules.size

    fun add(fromPrefix: String, toPrefix: String) {
        rules[fromPrefix] = toPrefix
        Timber.tag("PathRedirect").d("rule: $fromPrefix -> $toPrefix")
        onAdd?.invoke(fromPrefix, toPrefix)
    }

    fun remove(fromPrefix: String) {
        rules.remove(fromPrefix)
        onRemove?.invoke(fromPrefix)
    }

    fun clear() {
        rules.clear()
        onClear?.invoke()
        Timber.tag("PathRedirect").d("all rules cleared")
    }

    /**
     * 把路径按规则翻译（最长前缀匹配，单次替换）。
     *
     * - 命中隐藏路径 → `/dev/null`
     * - 未命中任何规则 → 原样返回
     */
    fun translate(originalPath: String): String {
        if (isHidden(originalPath)) {
            return "/dev/null"
        }
        var bestMatch = ""
        var bestRedirect = ""
        for ((from, to) in rules) {
            if (originalPath.startsWith(from) && from.length > bestMatch.length) {
                bestMatch = from
                bestRedirect = to
            }
        }
        if (bestMatch.isEmpty()) return originalPath
        return bestRedirect + originalPath.substring(bestMatch.length)
    }
}
