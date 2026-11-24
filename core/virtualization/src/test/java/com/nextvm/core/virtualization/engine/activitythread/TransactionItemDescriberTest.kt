package com.nextvm.core.virtualization.engine.activitythread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事务 item 描述：字段可达 + 反射失败降级。
 * 详细说明见 `docs/代码镜像/core/virtualization/engine/TransactionDiagnostics.md`。
 */
class TransactionItemDescriberTest {

    /** 有 mIntent 字段但类型不是 Intent（单测无 Android 运行时）。 */
    private class LaunchItemWithWrongType(val mIntent: String = "not-an-intent")

    /** 完全没有 mIntent 字段。 */
    private class LaunchItemMissingField

    /** 有 mIntents 字段（NewIntentItem 的结构）。 */
    private class NewIntentItemWithList(val mIntents: List<String> = listOf("a", "b"))

    @Test
    fun `未知 item 返回简单类名`() {
        val r = TransactionItemDescriber.describe(
            Any(), "android.app.servertransaction.ClientTransactionItem"
        )
        assertEquals("ClientTransactionItem", r)
    }

    @Test
    fun `LaunchActivityItem 缺少 mIntent 字段时降级且不抛`() {
        val r = TransactionItemDescriber.describe(
            LaunchItemMissingField(),
            "android.app.servertransaction.LaunchActivityItem"
        )
        assertTrue("应包含简单类名，实际=$r", r.startsWith("LaunchActivityItem"))
    }

    @Test
    fun `LaunchActivityItem 的 mIntent 类型不符时降级且不抛`() {
        val r = TransactionItemDescriber.describe(
            LaunchItemWithWrongType(),
            "android.app.servertransaction.LaunchActivityItem"
        )
        assertTrue("应包含简单类名，实际=$r", r.startsWith("LaunchActivityItem"))
        // intent 解析失败 -> component/extras 都是 null，但不能抛
        assertTrue("应带 cmp= 段，实际=$r", r.contains("cmp="))
    }

    @Test
    fun `NewIntentItem 字段存在但元素类型不符时降级`() {
        val r = TransactionItemDescriber.describe(
            NewIntentItemWithList(),
            "android.app.servertransaction.NewIntentItem"
        )
        assertTrue("应包含简单类名，实际=$r", r.startsWith("NewIntentItem"))
        // 泛型擦除让 List<String> 通过 as? List<Intent>，随后 lambda 取 it.component
        // 抛 ClassCastException -> 被 catch -> 降级为 <describe failed>。契约是「永不抛」。
        assertTrue("应降级且不抛，实际=$r", r.contains("<describe failed>"))
    }

    @Test
    fun `NewIntentItem 缺少 mIntents 字段时降级`() {
        val r = TransactionItemDescriber.describe(
            Any(), "android.app.servertransaction.NewIntentItem"
        )
        assertTrue(r.startsWith("NewIntentItem"))
        assertTrue("应为空列表占位，实际=$r", r.contains("intents=[?]"))
    }

    @Test
    fun `空类名返回空串且不抛`() {
        val r = TransactionItemDescriber.describe(Any(), "")
        assertEquals("", r)
    }

    @Test
    fun `多次调用互不影响（无状态）`() {
        val a = TransactionItemDescriber.describe(Any(), "x.Y")
        val b = TransactionItemDescriber.describe(Any(), "p.Q")
        assertEquals("Y", a)
        assertEquals("Q", b)
        assertNotNull(a)
    }
}
