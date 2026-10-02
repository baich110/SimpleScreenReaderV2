package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗读替换引擎单测 —— 这是纯逻辑类，可完整测试
 */
class ReadReplacementTest {

    private val engine = ReadReplacementEngine()

    @After
    fun tearDown() {
        engine.clear()
        engine.enabled = false
    }

    // ==================== 基础行为 ====================

    @Test
    fun disabled_engine_returns_original() {
        engine.enabled = false
        engine.addRule(ReadReplacement("zg", "中国"))
        assertEquals("zg 你好", engine.apply("zg 你好", null))
    }

    @Test
    fun null_and_empty_are_safe() {
        engine.enabled = true
        assertEquals(null, engine.apply(null, null))
        assertEquals("", engine.apply("", null))
    }

    @Test
    fun engine_with_no_rules_returns_original() {
        engine.enabled = true
        assertEquals("原文本", engine.apply("原文本", null))
    }

    // ==================== 普通文本替换 ====================

    @Test
    fun literal_replacement_works() {
        engine.setRules(listOf(ReadReplacement("zg", "中国")))
        assertEquals("中国 你好", engine.apply("zg 你好", null))
    }

    @Test
    fun literal_replaces_all_occurrences() {
        engine.setRules(listOf(ReadReplacement("a", "b")))
        assertEquals("bbb", engine.apply("aaa", null))
    }

    @Test
    fun literal_to_empty_removes_text() {
        engine.setRules(listOf(ReadReplacement("广告", "")))
        assertEquals("正文内容", engine.apply("正文广告内容", null))
    }

    @Test
    fun no_match_returns_original() {
        engine.setRules(listOf(ReadReplacement("xyz", "abc")))
        assertEquals("原文本", engine.apply("原文本", null))
    }

    @Test
    fun identical_rule_is_rejected() {
        // 原文 == 替换 是无意义规则
        assertFalse(engine.addRule(ReadReplacement("same", "same")))
    }

    // ==================== 正则替换 ====================

    @Test
    fun regex_replacement_works() {
        engine.setRules(listOf(ReadReplacement("\\d+", "数字", isRegExp = true)))
        assertEquals("第 数字 章", engine.apply("第 123 章", null))
    }

    @Test
    fun regex_with_capture_group() {
        engine.setRules(listOf(ReadReplacement("(\\d+)F", "$1 楼", isRegExp = true)))
        assertEquals("在 3 楼", engine.apply("在 3F", null))
    }

    @Test
    fun invalid_regex_is_rejected() {
        assertFalse(engine.addRule(ReadReplacement("[invalid(", "x", isRegExp = true)))
    }

    @Test
    fun regex_no_match_returns_original() {
        engine.setRules(listOf(ReadReplacement("\\d+", "数字", isRegExp = true)))
        assertEquals("没有数字", engine.apply("没有数字", null))
    }

    // ==================== 两级作用域 ====================

    @Test
    fun package_rule_only_applies_to_that_package() {
        engine.setRules(listOf(ReadReplacement("app", "应用", packageName = "com.test.a")))
        assertEquals("应用", engine.apply("app", "com.test.a"))
        assertEquals("app", engine.apply("app", "com.test.b"))
    }

    @Test
    fun global_rule_applies_everywhere() {
        engine.setRules(listOf(ReadReplacement("g", "全局")))
        assertEquals("全局", engine.apply("g", "com.any.pkg"))
        assertEquals("全局", engine.apply("g", null))
    }

    @Test
    fun package_rules_applied_before_global() {
        engine.setRules(
            listOf(
                ReadReplacement("x", "包名版", packageName = "com.test.a"),
                ReadReplacement("x", "全局版")
            )
        )
        // 包名专用先执行，把 x 换成"包名版"，全局规则不再匹配
        assertEquals("包名版", engine.apply("x", "com.test.a"))
        // 其他包 → 全局版
        assertEquals("全局版", engine.apply("x", "com.other"))
    }

    // ==================== 规则管理 ====================

    @Test
    fun rule_count_tracks_additions() {
        assertEquals(0, engine.ruleCount)
        engine.addRule(ReadReplacement("a", "b"))
        engine.addRule(ReadReplacement("c", "d", packageName = "com.x"))
        assertEquals(2, engine.ruleCount)
    }

    @Test
    fun clear_removes_all_rules() {
        engine.addRule(ReadReplacement("a", "b"))
        engine.clear()
        assertEquals(0, engine.ruleCount)
    }

    @Test
    fun remove_rule_works() {
        val rule = ReadReplacement("a", "b")
        engine.addRule(rule)
        assertEquals(1, engine.ruleCount)
        engine.removeRule(rule)
        assertEquals(0, engine.ruleCount)
    }

    @Test
    fun set_rules_enables_engine() {
        engine.enabled = false
        engine.setRules(listOf(ReadReplacement("a", "b")))
        assertTrue(engine.enabled)
    }

    @Test
    fun stats_is_non_empty() {
        engine.addRule(ReadReplacement("a", "b"))
        assertTrue(engine.stats().isNotEmpty())
    }

    // ==================== 边界 ====================

    @Test
    fun text_becoming_empty_stops_processing() {
        // 第一条规则把文本清空 → 应提前结束，后续规则不再执行，也不崩溃
        engine.setRules(
            listOf(
                ReadReplacement("删", ""),
                ReadReplacement("保留", "替换")
            )
        )
        assertEquals("", engine.apply("删", null))
    }

    @Test
    fun empty_phrase_is_rejected() {
        // 空原文是无意义规则（会无限匹配），构造时应被拒绝
        var threw = false
        try {
            ReadReplacement("", "x")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("空原文应抛异常", threw)

        // 正则模式允许空 phrase（正则本身可能合法）
        val regexRule = ReadReplacement("", "x", isRegExp = true)
        assertTrue(regexRule.isRegExp)
    }

    @Test
    fun multiple_rules_chain() {
        engine.setRules(
            listOf(
                ReadReplacement("A", "B"),
                ReadReplacement("B", "C")
            )
        )
        // 链式：A→B→C
        assertEquals("C", engine.apply("A", null))
    }
}
