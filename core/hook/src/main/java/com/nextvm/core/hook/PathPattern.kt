package com.nextvm.core.hook

/**
 * 路径模式匹配（与 C++ `core/path_pattern.h` 同构，两端语义必须逐字一致）。
 *
 * 镜像文档：`docs/代码镜像/core/hook/PathPattern.md`
 */
data class PathPattern(val kind: Kind, val text: String) {

    enum class Kind { EXACT, PREFIX, GLOB }

    fun matches(path: String): Boolean = when (kind) {
        Kind.EXACT -> text == path
        Kind.GLOB -> globMatches(text, path)
        Kind.PREFIX -> path.startsWith(text) || isRootForm(path)
    }

    /** 前缀规则"拥有自己的根形态"：text 去掉尾 '/' 后与 path 完全相等。 */
    fun isRootForm(path: String): Boolean =
        kind == Kind.PREFIX && text.endsWith("/") &&
            text.length == path.length + 1 && text.startsWith(path)

    companion object {

        /** 规则串解析：含通配字符 -> GLOB；否则 PREFIX（EXACT 由调用方显式构造）。 */
        fun parse(raw: String): PathPattern =
            PathPattern(if (raw.indexOf('*') >= 0) Kind.GLOB else Kind.PREFIX, raw)

        /** 精确匹配（隐藏集合这类判定用）。 */
        fun exact(raw: String): PathPattern = PathPattern(Kind.EXACT, raw)

        /** 通配：`*` 匹配任意字符序列（含 '/'），其余字符逐字比较。 */
        fun globMatches(pattern: String, path: String): Boolean {
            var p = 0
            var s = 0
            var star = -1
            var mark = 0
            while (s < path.length) {
                if (p < pattern.length && pattern[p] == '*') {
                    star = p++
                    mark = s
                } else if (p < pattern.length && pattern[p] == path[s]) {
                    p++
                    s++
                } else if (star >= 0) {
                    p = star + 1
                    s = ++mark
                } else {
                    return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}
