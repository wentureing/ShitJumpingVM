package com.nextvm.core.virtualization.diag

/**
 * 诊断出口（sandboxsensor P0-1）。
 *
 * 这是一个「测试挂钩」，不是日志系统：
 *   * 它不采集数据（采集仍由 GuestBreadcrumb / StateProbe 做）
 *   * 它不格式化、不落盘、不轮转、不判断对错
 *   * 它只提供一个**程序化的出口**，让 debug 模块能把实现插进来
 *
 * 为什么需要它：
 *   现有的诊断设施（GuestBreadcrumb 199 处埋点 / StateProbe / WebViewWatch /
 *   WebViewWarmup）全部把结果写成「给人看的文本」（logcat 或各自独立的 log 文件），
 *   没有任何「给程序读的结构」。于是想做一个启动自检（SelfCheckRunner）时，
 *   结果没有正当出口——只能写死文件路径（耦合），或写 logcat（回到看日志反推）。
 *
 * 接入方式（P1-1）：
 *   * 独立模块 `:core:diagnostics` 提供真实实现，通过 `debugImplementation` 引入；
 *   * `app/src/debug/.../DebugInit.kt` 在 Application.onCreate 里调用
 *     `Diagnostics.install(...)` 完成注入；
 *   * release 构建完全不包含该模块，`sink` 永远是 [NoOp]。
 *
 * 重要：现有 199 处 `GuestBreadcrumb.log()` 调用**不需要改**。
 * 由一个「桥接器」在 debug 下把 Breadcrumb 的输出镜像到 sink（见 P1-1）。
 */
interface DiagnosticsSink {

    /** 一条过程事件（对应 Breadcrumb 的 log / 关键生命周期）。 */
    fun onEvent(phase: String, detail: String)

    /** 一个可断言的快照（键值对，供自检比对）。 */
    fun onSnapshot(name: String, value: String)
}

/**
 * 全局访问点。
 *
 * 线程安全：`sink` 是 `@Volatile`，只在 Application 启动时写一次，
 * 之后全是读，不需要加锁（避免在热路径引入同步开销）。
 *
 * 开销：两个 emit 方法都是 `inline` + `s !== NoOp` 早退，
 * release 构建里 lambda 根本不会执行，方法体等价于一次 volatile 读。
 */
object Diagnostics {

    @Volatile
    var sink: DiagnosticsSink = NoOp
        private set

    /** 供 debug 源集在启动时注入真实实现。 */
    fun install(s: DiagnosticsSink) {
        sink = s
    }

    /** 供 debug 源集在测试结束 / 进程退出前还原。 */
    fun reset() {
        sink = NoOp
    }

    /** 是否已被 debug 实现接管（自检自身可以用来判断）。 */
    val isInstalled: Boolean
        get() = sink !== NoOp

    inline fun event(phase: String, detail: () -> String) {
        val s = sink
        if (s !== NoOp) s.onEvent(phase, detail())
    }

    inline fun snapshot(name: String, value: () -> String) {
        val s = sink
        if (s !== NoOp) s.onSnapshot(name, value())
    }

    private object NoOp : DiagnosticsSink {
        override fun onEvent(phase: String, detail: String) = Unit
        override fun onSnapshot(name: String, value: String) = Unit
    }
}