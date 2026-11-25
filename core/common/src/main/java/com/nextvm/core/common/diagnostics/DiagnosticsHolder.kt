package com.nextvm.core.common.diagnostics

/**
 * 诊断实例的全局入口（过渡方案）。
 *
 * 为什么用 Holder 而不是构造注入：诊断是横切关注点，加到 4 个类的构造上会改
 * 依赖图（Hilt AppModule / VirtualEngine 等），而当前阶段只需要"能写入"。
 * 业务代码仍**只依赖 [Diagnostics] 接口**，将来改注入时只改这里的赋值点。
 */
object DiagnosticsHolder {

    @Volatile
    private var current: Diagnostics = Diagnostics.Noop

    fun get(): Diagnostics = current

    /** 由 `VirtualEngine.initialize` / 测试调用。传 null 恢复 Noop。 */
    fun install(diagnostics: Diagnostics?) {
        current = diagnostics ?: Diagnostics.Noop
    }

    /** 便捷方法，减少业务代码的样板。 */
    fun check(result: CheckResult) = current.check(result)

    fun error(tag: String, message: String, cause: Throwable? = null) =
        current.error(tag, message, cause)

    fun stage(tag: String, message: String) = current.stage(tag, message)
}
