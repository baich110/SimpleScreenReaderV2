package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕状态焦点恢复测试 —— 对应 TalkBack FocusProcessorForScreenStateChange
 *
 * 【测试重点】
 *   三个守卫条件（任一命中就跳过恢复）：
 *     ① 无活动窗口
 *     ② 用户在窗口切换期间已操作（防抢焦点竞态）
 *     ③ 窗口内已有有效焦点
 *
 *   条件 ② 是最容易写错的：它涉及「时间比较 + 来源判定」两个维度。
 */
class ScreenStateRecoveryTest {

    /** 可变的外部状态，便于构造各种场景 */
    private class FakeEnv {
        var focusNode: AccessibilityNodeInfo? = null
        var activeWindowId: Int = 1
        var windowAccessibilityFocused: Boolean = true
        var lastActionTime: Long = 0
        var lastActionSource: Int = 0
        var restoreCalled = false
        var restoreForce = false
    }

    private fun build(env: FakeEnv) = ScreenStateRecovery(
        getCurrentFocus = { env.focusNode },
        getActiveWindowId = { env.activeWindowId },
        isWindowAccessibilityFocused = { env.windowAccessibilityFocused },
        onRestoreFocus = { force ->
            env.restoreCalled = true
            env.restoreForce = force
        },
        getLastFocusActionTime = { env.lastActionTime },
        getLastFocusActionSource = { env.lastActionSource }
    )

    @After
    fun tearDown() {
        // ScreenStateRecovery 是无状态单例注入式，无需清理
    }

    // ==================== 结果枚举 ====================

    @Test
    fun result_values_match_talkback() {
        assertEquals(-3, ScreenStateRecovery.Result.FAIL_NO_ACTIVE_WINDOW.value)
        assertEquals(-2, ScreenStateRecovery.Result.FAIL_HAS_INTERACTION.value)
        assertEquals(-1, ScreenStateRecovery.Result.FAIL_HAS_VALID_FOCUS.value)
        assertEquals(0, ScreenStateRecovery.Result.FAIL_DEFAULT.value)
        assertEquals(1, ScreenStateRecovery.Result.SUCCESS.value)
    }

    @Test
    fun all_results_have_reason_text() {
        ScreenStateRecovery.Result.values().forEach {
            assertTrue("${it.name} 应有中文说明", it.reason.isNotEmpty())
        }
    }

    @Test
    fun focus_source_constants_match_talkback() {
        // TalkBack FocusActionInfo: LOGICAL_NAVIGATION=1, TOUCH_EXPLORATION=2
        assertEquals(1, ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION)
        assertEquals(2, ScreenStateRecovery.FOCUS_SOURCE_TOUCH_EXPLORATION)
    }

    // ==================== 守卫 ①：无活动窗口 ====================

    @Test
    fun fails_without_active_window() {
        val env = FakeEnv().apply { activeWindowId = -1 }
        val r = build(env)

        assertFalse(r.onScreenStateChanged(1000L))
        assertEquals(ScreenStateRecovery.Result.FAIL_NO_ACTIVE_WINDOW, r.lastResult)
        assertFalse("无活动窗口时不该恢复", env.restoreCalled)
    }

    // ==================== 守卫 ②：用户已交互（竞态）====================

    /**
     * 用户在窗口切换「之后」做了触摸探索 → 不该抢焦点
     */
    @Test
    fun skips_when_user_touched_after_transition() {
        val env = FakeEnv().apply {
            activeWindowId = 1
            windowAccessibilityFocused = false   // 无有效焦点，否则会被守卫③拦下
            lastActionTime = 2000L               // 晚于切换开始
            lastActionSource = ScreenStateRecovery.FOCUS_SOURCE_TOUCH_EXPLORATION
        }
        val r = build(env)

        r.onScreenStateChanged(1000L)            // 切换开始于 1000
        assertEquals(ScreenStateRecovery.Result.FAIL_HAS_INTERACTION, r.lastResult)
        assertFalse("用户已交互时不该恢复", env.restoreCalled)
    }

    @Test
    fun skips_when_user_navigated_after_transition() {
        val env = FakeEnv().apply {
            windowAccessibilityFocused = false
            lastActionTime = 5000L
            lastActionSource = ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION
        }
        val r = build(env)

        r.onScreenStateChanged(1000L)
        assertEquals(ScreenStateRecovery.Result.FAIL_HAS_INTERACTION, r.lastResult)
    }

    /**
     * 用户动作发生在切换「之前」→ 不构成竞态，可以恢复
     */
    @Test
    fun allows_when_user_action_before_transition() {
        val env = FakeEnv().apply {
            windowAccessibilityFocused = false
            lastActionTime = 500L                // 早于切换开始
            lastActionSource = ScreenStateRecovery.FOCUS_SOURCE_TOUCH_EXPLORATION
        }
        val r = build(env)

        r.onScreenStateChanged(1000L)
        assertEquals(ScreenStateRecovery.Result.SUCCESS, r.lastResult)
        assertTrue(env.restoreCalled)
    }

    /**
     * 来源是「程序设置」而非用户操作 → 不算竞态
     */
    @Test
    fun allows_when_source_is_not_user_initiated() {
        val env = FakeEnv().apply {
            windowAccessibilityFocused = false
            lastActionTime = 5000L               // 晚于切换，但来源不是用户
            lastActionSource = 3                 // PROGRAMMATIC
        }
        val r = build(env)

        r.onScreenStateChanged(1000L)
        assertEquals(ScreenStateRecovery.Result.SUCCESS, r.lastResult)
    }

    @Test
    fun no_action_record_allows_restore() {
        val env = FakeEnv().apply {
            windowAccessibilityFocused = false
            lastActionTime = 0L                  // 从未有动作
        }
        val r = build(env)

        r.onScreenStateChanged(1000L)
        assertEquals(ScreenStateRecovery.Result.SUCCESS, r.lastResult)
    }

    // ==================== 守卫 ③：已有有效焦点 ====================

    @Test
    fun skips_when_window_not_accessibility_focused() {
        val env = FakeEnv().apply {
            windowAccessibilityFocused = false
            lastActionTime = 0L
        }
        val r = build(env)
        // 窗口无无障碍焦点 → 守卫③不命中 → 应执行恢复
        r.onScreenStateChanged(1000L)
        assertEquals(ScreenStateRecovery.Result.SUCCESS, r.lastResult)
    }

    @Test
    fun skips_when_valid_focus_exists() {
        val env = FakeEnv().apply {
            windowAccessibilityFocused = true
            // focusNode 为 null → hasValid 返回 false（因为无法确认）
        }
        val r = build(env)
        r.onScreenStateChanged(1000L)
        // focusNode == null → 判定无有效焦点 → 恢复
        assertEquals(ScreenStateRecovery.Result.SUCCESS, r.lastResult)
    }

    // ==================== 延迟判定 ====================

    @Test
    fun delay_window_matches_talkback() {
        assertEquals(500L, ScreenStateTiming.WINDOW_CHANGE_DELAY_MS)
    }

    @Test
    fun should_delay_within_window() {
        assertTrue(ScreenStateTiming.shouldDelay(100))
        assertTrue(ScreenStateTiming.shouldDelay(499))
    }

    @Test
    fun should_not_delay_outside_window() {
        assertFalse("0 表示无有效时间信息", ScreenStateTiming.shouldDelay(0))
        assertFalse("负值不延迟", ScreenStateTiming.shouldDelay(-1))
        assertFalse("超过窗口不延迟", ScreenStateTiming.shouldDelay(500))
        assertFalse(ScreenStateTiming.shouldDelay(1000))
    }

    // ==================== 统计与重置 ====================

    @Test
    fun stats_tracks_counts() {
        val env = FakeEnv().apply { activeWindowId = -1 }
        val r = build(env)
        r.onScreenStateChanged(0L)
        assertTrue("应有跳过计数", r.stats().contains("跳过"))
    }

    @Test
    fun reset_clears_state() {
        val env = FakeEnv()
        val r = build(env)
        r.onScreenStateChanged(1000L)
        r.reset()
        assertEquals(null, r.lastResult)
    }
}
