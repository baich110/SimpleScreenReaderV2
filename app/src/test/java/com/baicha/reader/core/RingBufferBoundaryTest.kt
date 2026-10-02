package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志环形缓冲 —— **边界与顺序测试**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么专门测环形缓冲】
 * ══════════════════════════════════════════════════════════
 *
 * 环形缓冲的正确性全在**下标算术**上：
 * ```
 *   写入： idx = (writes++ % capacity)
 *   读取： start = writes - want
 *          buffer[(start + i) % capacity]
 * ```
 * 这套公式在「刚好写满」「刚覆盖一轮」「limit 截断」几个边界上
 * 极易写错 —— 错了的后果不是崩溃，而是**顺序错乱**：
 * 日志面板显示的顺序与实际发生顺序不符，
 * 排障时按错误顺序推断因果，得出完全相反的结论。
 *
 * 本项目已多次被「日志把人带偏」坑过（目标里的纪律③），
 * 所以日志工具本身必须可信。
 */
class RingBufferBoundaryTest {

    private fun rec(i: Int) = LogRecord(
        LogLevel.INFO, LogCategory.OTHER, "T", "第 $i 条", null, i.toLong()
    )

    private fun messages(buf: RateLimitedRingBuffer) = buf.snapshot().map { it.message }

    // ==================== 基本顺序 ====================

    @Test
    fun empty_returnsEmpty() {
        assertEquals(emptyList<String>(), messages(RateLimitedRingBuffer(8)))
        assertEquals(0, RateLimitedRingBuffer(8).size)
    }

    @Test
    fun belowCapacity_preservesInsertionOrder() {
        val b = RateLimitedRingBuffer(8)
        repeat(5) { b.add(rec(it)) }
        assertEquals(
            "未满时应按写入顺序（旧→新）",
            listOf("第 0 条", "第 1 条", "第 2 条", "第 3 条", "第 4 条"),
            messages(b)
        )
        assertEquals(5, b.size)
    }

    /** 刚好写满 —— 边界 */
    @Test
    fun exactlyFull_preservesOrder() {
        val b = RateLimitedRingBuffer(4)
        repeat(4) { b.add(rec(it)) }
        assertEquals(listOf("第 0 条", "第 1 条", "第 2 条", "第 3 条"), messages(b))
        assertEquals(4, b.size)
    }

    /**
     * ★ 覆盖一轮 —— 最容易出错的地方
     *
     * 写 6 条进容量 4 的缓冲：应保留最后 4 条（第 2..5 条），
     * **顺序仍是旧→新**。
     */
    @Test
    fun oneWrapAround_keepsNewestInOrder() {
        val b = RateLimitedRingBuffer(4)
        repeat(6) { b.add(rec(it)) }
        assertEquals(
            "★ 覆盖后应保留最后 4 条且顺序正确（旧→新）",
            listOf("第 2 条", "第 3 条", "第 4 条", "第 5 条"),
            messages(b)
        )
        assertEquals("size 不应超过容量", 4, b.size)
    }

    /** 覆盖多轮仍正确 */
    @Test
    fun manyWrapArounds_keepsNewestInOrder() {
        val b = RateLimitedRingBuffer(3)
        repeat(100) { b.add(rec(it)) }
        assertEquals(
            listOf("第 97 条", "第 98 条", "第 99 条"),
            messages(b)
        )
    }

    // ==================== limit 截断 ====================

    @Test
    fun limit_takesNewestN() {
        val b = RateLimitedRingBuffer(8)
        repeat(6) { b.add(rec(it)) }
        assertEquals(
            "limit=2 应取**最近** 2 条（旧→新）",
            listOf("第 4 条", "第 5 条"),
            b.snapshot(limit = 2).map { it.message }
        )
    }

    @Test
    fun limit_largerThanSize_returnsAll() {
        val b = RateLimitedRingBuffer(8)
        repeat(3) { b.add(rec(it)) }
        assertEquals(3, b.snapshot(limit = 100).size)
    }

    @Test
    fun limit_zeroOrNegative_returnsAll() {
        val b = RateLimitedRingBuffer(8)
        repeat(3) { b.add(rec(it)) }
        assertEquals(3, b.snapshot(limit = 0).size)
        assertEquals(3, b.snapshot(limit = -1).size)
    }

    // ==================== 容量 1 的极端 ====================

    @Test
    fun capacityOne_keepsOnlyLatest() {
        val b = RateLimitedRingBuffer(1)
        repeat(5) { b.add(rec(it)) }
        assertEquals(listOf("第 4 条"), messages(b))
        assertEquals(1, b.size)
    }

    // ==================== 统计量 ====================

    @Test
    fun droppedTotal_countsOverwritten() {
        val b = RateLimitedRingBuffer(4)
        repeat(6) { b.add(rec(it)) }
        assertEquals("写 6 条、容量 4 → 丢 2 条", 2L, b.droppedTotal)
    }

    @Test
    fun droppedTotal_isZeroBeforeFull() {
        val b = RateLimitedRingBuffer(10)
        repeat(10) { b.add(rec(it)) }
        assertEquals("刚好写满不算丢弃", 0L, b.droppedTotal)
    }

    // ==================== clear ====================

    @Test
    fun clear_resetsEverything() {
        val b = RateLimitedRingBuffer(4)
        repeat(6) { b.add(rec(it)) }
        b.clear()
        assertEquals(emptyList<String>(), messages(b))
        assertEquals(0, b.size)
        assertEquals("clear 后丢弃计数也应清零", 0L, b.droppedTotal)
    }

    /** clear 后重新写入必须正确（下标从 0 重新开始） */
    @Test
    fun clearThenWrite_startsFromZero() {
        val b = RateLimitedRingBuffer(4)
        repeat(6) { b.add(rec(it)) }
        b.clear()
        repeat(2) { b.add(rec(100 + it)) }
        assertEquals(listOf("第 100 条", "第 101 条"), messages(b))
    }

    // ==================== 过滤 ====================

    @Test
    fun filter_byCategory() {
        val b = RateLimitedRingBuffer(16)
        b.add(LogRecord(LogLevel.INFO, LogCategory.FOCUS, "T", "焦点1"))
        b.add(LogRecord(LogLevel.INFO, LogCategory.SPEECH, "T", "语音1"))
        b.add(LogRecord(LogLevel.INFO, LogCategory.FOCUS, "T", "焦点2"))

        val focus = b.filter(categories = setOf(LogCategory.FOCUS))
        assertEquals(2, focus.size)
        assertTrue(focus.all { it.category == LogCategory.FOCUS })
    }

    @Test
    fun filter_byMinLevel() {
        val b = RateLimitedRingBuffer(16)
        b.add(LogRecord(LogLevel.DEBUG, LogCategory.OTHER, "T", "调试"))
        b.add(LogRecord(LogLevel.INFO, LogCategory.OTHER, "T", "信息"))
        b.add(LogRecord(LogLevel.ERROR, LogCategory.OTHER, "T", "错误"))

        val aboveInfo = b.filter(minLevel = LogLevel.INFO)
        assertEquals("INFO 及以上应含 2 条", 2, aboveInfo.size)
        assertTrue(aboveInfo.none { it.level == LogLevel.DEBUG })
    }

    @Test
    fun filter_emptyCategories_meansNoCategoryFilter() {
        val b = RateLimitedRingBuffer(16)
        b.add(LogRecord(LogLevel.INFO, LogCategory.FOCUS, "T", "a"))
        b.add(LogRecord(LogLevel.INFO, LogCategory.SPEECH, "T", "b"))
        assertEquals("空集合 = 不过滤分类", 2, b.filter(categories = emptySet()).size)
    }

    @Test
    fun countsByCategory_isAccurate() {
        val b = RateLimitedRingBuffer(16)
        b.add(LogRecord(LogLevel.INFO, LogCategory.FOCUS, "T", "a"))
        b.add(LogRecord(LogLevel.INFO, LogCategory.FOCUS, "T", "b"))
        b.add(LogRecord(LogLevel.INFO, LogCategory.SPEECH, "T", "c"))

        val counts = b.countsByCategory()
        assertEquals(2, counts[LogCategory.FOCUS])
        assertEquals(1, counts[LogCategory.SPEECH])
        assertEquals("未出现的分类不应有计数", null, counts[LogCategory.CACHE])
    }

    @Test
    fun capacity_mustBePositive() {
        try {
            RateLimitedRingBuffer(0)
            assertTrue("容量 0 应被拒绝", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
