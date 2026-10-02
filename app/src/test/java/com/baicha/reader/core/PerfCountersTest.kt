package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能计数测试 —— 审查报告「目标 D：性能基线」
 *
 * 【为什么这批测试重要】
 *   计数器的价值全在「数字对不对」。
 *   计数错了会让性能结论整个反向 —— 比如把未命中记成命中，
 *   会得出「缓存命中率 100%，不需要优化」的错误结论。
 */
class PerfCountersTest {

    /**
     * ★ 本轮修复：原来只有 `@After` reset，**没有 `@Before`**
     *
     * ══════════════════════════════════════════════════════════
     * 【这是一个真实的测试隔离缺陷】
     * ══════════════════════════════════════════════════════════
     *
     * `PerfCounters` 是**全局单例可变状态**，而本类的断言全部依赖
     * 「从零开始计数」（如 `hitRate()` 期望 0.7 = 7/(7+3)）。
     *
     * 只有 `@After` 的问题：**任何其他测试类**只要调用过
     * `PerfCounters.cacheHit()/cacheMiss()`（例如 `NodeCache` 内部就会调），
     * 就会污染本类的初始状态，使断言失败。
     *
     * 【实际发生】第十四轮新增 `NodeCacheBoundTest`（大量调用
     * `shouldFocus` → 内部走 `PerfCounters.cacheHit/cacheMiss`），
     * 于是 `hit_rate_is_accurate` 断言失败：
     * ```
     *   expected:<0.7> but was:<0.006910167818361303>
     * ```
     * —— 分子分母都被别人的计数稀释了。
     *
     * **这类失败具有「顺序依赖」特征**：单独跑本类通过、全量跑失败，
     * 极易被误判为「偶发」而放过。必须靠 `@Before` 把前置状态钉死。
     */
    @Before
    fun setUp() {
        PerfCounters.reset()
    }

    @After
    fun tearDown() {
        PerfCounters.reset()
    }

    // ==================== 导航耗时 ====================

    @Test
    fun reset_clears_everything() {
        PerfCounters.cacheHit()
        PerfCounters.cacheMiss()
        PerfCounters.eventHandled()
        PerfCounters.eventDropped()
        PerfCounters.endNav(PerfCounters.begin())

        PerfCounters.reset()

        assertEquals(0L, PerfCounters.navCount)
        assertEquals(0L, PerfCounters.cacheHits)
        assertEquals(0L, PerfCounters.cacheMisses)
        assertEquals(0L, PerfCounters.eventCount)
        assertEquals(0L, PerfCounters.droppedEvents)
        assertEquals(0L, PerfCounters.navMaxMs)
    }

    @Test
    fun avg_nav_is_zero_before_any_sample() {
        assertEquals(0L, PerfCounters.avgNavMs())
    }

    /**
     * 用「起点设成过去」的方式模拟一段耗时，
     * 不依赖真实 sleep（测试要快且稳定）。
     */
    @Test
    fun nav_accumulates_count_and_max() {
        val now = PerfCounters.begin()

        // 模拟两次导航：第一次 0ms，第二次 5ms（起点往前推 5ms）
        PerfCounters.endNav(now)
        PerfCounters.endNav(now - 5)

        assertEquals(2L, PerfCounters.navCount)
        assertTrue(
            "最大耗时应 >= 5ms，实际 ${PerfCounters.navMaxMs}",
            PerfCounters.navMaxMs >= 5L
        )
        assertTrue(PerfCounters.avgNavMs() >= 2L)
    }

    // ==================== 命中率 ====================

    @Test
    fun hit_rate_is_zero_when_no_access() {
        assertEquals(0.0, PerfCounters.hitRate(), 0.0001)
    }

    @Test
    fun hit_rate_is_accurate() {
        repeat(7) { PerfCounters.cacheHit() }
        repeat(3) { PerfCounters.cacheMiss() }

        assertEquals(0.7, PerfCounters.hitRate(), 0.0001)
    }

    @Test
    fun hit_rate_all_hits_is_one() {
        repeat(5) { PerfCounters.cacheHit() }
        assertEquals(1.0, PerfCounters.hitRate(), 0.0001)
    }

    @Test
    fun hit_rate_all_misses_is_zero() {
        repeat(5) { PerfCounters.cacheMiss() }
        assertEquals(0.0, PerfCounters.hitRate(), 0.0001)
    }

    // ==================== 丢弃率 ====================

    @Test
    fun drop_rate_is_zero_when_nothing_dropped() {
        repeat(10) { PerfCounters.eventHandled() }
        assertEquals(0.0, PerfCounters.dropRate(), 0.0001)
    }

    /**
     * ★ 丢弃率必须把「丢弃」计入分母。
     *
     * 若写成 `dropped / handled`，在丢弃多、处理少时会算出 >100%
     * 的荒谬值；真相反过来：丢弃率高说明事件被大量吞掉。
     */
    @Test
    fun drop_rate_uses_total_as_denominator() {
        repeat(1) { PerfCounters.eventHandled() }
        repeat(3) { PerfCounters.eventDropped() }

        // 1 处理 + 3 丢弃 = 4 总数 → 3/4 = 75%
        assertEquals(
            "丢弃率应以总数为分母，而非以已处理数为分母",
            0.75,
            PerfCounters.dropRate(),
            0.0001
        )
    }

    @Test
    fun drop_rate_never_exceeds_one() {
        repeat(0) { PerfCounters.eventHandled() }
        repeat(100) { PerfCounters.eventDropped() }
        assertTrue(PerfCounters.dropRate() <= 1.0)
    }

    // ==================== 报告 ====================

    @Test
    fun report_contains_all_metrics() {
        PerfCounters.endNav(PerfCounters.begin())
        PerfCounters.cacheHit()
        PerfCounters.cacheMiss()
        PerfCounters.eventHandled()
        PerfCounters.eventDropped()

        val r = PerfCounters.report()

        assertTrue("报告应含导航数据：$r", r.contains("导航"))
        assertTrue("报告应含缓存数据：$r", r.contains("缓存"))
        assertTrue("报告应含事件数据：$r", r.contains("事件"))
        assertTrue("报告应含命中率：$r", r.contains("命中率"))
        assertTrue("报告应含丢弃率：$r", r.contains("丢弃率"))
    }

    @Test
    fun report_works_when_empty() {
        // 无数据时不能抛异常（除以零防御）
        val r = PerfCounters.report()
        assertTrue(r.isNotEmpty())
    }
}
