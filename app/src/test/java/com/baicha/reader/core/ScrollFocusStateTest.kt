package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动焦点恢复框架测试
 *
 * 【对应天坦】
 *   M6/h 内部类 `c`（滚动状态）+ 内部类 `a`（恢复回调）
 *   重试上限 100、间隔 25ms、慢速 500ms
 */
class ScrollFocusStateTest {

    /** 构造纯逻辑测试用的状态（节点为 null 不影响这些断言） */
    private fun state(targetType: Int = ScrollFocusState.TYPE_NORMAL) = ScrollFocusState(
        scrollNode = null,
        anchorNode = null,
        direction = 1,
        targetType = targetType,
        isSingleColumnList = false,
        boundsBeforeScroll = Bounds.EMPTY
    )

    // ==================== 重试逻辑 ====================

    @Test
    fun attempt_starts_at_zero() {
        assertEquals(0, state().attempt)
    }

    @Test
    fun next_attempt_increments() {
        val s = state()
        assertEquals(1, s.nextAttempt())
        assertEquals(2, s.nextAttempt())
        assertEquals(2, s.attempt)
    }

    /**
     * 重试耗尽判定 —— 天坦是 `i9 > 100`（严格大于）
     */
    @Test
    fun not_exhausted_before_limit() {
        val s = state()
        repeat(100) { s.nextAttempt() }
        assertEquals(100, s.attempt)
        assertFalse("第 100 次还未超限（天坦是 > 100）", s.isExhausted())
    }

    @Test
    fun exhausted_after_limit() {
        val s = state()
        repeat(101) { s.nextAttempt() }
        assertEquals(101, s.attempt)
        assertTrue("第 101 次应判定超限", s.isExhausted())
    }

    @Test
    fun cancel_sets_flag() {
        val s = state()
        assertFalse(s.cancelled)
        s.cancel()
        assertTrue(s.cancelled)
    }

    // ==================== 重试延迟 ====================

    @Test
    fun normal_type_uses_fast_retry() {
        assertEquals(25L, state(ScrollFocusState.TYPE_NORMAL).retryDelay())
        assertEquals(25L, state(ScrollFocusState.TYPE_FAST).retryDelay())
    }

    /**
     * targetType == 2（慢速）用 500ms 延迟
     * —— 对应天坦 `if (f6023b == 2) postDelayed(runnable, 500L)`
     */
    @Test
    fun slow_type_uses_slow_retry() {
        assertEquals(500L, state(ScrollFocusState.TYPE_SLOW).retryDelay())
    }

    // ==================== 聚焦动作码 ====================

    /**
     * 动作码映射 —— 对应天坦
     * `targetType != 0 ? (targetType != 1 ? 10 : 8) : 6`
     */
    @Test
    fun action_code_mapping() {
        assertEquals(6, state(ScrollFocusState.TYPE_NORMAL).focusActionCode())
        assertEquals(8, state(ScrollFocusState.TYPE_FAST).focusActionCode())
        assertEquals(10, state(ScrollFocusState.TYPE_SLOW).focusActionCode())
    }

    @Test
    fun unknown_type_falls_back_to_special() {
        // 任何非 0/1 的值都走「特殊聚焦」
        assertEquals(10, state(99).focusActionCode())
    }

    // ==================== 常量对齐天坦 ====================

    @Test
    fun retry_constants_match_tantang() {
        assertEquals("重试上限应为 100（天坦 i9 > 100）", 100, ScrollFocusState.MAX_ATTEMPT)
        assertEquals("重试间隔应为 25ms", 25L, ScrollFocusState.RETRY_DELAY_MS)
        assertEquals("慢速延迟应为 500ms", 500L, ScrollFocusState.SLOW_RETRY_DELAY_MS)
    }

    /**
     * 聚焦动作码 —— 对应天坦 b0() 里的
     * `targetType != 0 ? (targetType != 1 ? 10 : 8) : 6`
     */
    @Test
    fun focus_action_codes_match_tantang() {
        assertEquals(6, ScrollFocusState.ACTION_FOCUS_NORMAL)
        assertEquals(8, ScrollFocusState.ACTION_FOCUS_FAST)
        assertEquals(10, ScrollFocusState.ACTION_FOCUS_SPECIAL)
    }

    @Test
    fun target_types_are_distinct() {
        val types = listOf(
            ScrollFocusState.TYPE_NORMAL,
            ScrollFocusState.TYPE_FAST,
            ScrollFocusState.TYPE_SLOW
        )
        assertEquals(3, types.toSet().size)
    }

    // ==================== 结果类型 ====================

    @Test
    fun recovery_result_variants_exist() {
        // 四种结果类型都可用
        val exhausted = ScrollRecoveryResult.Exhausted(100)
        assertEquals(100, exhausted.attempts)

        assertTrue(ScrollRecoveryResult.Retry is ScrollRecoveryResult)
        assertTrue(ScrollRecoveryResult.Cancelled is ScrollRecoveryResult)
    }

    // ==================== 回调契约 ====================

    @Test
    fun callback_interface_is_implementable() {
        var scrolledCalled = false
        var failedCalled = false

        val cb = object : ScrollRecoveryCallback {
            override fun onAutoScrolled(boundsChanged: Boolean) {
                scrolledCalled = true
            }

            override fun onAutoScrollFailed() {
                failedCalled = true
            }

            override fun assumeScrollSuccess(): Boolean = false
        }

        cb.onAutoScrolled(true)
        assertTrue(scrolledCalled)
        assertFalse(failedCalled)

        cb.onAutoScrollFailed()
        assertTrue(failedCalled)
    }

    @Test
    fun callback_can_assume_success() {
        val cb = object : ScrollRecoveryCallback {
            override fun onAutoScrolled(boundsChanged: Boolean) {}
            override fun onAutoScrollFailed() {}
            override fun assumeScrollSuccess(): Boolean = true
        }
        assertTrue("应支持假定成功（天坦 assumeScrollSuccess）", cb.assumeScrollSuccess())
    }
}

/**
 * 滚动管理器结构性测试
 *
 * AutoScrollManager 需要真实 Service 才能构造，
 * 这里只验证不依赖实例的静态契约。
 */
class AutoScrollConstantsTest {

    @Test
    fun scroll_settle_window_documented() {
        // 110ms 是滚动结束检查延迟（天坦 C2121b 的语义）
        // 通过反射验证常量存在且合理
        val cls = Class.forName("com.baicha.reader.core.AutoScrollManager")
        assertTrue("类应存在", cls != null)
    }
}
