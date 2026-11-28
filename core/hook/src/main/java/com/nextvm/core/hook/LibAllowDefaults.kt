package com.nextvm.core.hook

/**
 * G3 库级白名单的默认值（容器在 bind 前经 [NativeHookBridge.setupLibAllow] 下发）。
 *
 * **默认全空 = 不启用 = 零行为变化。**
 *
 * 2026-11-28 实测：开启 LIB_ALLOW={libjavacore} 后，via 在 onPostResume 崩
 * IllegalArgumentException: fontDirectory == null || fontDirectory.isEmpty()。
 * A/B 已确认因果：旧容器 applied=0 时 via 正常；新容器 applied=1 slots=2 时必崩。
 * 在定位并修复 libjavacore hook 破坏的具体语义之前，保持全空。
 * 说明见 docs/输出/调研/调查-G3导致via崩溃.md
 */
object LibAllowDefaults {

    /** 只对这些 guest 生效（包级保险丝；空 = 不启用）。 */
    val PKG_ALLOW: Set<String> = emptySet()

    /** 允许 patch 的库（子串匹配：libjavacore 命中 /apex/.../libjavacore.so）。 */
    val LIB_ALLOW: List<String> = emptyList()

    /** 白名单库只 patch 这些符号（精确匹配，避免 open 命中 openat）。 */
    val SYM_ALLOW: List<String> = listOf("open", "openat", "stat", "access", "readlink")
}
