package com.nextvm.core.common.diagnostics

/**
 * 按级别路由到多个通道；同时给 check 编序号（保证输出顺序稳定、可 diff）。
 * 详见 `docs/设计/设计-诊断输出与golden验证.md`。
 */
class DiagnosticsRouter(
    private val sinks: List<DiagnosticsSink>
) : Diagnostics {

    private val lock = Any()
    private var seq = 0

    /** 已输出的 check 条数（供汇总行使用）。 */
    val checkCount: Int get() = synchronized(lock) { seq }

    override fun check(result: CheckResult) {
        val n = synchronized(lock) { ++seq }
        // 先物化序号，避免各 sink 重复编号
        val ordered = result.copy()
        sinks.forEach { sink ->
            try {
                sink.onCheck(ordered, n)
            } catch (_: Throwable) {
                // 诊断输出绝不能影响主流程
            }
        }
    }

    override fun error(tag: String, message: String, cause: Throwable?) {
        sinks.forEach { sink ->
            try {
                sink.onError(tag, message, cause)
            } catch (_: Throwable) {
            }
        }
    }

    override fun stage(tag: String, message: String) {
        sinks.forEach { sink ->
            try {
                sink.onStage(tag, message)
            } catch (_: Throwable) {
            }
        }
    }
}
