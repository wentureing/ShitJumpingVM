package com.nextvm.core.virtualization.engine

import android.content.Context
import android.content.Intent
import com.nextvm.core.common.findField
import com.nextvm.core.model.VirtualIntentExtras
import java.io.File

/**
 * 事务投递诊断（v150 从 `ActivityThreadHook` 拆出）。
 *
 * ## 职责
 * 只做**观测**，不改任何控制流：
 * - [traceDelivery]：把 `ActivityThread` 收到的 activity 事务按行追加到
 *   `<filesDir>/startactivity-trace-<pid>.log`，用于区分
 *   「AMS 根本没投递」和「投递了但 guest 侧丢弃」。
 * - [describeTransactionItem]：把 `ClientTransaction` 里的 item 压成一行可读描述
 *   （组件名、是否带 nextvm extras、多 Intent 列表）。
 *
 * ## 不负责
 * - 不做拦截、不改 classloader、不启动 Activity（`TransactionInterceptor` 负责）。
 * - 不做任何异常抛出：本类**任何失败都只降级**，绝不影响投递（注释里叫 "witness"）。
 *
 * ## 约束与坑
 * 1. **绝不能抛异常**：观察点位于 Activity 投递主链路，抛一次就可能导致 Activity 起不来。
 *    所有分支都包在 try/catch 里，失败返回降级文本。
 * 2. **文件按 pid 分**：guest 进程会重启，同一实例不同 pid 的日志不能混。
 * 3. **写入加锁**：主线程 + 可能的其他线程都会写，必须串行化，否则行会交错。
 * 4. 依赖隐藏字段名（`mIntent` / `mIntents`），OS 升级可能改名；
 *    `findField` 是公共工具（`com.nextvm.core.common.findField`），改名时降级为 `<describe failed>`。
 */
internal class TransactionDiagnostics(private val context: Context) {

    private val writeLock = Any()

    /** 记录一条投递见证（永不抛）。 */
    fun traceDelivery(line: String) {
        try {
            val f = File(
                context.filesDir,
                "startactivity-trace-" + android.os.Process.myPid() + ".log"
            )
            synchronized(writeLock) {
                f.appendText(
                    "[t=" + System.currentTimeMillis() + "] athook-delivery: " + line + "\n"
                )
            }
        } catch (_: Throwable) {
            // witness only; never disturb delivery
        }
    }

    /** 把事务 item 压成一行描述（永不抛）。实现委托给纯函数，便于单测。 */
    fun describeTransactionItem(item: Any, className: String): String =
        TransactionItemDescriber.describe(item, className)
}

/**
 * 事务 item 的单行描述（v151 抽出的**纯函数**，不依赖 Context / Android 运行时）。
 *
 * 抽出目的：让「反射失败时降级」「各类 item 的字段解析」可以在 JVM 单测里验证，
 * 不需要 Robolectric 或真机。
 *
 * 约定：**永不抛异常** —— 任何失败都返回 `<describe failed>` 或简单类名。
 */
internal object TransactionItemDescriber {

    fun describe(item: Any, className: String): String {
        val simple = className.substringAfterLast('.')
        return try {
            when {
                className.contains("LaunchActivityItem") -> {
                    val intentField = findField(item::class.java, "mIntent")
                    intentField?.isAccessible = true
                    val intent = intentField?.get(item) as? Intent
                    simple + " cmp=" + intent?.component +
                        " nextvmExtras=" +
                        (intent?.getStringExtra(VirtualIntentExtras.TARGET_PACKAGE) != null) +
                        " target=" + intent?.getStringExtra(VirtualIntentExtras.TARGET_ACTIVITY)
                }
                className.contains("NewIntentItem") -> {
                    val intentsField = findField(item::class.java, "mIntents")
                    intentsField?.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val intents = intentsField?.get(item) as? List<Intent>
                    simple + " intents=[" +
                        (intents?.joinToString(",") {
                            it.component?.flattenToShortString() ?: "?"
                        } ?: "?") + "]"
                }
                else -> simple
            }
        } catch (_: Throwable) {
            simple + " <describe failed>"
        }
    }
}
