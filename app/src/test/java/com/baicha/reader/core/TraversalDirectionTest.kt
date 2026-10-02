package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遍历方向系统单测 —— 锁定与 TalkBack TraversalStrategy 的数值对齐
 */
class TraversalDirectionTest {

    /**
     * 方向常量数值必须与 TalkBack 的 SearchDirection 一致
     */
    @Test
    fun direction_constants_match_talkback() {
        assertEquals(0, TraversalDirection.UNKNOWN)
        assertEquals(1, TraversalDirection.FORWARD)
        assertEquals(2, TraversalDirection.BACKWARD)
        assertEquals(3, TraversalDirection.LEFT)
        assertEquals(4, TraversalDirection.RIGHT)
        assertEquals(5, TraversalDirection.UP)
        assertEquals(6, TraversalDirection.DOWN)
    }

    @Test
    fun spatial_direction_classification() {
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.LEFT))
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.RIGHT))
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.UP))
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.DOWN))

        assertFalse(TraversalDirection.isSpatial(TraversalDirection.FORWARD))
        assertFalse(TraversalDirection.isSpatial(TraversalDirection.BACKWARD))
    }

    @Test
    fun logical_direction_classification() {
        assertTrue(TraversalDirection.isLogical(TraversalDirection.FORWARD))
        assertTrue(TraversalDirection.isLogical(TraversalDirection.BACKWARD))
        assertFalse(TraversalDirection.isLogical(TraversalDirection.LEFT))
        assertFalse(TraversalDirection.isLogical(TraversalDirection.UP))
    }

    @Test
    fun invalid_direction_throws() {
        var threw = false
        try {
            TraversalDirection.isSpatial(99)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("非法方向应抛异常", threw)
    }

    /**
     * LTR 下：左 → BACKWARD，右 → FORWARD（对齐 TalkBack getLogicalDirection）
     */
    @Test
    fun ltr_spatial_to_logical() {
        assertEquals(TraversalDirection.BACKWARD, TraversalDirection.toLogical(TraversalDirection.LEFT, false))
        assertEquals(TraversalDirection.FORWARD, TraversalDirection.toLogical(TraversalDirection.RIGHT, false))
        assertEquals(TraversalDirection.BACKWARD, TraversalDirection.toLogical(TraversalDirection.UP, false))
        assertEquals(TraversalDirection.FORWARD, TraversalDirection.toLogical(TraversalDirection.DOWN, false))
    }

    /**
     * RTL 下：左右映射翻转（阿拉伯语等）
     */
    @Test
    fun rtl_spatial_to_logical_flips_lr() {
        assertEquals(TraversalDirection.FORWARD, TraversalDirection.toLogical(TraversalDirection.LEFT, true))
        assertEquals(TraversalDirection.BACKWARD, TraversalDirection.toLogical(TraversalDirection.RIGHT, true))
        // 上下不受 RTL 影响
        assertEquals(TraversalDirection.BACKWARD, TraversalDirection.toLogical(TraversalDirection.UP, true))
        assertEquals(TraversalDirection.FORWARD, TraversalDirection.toLogical(TraversalDirection.DOWN, true))
    }

    @Test
    fun logical_directions_pass_through() {
        assertEquals(TraversalDirection.FORWARD, TraversalDirection.toLogical(TraversalDirection.FORWARD, false))
        assertEquals(TraversalDirection.BACKWARD, TraversalDirection.toLogical(TraversalDirection.BACKWARD, false))
    }

    @Test
    fun scroll_action_mapping() {
        assertEquals(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            TraversalDirection.toScrollAction(TraversalDirection.FORWARD)
        )
        assertEquals(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            TraversalDirection.toScrollAction(TraversalDirection.BACKWARD)
        )
        assertEquals(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            TraversalDirection.toScrollAction(TraversalDirection.DOWN)
        )
        assertEquals(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            TraversalDirection.toScrollAction(TraversalDirection.UP)
        )
    }

    @Test
    fun scroll_action_roundtrip() {
        for (dir in listOf(TraversalDirection.FORWARD, TraversalDirection.BACKWARD)) {
            val action = TraversalDirection.toScrollAction(dir)
            assertEquals("往返转换失败", dir, TraversalDirection.fromScrollAction(action))
        }
    }

    @Test
    fun names_are_chinese() {
        assertEquals("前进", TraversalDirection.name(TraversalDirection.FORWARD))
        assertEquals("后退", TraversalDirection.name(TraversalDirection.BACKWARD))
        assertEquals("左", TraversalDirection.name(TraversalDirection.LEFT))
        assertEquals("未知", TraversalDirection.name(TraversalDirection.UNKNOWN))
    }

    @Test
    fun all_contains_six_directions() {
        assertEquals(6, TraversalDirection.all.size)
    }
}
