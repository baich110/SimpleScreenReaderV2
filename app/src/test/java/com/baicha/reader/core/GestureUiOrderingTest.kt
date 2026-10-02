package com.baicha.reader.core

import com.baicha.reader.ui.GestureMapOrdering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势配置页的**纯逻辑**测试（D2）
 *
 * 【为什么能测】`GestureMapOrdering` 只做「列出哪些手势/动作」，
 * 不依赖 Activity —— 抽出来就是为了可测。
 *
 * 【测不到什么】界面交互、读屏播报、点击响应 → 需真机
 */
class GestureUiOrderingTest {

    /**
     * ★★ 配置页列出的每个手势都必须**有映射**，
     * 否则用户点进去看到「不执行任何操作」却不知道为什么。
     */
    @Test
    fun every_displayed_gesture_has_a_mapping() {
        GestureMapOrdering.displayOrder().forEach { id ->
            assertFalse(
                "手势 $id 在配置页显示但没有任何映射 —— 用户会困惑",
                GestureStore.resolveOrNull(id) == null
            )
        }
    }

    /**
     * ★ 反向：`GestureMap` 里所有有映射的手势都应出现在配置页上，
     * 否则「功能存在但用户改不了」。
     */
    @Test
    fun every_mapped_gesture_is_configurable() {
        val displayed = GestureMapOrdering.displayOrder().toSet()
        GestureMap.allMappedGestures().forEach { id ->
            val action = GestureMap.actionOf(id)
            if (action != GestureAction.NONE) {
                assertTrue(
                    "手势 $id（→$action）有映射但不在配置页列表里，用户改不了",
                    displayed.contains(id)
                )
            }
        }
    }

    /** 显示列表不应有重复 —— 重复会导致同一手势出现两行 */
    @Test
    fun display_order_has_no_duplicates() {
        val order = GestureMapOrdering.displayOrder()
        assertEquals("配置页手势列表不应重复", order.size, order.toSet().size)
    }

    /** 可选动作不应重复 */
    @Test
    fun selectable_actions_have_no_duplicates() {
        val actions = GestureMapOrdering.selectableActions()
        assertEquals("可选动作列表不应重复", actions.size, actions.toSet().size)
    }

    /**
     * ★ 可选动作必须包含 NONE（用于禁用某手势）
     */
    @Test
    fun selectable_actions_include_none() {
        assertTrue(
            "必须能把手势设为「不执行」—— 用户可能要禁用与输入法冲突的手势",
            GestureMapOrdering.selectableActions().contains(GestureAction.NONE)
        )
    }

    /**
     * ★★ 每个默认动作都必须能在选择列表里找到，
     * 否则用户打开某手势时会发现「当前值不在选项里」（单选无选中项）
     */
    @Test
    fun every_default_action_is_selectable() {
        val selectable = GestureMapOrdering.selectableActions().toSet()
        GestureStore.allDefaults().forEach { (gestureId, action) ->
            assertTrue(
                "手势 $gestureId 的默认动作 $action 不在可选列表里 —— 单选会没有选中项",
                selectable.contains(action)
            )
        }
    }

    @Test
    fun display_order_is_not_empty() {
        assertTrue(GestureMapOrdering.displayOrder().size >= 30)
    }

    /**
     * 顺序应按手指数递增 —— 用户容易定位（1 指 → 2 指 → 3 指 → 4 指）
     */
    @Test
    fun display_order_groups_by_finger_count() {
        val order = GestureMapOrdering.displayOrder()
        // 单指手势（GESTURE_SWIPE_* / DOUBLE_TAP*）应排在前半部分
        val singleFinger = listOf(
            Gestures.GESTURE_SWIPE_LEFT, Gestures.GESTURE_SWIPE_RIGHT,
            Gestures.GESTURE_DOUBLE_TAP
        )
        val fourFinger = listOf(
            Gestures.GESTURE_4_FINGER_SWIPE_UP, Gestures.GESTURE_4_FINGER_DOUBLE_TAP
        )
        val maxSingle = singleFinger.maxOf { order.indexOf(it) }
        val minFour = fourFinger.minOf { order.indexOf(it) }
        assertTrue(
            "单指手势应排在四指之前，便于定位",
            maxSingle < minFour
        )
    }
}
