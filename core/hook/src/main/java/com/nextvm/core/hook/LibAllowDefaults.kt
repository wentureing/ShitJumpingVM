package com.nextvm.core.hook

/**
 * G3 库级白名单的默认值（容器在 bind 前经 [NativeHookBridge.setupLibAllow] 下发）。
 *
 * 空集合 = 不启用，完全等价于旧行为。说明见 docs/计划/计划-阶段0与测试体系补齐.md
 */
object LibAllowDefaults {

    /** 只对这些 guest 生效（包级保险丝；空 = 不限包）。 */
    val PKG_ALLOW: Set<String> = setOf("mark.via")

    /** 允许 patch 的库（子串匹配：`libjavacore` 命中 `/apex/.../libjavacore.so`）。 */
    val LIB_ALLOW: List<String> = listOf("libjavacore")

    /** 白名单库只 patch 这些符号（精确匹配，避免 open ⊂ openat）。 */
    val SYM_ALLOW: List<String> = listOf("open", "openat", "stat", "access", "readlink")
}
