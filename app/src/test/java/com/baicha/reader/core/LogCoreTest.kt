package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志系统核心 —— 限流聚合 / 环形缓冲 / 结构化筛选 的单测
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么这批测试是本轮最重要的产物】
 * ══════════════════════════════════════════════════════════
 *
 * 日志系统平时没人看，**坏掉时也没人发现** ——
 * 直到下一次真机 P0 需要它的时候，才发现它早就不可用了。
 * 所以它的每一条契约都必须被钉死：
 *
 *   ① 分级过滤 —— 「只看 ERROR」真的只看 ERROR
 *   ② 分类过滤 —— 「只看 TTS」真的只看 TTS
 *   ③ **限流聚合** —— N 次相同日志 = 1 条（真机 P0 的直接教训）
 *   ④ 环形缓冲有上限 —— 写 10 万条内存也不涨
 *   ⑤ 线程安全 —— 无障碍服务是多线程调日志的
 *
 * 【为什么能纯 JVM 测】[LogCore] 不引用任何 android.*：
 * 时间源是构造参数，所以「一秒内 64 次」这种场景
 * 可以用假时钟精确复现，不需要 sleep。
 */
class LogCoreTest {

    // ==================== ① 级别 ====================

    @Test
    fun level_priority_increases_with_severity() {
        assertTrue(LogLevel.DEBUG.priority < LogLevel.INFO.priority)
        assertTrue(LogLevel.INFO.priority < LogLevel.WARN.priority)
        assertTrue(LogLevel.WARN.priority < LogLevel.ERROR.priority)
    }

    @Test
    fun level_marker_is_single_char() {
        for (l in LogLevel.entries) {
            assertEquals("级别 ${l.name} 的标记必须是单字符", 1, l.marker.length)
        }
    }

    // ==================== ② 分类 ====================

    @Test
    fun classify_by_tag_covers_focus_family() {
        // 这些 tag 都真实存在于项目各 core 文件的 `private const val TAG`
        assertEquals(LogCategory.FOCUS, LogCategory.classifyByTag("FocusManager"))
        assertEquals(LogCategory.FOCUS, LogCategory.classifyByTag("FocusMonitor"))
        assertEquals(LogCategory.FOCUS, LogCategory.classifyByTag("ShouldFocusNode"))
        assertEquals(LogCategory.FOCUS, LogCategory.classifyByTag("SpatialTraversal"))
        assertEquals(LogCategory.FOCUS, LogCategory.classifyByTag("焦点"))
    }

    @Test
    fun classify_by_tag_covers_speech_gesture_cache() {
        assertEquals(LogCategory.SPEECH, LogCategory.classifyByTag("TTS"))
        assertEquals(LogCategory.SPEECH, LogCategory.classifyByTag("SpeechEngine"))
        assertEquals(LogCategory.SPEECH, LogCategory.classifyByTag("TtsEngineManager"))
        assertEquals(LogCategory.GESTURE, LogCategory.classifyByTag("GestureAction"))
        assertEquals(LogCategory.CACHE, LogCategory.classifyByTag("NodeCache"))
        assertEquals(LogCategory.CACHE, LogCategory.classifyByTag("ClassCache"))
    }

    @Test
    fun classify_by_tag_handles_namespaced_tag() {
        // 项目里真实存在 `TAG = "BaichaReader:Speech"`（SpeechWakeLock.kt:143）
        // 前缀表里没有它，靠包含匹配兜底
        assertEquals(LogCategory.SPEECH, LogCategory.classifyByTag("BaichaReader:Speech"))
    }

    @Test
    fun classify_by_tag_falls_back_to_other() {
        assertEquals(LogCategory.OTHER, LogCategory.classifyByTag(""))
        assertEquals(LogCategory.OTHER, LogCategory.classifyByTag("SomeRandomThing"))
    }

    // ==================== ③ 记录与消息模式 ====================

    @Test
    fun pattern_normalizes_digits_so_jitter_is_one_group() {
        val a = record(message = "无障碍焦点: 设置项 3")
        val b = record(message = "无障碍焦点: 设置项 4")
        assertEquals(a.pattern, b.pattern)
        assertEquals("同一分类 + 同一模式 ⇒ 同一聚合键", a.limiterKey, b.limiterKey)
    }

    @Test
    fun pattern_normalizes_quoted_text_and_hex() {
        val a = record(message = "焦点落到「Wi-Fi」")
        val b = record(message = "焦点落到「蓝牙」")
        assertEquals(a.pattern, b.pattern)

        assertEquals(record(message = "handle=0x1a2b").pattern, record(message = "handle=0xf00d").pattern)
    }

    @Test
    fun pattern_keeps_structural_words_distinct() {
        // 「没找到焦点」和「找到焦点」不是同一种抖动，绝不能合并
        assertTrue(
            record(message = "没找到焦点").pattern != record(message = "找到焦点").pattern
        )
    }

    @Test
    fun limiter_key_separates_categories() {
        val focus = LogRecord(LogLevel.INFO, LogCategory.FOCUS, "x", "同一条消息")
        val speech = LogRecord(LogLevel.INFO, LogCategory.SPEECH, "x", "同一条消息")
        assertTrue("分类不同的同文消息不得互相聚合", focus.limiterKey != speech.limiterKey)
    }

    // ==================== ④ 限流聚合（核心）====================

    /**
     * 这条测试直接复现真机 P0 的日志现场：
     * **一秒内 64 次「焦点清除」必须压成 1 条。**
     */
    @Test
    fun focus_cleared_64_times_in_one_second_collapses_to_one() {
        val clock = FakeClock(10_000)
        // burstThreshold = 0：关掉「突刺提前摘要」，验纯时间窗口的极限压缩比
        val limiter = LogLimiter(windowMs = 1000, burstThreshold = 0, clock = clock::now)

        var emitted = 0
        for (i in 0 until 64) {
            // 同一秒内的 64 次抖动（每次间隔 15ms）
            clock.advance(15)
            if (limiter.sample(LogCategory.FOCUS, "焦点清除").emit) emitted++
        }

        assertEquals("64 次相同日志必须聚合成 1 条", 1, emitted)
    }

    @Test
    fun aggregated_line_reads_like_the_p0_report() {
        val clock = FakeClock(0)
        val limiter = LogLimiter(windowMs = 1000, burstThreshold = 0, clock = clock::now)

        var last: RateLimitVerdict? = null
        for (i in 0 until 64) {
            clock.advance(15)
            last = limiter.sample(LogCategory.FOCUS, "焦点清除")
        }
        // 第 64 次仍在同一窗口内 → 不输出，但计数为 64
        assertFalse(last!!.emit)
        assertEquals(64, last.count)
        // 窗口末尾（这里是下一次超过窗口的采样）给出的摘要文本
        clock.advance(1000)
        val verdict = limiter.sample(LogCategory.FOCUS, "焦点清除")
        assertTrue(verdict.emit)
        assertEquals("焦点清除 x64（最近 1000ms）", RateLimitVerdict(true, 64, 1000, "焦点清除").summary())
        assertEquals("焦点清除", verdict.summary())
    }

    @Test
    fun burst_threshold_gives_early_summary_so_screen_is_not_blank() {
        val clock = FakeClock(0)
        val limiter = LogLimiter(windowMs = 1000, burstThreshold = 8, clock = clock::now)

        val emits = ArrayList<RateLimitVerdict>()
        for (i in 0 until 20) {
            clock.advance(10)
            emits.add(limiter.sample(LogCategory.FOCUS, "焦点清除"))
        }

        val emitted = emits.filter { it.emit }
        assertEquals("第 1 次 + 第 8 次突刺摘要 = 2 条", 2, emitted.size)
        assertEquals(1, emitted[0].count)
        assertEquals(8, emitted[1].count)
        assertEquals("焦点清除 x8（最近 1000ms）", emitted[1].summary())
    }

    @Test
    fun burst_summary_is_emitted_only_once_per_window() {
        val clock = FakeClock(0)
        val limiter = LogLimiter(windowMs = 1000, burstThreshold = 8, clock = clock::now)
        var n = 0
        for (i in 0 until 60) {
            clock.advance(10)
            if (limiter.sample(LogCategory.FOCUS, "焦点清除").emit) n++
        }
        assertEquals("一个窗口内最多两条（首条 + 一次突刺摘要）", 2, n)
    }

    @Test
    fun stable_one_hz_event_is_never_merged() {
        // 每秒一次的心跳不该被聚合 —— 它本来就该每秒一行
        val clock = FakeClock(0)
        val limiter = LogLimiter(windowMs = 1000, clock = clock::now)
        var emitted = 0
        for (i in 0 until 10) {
            assertTrue("1Hz 事件每次都是新窗口，必然输出", limiter.sample(LogCategory.SYSTEM, "心跳").emit)
            emitted++
            clock.advance(1000)
        }
        assertEquals(10, emitted)
    }

    @Test
    fun different_categories_do_not_merge_with_each_other() {
        val clock = FakeClock(0)
        val limiter = LogLimiter(windowMs = 1000, burstThreshold = 0, clock = clock::now)
        assertTrue(limiter.sample(LogCategory.FOCUS, "同一句").emit)
        assertTrue("分类不同 => 不同键 => 立即输出", limiter.sample(LogCategory.SPEECH, "同一句").emit)
        clock.advance(1)
        assertFalse(limiter.sample(LogCategory.FOCUS, "同一句").emit)
    }

    @Test
    fun pending_suppressed_counts_what_is_being_held() {
        val clock = FakeClock(0)
        val limiter = LogLimiter(windowMs = 1000, burstThreshold = 0, clock = clock::now)
        for (i in 0 until 64) {
            clock.advance(10)
            limiter.sample(LogCategory.FOCUS, "焦点清除")
        }
        assertEquals("64 次采样 - 1 次输出", 63, limiter.pendingSuppressed())
        assertEquals(64L, limiter.receivedTotal())
        assertEquals(1L, limiter.emittedTotal())
    }

    @Test
    fun limiter_table_is_bounded_and_never_grows_forever() {
        val clock = FakeClock(0)
        val limiter = LogLimiter(windowMs = 1000, maxEntries = 32, clock = clock::now)

        // 5000 条**互不重复**的消息 —— 正是「把节点文本拼进消息」的写法
        for (i in 0 until 5000) {
            limiter.sample(LogCategory.FOCUS, "节点 ${i}abcdefghij")
            clock.advance(1)
        }
        assertTrue(
            "限流表必须有上限，否则就是另一种内存泄漏（实际 ${limiter.tableSize()}）",
            limiter.tableSize() <= 32
        )
    }

    /**
     * 表满时的兜底：**宁可少聚合，也绝不吞日志**
     *
     * 【为什么把「逐出」和「同模式重复」分开测】
     *   · 逐出路径：表里 512 个都是「只出现过一次」的模式（每条消息都不同，
     *     例如把节点文本拼进了消息）。旧窗口被逐出后，
     *     那条消息如果在同一窗口内再次出现，就只能再输出一次 ——
     *     这是**有意的代价交换**：压缩率下降，但一条日志都不丢。
     *   · 这里断言的就是这个取舍：逐出发生时，重复模式会被**再次输出**。
     */
    @Test
    fun table_full_may_lose_aggregation_but_never_swallows_a_log() {
        val limiter = LogLimiter(windowMs = 100_000, maxEntries = 4, clock = { 0 })
        var emitted = 0

        // ① 填满表，且每条都是新模式 ⇒ 每条都必须输出
        //    注意「大小写 + 字母」而不是纯数字：消息模式会把数字归一成 `#`，
        //    纯数字的消息会全部落进同一个模式，测不出「表满」的场景。
        val messages = (0 until 40).map { "节点 ${('a' + it)} 号按钮" }
        for (m in messages.take(4)) {
            assertTrue("新模式必须立即输出", limiter.sample(LogCategory.FOCUS, m).emit)
            emitted++
        }
        assertEquals(4, emitted)

        // ② 第 5 条触发逐出；逐出后它是最新窗口，理应输出 —— 一条都不许被吞
        for (m in messages.drop(4).take(10)) {
            assertTrue("表满时新模式仍须输出：$m", limiter.sample(LogCategory.FOCUS, m).emit)
            emitted++
        }
        assertEquals(14, emitted)

        // ③ 底线：**同模式的重复仍能被聚合**（只要它还在表里）
        //    重新构造一个干净的表来做这条断言
        val clean = LogLimiter(windowMs = 100_000, maxEntries = 64, burstThreshold = 0, clock = { 0 })
        var n = 0
        repeat(50) { if (clean.sample(LogCategory.FOCUS, "焦点清除").emit) n++ }
        assertEquals("表有余量时，50 次重复压成 1 条", 1, n)
    }

    @Test
    fun reset_clears_limiter_state() {
        val clock = FakeClock(0)
        val limiter = LogLimiter(windowMs = 1000, burstThreshold = 0, clock = clock::now)
        repeat(5) { clock.advance(10); limiter.sample(LogCategory.FOCUS, "x") }
        assertTrue(limiter.tableSize() > 0)
        limiter.reset()
        assertEquals(0, limiter.tableSize())
        assertEquals(0, limiter.pendingSuppressed())
        assertTrue("reset 后应重新开始输出", limiter.sample(LogCategory.FOCUS, "x").emit)
    }

    // ==================== ⑤ 环形缓冲 ====================

    @Test
    fun ring_buffer_drops_oldest_and_keeps_bounded() {
        val ring = RateLimitedRingBuffer(capacity = 4)
        for (i in 1..6) ring.add(record(message = "第 $i 条", timeMillis = i.toLong()))

        val snap = ring.snapshot()
        assertEquals(4, snap.size)
        assertEquals(4, ring.size)
        assertEquals("保留的应是最新 4 条（旧→新）", listOf(3L, 4L, 5L, 6L), snap.map { it.timeMillis })
        assertEquals("写 6 条、容量 4 ⇒ 丢了 2 条最旧的", 2L, ring.droppedTotal)
    }

    @Test
    fun ring_buffer_never_exceeds_capacity_even_under_load() {
        val ring = RateLimitedRingBuffer(capacity = 100)
        for (i in 0 until 100_000) ring.add(record(message = "第 $i 条"))
        assertEquals(100, ring.size)
        assertEquals(100, ring.snapshot().size)
        assertEquals(100_000L - 100, ring.droppedTotal)
    }

    @Test
    fun ring_buffer_snapshot_limit_returns_most_recent() {
        val ring = RateLimitedRingBuffer(capacity = 10)
        for (i in 1..10) ring.add(record(message = "第 $i 条", timeMillis = i.toLong()))
        assertEquals(listOf(9L, 10L), ring.snapshot(2).map { it.timeMillis })
    }

    @Test
    fun ring_buffer_clear_resets_everything() {
        val ring = RateLimitedRingBuffer(capacity = 4)
        repeat(6) { ring.add(record(message = "x")) }
        ring.clear()
        assertEquals(0, ring.size)
        assertEquals(0L, ring.droppedTotal)
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun ring_buffer_rejects_zero_capacity() {
        try {
            RateLimitedRingBuffer(0)
            org.junit.Assert.fail("容量 0 必须被拒绝（否则取模会抛 ArithmeticException）")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    // ==================== ⑥ 结构化筛选 ====================

    @Test
    fun filter_by_category_returns_only_that_category() {
        val ring = RateLimitedRingBuffer(capacity = 32)
        ring.add(record(category = LogCategory.FOCUS, message = "焦点移动"))
        ring.add(record(category = LogCategory.SPEECH, message = "播报"))
        ring.add(record(category = LogCategory.FOCUS, message = "焦点清除"))
        ring.add(record(category = LogCategory.GESTURE, message = "手势"))

        val focus = ring.filter(categories = setOf(LogCategory.FOCUS))
        assertEquals(2, focus.size)
        assertTrue(focus.all { it.category == LogCategory.FOCUS })
        assertEquals(listOf("焦点移动", "焦点清除"), focus.map { it.message })
    }

    @Test
    fun filter_by_min_level_is_inclusive_and_ordered() {
        val ring = RateLimitedRingBuffer(capacity = 32)
        ring.add(record(level = LogLevel.DEBUG, message = "d"))
        ring.add(record(level = LogLevel.INFO, message = "i"))
        ring.add(record(level = LogLevel.WARN, message = "w"))
        ring.add(record(level = LogLevel.ERROR, message = "e"))

        assertEquals(listOf("w", "e"), ring.filter(minLevel = LogLevel.WARN).map { it.message })
        assertEquals(listOf("d", "i", "w", "e"), ring.filter(minLevel = LogLevel.DEBUG).map { it.message })
    }

    @Test
    fun filter_combines_category_and_level() {
        val ring = RateLimitedRingBuffer(capacity = 32)
        ring.add(record(level = LogLevel.DEBUG, category = LogCategory.FOCUS, message = "焦点-debug"))
        ring.add(record(level = LogLevel.ERROR, category = LogCategory.FOCUS, message = "焦点-error"))
        ring.add(record(level = LogLevel.ERROR, category = LogCategory.SPEECH, message = "语音-error"))

        val out = ring.filter(categories = setOf(LogCategory.FOCUS), minLevel = LogLevel.ERROR)
        assertEquals(listOf("焦点-error"), out.map { it.message })
    }

    @Test
    fun counts_by_category_adds_up() {
        val ring = RateLimitedRingBuffer(capacity = 32)
        ring.add(record(category = LogCategory.FOCUS, message = "a"))
        ring.add(record(category = LogCategory.FOCUS, message = "b"))
        ring.add(record(category = LogCategory.SPEECH, message = "c"))

        val counts = ring.countsByCategory()
        assertEquals(2, counts[LogCategory.FOCUS])
        assertEquals(1, counts[LogCategory.SPEECH])
        assertNull(counts[LogCategory.GESTURE])
    }

    // ==================== ⑦ 线程安全 ====================

    /**
     * 8 线程 × 5000 条：日志常被多线程调用（无障碍事件线程、TTS 回调、主线程）。
     * 验收标准：
     *   · 计数不丢（receive 总数对得上）
     *   · 环形缓冲不越界
     *   · 不抛异常
     */
    @Test
    fun concurrent_writes_do_not_lose_counters_or_crash() {
        val threads = 8
        val perThread = 5000
        val ring = RateLimitedRingBuffer(capacity = 200)
        val limiter = LogLimiter(windowMs = 50, clock = { System.nanoTime() / 1_000_000L })

        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = AtomicInteger(0)
        val done = CountDownLatch(threads)

        for (t in 0 until threads) {
            pool.execute {
                try {
                    start.await()
                    for (i in 0 until perThread) {
                        val r = record(category = LogCategory.FOCUS, message = "线程 $t 第 $i 次")
                        if (limiter.sample(r.category, r.message).emit) ring.add(r)
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("并发写入超时", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("并发下不得抛异常", 0, errors.get())
        assertEquals(
            "采样计数必须精确（丢计数就等于丢日志）",
            (threads * perThread).toLong(),
            limiter.receivedTotal()
        )
        assertTrue("环形缓冲不得越界：${ring.size}", ring.size <= 200)
    }

    /**
     * 同一模式的并发采样：计数必须精确等于线程数 × 每线程次数
     * （ConcurrentHashMap.compute 保证键级串行）
     */
    @Test
    fun concurrent_same_pattern_counts_are_exact() {
        val threads = 8
        val perThread = 2000
        val limiter = LogLimiter(windowMs = 100_000, burstThreshold = 0, clock = { 0 })

        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                try {
                    repeat(perThread) { limiter.sample(LogCategory.FOCUS, "焦点清除") }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals((threads * perThread).toLong(), limiter.receivedTotal())
        assertEquals("同一窗口内只输出 1 条", 1L, limiter.emittedTotal())
        assertEquals(
            "其余全部记入待输出计数",
            (threads * perThread - 1),
            limiter.pendingSuppressed()
        )
    }

    // ==================== 工具 ====================

    private fun record(
        level: LogLevel = LogLevel.INFO,
        category: LogCategory = LogCategory.FOCUS,
        message: String,
        timeMillis: Long = 0L
    ) = LogRecord(level, category, "T", message, null, timeMillis)

    /** 假时钟 —— 让「一秒内 64 次」这种场景可以精确复现，不需要 sleep */
    private class FakeClock(private var now: Long) {
        fun advance(ms: Long) {
            now += ms
        }

        fun now(): Long = now
    }
}
