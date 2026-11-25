package com.nextvm.core.model

/**
 * 路径欺骗策略（控制流）。所有"要不要合成"的判断都走这里，保证豁免优先级唯一。
 *
 * 优先级（从高到低）：
 *   1. guest 在豁免名单            -> 不做任何路径形态欺骗（返回 false）
 *   2. 该路径族被显式关闭           -> 不合成
 *   3. guest 在 PATH_FIDELITY_WHITELIST -> 合成
 *   4. 其余                         -> 不合成
 *
 * 注意：返回 false 只表示"不产生合成路径"，**不影响沙箱隔离**——
 * 规则表照常注册，guest 的 I/O 仍然落在容器私有目录。
 */
object PathPolicy {

    fun isGuestExempt(packageName: String): Boolean =
        PathSpoofConfig.isGuestExempt(packageName)

    /** 该 guest 的该路径族是否启用"合成路径"（路径形态欺骗）。 */
    fun isSpoofEnabled(kind: PathKind, packageName: String): Boolean {
        if (PathSpoofConfig.isGuestExempt(packageName)) return false
        if (!PathSpoofConfig.isKindEnabled(kind.name)) return false
        return VirtualConstants.isPathFidelityGuest(packageName)
    }

    /** 该 guest 是否需要注册"宿主路径 -> 沙箱"的翻译规则（隔离）。恒为 true，除非将来加开关。 */
    fun isRedirectionEnabled(packageName: String): Boolean = true
}
