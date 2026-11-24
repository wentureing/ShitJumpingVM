package com.nextvm.core.virtualization.engine

import android.content.Context
import android.content.Intent
import com.nextvm.core.common.findField
import com.nextvm.core.model.VirtualIntentExtras
import java.io.File

/**
 * 事务投递观测（witness 日志 + item 单行描述），永不抛。
 * 详见 `docs/代码镜像/core/virtualization/engine/TransactionDiagnostics.md`。
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
