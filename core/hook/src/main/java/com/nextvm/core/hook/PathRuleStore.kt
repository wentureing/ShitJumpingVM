package com.nextvm.core.hook

import java.util.concurrent.ConcurrentHashMap

/**
 * 一次路径翻译的结果。
 *
 * @param path 翻译后的路径（未命中时等于输入）
 * @param matched 命中的规则前缀；未命中为 null
 * @param hiddenHit 是否命中"隐藏"（命中时 [path] 为 /dev/null，且调用方不应记翻译日志）
 */
data class PathTranslation(
    val path: String,
    val matched: String?,
    val hiddenHit: Boolean = false,
)

/**
 * 路径规则状态机：规则表 / 隐藏集合 / fake content / 属性覆盖 + 纯翻译逻辑。
 *
 * **纯 Kotlin**（不依赖 Android / Timber / DebugTrace / native），可独立 JVM 测试；
 * 注册时机、native 同步、日志与限流都在 [NativeHookBridge]（控制流）。
 * 说明见 docs/输出/调研/调研-NativeHookBridge可测试性评估.md
 */
class PathRuleStore {

    private val pathRedirections = ConcurrentHashMap<String, String>()
    private val hiddenPaths = ConcurrentHashMap.newKeySet<String>()
    private val fakeFileContent = ConcurrentHashMap<String, ByteArray>()
    private val propertyOverrides = ConcurrentHashMap<String, String>()

    // ---------------- 规则表 ----------------

    fun add(fromPrefix: String, toPrefix: String) {
        pathRedirections[fromPrefix] = toPrefix
    }

    fun remove(fromPrefix: String) {
        pathRedirections.remove(fromPrefix)
    }

    fun clear() {
        pathRedirections.clear()
    }

    fun snapshot(): Map<String, String> = pathRedirections.toMap()

    fun count(): Int = pathRedirections.size

    /** 清空全部状态（规则/隐藏/fake/属性）—— 对应 cleanup。 */
    fun clearAll() {
        pathRedirections.clear()
        hiddenPaths.clear()
        fakeFileContent.clear()
        propertyOverrides.clear()
    }

    // ---------------- 隐藏（精确匹配） ----------------

    fun hide(path: String) {
        hiddenPaths.add(path)
    }

    fun unhide(path: String) {
        hiddenPaths.remove(path)
    }

    fun isHidden(path: String): Boolean = hiddenPaths.contains(path)

    fun hiddenCount(): Int = hiddenPaths.size

    // ---------------- fake content ----------------

    fun setFakeContent(path: String, content: ByteArray) {
        fakeFileContent[path] = content
    }

    fun setFakeContent(path: String, content: String) {
        fakeFileContent[path] = content.toByteArray()
    }

    fun hasFakeContent(path: String): Boolean = fakeFileContent.containsKey(path)

    fun fakeContent(path: String): ByteArray? = fakeFileContent[path]

    // ---------------- 属性覆盖 ----------------

    fun overrideProperty(key: String, value: String) {
        propertyOverrides[key] = value
    }

    fun propertyOverride(key: String): String? = propertyOverrides[key]

    // ---------------- 翻译 ----------------

    /**
     * 规则匹配与拼接。隐藏优先（精确匹配）。
     *
     * 规则是前缀；以 '/' 结尾的规则还"拥有自己的根形态"（去掉尾斜杠的那个字符串）。
     * 有效长度最长者胜，平局时真正的前缀优先。
     */
    fun translate(originalPath: String): PathTranslation {
        if (hiddenPaths.contains(originalPath)) {
            return PathTranslation("/dev/null", matched = null, hiddenHit = true)
        }

        var bestMatch = ""
        var bestRedirect = ""
        var bestLen = 0
        var bestIsRoot = false
        for ((from, to) in pathRedirections) {
            val isPrefix = originalPath.startsWith(from)
            val isRoot = !isPrefix && from.endsWith("/") &&
                from.length == originalPath.length + 1 && from.startsWith(originalPath)
            if (!isPrefix && !isRoot) continue
            val effectiveLen = if (isRoot) originalPath.length else from.length
            if (!(effectiveLen > bestLen || (effectiveLen == bestLen && bestIsRoot && !isRoot))) continue
            bestLen = effectiveLen
            bestMatch = if (isRoot) originalPath else from
            bestRedirect = if (isRoot) to.trimEnd('/') else to
            bestIsRoot = isRoot
        }

        if (bestMatch.isEmpty()) return PathTranslation(originalPath, matched = null)

        val rest = originalPath.substring(bestMatch.length)
        val redirect = when {
            rest.isEmpty() -> bestRedirect
            bestRedirect.endsWith("/") && rest.startsWith("/") -> bestRedirect + rest.substring(1)
            bestRedirect.endsWith("/") -> bestRedirect + rest
            rest.startsWith("/") -> bestRedirect + rest
            else -> "$bestRedirect/$rest"
        }
        return PathTranslation(redirect, matched = bestMatch)
    }
}
