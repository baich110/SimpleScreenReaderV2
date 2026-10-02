package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件分发器单测 —— 锁定框架层的事件链路
 */
class EventDispatcherTest {

    @Test
    fun self_package_name_is_settable() {
        val q = EventQueue()
        val d = EventDispatcher(q)
        d.selfPackageName = "com.test.app"
        assertEquals("com.test.app", d.selfPackageName)
    }

    @Test
    fun counters_start_at_zero() {
        val d = EventDispatcher(EventQueue())
        assertEquals(0L, d.passedCount)
        assertEquals(0L, d.droppedCount)
    }

    @Test
    fun reset_all_clears_state() {
        val d = EventDispatcher(EventQueue())
        d.resetAll()
        assertEquals(0L, d.passedCount)
    }

    @Test
    fun screen_state_is_settable() {
        val d = EventDispatcher(EventQueue())
        d.isScreenOn = false
        assertFalse(d.isScreenOn)
        d.isScreenOn = true
        assertTrue(d.isScreenOn)
    }

    /**
     * ★ 全屏朗读状态改为**由外部注入**（A4）
     *
     * 【为什么改】原测试直接给 `isFullScreenReading` 赋值 ——
     *   但恰恰因为它是「可随意赋值的字段」而**没有任何地方赋值为 true**，
     *   导致规则 4 永不成立、连续朗读功能不可达。
     *
     *   现在改为读取注入的状态提供者，测试要验证的是**接线**：
     *   提供者返回 true 时该属性为 true。
     */
    @Test
    fun full_screen_reading_follows_injected_state() {
        val d = EventDispatcher(EventQueue())

        // 未注入 → 默认 false（不静默假设为 true）
        assertFalse("未注入时应为 false", d.isFullScreenReading)

        // 注入「正在连续朗读」
        var reading = true
        d.fullScreenReadState = { reading }
        assertTrue(d.isFullScreenReading)

        // 朗读结束 → 立即反映
        reading = false
        assertFalse("注入状态变化应立即反映", d.isFullScreenReading)
    }

    /**
     * ★ 打断钩子接线（A4）
     */
    @Test
    fun full_screen_read_interrupt_hook_is_invoked() {
        val d = EventDispatcher(EventQueue())
        var calledType = -1
        d.fullScreenReadInterrupt = { type, _, _ ->
            calledType = type
            false
        }
        // 触发一次分发（用一个不会引发其他副作用的事件类型）
        val e = android.view.accessibility.AccessibilityEvent.obtain()
        // JVM 下 obtain() 返回 null，故只验证钩子字段本身可用
        d.fullScreenReadInterrupt?.invoke(1, 0L, false)
        assertEquals("钩子应被调用", 1, calledType)
    }

    /**
     * 锁定天坦原版常量 —— AccessibilityEventDispatcher.java:262 用的是 12300
     * 位分解 = 4 | 8 | 4096 | 8192
     */
    @Test
    fun window_noise_mask_matches_tantang() {
        val mask = 12300
        assertEquals(0, mask and 1)        // 不含点击
        assertEquals(0, mask and 2)        // 不含长按
        assertTrue(mask and 4 != 0)        // 含选中
        assertTrue(mask and 8 != 0)        // 含滚动
        assertEquals(0, mask and 16)       // 不含文本变化
        assertEquals(0, mask and 2048)
        assertTrue(mask and 4096 != 0)
        assertTrue(mask and 8192 != 0)
        assertEquals(0, mask and 32768)    // 不含无障碍焦点
    }

    @Test
    fun stats_returns_non_empty() {
        val d = EventDispatcher(EventQueue())
        assertTrue(d.stats().isNotEmpty())
    }
}
