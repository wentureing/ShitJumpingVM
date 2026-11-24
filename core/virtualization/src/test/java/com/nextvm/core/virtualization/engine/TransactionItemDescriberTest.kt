package com.nextvm.core.virtualization.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块级单测：事务 item 描述的**降级行为**（v151）。
 *
 * 为什么只测降级：这个描述函数运行在 Activity 投递主链路上，
 * **它抛一次异常就可能让 Activity 起不来**。所以最重要的契约是"永不抛"，
 * 其次才是"描述得准"。
 */
class TransactionItemDescriberTest {

    /** 一个和 LaunchActivityItem 同名的假 item（没有 mIntent 字段）。 */
    private class FakeLaunchActivityItem

    /** 普通对象 + 未知类名 → 应只返回简单类名。 */
    @Test
    fun `未知 item 返回简单类名`() {
        val r = TransactionItemDescriber.describe(Any(), "android.app.servertransaction.ClientTransactionItem")
        assertEquals("ClientTransactionItem", r)
    }

    /** 类名匹配 LaunchActivityItem 但对象没有 mIntent → 必须降级而不是抛。 */
    @Test
    fun `LaunchActivityItem 缺少 mIntent 字段时降级`() {
        val r = TransactionItemDescriber.describe(
            FakeLaunchActivityItem(),
            "android.app.servertransaction.LaunchActivityItem"
        )
        assertTrue("应包含简单类名，实际=$r", r.startsWith("LaunchActivityItem"))
        // 没有 intent 时，字段解析为 null，但绝不能抛
        assertTrue("不应抛异常，实际=$r", r.isNotEmpty())
    }

    /** 类名匹配 NewIntentItem 但对象没有 mIntents → 降级。 */
    @Test
    fun `NewIntentItem 缺少 mIntents 字段时降级`() {
        val r = TransactionItemDescriber.describe(
            Any(),
            "android.app.servertransaction.NewIntentItem"
        )
        assertTrue("应包含简单类名，实际=$r", r.startsWith("NewIntentItem"))
    }

    /**
     * 空类名：契约是「不抛异常」，返回空字符串是允许的。
     *
     * 注意：这里**不能**断言 isNotEmpty() —— 首版就是这么写错的，
     * 被 module-test CI 抓到（14 tests, 1 failed）。这个函数的目标是
     * 「绝不因为输入异常而崩溃」，而不是「一定返回有意义的描述」。
     */
    @Test
    fun `奇怪类名不会导致异常`() {
        val r = TransactionItemDescriber.describe(Any(), "")
        assertEquals("", r)
    }
}
