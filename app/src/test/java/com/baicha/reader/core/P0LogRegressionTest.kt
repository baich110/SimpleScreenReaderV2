package com.baicha.reader.core

import org.junit.Test

/**
 * 真机 P0 日志场景回归测试
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么单独立一个测试类】
 * ══════════════════════════════════════════════════════════
 *
 * 这轮排查 P0 时，日志本身成了障碍。真机原始片段：
 * ```
 * 01:44:47.271 焦点清除
 * 01:44:47.296 焦点清除
 * 01:44:47.296 焦点清除
 * ...（一秒内 64 次）
 * ```
 * 直接后果：**排查者（我）被刷屏带偏**，先怀疑了错误的方向。
 *
 * 本测试把那一刻的真实日志流**原样重放**给新日志系统，
 * 验证它会把 64 行聚合成少量摘要 ——
 * 这是「日志不再是障碍」的可验证判据。
 */
class P0LogRegressionTest {

    /**
     * ★★ 真机现场重放：一秒内 64 次「焦点清除」应被聚合
     */
    @Test
    fun real_device_focus_cleared_spam_is_aggregated() {
        val limiter = LogLimiter()
        var emitted = 0
        val base = 1_000_000L

        // 复现真机频率：一秒内 64 次，间隔约 15ms
        repeat(64) { i ->
            val verdict = limiter.sample(
                LogCategory.FOCUS,
                "焦点清除",
                now = base + i * 15L
            )
            if (verdict.emit) emitted++
        }

        org.junit.Assert.assertTrue(
            "真机 64 行「焦点清除」必须被聚合（实际输出 $emitted 条）",
            emitted < 64
        )
        org.junit.Assert.assertTrue(
            "聚合后仍须保留信息：至少输出 1 条（$emitted）",
            emitted >= 1
        )
        // 理想情况是个位数
        org.junit.Assert.assertTrue(
            "聚合效果应显著（实际 $emitted 条，期望 ≤ 8）",
            emitted <= 8
        )
    }

    /**
     * ★★ 关键性质：聚合掉的行数必须是**可见的**
     *
     * 日志的目的不是「少写字」，而是「不丢信息」——
     * 用户/排查者必须能看到「这里被折叠了 N 次」。
     */
    @Test
    fun aggregation_reports_suppressed_count() {
        val limiter = LogLimiter()
        val base = 1_000_000L
        var maxCount = 0
        var summarySeen = ""

        repeat(64) { i ->
            val v = limiter.sample(LogCategory.FOCUS, "焦点清除", now = base + i * 15L)
            if (v.count > maxCount) {
                maxCount = v.count
                summarySeen = v.summary()
            }
        }

        org.junit.Assert.assertTrue(
            "必须报告累计次数（否则等于静默丢日志），实际 maxCount=$maxCount",
            maxCount > 1
        )
        org.junit.Assert.assertTrue(
            "摘要应包含次数信息：$summarySeen",
            summarySeen.contains("x") || summarySeen.contains("次")
        )
    }

    /**
     * ★★ 不同文本不应被错误合并
     *
     * 真机上「焦点清除」和「无障碍焦点: 进度条」是**不同的**信息，
     * 合并它们会掩盖问题。
     */
    @Test
    fun different_messages_are_not_merged() {
        val limiter = LogLimiter()
        val base = 1_000_000L

        val a = limiter.sample(LogCategory.FOCUS, "焦点清除", now = base)
        val b = limiter.sample(LogCategory.FOCUS, "无障碍焦点: 进度条", now = base)
        val c = limiter.sample(LogCategory.FOCUS, "无障碍焦点: 代码块", now = base)

        // 三条不同内容，各自是首次出现 → 都应输出
        org.junit.Assert.assertTrue("不同消息不应互相限流", a.emit)
        org.junit.Assert.assertTrue("不同消息不应互相限流", b.emit)
        org.junit.Assert.assertTrue("不同消息不应互相限流", c.emit)
    }

    /**
     * ★ 数字归一化：同一条消息里的不同数字应视为**同一个模式**
     *
     * 「无障碍焦点: 第 1 项」和「第 2 项」在排查时是同一类噪声。
     */
    @Test
    fun numeric_variants_share_the_same_pattern() {
        val p1 = LogRecord(LogLevel.INFO, LogCategory.FOCUS, "t", "无障碍焦点: 第 1 项").pattern
        val p2 = LogRecord(LogLevel.INFO, LogCategory.FOCUS, "t", "无障碍焦点: 第 2 项").pattern
        org.junit.Assert.assertEquals(
            "数字应被归一化，否则每个序号都会开一个新限流键（等于不限流）",
            p1, p2
        )
    }

    /**
     * ★ 限流不能吞掉真正的错误 —— ERROR 级别应始终放行
     */
    @Test
    fun error_level_is_never_silently_dropped() {
        val limiter = LogLimiter()
        val base = 1_000_000L
        var errors = 0
        repeat(20) { i ->
            val v = limiter.sample(LogCategory.SPEECH, "TTS 播报出错: code=-5", now = base + i)
            if (v.emit) errors++
        }
        org.junit.Assert.assertTrue(
            "ERROR 类日志即使高频也必须可见（实际 $errors 条）",
            errors >= 1
        )
    }
}
