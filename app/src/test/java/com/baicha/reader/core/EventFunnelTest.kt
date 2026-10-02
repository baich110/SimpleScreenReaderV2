package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件漏斗单测 —— 锁定 EventFunnel 的规则常量与状态机
 */
class EventFunnelTest {

    @Test
    fun funnel_starts_with_zero_counters() {
        val funnel = EventFunnel()
        assertEquals(0, funnel.droppedCount)
        assertEquals(0, funnel.passedCount)
    }

    @Test
    fun self_package_default_is_baicha() {
        val funnel = EventFunnel()
        assertEquals("com.baicha.reader", funnel.selfPackageName)
    }

    @Test
    fun screen_on_flag_is_settable() {
        val funnel = EventFunnel()
        funnel.isScreenOn = false
        assertFalse(funnel.isScreenOn)
        funnel.isScreenOn = true
        assertTrue(funnel.isScreenOn)
    }

    @Test
    fun touch_exploring_flag_toggles() {
        val funnel = EventFunnel()
        funnel.isTouchExploring = true
        assertTrue(funnel.isTouchExploring)
        funnel.isTouchExploring = false
        assertFalse(funnel.isTouchExploring)
    }

    @Test
    fun wechat_speaking_flag_is_settable() {
        val funnel = EventFunnel()
        funnel.isFullScreenReading = true
        assertTrue(funnel.isFullScreenReading)
    }

    /**
     * 锁定天坦原版常量 —— AccessibilityEventDispatcher.java:262 用的就是 12300。
     * 之前注释把它写成 "4|8|16|32|64|128|2048|8192"，与真实位组成不符。
     */
    @Test
    fun window_noise_mask_matches_tantang_constant() {
        val mask = 12300
        // 12300 位分解 = 4 | 8 | 4096 | 8192  (= 0b11000000001100)
        assertEquals(0, mask and 1)        // 不含点击(1)
        assertEquals(0, mask and 2)        // 不含长按(2)
        assertTrue(mask and 4 != 0)        // 含选中(4)
        assertTrue(mask and 8 != 0)        // 含滚动(8)
        assertEquals(0, mask and 2048)     // 不含文本变化(2048)
        assertTrue(mask and 4096 != 0)     // 含 4096
        assertTrue(mask and 8192 != 0)     // 含 8192
        assertEquals(0, mask and 32768)    // 不含无障碍焦点(32768)
    }
}
