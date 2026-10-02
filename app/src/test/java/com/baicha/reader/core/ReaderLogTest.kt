package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * [ReaderLog] 端到端测试 —— 真正的写入链路（分级 → 分类 → 限流 → 缓冲 → 落盘）
 *
 * ══════════════════════════════════════════════════════════
 * 【与 LogCoreTest 的分工】
 * ══════════════════════════════════════════════════════════
 *
 *   · `LogCoreTest`  —— 算法本身（限流、环形、结构化筛选），纯函数、可精确复现
 *   · `ReaderLogTest` —— **接线**：这些算法真的接在 `ReaderLog.d/i/w/e` 上了吗？
 *
 * 接线测试的价值在于「静默失效」是这类系统的默认故障模式：
 * 限流器写得再对，只要 `emit()` 忘了调用它就是死的 ——
 * 而日志系统死掉**不会有人发现**，直到下一次真机 P0 需要它。
 *
 * 【并行安全】测试自己创建临时文件并注入同步执行器，
 * 不依赖 `init(context)`，因此不受其他测试的顺序影响。
 */
class ReaderLogTest {

    private lateinit var tmpDir: File
    private lateinit var writerLog: File
    private lateinit var writer: ThreadPoolExecutor

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "baicha-log-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        writerLog = File(tmpDir, "reader.log")

        // 写入线程：单线程。
        // 【为什么不直接断言「写盘后立刻可读」】
        //   写盘是**异步**的（这是设计目标之一：I/O 不许落在无障碍交互线程上）。
        //   早期版本注入「当前线程执行」的执行器想让它变同步，
        //   结果测试通过了、生产却丢日志 —— 因为同步执行器的行为
        //   和真实单线程池并不等价。现在改为**保持真实异步**，
        //   用 [awaitFileWritten] 显式等待，测的就是真实链路。
        writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        ReaderLog.setWriterForTest(writer)
        ReaderLog.resetForTest(writerLog)
        // 双保险：保证限流器是干净的 —— 否则一条被压住的重复日志
        // 会让断言看到空内容，表现为「跨测试类才失败」的幽灵 bug
        ReaderLog.resetLimiter()
        ReaderLog.clearBuffers()
    }

    @After
    fun tearDown() {
        // 【为什么必须换回一个「活着」的执行器再收场】
        //   `ReaderLog` 是进程级单例，别的测试类（如 ScreenStateRecoveryTest）
        //   也会打日志。若只 shutdown 不还原，后续任何一次日志写入都会
        //   撞上 Terminated 的执行器 —— 表现为**与本类无关的测试集体失败**。
        writer.shutdownNow()
        ReaderLog.setWriterForTest(
            ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        )
        ReaderLog.resetForTest(null)
        tmpDir.deleteRecursively()
    }

    /**
     * 等待写队列排空（最多 [timeoutMs]），然后返回文件内容
     *
     * 【为什么要等待而不是 sleep 一个固定值】
     *   固定 sleep 既慢又不稳；按队列状态等待既快又确定：
     *   队列空 + 无活跃任务 ⇒ 该写的都写完了。
     */
    private fun awaitFileContent(timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (writer.queue.isEmpty() && writer.activeCount == 0) break
            Thread.sleep(10)
        }
        return if (writerLog.exists()) writerLog.readText() else ""
    }

    /** 同上，但针对任意导出文件 */
    private fun awaitFile(file: File, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !file.exists()) Thread.sleep(10)
        return if (file.exists()) file.readText() else ""
    }

    // ==================== ① 分级过滤 ====================

    @Test
    fun min_level_filters_out_lower_levels() {
        ReaderLog.minLevel = LogLevel.WARN
        ReaderLog.d("FocusManager", "debug-消息")
        ReaderLog.i("FocusManager", "info-消息")
        ReaderLog.w("FocusManager", "warn-消息")
        ReaderLog.e("FocusManager", "error-消息")

        val lines = ReaderLog.memoryLinesForTest().joinToString("\n")
        assertFalse("DEBUG 应被过滤", lines.contains("debug-消息"))
        assertFalse("INFO 应被过滤", lines.contains("info-消息"))
        assertTrue("WARN 应保留", lines.contains("warn-消息"))
        assertTrue("ERROR 应保留", lines.contains("error-消息"))
    }

    @Test
    fun level_appears_in_the_rendered_line() {
        ReaderLog.minLevel = LogLevel.DEBUG
        ReaderLog.i("FocusManager", "一条信息")
        val line = ReaderLog.memoryLinesForTest().single()
        assertTrue("行里必须能看出级别：$line", line.contains("] INFO/"))
    }

    @Test
    fun enabled_false_silences_everything() {
        ReaderLog.minLevel = LogLevel.DEBUG
        ReaderLog.enabled = false
        ReaderLog.e("FocusManager", "错误")
        assertEquals(0, ReaderLog.memoryLinesForTest().size)
        ReaderLog.enabled = true
    }

    /** 兼容旧字段：`verbose = false` 等价于只留 INFO 及以上 */
    @Test
    fun legacy_verbose_flag_still_works() {
        ReaderLog.verbose = false
        assertEquals(LogLevel.INFO, ReaderLog.minLevel)
        ReaderLog.d("FocusManager", "debug-消息")
        ReaderLog.i("FocusManager", "info-消息")
        val lines = ReaderLog.memoryLinesForTest()
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("info-消息"))
        ReaderLog.verbose = true
        assertEquals(LogLevel.DEBUG, ReaderLog.minLevel)
    }

    // ==================== ② 分类（含按 tag 自动归类）====================

    @Test
    fun legacy_call_site_is_auto_classified_by_tag() {
        // 最旧式的调用：只有 tag 和消息，没有任何分类信息
        ReaderLog.d("FocusManager", "焦点移动")
        ReaderLog.i("SpeechEngine", "播报开始")
        ReaderLog.w("NodeCache", "缓存失效")
        ReaderLog.e("GestureChain", "手势失败")

        assertEquals(4, ReaderLog.recordCount())
        assertEquals(
            listOf(LogCategory.FOCUS, LogCategory.SPEECH, LogCategory.CACHE, LogCategory.GESTURE),
            ReaderLog.readRecords().map { it.category }
        )
    }

    @Test
    fun explicit_category_overrides_tag_inference() {
        // 显式传分类 —— 迁移期最常用的写法
        ReaderLog.i("BaiChaReader", "TTS 就绪", LogCategory.SPEECH)
        assertEquals(LogCategory.SPEECH, ReaderLog.readRecords().single().category)
    }

    @Test
    fun category_shortcuts_work() {
        ReaderLog.focus("下一焦点 | 通过")
        ReaderLog.speech("播报: 你好")
        ReaderLog.gesture("手势: 下滑")

        val byCategory = ReaderLog.readRecords().groupBy { it.category }
        assertEquals(listOf("下一焦点 | 通过"), byCategory[LogCategory.FOCUS]?.map { it.message })
        assertEquals(listOf("播报: 你好"), byCategory[LogCategory.SPEECH]?.map { it.message })
        assertEquals(listOf("手势: 下滑"), byCategory[LogCategory.GESTURE]?.map { it.message })
    }

    // ==================== ③ 限流聚合（端到端）====================

    /**
     * 真机 P0 现场：一秒内 64 次「焦点清除」，日志里必须**只占一行**。
     * 这条测的是「限流真的接在 ReaderLog 上了」。
     */
    @Test
    fun sixty_four_identical_logs_collapse_to_at_most_two_lines() {
        ReaderLog.resetForTest(writerLog)
        repeat(64) { ReaderLog.i("BaiChaReader", "焦点清除") }

        // 上限 2 = 首行 + 一次突刺摘要（阈值 8）。
        // 相对于 64 行刷屏，压缩比 32:1 —— 这正是真机 P0 需要的。
        val lines = ReaderLog.memoryLinesForTest()
        assertTrue("64 次相同日志最多 2 行（实际 ${lines.size} 行）", lines.size <= 2)
        assertTrue(lines[0].contains("焦点清除"))
    }

    /**
     * 关掉突刺摘要后，纯时间窗口的极限压缩比是 **N:1**。
     * 这条用假时钟在 [LogCoreTest] 里已经精确测过（64 次 ⇒ 1 条），
     * 这里验证的是「同一个窗口内不会因为行数多而多打几条」。
     */
    @Test
    fun repeated_identical_logs_do_not_scale_linearly() {
        ReaderLog.resetForTest(writerLog)
        repeat(500) { ReaderLog.i("BaiChaReader", "焦点清除") }
        assertTrue(
            "500 次相同日志不得刷屏（实际 ${ReaderLog.memoryLinesForTest().size} 行）",
            ReaderLog.memoryLinesForTest().size <= 3
        )
    }

    @Test
    fun aggregation_reports_the_suppressed_count() {
        ReaderLog.resetForTest(writerLog)
        repeat(10) { ReaderLog.i("BaiChaReader", "焦点清除") }
        val suppressed = ReaderLog.pendingSuppressed()
        assertEquals("10 次采样 - 1 次输出（突刺摘要另计）", 9 - 1, suppressed)
    }

    @Test
    fun jittering_messages_with_different_digits_are_still_merged() {
        // 「无障碍焦点: 设置项 3」「…4」「…5」是同一种抖动
        repeat(30) { i -> ReaderLog.i("BaiChaReader", "无障碍焦点: 设置项 $i") }
        val lines = ReaderLog.memoryLinesForTest()
        assertTrue("数字抖动必须被归为同一模式（实际 ${lines.size} 行）", lines.size <= 2)
    }

    @Test
    fun distinct_messages_are_not_merged() {
        ReaderLog.i("BaiChaReader", "焦点清除")
        ReaderLog.i("BaiChaReader", "窗口变化")
        ReaderLog.i("BaiChaReader", "TTS 就绪")
        assertEquals("不同消息不得互相聚合", 3, ReaderLog.memoryLinesForTest().size)
    }

    // ==================== ④ 内存上限 ====================

    @Test
    fun memory_lines_are_capped_at_max_memory_lines() {
        // 每条消息的**词**都不同（不只是在数字上不同），否则会被消息模式归一
        // 成同一种抖动而聚合掉，测不到缓冲上限
        val words = ('a'..'z').toList()
        var n = 0
        for (a in words) for (b in words) for (c in words) {
            if (n >= ReaderLog.MAX_MEMORY_LINES + 200) break
            ReaderLog.i("BaiChaReader", "行 $a$b$c")
            n++
        }
        val lines = ReaderLog.memoryLinesForTest()
        assertEquals(ReaderLog.MAX_MEMORY_LINES, lines.size)
    }

    @Test
    fun structured_records_are_also_capped() {
        val words = ('a'..'z').toList()
        val extra = ('A'..'Z').toList()
        var n = 0
        for (a in words) for (b in extra) for (c in words) {
            if (n >= ReaderLog.recordCapacity() + 500) break
            ReaderLog.i("BaiChaReader", "记录 $a$b$c")
            n++
        }
        assertEquals(ReaderLog.recordCapacity(), ReaderLog.recordCount())
        assertTrue(ReaderLog.recordDroppedTotal() > 0)
    }

    // ==================== ⑤ 结构化筛选 ====================

    @Test
    fun read_by_category_returns_only_that_category() {
        ReaderLog.focus("焦点甲")
        ReaderLog.speech("语音甲")
        ReaderLog.focus("焦点乙")

        val focus = ReaderLog.readByCategory(LogCategory.FOCUS)
        assertEquals(2, focus.size)
        assertTrue(focus.all { it.contains("/FOCUS/") })
    }

    @Test
    fun read_by_level_returns_that_level_and_above() {
        ReaderLog.minLevel = LogLevel.DEBUG
        ReaderLog.d("FocusManager", "低")
        ReaderLog.w("FocusManager", "中")
        ReaderLog.e("FocusManager", "高")

        assertEquals(2, ReaderLog.readByLevel(LogLevel.WARN).size)
        assertEquals(3, ReaderLog.readByLevel(LogLevel.DEBUG).size)
    }

    @Test
    fun filter_lines_parses_category_from_rendered_text() {
        ReaderLog.focus("焦点甲")
        ReaderLog.speech("语音甲")
        val lines = ReaderLog.memoryLinesForTest()

        val picked = ReaderLog.filterLines(lines, listOf(LogCategory.SPEECH))
        assertEquals(1, picked.size)
        assertTrue(picked[0].contains("语音甲"))
    }

    @Test
    fun empty_category_filter_returns_everything_newest_first() {
        ReaderLog.focus("甲")
        ReaderLog.speech("乙")
        val all = ReaderLog.filterLines(ReaderLog.memoryLinesForTest(), emptyList())
        assertEquals(2, all.size)
        assertTrue("最新在前：${all[0]}", all[0].contains("乙"))
    }

    @Test
    fun category_filter_field_limits_read_memory() {
        ReaderLog.focus("焦点甲")
        ReaderLog.speech("语音甲")
        ReaderLog.categoryFilter = setOf(LogCategory.SPEECH)
        val visible = ReaderLog.readMemory()
        assertEquals(1, visible.size)
        assertTrue(visible[0].contains("语音甲"))
        ReaderLog.categoryFilter = emptySet()
    }

    @Test
    fun counts_by_category_matches_what_was_logged() {
        ReaderLog.focus("a")
        ReaderLog.focus("b")
        ReaderLog.speech("c")
        val counts = ReaderLog.countsByCategory()
        assertEquals(2, counts[LogCategory.FOCUS])
        assertEquals(1, counts[LogCategory.SPEECH])
    }

    // ==================== ⑥ 落盘与导出 ====================

    @Test
    fun lines_are_written_to_the_file() {
        ReaderLog.i("FocusManager", "写盘验证")
        val content = awaitFileContent()
        assertTrue("文件里必须有这一行，实际内容：$content", content.contains("写盘验证"))
    }

    @Test
    fun file_export_copies_everything() {
        ReaderLog.i("FocusManager", "一行")
        awaitFileContent()
        val dest = File(tmpDir, "export-all.log")
        assertTrue(ReaderLog.export(dest.absolutePath))
        assertTrue(awaitFile(dest).contains("一行"))
    }

    @Test
    fun filtered_export_only_includes_the_requested_category() {
        ReaderLog.focus("焦点行")
        ReaderLog.speech("语音行")
        ReaderLog.speech("语音行二")

        awaitFileContent()
        val dest = File(tmpDir, "export-speech.log")
        assertTrue(ReaderLog.export(dest.absolutePath, categories = setOf(LogCategory.SPEECH)))

        val text = awaitFile(dest)
        assertTrue("导出内容：$text", text.contains("语音行"))
        assertFalse("导出「只看 TTS」不该带出焦点日志，实际：$text", text.contains("焦点行"))
    }

    @Test
    fun filtered_export_respects_min_level() {
        ReaderLog.minLevel = LogLevel.DEBUG
        ReaderLog.d("FocusManager", "低级别行")
        ReaderLog.e("FocusManager", "错误行")

        awaitFileContent()
        val dest = File(tmpDir, "export-error.log")
        assertTrue(ReaderLog.export(dest.absolutePath, minLevel = LogLevel.ERROR))
        val text = awaitFile(dest)
        assertTrue(text.contains("错误行"))
        assertFalse(text.contains("低级别行"))
    }

    @Test
    fun export_to_uninitialized_log_returns_false() {
        ReaderLog.resetForTest(null)
        assertFalse(ReaderLog.export(File(tmpDir, "never.log").absolutePath))
    }

    // ==================== ⑦ 线程安全 ====================

    @Test
    fun concurrent_logging_does_not_crash_or_exceed_caps() {
        val threads = 8
        val perThread = 2000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                try {
                    start.await()
                    repeat(perThread) { i ->
                        ReaderLog.i("BaiChaReader", "并发 $t-$i")
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("并发写日志超时", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertTrue(
            "内存行数不得超上限：${ReaderLog.memoryLinesForTest().size}",
            ReaderLog.memoryLinesForTest().size <= ReaderLog.MAX_MEMORY_LINES
        )
        assertTrue(
            "结构化记录不得超上限：${ReaderLog.recordCount()}",
            ReaderLog.recordCount() <= ReaderLog.recordCapacity()
        )
    }

    @Test
    fun concurrent_concurrent_calls_to_read_memory_are_safe() {
        ReaderLog.i("BaiChaReader", "写一条")
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) {
            pool.execute {
                try {
                    repeat(500) { ReaderLog.readMemory() }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertNotNull(ReaderLog.readMemory())
    }

    // ==================== ⑧ 回调（面板）====================

    @Test
    fun on_log_update_receives_each_emitted_line() {
        val seen = ArrayList<String>()
        ReaderLog.onLogUpdate = { seen.add(it) }
        try {
            ReaderLog.i("FocusManager", "面板行")
            assertEquals(1, seen.size)
            assertTrue(seen[0].contains("面板行"))
        } finally {
            ReaderLog.onLogUpdate = null
        }
    }

    @Test
    fun a_throwing_panel_callback_does_not_break_logging() {
        ReaderLog.onLogUpdate = { throw IllegalStateException("面板崩了") }
        try {
            ReaderLog.i("FocusManager", "仍然要写进去")
            assertEquals(1, ReaderLog.memoryLinesForTest().size)
        } finally {
            ReaderLog.onLogUpdate = null
        }
    }

    @Test
    fun aggregation_listener_is_notified_when_lines_are_suppressed() {
        val notes = ArrayList<String>()
        val listener: (String) -> Unit = { notes.add(it) }
        ReaderLog.addAggregationListener(listener)
        try {
            repeat(6) { ReaderLog.i("BaiChaReader", "焦点清除") }
            // 第 2..6 条被聚合，回调收到的应是聚合摘要而不是原文
            assertTrue("聚合摘要必须能被面板看到", notes.any { it.contains("焦点清除") })
        } finally {
            ReaderLog.removeAggregationListener(listener)
        }
    }
}
