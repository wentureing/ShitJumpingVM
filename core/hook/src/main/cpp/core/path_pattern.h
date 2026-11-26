// 路径模式匹配（纯逻辑，无 Android/JNI 依赖）—— Kotlin 侧 PathPattern.kt 的同构实现。
//
// 红线同 path_rules.h：本目录不得 include <jni.h>/<android/*>/<sys/*>。
//
// 为什么不用 std::regex：NDK 上 <regex> 体积大、回溯不可控，而匹配发生在每次 open 的热路径。
// 需要"通配"时用 Glob（'*' 匹配任意字符序列，含 '/'），语义简单且两端可逐字对齐。
#pragma once

#include <cstddef>
#include <string>

namespace nextvm {
namespace path {

enum class PatternKind {
    /** 整路径相等（用于隐藏集合这类精确判定）。 */
    Exact,
    /** 前缀匹配；text 以 '/' 结尾时还"拥有自己的根形态"（text 去掉尾 '/' == path）。 */
    Prefix,
    /** 通配：text 含 '*'，'*' 匹配任意字符序列（含 '/'）。 */
    Glob,
};

struct PathPattern {
    PatternKind kind = PatternKind::Prefix;
    std::string text;
};

/** 解析规则串：含 '*' -> Glob；否则 Prefix（Exact 由调用方显式构造）。 */
PathPattern parse_pattern(const std::string& raw);

/** 是否匹配（Prefix 额外支持根形态）。 */
bool pattern_matches(const PathPattern& pat, const std::string& path);

/** 是否为"根形态"命中（text 去掉尾 '/' 后与 path 完全相等）。 */
bool pattern_is_root_form(const PathPattern& pat, const std::string& path);

/** Glob：'*' 匹配任意字符序列（含 '/'），其余字符逐字比较。 */
bool glob_match(const std::string& pat, const std::string& path);

}  // namespace path
}  // namespace nextvm
