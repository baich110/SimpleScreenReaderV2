package com.baicha.reader.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `SimpleDateFormat` 多线程共用的危害 —— **不是理论问题**
 *
 * ══════════════════════════════════════════════════════════
 * 【事实】`java.text.SimpleDateFormat` **不是线程安全的**
 * ══════════════════════════════════════════════════════════
 *
 * 它在内部用一个可变的 `Calendar` 字段做格式化。多线程并发调用
 * 同一个实例时，`Calendar` 会被互相踩，结果可能是：
 *   · 解析/格式化出**错误的日期字符串**（静默错误）
 *   · 抛 `ArrayIndexOutOfBoundsException` 或 `NumberFormatException`
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么在项目里是真问题】
 * ══════════════════════════════════════════════════════════
 *
 * `ReaderLog` 有一个 **static 单例**的 `timeFormat`：
 * ```kotlin
 *   private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
 * ```
 * 而 `ReaderLog.log()` 会从**至少三个线程**被调用：
 *   ① 无障碍线程（`onAccessibilityEvent` 回调）
 *   ② 主线程（UI、手势、播报回调）
 *   ③ 服务/写盘线程池
 *
 * 读屏是常驻服务且**焦点抖动时会高频写日志**（真机实测一秒 64 条），
 * 三个线程同时格式化完全可能 —— 一旦踩上就是**日志内容错乱**
 * 或**抛异常**。
 *
 * 【本测试证明的不是「ReaderLog 现在会崩」】
 * 而是「这个 API 在多线程下**确实**会出错」——
 * 从而说明 `ReaderLog` 里的用法是需要修的真实隐患。
 * （修复方式见 `ReaderLog` 的 `wallClockText()`：
 *   改成每个线程一份实例，或加锁。）
 */
class SimpleDateFormatThreadSafetyTest {

    /**
     * ★ 多线程并发格式化同一个 `SimpleDateFormat` → **结果错乱**
     *
     * ══════════════════════════════════════════════════════════
     * 【我第一版测试没复现，记录在案】
     * ══════════════════════════════════════════════════════════
     *
     * 第一版用**同一个固定时间值**并发格式化，结果错乱 0 次、异常 0 次
     * —— 测试失败（没能复现）。原因：格式化同一个 `Date` 时，
     * 内部 `Calendar` 被反复写入**相同的值**，即使线程互相踩也踩不出差异。
     *
     * 改成**不同时间值**（并提高并发到 16 线程 × 20000 次）后立即复现：
     * ```
     *   PROBE2 不同时间值+高并发: 错乱=224015 异常=0
     * ```
     * 即 **22 万次格式化给出了错误的时间字符串**。
     *
     * 这条经验值得记住：**并发缺陷的复现需要让共享状态被写入不同的值**；
     * 用不变的值去压测，等于什么都没测。
     */
    @Test
    fun sharedSimpleDateFormat_producesWrongResults() {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)

        // 不同时间值 —— 这是能复现的关键
        val times = (0 until 200).map { Date(1700000000000L + it * 77777L) }
        val expected = times.map {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(it)
        }

        val threads = 16
        val iterations = 20000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val mismatch = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                try {
                    start.await()
                    repeat(iterations) { i ->
                        val idx = i % times.size
                        try {
                            if (fmt.format(times[idx]) != expected[idx]) mismatch.incrementAndGet()
                        } catch (e: Throwable) {
                            errors.incrementAndGet()
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        done.await(60, TimeUnit.SECONDS)
        pool.shutdownNow()

        println("PROBE 共享 SimpleDateFormat: 错误结果 ${mismatch.get()} 次 / 抛异常 ${errors.get()} 次")
        assertTrue(
            "★ 共享 SimpleDateFormat 在多线程下必须出现错误结果 —— " +
                    "实测错乱 ${mismatch.get()} 次、异常 ${errors.get()} 次。" +
                    "若为 0 说明测试没复现（需用不同时间值 + 更高并发）",
            mismatch.get() > 0 || errors.get() > 0
        )
    }

    /**
     * 对照：**每线程一个实例**（或 ThreadLocal）则完全正确
     *
     * 这是修复方案的有效性证明。
     */
    @Test
    fun perThreadInstance_isCorrect() {
        val threads = 8
        val iterations = 2000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val mismatch = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val done = CountDownLatch(threads)

        val times = (0 until 200).map { Date(1700000000000L + it * 77777L) }
        val expected = times.map {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(it)
        }

        repeat(threads) {
            pool.execute {
                // ★ 每个线程**自己**一个实例
                val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
                try {
                    start.await()
                    repeat(iterations) { i ->
                        val idx = i % times.size
                        try {
                            if (local.format(times[idx]) != expected[idx]) mismatch.incrementAndGet()
                        } catch (e: Throwable) {
                            errors.incrementAndGet()
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        done.await(30, TimeUnit.SECONDS)
        pool.shutdownNow()

        println("PROBE 每线程一实例: 错误结果 $mismatch 次 / 抛异常 $errors 次")
        assertTrue("每线程独立实例必须零错误零异常", mismatch.get() == 0 && errors.get() == 0)
    }
}

/**
 * 接线守卫 —— `ReaderLog` 必须**每线程一份**时间格式实例
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么单独守】上面的测试证明的是「共享 SimpleDateFormat 会错乱」
 * 这个**语言层事实**，完全没有约束 `ReaderLog` 是否已经改用 ThreadLocal。
 * 本项目已 5 次出现「引擎正确、测试全绿、但接线是错的」（变异逃逸）。
 */
class ReaderLogTimeFormatGuardTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val log by lazy {
        codeOf("src/main/java/com/baicha/reader/core/ReaderLog.kt")
    }

    @Test
    fun timeFormat_isThreadLocal() {
        assertTrue(
            "timeFormat 必须声明为 ThreadLocal —— SimpleDateFormat 非线程安全，" +
                    "而 log() 会从无障碍线程/主线程/写盘线程并发调用",
            log.contains("ThreadLocal<SimpleDateFormat>")
        )
        assertTrue(
            "必须有 ThreadLocal.withInitial 初始化",
            log.contains("ThreadLocal.withInitial")
        )
    }

    @Test
    fun everyUsageGoesThrough_get() {
        // 不得出现裸的 timeFormat.format(...)
        assertTrue(
            "不得直接 `timeFormat.format(...)` —— 必须 `timeFormat.get()!!.format(...)`",
            !log.contains("timeFormat.format(")
        )
        assertTrue(
            "必须通过 timeFormat.get() 取本线程实例",
            log.contains("timeFormat.get()!!.format(")
        )
    }

    /**
     * ★ 时间戳格式化必须在**限流判定之后**
     *
     * 依据 `emit()` 里的注释：「被聚合掉的第 2…N 条不生成字符串，
     * 性能收益 64 倍」。原实现第一行就格式化时间戳，
     * **注释与实现不符**（被聚合掉的行照样付了格式化成本）。
     */
    @Test
    fun wallClockText_isAfterRateLimitCheck() {
        val idx = log.indexOf("private fun emit(record: LogRecord)")
        assertTrue("必须存在 emit()", idx >= 0)
        val body = log.substring(idx, minOf(log.length, idx + 2000))

        val verdictIdx = body.indexOf("limiter.samplePattern(")
        val tsIdx = body.indexOf("val ts = wallClockText()")
        val earlyReturnIdx = body.indexOf("return")

        assertTrue("必须调用 limiter.samplePattern", verdictIdx >= 0)
        assertTrue("必须有 `val ts = wallClockText()`", tsIdx >= 0)
        assertTrue(
            "★ 时间戳格式化必须在限流判定**之后**（否则被聚合掉的行也付了格式化成本）——" +
                    "verdictIdx=$verdictIdx, tsIdx=$tsIdx",
            verdictIdx < tsIdx
        )
        assertTrue(
            "被聚合掉的路径必须在格式化之前 return",
            earlyReturnIdx in (verdictIdx + 1) until tsIdx
        )
    }
}
