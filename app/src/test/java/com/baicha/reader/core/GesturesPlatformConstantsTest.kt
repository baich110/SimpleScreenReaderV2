package com.baicha.reader.core

import android.accessibilityservice.AccessibilityService as A11y
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势 ID 常量 —— **必须与 Android 平台一致**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么这类测试不可省（本项目已踩过的坑）】
 * ══════════════════════════════════════════════════════════
 *
 * `Gestures.kt` 里的手势 ID 是**手抄进来的字面量**。
 * 抄错一个数字的后果是**静默且难查**：
 *   · 代码能编译、测试能全绿（因为两边都是自家常量，自洽）
 *   · 真机上表现为「某个手势完全没反应」，或更糟 ——
 *     **触发成另一个手势**（例如把「三指向下滑」抄成「四指下滑」）
 *
 * 而且它不影响主流程，只是让某个手势偷偷失效 ——
 * 对视障用户，失灵的手势就是**不可用的功能**，
 * 但用户通常只会觉得「这个手势不支持」。
 *
 * 【本测试的判定方式】
 * 直接与 `android.accessibilityservice.AccessibilityService` 的
 * 官方常量逐一对齐 —— 编译期就能定位到哪一个抄错了，
 * 而不是等真机试出来。
 *
 * 【平台依据】`android.accessibilityservice.AccessibilityService`
 * （API 37 平台 jar 实测取值，见下每条的 assert 参数）
 */
class GesturesPlatformConstantsTest {

    // ==================== 单指滑动 ====================

    @Test
    fun singleFingerSwipeIds_matchPlatform() {
        assertEquals(A11y.GESTURE_SWIPE_UP, Gestures.GESTURE_SWIPE_UP)
        assertEquals(A11y.GESTURE_SWIPE_DOWN, Gestures.GESTURE_SWIPE_DOWN)
        assertEquals(A11y.GESTURE_SWIPE_LEFT, Gestures.GESTURE_SWIPE_LEFT)
        assertEquals(A11y.GESTURE_SWIPE_RIGHT, Gestures.GESTURE_SWIPE_RIGHT)
    }

    // ==================== 组合滑动（L 形 / 往返）====================

    @Test
    fun combinedSwipeIds_matchPlatform() {
        assertEquals(A11y.GESTURE_SWIPE_LEFT_AND_RIGHT, Gestures.GESTURE_SWIPE_LEFT_AND_RIGHT)
        assertEquals(A11y.GESTURE_SWIPE_RIGHT_AND_LEFT, Gestures.GESTURE_SWIPE_RIGHT_AND_LEFT)
        assertEquals(A11y.GESTURE_SWIPE_UP_AND_DOWN, Gestures.GESTURE_SWIPE_UP_AND_DOWN)
        assertEquals(A11y.GESTURE_SWIPE_DOWN_AND_UP, Gestures.GESTURE_SWIPE_DOWN_AND_UP)

        assertEquals(A11y.GESTURE_SWIPE_LEFT_AND_UP, Gestures.GESTURE_SWIPE_LEFT_AND_UP)
        assertEquals(A11y.GESTURE_SWIPE_LEFT_AND_DOWN, Gestures.GESTURE_SWIPE_LEFT_AND_DOWN)
        assertEquals(A11y.GESTURE_SWIPE_RIGHT_AND_UP, Gestures.GESTURE_SWIPE_RIGHT_AND_UP)
        assertEquals(A11y.GESTURE_SWIPE_RIGHT_AND_DOWN, Gestures.GESTURE_SWIPE_RIGHT_AND_DOWN)

        assertEquals(A11y.GESTURE_SWIPE_UP_AND_LEFT, Gestures.GESTURE_SWIPE_UP_AND_LEFT)
        assertEquals(A11y.GESTURE_SWIPE_UP_AND_RIGHT, Gestures.GESTURE_SWIPE_UP_AND_RIGHT)
        assertEquals(A11y.GESTURE_SWIPE_DOWN_AND_LEFT, Gestures.GESTURE_SWIPE_DOWN_AND_LEFT)
        assertEquals(A11y.GESTURE_SWIPE_DOWN_AND_RIGHT, Gestures.GESTURE_SWIPE_DOWN_AND_RIGHT)
    }

    // ==================== 点击类 ====================

    @Test
    fun tapIds_matchPlatform() {
        assertEquals("双击是 17 —— GestureChain 硬编码依赖它",
            A11y.GESTURE_DOUBLE_TAP, Gestures.GESTURE_DOUBLE_TAP)
        assertEquals("双击长按是 18 —— 天坦 :869 硬编码",
            A11y.GESTURE_DOUBLE_TAP_AND_HOLD, Gestures.GESTURE_DOUBLE_TAP_AND_HOLD)

        assertEquals(A11y.GESTURE_2_FINGER_SINGLE_TAP, Gestures.GESTURE_2_FINGER_SINGLE_TAP)
        assertEquals(A11y.GESTURE_2_FINGER_DOUBLE_TAP, Gestures.GESTURE_2_FINGER_DOUBLE_TAP)
        assertEquals(A11y.GESTURE_2_FINGER_TRIPLE_TAP, Gestures.GESTURE_2_FINGER_TRIPLE_TAP)

        assertEquals(A11y.GESTURE_3_FINGER_SINGLE_TAP, Gestures.GESTURE_3_FINGER_SINGLE_TAP)
        assertEquals(A11y.GESTURE_3_FINGER_DOUBLE_TAP, Gestures.GESTURE_3_FINGER_DOUBLE_TAP)
        assertEquals(A11y.GESTURE_3_FINGER_TRIPLE_TAP, Gestures.GESTURE_3_FINGER_TRIPLE_TAP)

        assertEquals(A11y.GESTURE_4_FINGER_SINGLE_TAP, Gestures.GESTURE_4_FINGER_SINGLE_TAP)
        assertEquals(A11y.GESTURE_4_FINGER_DOUBLE_TAP, Gestures.GESTURE_4_FINGER_DOUBLE_TAP)
        assertEquals(A11y.GESTURE_4_FINGER_TRIPLE_TAP, Gestures.GESTURE_4_FINGER_TRIPLE_TAP)
    }

    // ==================== 多指滑动 ====================

    @Test
    fun twoFingerSwipeIds_matchPlatform() {
        assertEquals(A11y.GESTURE_2_FINGER_SWIPE_UP, Gestures.GESTURE_2_FINGER_SWIPE_UP)
        assertEquals(A11y.GESTURE_2_FINGER_SWIPE_DOWN, Gestures.GESTURE_2_FINGER_SWIPE_DOWN)
        assertEquals(A11y.GESTURE_2_FINGER_SWIPE_LEFT, Gestures.GESTURE_2_FINGER_SWIPE_LEFT)
        assertEquals(A11y.GESTURE_2_FINGER_SWIPE_RIGHT, Gestures.GESTURE_2_FINGER_SWIPE_RIGHT)
    }

    @Test
    fun threeFingerSwipeIds_matchPlatform() {
        assertEquals(A11y.GESTURE_3_FINGER_SWIPE_UP, Gestures.GESTURE_3_FINGER_SWIPE_UP)
        assertEquals(A11y.GESTURE_3_FINGER_SWIPE_DOWN, Gestures.GESTURE_3_FINGER_SWIPE_DOWN)
        assertEquals(A11y.GESTURE_3_FINGER_SWIPE_LEFT, Gestures.GESTURE_3_FINGER_SWIPE_LEFT)
        assertEquals(A11y.GESTURE_3_FINGER_SWIPE_RIGHT, Gestures.GESTURE_3_FINGER_SWIPE_RIGHT)
    }

    @Test
    fun fourFingerSwipeIds_matchPlatform() {
        assertEquals(A11y.GESTURE_4_FINGER_SWIPE_UP, Gestures.GESTURE_4_FINGER_SWIPE_UP)
        assertEquals(A11y.GESTURE_4_FINGER_SWIPE_DOWN, Gestures.GESTURE_4_FINGER_SWIPE_DOWN)
        assertEquals(A11y.GESTURE_4_FINGER_SWIPE_LEFT, Gestures.GESTURE_4_FINGER_SWIPE_LEFT)
        assertEquals(A11y.GESTURE_4_FINGER_SWIPE_RIGHT, Gestures.GESTURE_4_FINGER_SWIPE_RIGHT)
    }

    // ==================== 多指长按 ====================

    @Test
    fun multiFingerHoldIds_matchPlatform() {
        assertEquals(A11y.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD, Gestures.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD)
        assertEquals(A11y.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD, Gestures.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD)
        assertEquals(A11y.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD, Gestures.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD)
        assertEquals(A11y.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD, Gestures.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD)
        assertEquals(A11y.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD, Gestures.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD)
        assertEquals(A11y.GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD, Gestures.GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD)
    }

    @Test
    fun unknownConstant_isZero() {
        assertEquals("GESTURE_UNKNOWN 必须是平台的 0", A11y.GESTURE_UNKNOWN, Gestures.GESTURE_UNKNOWN)
    }

    // ==================== 内部约定：无冲突 ====================

    /**
     * ★ 自定义负值常量**不得**与平台 ID 冲突
     *
     * 白茶用 -1 / -2 表示「穿透」「触摸探索」两个内部事件，
     * 而平台 ID 全是 0 或正整数。若有人把白茶的常量改成正数，
     * 就会**撞上真实手势**（例如 -1 改成 1 = GESTURE_SWIPE_UP），
     * 于是「触摸探索」事件会被当成「单指上滑」——
     * 用户摸一下屏幕焦点就跳走。
     */
    @Test
    fun internalNegativeConstants_doNotCollideWithPlatform() {
        assertTrue("GESTURE_PASSTHROUGH 必须为负", Gestures.GESTURE_PASSTHROUGH < 0)
        assertTrue("GESTURE_TOUCH_EXPLORATION 必须为负", Gestures.GESTURE_TOUCH_EXPLORATION < 0)
        assertTrue(
            "两者必须不同",
            Gestures.GESTURE_PASSTHROUGH != Gestures.GESTURE_TOUCH_EXPLORATION
        )
    }

    /**
     * ★ 所有公开的**真实**手势常量必须两两不同
     *
     * 抄写时最容易犯的错是「复制一行忘了改数字」，
     * 导致两个不同名字的手势其实是同一个 ID ——
     * 于是其中一个**永远不可能被触发**（被另一个抢先处理）。
     */
    @Test
    fun allPlatformGestureIds_areDistinct() {
        val ids = listOf(
            Gestures.GESTURE_SWIPE_UP, Gestures.GESTURE_SWIPE_DOWN,
            Gestures.GESTURE_SWIPE_LEFT, Gestures.GESTURE_SWIPE_RIGHT,
            Gestures.GESTURE_SWIPE_LEFT_AND_RIGHT, Gestures.GESTURE_SWIPE_RIGHT_AND_LEFT,
            Gestures.GESTURE_SWIPE_UP_AND_DOWN, Gestures.GESTURE_SWIPE_DOWN_AND_UP,
            Gestures.GESTURE_SWIPE_LEFT_AND_UP, Gestures.GESTURE_SWIPE_LEFT_AND_DOWN,
            Gestures.GESTURE_SWIPE_RIGHT_AND_UP, Gestures.GESTURE_SWIPE_RIGHT_AND_DOWN,
            Gestures.GESTURE_SWIPE_UP_AND_LEFT, Gestures.GESTURE_SWIPE_UP_AND_RIGHT,
            Gestures.GESTURE_SWIPE_DOWN_AND_LEFT, Gestures.GESTURE_SWIPE_DOWN_AND_RIGHT,
            Gestures.GESTURE_DOUBLE_TAP, Gestures.GESTURE_DOUBLE_TAP_AND_HOLD,
            Gestures.GESTURE_2_FINGER_SINGLE_TAP, Gestures.GESTURE_2_FINGER_DOUBLE_TAP,
            Gestures.GESTURE_2_FINGER_TRIPLE_TAP, Gestures.GESTURE_3_FINGER_SINGLE_TAP,
            Gestures.GESTURE_3_FINGER_DOUBLE_TAP, Gestures.GESTURE_3_FINGER_TRIPLE_TAP,
            Gestures.GESTURE_2_FINGER_SWIPE_UP, Gestures.GESTURE_2_FINGER_SWIPE_DOWN,
            Gestures.GESTURE_2_FINGER_SWIPE_LEFT, Gestures.GESTURE_2_FINGER_SWIPE_RIGHT,
            Gestures.GESTURE_3_FINGER_SWIPE_UP, Gestures.GESTURE_3_FINGER_SWIPE_DOWN,
            Gestures.GESTURE_3_FINGER_SWIPE_LEFT, Gestures.GESTURE_3_FINGER_SWIPE_RIGHT,
            Gestures.GESTURE_4_FINGER_SWIPE_UP, Gestures.GESTURE_4_FINGER_SWIPE_DOWN,
            Gestures.GESTURE_4_FINGER_SWIPE_LEFT, Gestures.GESTURE_4_FINGER_SWIPE_RIGHT,
            Gestures.GESTURE_4_FINGER_SINGLE_TAP, Gestures.GESTURE_4_FINGER_DOUBLE_TAP,
            Gestures.GESTURE_4_FINGER_TRIPLE_TAP,
            Gestures.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD,
            Gestures.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD,
            Gestures.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD,
            Gestures.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD,
            Gestures.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD,
            Gestures.GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD
        )
        val dup = ids.groupBy { it }.filterValues { it.size > 1 }
        assertTrue("存在重复的手势 ID（复制行忘了改数字）: $dup", dup.isEmpty())
        assertEquals("手势常量总数", 45, ids.size)
    }
}
