package com.nextvm.core.common.diagnostics

/**
 * 验证结果：关键路径每一步的断言输出，格式稳定可作 golden 基线。
 * 详见 `docs/设计/设计-诊断输出与golden验证.md`。
 */
enum class CheckState { OK, FAIL, SKIP }

/**
 * 一条验证结果。
 *
 * 构造即校验契约（[init] 里的 require），防止产出"没法断言"的记录。
 */
data class CheckResult(
    /** 路径分组：activity / application / native / path */
    val path: String,
    /** 该路径内的步骤编号（沿用上游 STEP 编号，如 "0" / "2.5" / "phase2"） */
    val step: String,
    /** 步骤内的小名，如 preRegisterToken */
    val name: String,
    val state: CheckState,
    /** 被验证的对象，如 LoadedApk.mClassLoader */
    val target: String,
    /** 期望 */
    val expected: String,
    /** 实际 */
    val actual: String,
    /** 附加上下文；用 LinkedHashMap 保证输出顺序稳定 */
    val context: Map<String, String> = emptyMap(),
    /** 失败或跳过的原因（枚举语义，如 native_not_synced / engine_not_ready） */
    val reason: String? = null
) {

    init {
        require(state != CheckState.FAIL || !reason.isNullOrBlank()) {
            "FAIL 必须带 reason（$path/$step/$name）"
        }
        require(state != CheckState.SKIP || !reason.isNullOrBlank()) {
            "SKIP 必须带 reason（$path/$step/$name）"
        }
        require(expected.isNotBlank()) { "expected 不能为空（$path/$step/$name）" }
        require(actual.isNotBlank()) { "actual 不能为空（$path/$step/$name）" }
        require(state != CheckState.OK || expected == actual) {
            "OK 要求 expected == actual，否则是假成功（$path/$step/$name: $expected vs $actual）"
        }
    }

    /** 一行输出。字段顺序固定，无时间戳/随机值，可直接作为 golden 行。 */
    fun format(seq: Int): String = buildString {
        append(seq.toString().padStart(4, '0')).append(' ')
        append(path).append('.').append(step).append(' ')
        append(name).append(' ')
        append("ok=").append(
            when (state) {
                CheckState.OK -> "true"
                CheckState.FAIL -> "false"
                CheckState.SKIP -> "skip"
            }
        )
        append(" target=").append(target)
        append(" expected=").append(expected)
        append(" actual=").append(actual)
        if (context.isNotEmpty()) {
            append(" ctx=").append(context.entries.joinToString(",") { "${it.key}=${it.value}" })
        }
        reason?.let { append(" reason=").append(it) }
    }
}
