package com.nextvm.core.common.diagnostics

/**
 * 诊断输出：业务代码**只依赖这个接口**，换通道只改实现。
 * 详见 `docs/设计/设计-诊断输出抽象层.md` 与 `设计-诊断输出与golden验证.md`。
 */
interface Diagnostics {

    /** 验证结果（核心）：关键路径每一步的成功/失败/跳过，必带上下文。 */
    fun check(result: CheckResult)

    /** 严重错误：必须落盘、不能丢、不能抛。 */
    fun error(tag: String, message: String, cause: Throwable? = null)

    /** 阶段转移（低频）：只在"进入/离开某个阶段"时用，禁止用于高频路径。 */
    fun stage(tag: String, message: String)

    /** release / 测试里不需要任何输出时用它。 */
    object Noop : Diagnostics {
        override fun check(result: CheckResult) = Unit
        override fun error(tag: String, message: String, cause: Throwable?) = Unit
        override fun stage(tag: String, message: String) = Unit
    }
}

/** 输出通道。换通道 = 换实现，业务代码不动。 */
interface DiagnosticsSink {
    /** [seq] 由 [DiagnosticsRouter] 统一分配，保证输出顺序稳定可比。 */
    fun onCheck(result: CheckResult, seq: Int) {}
    fun onError(tag: String, message: String, cause: Throwable?) {}
    fun onStage(tag: String, message: String) {}
}

/**
 * 内存记录通道：测试里断言"确实产生了某条 check"，也给调试面板用。
 */
class RecordingSink : DiagnosticsSink {

    private val lines = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    val checks: List<String> get() = synchronized(lines) { lines.toList() }
    val errorLines: List<String> get() = synchronized(errors) { errors.toList() }

    override fun onCheck(result: CheckResult, seq: Int) {
        synchronized(lines) { lines.add(result.format(seq)) }
    }

    override fun onError(tag: String, message: String, cause: Throwable?) {
        synchronized(errors) { errors.add("E $tag $message ${cause?.javaClass?.simpleName ?: ""}") }
    }

    /** 供 golden 用：checks 段 + errors 段。 */
    fun dump(): String = buildString {
        append("--- checks ---\n")
        checks.forEach { append(it).append('\n') }
        append("--- errors ---\n")
        errorLines.forEach { append(it).append('\n') }
    }
}
