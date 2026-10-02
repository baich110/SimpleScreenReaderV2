package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 值语义 —— **「只有 val 字段却写成普通 class」这一类缺陷**
 *
 * ══════════════════════════════════════════════════════════
 * 【这类缺陷为什么危险】
 * ══════════════════════════════════════════════════════════
 *
 * 一个字段全是 `val`、无身份语义的类，是**值类型** ——
 * 「内容相同即相等」是调用方的自然预期。
 *
 * 但若写成普通 `class`，`equals`/`hashCode` 继承自 `Any`（引用相等）：
 * ```kotlin
 *   DiffSegment(EQUAL, "你") == DiffSegment(EQUAL, "你")   // false ❗
 *   LogRecord(...相同内容...) == LogRecord(...相同内容...) // false ❗
 * ```
 *
 * 后果是**静默失败**（目标里点名的模式⑨）：
 * 比较永远为 false，不报错、不崩溃，逻辑悄悄走错分支。
 *
 * 【本轮实证到的危害】写 `TextDiffPropertyTest` 的稳定性不变式时，
 * 断言失败信息是：
 * ```
 *   expected: [EQUAL('你好'), INSERT('世界')]
 *    but was: [EQUAL('你好'), INSERT('世界')]
 * ```
 * **两边打印完全相同**，完全看不出原因 —— 这种失败极易被
 * 误判为「测试不稳定」而放过。
 *
 * 【本轮修复的 3 处】（全项目扫描结果）
 * | 类 | 位置 | 公开 API |
 * |---|---|---|
 * | `DiffSegment` | `TextDiff.kt` | `TextDiffEngine.diff()` 返回值 |
 * | `LogRecord` | `LogCore.kt` | 日志查询/导出返回值 |
 * | `ReadReplacement` | `ReadReplacement.kt` | `ReadReplacementStore.load()` 返回值 |
 */
class ValueSemanticsTest {

    // ==================== DiffSegment ====================

    @Test
    fun diffSegment_contentEquality() {
        assertEquals(
            "相同内容必须相等（修复前是引用相等 → 永远 false）",
            DiffSegment(DiffOp.EQUAL, "你好"),
            DiffSegment(DiffOp.EQUAL, "你好")
        )
        assertEquals(
            "同内容 hashCode 必须相同（否则 HashMap 查找失效）",
            DiffSegment(DiffOp.EQUAL, "你好").hashCode(),
            DiffSegment(DiffOp.EQUAL, "你好").hashCode()
        )
    }

    @Test
    fun diffSegment_differentContent_notEqual() {
        assertNotEquals(
            DiffSegment(DiffOp.EQUAL, "a"),
            DiffSegment(DiffOp.EQUAL, "b")
        )
        assertNotEquals(
            "op 不同也不相等",
            DiffSegment(DiffOp.EQUAL, "a"),
            DiffSegment(DiffOp.INSERT, "a")
        )
    }

    @Test
    fun diffSegment_listComparisonWorks() {
        val engine = TextDiffEngine()
        assertEquals(
            "★ 同一输入的两次 diff 必须相等（稳定性不变式的基础）",
            engine.diff("你好", "你好世界"),
            engine.diff("你好", "你好世界")
        )
    }

    /** toString 格式必须保持原样（有测试依赖可读输出） */
    @Test
    fun diffSegment_toStringFormat() {
        assertEquals("EQUAL('你好')", DiffSegment(DiffOp.EQUAL, "你好").toString())
        assertEquals("INSERT('x')", DiffSegment(DiffOp.INSERT, "x").toString())
    }

    // ==================== LogRecord ====================

    private fun rec(
        message: String = "消息",
        category: LogCategory = LogCategory.FOCUS,
        level: LogLevel = LogLevel.INFO,
        tag: String = "T",
        time: Long = 0L
    ) = LogRecord(level, category, tag, message, null, time)

    @Test
    fun logRecord_contentEquality() {
        assertEquals("相同内容必须相等", rec(), rec())
        assertEquals("hashCode 必须一致", rec().hashCode(), rec().hashCode())
    }

    @Test
    fun logRecord_differentFields_notEqual() {
        assertNotEquals("消息不同", rec(message = "A"), rec(message = "B"))
        assertNotEquals("分类不同", rec(), rec(category = LogCategory.SPEECH))
        assertNotEquals("级别不同", rec(), rec(level = LogLevel.ERROR))
        assertNotEquals("tag 不同", rec(), rec(tag = "X"))
    }

    /**
     * ★ 时间戳**不参与**相等判定
     *
     * 时间戳是「记录时刻」而非「记录内容」——
     * 同一句日志在不同时刻产生，内容相同就应视为相同
     * （聚合/去重都按内容）。
     */
    @Test
    fun logRecord_timestampIgnoredInEquality() {
        assertEquals(
            "同一内容、不同时间戳的记录应视为相等（时间戳不是记录内容）",
            rec(time = 1000L), rec(time = 9999L)
        )
    }

    @Test
    fun logRecord_worksInHashSet() {
        val set = hashSetOf(rec(time = 1L), rec(time = 2L))
        assertEquals(
            "内容相同的记录在 HashSet 里应只留一条（依赖 hashCode/equals）",
            1, set.size
        )
    }

    /** `limiterKey` 必须仍可用（聚合依赖它，与 equals 是两套机制） */
    @Test
    fun logRecord_limiterKeyStillWorks() {
        val a = LogRecord(LogLevel.INFO, LogCategory.FOCUS, "t", "无障碍焦点: 第 1 项")
        val b = LogRecord(LogLevel.INFO, LogCategory.FOCUS, "t", "无障碍焦点: 第 2 项")
        assertEquals(
            "数字被归一化后，两条不同内容的日志应有相同的限流键",
            a.limiterKey, b.limiterKey
        )
    }

    // ==================== ReadReplacement ====================

    @Test
    fun readReplacement_contentEquality() {
        assertEquals(
            "相同规则必须相等（修复前永远 false）",
            ReadReplacement("的", ""),
            ReadReplacement("的", "")
        )
        assertEquals(
            ReadReplacement("的", "").hashCode(),
            ReadReplacement("的", "").hashCode()
        )
    }

    @Test
    fun readReplacement_differentFields_notEqual() {
        assertNotEquals("原文不同", ReadReplacement("的", ""), ReadReplacement("了", ""))
        assertNotEquals("替换不同", ReadReplacement("的", ""), ReadReplacement("的", "x"))
        assertNotEquals(
            "包名不同（全局 vs 指定包）不看同一条规则",
            ReadReplacement("的", "", packageName = null),
            ReadReplacement("的", "", packageName = "com.a")
        )
        assertNotEquals(
            "优先级不同不是同一条规则（会影响生效顺序）",
            ReadReplacement("的", "", priority = 0),
            ReadReplacement("的", "", priority = 5)
        )
    }

    /**
     * ★ 实用场景：判断「规则是否已存在」
     *
     * 这是 `equals` 修复后**真正能工作**的写法 ——
     * 修复前 `list.contains(rule)` 永远返回 false，
     * 于是「重复添加同一条规则时提示已存在」这类功能静默失效。
     */
    @Test
    fun readReplacement_containsWorks() {
        val list = listOf(ReadReplacement("的", ""), ReadReplacement("了", ""))
        assertTrue(
            "★ list.contains 必须能命中相同内容的规则（修复前永远 false）",
            list.contains(ReadReplacement("的", ""))
        )
        assertFalse("不同规则不应命中", list.contains(ReadReplacement("吗", "")))
    }

    @Test
    fun readReplacement_validityUnchanged() {
        assertTrue("原文与替换不同 → 有效", ReadReplacement("的", "").isValid)
        assertFalse("原文与替换相同 → 无意义", ReadReplacement("的", "的").isValid)
    }
}
