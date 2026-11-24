package com.nextvm.core.hook

import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * 路径重定向规则表：最长前缀匹配 + 单次替换。
 * 详见 `docs/代码镜像/core/hook/PathRedirectRegistry.md`（偏差与约束在那里）。
 */
internal class PathRedirectRegistry(
    /** 命中则翻译为 `/dev/null`。 */
    private val isHidden: (String) -> Boolean = { false },
    /** 规则变更时同步给 native（未就绪时应为空操作）。 */
    private val onAdd: ((String, String) -> Unit)? = null,
    private val onRemove: ((String) -> Unit)? = null,
    private val onClear: (() -> Unit)? = null
) {

    private val rules = ConcurrentHashMap<String, String>()

    fun snapshot(): Map<String, String> = rules.toMap()

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

    /** 按规则翻译路径；未命中返回原值。 */
    fun translate(originalPath: String): String {
        if (isHidden(originalPath)) return "/dev/null"
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
