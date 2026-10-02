package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件队列单测 —— 锁定环形缓冲与背压行为
 *
 * 注意：offer 需要真实的 AccessibilityEvent，JVM 单测拿不到，
 * 所以这里只测不依赖事件对象的部分（容量校验、初始化、统计、清空）。
 */
class EventQueueTest {

    @Test
    fun capacity_must_be_power_of_two() {
        // 合法容量
        EventQueue(capacity = 2)
        EventQueue(capacity = 4)
        EventQueue(capacity = 256)

        // 非法容量应抛异常
        var threw = false
        try {
            EventQueue(capacity = 3)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("非 2 的幂应抛异常", threw)
    }

    @Test
    fun new_queue_is_empty() {
        val q = EventQueue(capacity = 16)
        assertTrue(q.isEmpty)
        assertEquals(0, q.pendingSize)
    }

    @Test
    fun counters_start_at_zero() {
        val q = EventQueue()
        assertEquals(0L, q.offeredCount)
        assertEquals(0L, q.droppedCount)
        assertEquals(0L, q.polledCount)
    }

    @Test
    fun peek_on_empty_returns_null() {
        val q = EventQueue()
        assertEquals(null, q.peek())
    }

    @Test
    fun clear_on_empty_is_safe() {
        val q = EventQueue()
        q.clear()
        assertTrue(q.isEmpty)
    }

    @Test
    fun consumer_is_settable() {
        val q = EventQueue()
        var invoked = false
        q.consumer = { invoked = true }
        assertFalse(invoked)   // 设置本身不触发
    }

    @Test
    fun stats_returns_non_empty() {
        val q = EventQueue()
        assertTrue(q.stats().isNotEmpty())
    }
}
