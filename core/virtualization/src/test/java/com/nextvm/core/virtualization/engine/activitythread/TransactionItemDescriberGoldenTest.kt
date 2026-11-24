package com.nextvm.core.virtualization.engine.activitythread

import com.nextvm.core.virtualization.testsupport.Golden
import org.junit.Test

/**
 * 行为等价性 golden：`describe()` 的**完整输出**必须与批准文件一致。
 * 与 `TransactionItemDescriberTest` 互补：那边钉关键性质，这里钉整体不变。
 * 详见 `docs/设计/设计-重构行为等价性验证.md`。
 */
class TransactionItemDescriberGoldenTest {

    private class LaunchItem(val mIntent: String = "not-an-intent")
    private class LaunchItemNoField
    private class NewIntentItemWithList(val mIntents: List<String> = listOf("a", "b"))
    private class NewIntentItemNoField

    private fun corpus(): List<Triple<String, Any, String>> = listOf(
        Triple(
            "launch-missing-field",
            LaunchItemNoField(),
            "android.app.servertransaction.LaunchActivityItem"
        ),
        Triple(
            "launch-wrong-type",
            LaunchItem(),
            "android.app.servertransaction.LaunchActivityItem"
        ),
        Triple(
            "newintent-missing-field",
            NewIntentItemNoField(),
            "android.app.servertransaction.NewIntentItem"
        ),
        Triple(
            "newintent-wrong-element-type",
            NewIntentItemWithList(),
            "android.app.servertransaction.NewIntentItem"
        ),
        Triple(
            "unknown-item",
            Any(),
            "android.app.servertransaction.ClientTransactionItem"
        ),
        Triple("empty-classname", Any(), "")
    )

    @Test
    fun `所有语料的输出与批准文件一致`() {
        val actual = corpus().joinToString("\n") { (label, item, cls) ->
            "$label => ${TransactionItemDescriber.describe(item, cls)}"
        } + "\n"
        Golden.verify("TransactionItemDescriber", actual)
    }
}
