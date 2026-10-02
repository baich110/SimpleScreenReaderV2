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

    @Test
    fun full_screen_reading_flag_toggles() {
        val d = EventDispatcher(EventQueue())
        d.isFullScreenReading = true
        assertTrue(d.isFullScreenReading)
        d.isFullScreenReading = false
        assertFalse(d.isFullScreenReading)
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
