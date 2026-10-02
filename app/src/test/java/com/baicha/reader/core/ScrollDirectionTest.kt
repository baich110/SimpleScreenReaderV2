package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动方向与位置框架测试
 *
 * 三级 fallback 的判定链：
 *   ① 索引（fromIndex/toIndex）
 *   ② 滚动量（scrollX/scrollY）
 *   ③ 增量（scrollDelta）
 *
 * 任何一级写错都会导致「滚动方向反了」或「永远判定失败」，
 * 而后者正是原实现（只看 scrollDeltaY）的问题。
 */
class ScrollDirectionTest {

    // ==================== 快照有效性 ====================

    /**
     * 全 -1 的快照 = 无效（很多 App 的常见情况）
     */
    @Test
    fun all_invalid_fields_mean_no_data() {
        val s = FakeSnapshot(from = -1, to = -1, x = -1, y = -1, dx = -1, dy = -1)
        assertFalse("全无效字段应判定为无数据", s.hasValidData())
    }

    @Test
    fun any_valid_field_counts() {
        assertTrue(FakeSnapshot(from = 0, to = 0, x = -1, y = -1).hasValidData())
        assertTrue(FakeSnapshot(from = -1, to = -1, x = 0, y = -1).hasValidData())
        assertTrue(FakeSnapshot(from = -1, to = -1, x = -1, y = 0).hasValidData())
    }

    @Test
    fun valid_delta_counts_as_data() {
        val s = FakeSnapshot(from = -1, to = -1, x = -1, y = -1, dx = 5, dy = 0)
        assertTrue("有有效增量应算有数据", s.hasValidData())
    }

    // ==================== 三级 fallback ====================

    /**
     * 第一级：索引比较
     */
    @Test
    fun index_comparison_takes_priority() {
        val prev = FakeSnapshot(from = 0, to = 5)
        val fwd = FakeSnapshot(from = 5, to = 10)
        val bwd = FakeSnapshot(from = 0, to = 3)

        assertEquals(
            ScrollDirectionDetector.FORWARD,
            ScrollDirectionDetector.detect(fwd, prev)
        )
        assertEquals(
            ScrollDirectionDetector.BACKWARD,
            ScrollDirectionDetector.detect(bwd, prev)
        )
    }

    /**
     * 第二级：索引相同时比滚动量
     */
    @Test
    fun scroll_offset_used_when_index_same() {
        val prev = FakeSnapshot(from = 2, to = 5, x = 0, y = 100)
        val fwd = FakeSnapshot(from = 2, to = 5, x = 0, y = 200)
        val bwd = FakeSnapshot(from = 2, to = 5, x = 0, y = 50)

        assertEquals(
            ScrollDirectionDetector.FORWARD,
            ScrollDirectionDetector.detect(fwd, prev)
        )
        assertEquals(
            ScrollDirectionDetector.BACKWARD,
            ScrollDirectionDetector.detect(bwd, prev)
        )
    }

    /**
     * 第三级：全都相同时用增量兜底
     */
    @Test
    fun delta_fallback_when_everything_else_equal() {
        val prev = FakeSnapshot(from = 2, to = 5, x = 0, y = 100, dy = 0)
        val fwd = FakeSnapshot(from = 2, to = 5, x = 0, y = 100, dy = 30)
        val bwd = FakeSnapshot(from = 2, to = 5, x = 0, y = 100, dy = -30)

        assertEquals(
            ScrollDirectionDetector.FORWARD,
            ScrollDirectionDetector.detect(fwd, prev)
        )
        assertEquals(
            ScrollDirectionDetector.BACKWARD,
            ScrollDirectionDetector.detect(bwd, prev)
        )
    }

    /**
     * 无历史快照 → 只能靠增量
     */
    @Test
    fun no_history_uses_delta_only() {
        val fwd = FakeSnapshot(dy = 10)
        assertEquals(
            ScrollDirectionDetector.FORWARD,
            ScrollDirectionDetector.detect(fwd, null)
        )

        // 增量也无效 → 未知
        val unknown = FakeSnapshot(from = 0, to = 5, dy = -1, dx = -1)
        assertEquals(
            ScrollDirectionDetector.UNKNOWN,
            ScrollDirectionDetector.detect(unknown, null)
        )
    }

    /**
     * 禁用增量兜底时（对应天坦的旧版本 SDK 门控）应返回 UNKNOWN
     */
    @Test
    fun delta_fallback_can_be_disabled() {
        val s = FakeSnapshot(dy = 10)
        assertEquals(
            ScrollDirectionDetector.UNKNOWN,
            ScrollDirectionDetector.detect(s, null, useDeltaFallback = false)
        )
    }

    // ==================== 增量判定 ====================

    @Test
    fun delta_detection_rules() {
        assertEquals(
            ScrollDirectionDetector.UNKNOWN,
            ScrollDirectionDetector.detectByDelta(FakeSnapshot(dx = -1, dy = -1))
        )
        assertEquals(
            ScrollDirectionDetector.FORWARD,
            ScrollDirectionDetector.detectByDelta(FakeSnapshot(dy = 1))
        )
        assertEquals(
            ScrollDirectionDetector.BACKWARD,
            ScrollDirectionDetector.detectByDelta(FakeSnapshot(dy = -1, dx = 0))
        )
        assertEquals(
            ScrollDirectionDetector.FORWARD,
            ScrollDirectionDetector.detectByDelta(FakeSnapshot(dx = 5, dy = 0))
        )
    }

    @Test
    fun direction_names_are_chinese() {
        assertEquals("向后", ScrollDirectionDetector.name(ScrollDirectionDetector.FORWARD))
        assertEquals("向前", ScrollDirectionDetector.name(ScrollDirectionDetector.BACKWARD))
        assertEquals("未知", ScrollDirectionDetector.name(ScrollDirectionDetector.UNKNOWN))
    }

    // ==================== 位置格式化 ====================

    @Test
    fun format_single_item() {
        assertEquals(
            "第 3 项，共 10 项",
            ScrollPositionFormatter.format(fromIndex = 2, toIndex = 2, itemCount = 10)
        )
    }

    @Test
    fun format_range() {
        assertEquals(
            "第 3 到 5 项，共 10 项",
            ScrollPositionFormatter.format(fromIndex = 2, toIndex = 4, itemCount = 10)
        )
    }

    @Test
    fun format_invalid_index_returns_null() {
        assertNull(ScrollPositionFormatter.format(fromIndex = -1, toIndex = 0, itemCount = 10))
        assertNull(ScrollPositionFormatter.format(fromIndex = 0, toIndex = 0, itemCount = 0))
    }

    @Test
    fun format_out_of_range_to_index_falls_back_to_single() {
        // toIndex 超出总数 → 按单项处理
        assertEquals(
            "第 1 项，共 5 项",
            ScrollPositionFormatter.format(fromIndex = 0, toIndex = 99, itemCount = 5)
        )
    }

    @Test
    fun format_pager_includes_label() {
        val msg = ScrollPositionFormatter.format(
            fromIndex = 1, toIndex = 1, itemCount = 5,
            isPager = true, pagerLabel = "第 2 页"
        )
        assertNotNull(msg)
        assertTrue("分页格式应含页码", msg!!.contains("第 2 页"))
        assertTrue("分页格式应含位置", msg.contains("共 5 项"))
    }

    @Test
    fun format_pager_without_label() {
        val msg = ScrollPositionFormatter.format(
            fromIndex = 0, toIndex = 0, itemCount = 3, isPager = true
        )
        assertEquals("第 1 项，共 3 项", msg)
    }
}

/**
 * 测试用快照构造辅助
 *
 * `ScrollEventSnapshot` 是纯数据类，直接构造即可测试。
 */
private fun FakeSnapshot(
    from: Int = -1,
    to: Int = -1,
    x: Int = -1,
    y: Int = -1,
    dx: Int = -1,
    dy: Int = -1,
    itemCount: Int = 0
) = ScrollEventSnapshot(
    fromIndex = from,
    toIndex = to,
    scrollX = x,
    scrollY = y,
    itemCount = itemCount,
    deltaX = dx,
    deltaY = dy
)
