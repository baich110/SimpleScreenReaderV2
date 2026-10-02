package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗读替换引擎补充测试
 *
 * 【背景】上一轮审计发现：`ReadReplacementEngine` 实现完整，
 * 但**从未被加载规则**（`enabled` 默认 false 且没人调 `setRules`），
 * 整个功能静默失效。本轮补了 `ReadReplacementStore` 存储层。
 *
 * 这里用**不依赖 Context** 的路径验证引擎本身的行为，
 * 以及内置规则的合理性。
 */
class ReadReplacementEngineTest {

    private val engine = ReadReplacementEngine()

    @After
    fun tearDown() {
        engine.clear()
        engine.enabled = false
    }

    // ==================== 静默失效的验证 ====================

    /**
     * 默认状态：未启用 → 原样返回
     *
     * 这解释了为什么「功能看起来在，但没效果」。
     */
    @Test
    fun default_engine_is_disabled() {
        assertFalse("默认应未启用", engine.enabled)
        assertEquals("文本", engine.apply("文本", null))
    }

    /**
     * 启用但无规则 → 原样返回（不算错误）
     */
    @Test
    fun enabled_without_rules_passes_through() {
        engine.enabled = true
        assertEquals("文本", engine.apply("文本", null))
    }

    /**
     * setRules 会自动启用
     */
    @Test
    fun set_rules_enables_engine() {
        engine.enabled = false
        engine.setRules(listOf(ReadReplacement("a", "b")))
        assertTrue("setRules 应自动启用", engine.enabled)
    }

    // ==================== 内置规则的合理性 ====================

    @Test
    fun builtin_rules_are_valid() {
        val rules = ReadReplacementStore.builtinRules()
        assertTrue("应有内置规则", rules.isNotEmpty())
        rules.forEach {
            assertTrue("内置规则应有效: $it", it.isValid)
        }
    }

    /**
     * 内置规则应能编译（正则不能写错）
     */
    @Test
    fun builtin_rules_compile() {
        val rules = ReadReplacementStore.builtinRules()
        val eng = ReadReplacementEngine()
        val added = rules.count { eng.addRule(it) }
        assertEquals("所有内置规则都应能成功添加（正则可编译）", rules.size, added)
    }

    /**
     * 网址简化规则的实际效果
     */
    @Test
    fun url_rule_simplifies_http() {
        val eng = ReadReplacementEngine()
        val urlRule = ReadReplacementStore.builtinRules()
            .firstOrNull { it.phrase.contains("http") }
        if (urlRule != null) {
            eng.setRules(listOf(urlRule))
            val result = eng.apply("访问 https://example.com", null)
            assertTrue("网址应被简化: $result", result?.contains("网址") == true)
        }
    }

    /**
     * 星号清理规则
     */
    @Test
    fun asterisk_rule_removes_markers() {
        val eng = ReadReplacementEngine()
        val rule = ReadReplacementStore.builtinRules()
            .firstOrNull { it.phrase.contains("\\*") }
        if (rule != null) {
            eng.setRules(listOf(rule))
            assertEquals("重要提示", eng.apply("**重要提示**", null))
        }
    }

    // ==================== 规则计数与清理 ====================

    @Test
    fun rule_count_after_set_rules() {
        engine.setRules(
            listOf(
                ReadReplacement("a", "1"),
                ReadReplacement("b", "2"),
                ReadReplacement("c", "3")
            )
        )
        assertEquals(3, engine.ruleCount)
    }

    @Test
    fun clear_resets_rule_count() {
        engine.setRules(listOf(ReadReplacement("a", "b")))
        engine.clear()
        assertEquals(0, engine.ruleCount)
    }

    // ==================== 优先级字段 ====================

    @Test
    fun priority_field_defaults_to_zero() {
        assertEquals(0, ReadReplacement("a", "b").priority)
        assertEquals(5, ReadReplacement("a", "b", priority = 5).priority)
    }
}
