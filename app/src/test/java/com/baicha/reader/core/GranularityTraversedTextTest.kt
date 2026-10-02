package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 粒度单元切片测试 —— 修复 BUG-1
 *
 * 【测试对象是纯函数 sliceUnit，不是 AccessibilityEvent】
 *   `AccessibilityEvent.obtain()` 在 JVM 单测里返回 null
 *   （Android SDK 是空壳类），所以事件对象根本构造不出来。
 *   本轮把切片逻辑抽成只吃 String/Int 的纯函数后再测 ——
 *   判定规则这才真正可验证。
 *
 * 【这批测试为什么能真正抓住 BUG-1】
 *   该 bug 的形态是「event.text 携带全文，实现却把它当单元」。
 *   所以每个用例都**故意让 eventText 等于全文**，
 *   只有按索引切片的实现才能通过。若实现退回「优先用 event.text」，
 *   这些用例会立刻失败。
 *
 * 【权威依据】
 *   TalkBack `TextEventInterpreter:568` + `getSubsequenceWithSpans()`：
 *   只在 `from >= 0 && text.length >= to && to >= from` 时
 *   `text.subSequence(from, to)`。
 */
class GranularityTraversedTextTest {

    private val full = "这是一段很长的测试文本用来模拟真实节点内容"

    /**
     * ★ 核心：索引合法时，必须切出 [from, to)，**而非返回全文**。
     *
     * 真机行为：event.text 携带全文，from/to 指向当前粒度单元。
     * 修复前 → 返回全文（bug）；修复后 → 返回切片。
     */
    @Test
    fun slices_by_index_when_event_text_is_full_text() {
        val expected = full.substring(2, 4)
        val result = GranularityMover.sliceUnit(
            fullText = full,
            from = 2,
            to = 4,
            eventText = full          // ★ 模拟真机：事件文本就是全文
        )
        assertEquals("必须切出 [from,to) 而不是返回全文", expected, result)
    }

    /**
     * ★ 反例锁定：结果绝不能等于全文。
     * 这是本 bug 最直接的判定特征。
     */
    @Test
    fun result_is_never_the_full_text() {
        val result = GranularityMover.sliceUnit(
            fullText = full,
            from = 0,
            to = 3,
            eventText = full
        )
        org.junit.Assert.assertNotEquals(
            "返回全文说明 bug 仍在", full, result
        )
    }

    /** 单字导航：切一个字符 */
    @Test
    fun single_character_unit() {
        assertEquals(
            full.substring(5, 6),
            GranularityMover.sliceUnit(full, 5, 6, full)
        )
    }

    /** 整段覆盖时，切片等于全文 —— 这是合法结果，不是 bug */
    @Test
    fun full_range_selection_is_legitimate() {
        assertEquals(
            "选区覆盖全文时切片本就等于全文",
            full,
            GranularityMover.sliceUnit(full, 0, full.length, full)
        )
    }

    // ==================== 边界（对齐 TalkBack）====================

    @Test
    fun negative_from_is_rejected() {
        assertNull(GranularityMover.sliceUnit(full, -1, 4, null))
    }

    @Test
    fun to_beyond_text_length_is_rejected() {
        assertNull(
            "to > 文本长度 应被拒（TalkBack: text.length < to → null）",
            GranularityMover.sliceUnit(full, 0, full.length + 5, null)
        )
    }

    @Test
    fun to_before_from_is_rejected() {
        assertNull(GranularityMover.sliceUnit(full, 5, 2, null))
    }

    @Test
    fun empty_selection_yields_null() {
        assertNull(
            "from == to 是空选区，没有单元可读",
            GranularityMover.sliceUnit(full, 3, 3, null)
        )
    }

    /** 空白切片应被丢弃（避免朗读纯空格） */
    @Test
    fun whitespace_only_slice_is_discarded() {
        val text = "abc   def"
        // 索引 3~6 是三个空格
        assertNull(
            "纯空白切片不该朗读",
            GranularityMover.sliceUnit(text, 3, 6, null)
        )
    }

    // ==================== event.text 兜底路径 ====================

    /**
     * 取不到全文时，保守采信 event.text
     * （真机上比返回 null 更有用 —— 至少能读点东西）
     */
    @Test
    fun falls_back_to_event_text_when_full_text_unavailable() {
        assertEquals(
            "取不到全文时应采信事件文本",
            "片段",
            GranularityMover.sliceUnit("", 0, 0, "片段")
        )
    }

    /**
     * ★ 关键：event.text 等于全文时**必须丢弃**。
     * 这是防止 bug 复发的最后一道闸。
     */
    @Test
    fun event_text_equal_to_full_text_is_discarded() {
        assertNull(
            "事件文本等于全文 → 它是整段不是单元，必须丢弃",
            GranularityMover.sliceUnit(full, -1, -1, full)
        )
    }

    /** event.text 比全文长（异常情况）同样丢弃 */
    @Test
    fun event_text_longer_than_full_text_is_discarded() {
        assertNull(
            GranularityMover.sliceUnit("短", -1, -1, "这是一段更长的事件文本")
        )
    }

    @Test
    fun null_and_empty_inputs_are_safe() {
        assertNull(GranularityMover.sliceUnit("", 0, 0, null))
        assertNull(GranularityMover.sliceUnit("", 0, 0, ""))
        assertNull(GranularityMover.sliceUnit("", 0, 0, "   "))
    }
}
