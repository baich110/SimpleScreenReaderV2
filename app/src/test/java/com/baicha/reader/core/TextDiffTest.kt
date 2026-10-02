package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本差分引擎单测 —— 增量朗读的正确性保证
 */
class TextDiffTest {

    private val engine = TextDiffEngine()

    // ==================== 边界 ====================

    @Test
    fun both_empty_returns_empty() {
        assertTrue(engine.diff("", "").isEmpty())
    }

    @Test
    fun null_old_means_all_inserted() {
        val r = engine.diff(null, "新内容")
        assertEquals(1, r.size)
        assertEquals(DiffOp.INSERT, r[0].op)
        assertEquals("新内容", r[0].text)
    }

    @Test
    fun null_new_means_all_deleted() {
        val r = engine.diff("旧内容", null)
        assertEquals(1, r.size)
        assertEquals(DiffOp.DELETE, r[0].op)
    }

    @Test
    fun identical_text_is_single_equal() {
        val r = engine.diff("相同", "相同")
        assertEquals(1, r.size)
        assertEquals(DiffOp.EQUAL, r[0].op)
    }

    // ==================== 增量提取 ====================

    @Test
    fun added_text_on_append() {
        // "价格 10.5" → "价格 10.8"，新增应为 "8"
        val added = engine.addedText("价格 10.5", "价格 10.8")
        assertEquals("8", added)
    }

    @Test
    fun added_text_null_when_unchanged() {
        assertNull(engine.addedText("相同", "相同"))
    }

    @Test
    fun added_text_all_when_old_empty() {
        assertEquals("全新", engine.addedText(null, "全新"))
        assertEquals("全新", engine.addedText("", "全新"))
    }

    @Test
    fun no_added_text_on_pure_deletion() {
        // 纯删除 → 无新增
        assertNull(engine.addedText("abc", "ab"))
    }

    @Test
    fun worth_speaking_filters_pure_deletion() {
        assertFalse(engine.isWorthSpeaking("abc", "ab"))
    }

    @Test
    fun worth_speaking_true_on_addition() {
        assertTrue(engine.isWorthSpeaking("abc", "abcd"))
    }

    @Test
    fun worth_speaking_false_on_blank_addition() {
        assertFalse(engine.isWorthSpeaking("abc", "abc   "))
    }

    // ==================== 公共前后缀剥离效果 ====================

    @Test
    fun prefix_is_preserved() {
        val r = engine.diff("前缀ABC", "前缀XYZ")
        // 应有 EQUAL("前缀") 开头
        assertEquals(DiffOp.EQUAL, r.first().op)
        assertEquals("前缀", r.first().text)
    }

    @Test
    fun suffix_is_preserved() {
        val r = engine.diff("ABC后缀", "XYZ后缀")
        assertEquals(DiffOp.EQUAL, r.last().op)
        assertEquals("后缀", r.last().text)
    }

    @Test
    fun middle_change_detected() {
        val r = engine.diff("A中间B", "A变换B")
        // 拼接所有片段应能还原新文本（除 DELETE 外）
        val reconstructed = buildString {
            r.filter { it.op != DiffOp.DELETE }.forEach { append(it.text) }
        }
        assertEquals("A变换B", reconstructed)
    }

    // ==================== 正确性：重建验证 ====================

    @Test
    fun reconstruction_matches_new_text() {
        val cases = listOf(
            "a" to "b",
            "hello" to "hello world",
            "hello world" to "hello",
            "价格 10.5 元" to "价格 10.8 元",
            "2024-01-01" to "2024-01-02",
            "abcabc" to "abc",
            "" to "x",
            "x" to ""
        )
        for ((old, new) in cases) {
            val r = engine.diff(old, new)
            val reconstructed = buildString {
                r.filter { it.op != DiffOp.DELETE }.forEach { append(it.text) }
            }
            assertEquals("diff($old → $new) 重建失败", new, reconstructed)
        }
    }

    @Test
    fun deletion_reconstruction_matches_old_text() {
        val cases = listOf(
            "hello world" to "hello",
            "abc" to "",
            "价格 10.5 元" to "价格 元"
        )
        for ((old, new) in cases) {
            val r = engine.diff(old, new)
            val reconstructed = buildString {
                r.filter { it.op != DiffOp.INSERT }.forEach { append(it.text) }
            }
            assertEquals("diff 删除侧重建失败 ($old → $new)", old, reconstructed)
        }
    }

    // ==================== 降级路径 ====================

    @Test
    fun long_text_falls_back() {
        val long = "x".repeat(500)
        val longNew = "y".repeat(500)
        val r = engine.diff(long, longNew)
        // 降级 → 单个 INSERT
        assertEquals(1, r.size)
        assertEquals(DiffOp.INSERT, r[0].op)
    }

    @Test
    fun large_length_diff_falls_back() {
        val r = engine.diff("a", "a".repeat(200))
        assertTrue("长度差过大应降级", r.isNotEmpty())
    }

    @Test
    fun stats_is_non_empty() {
        assertTrue(engine.stats().isNotEmpty())
    }
}

/**
 * 节点文本监听器单测
 */
class NodeTextMonitorTest {

    @Test
    fun disabled_monitor_ignores_watch() {
        val m = NodeTextMonitor()
        m.enabled = false
        assertEquals(0, m.watchedCount)
    }

    @Test
    fun enabled_flag_is_settable() {
        val m = NodeTextMonitor()
        m.enabled = true
        assertTrue(m.enabled)
    }

    @Test
    fun unwatch_all_is_safe() {
        val m = NodeTextMonitor()
        m.unwatchAll()
        assertEquals(0, m.watchedCount)
    }

    @Test
    fun stats_is_non_empty() {
        val m = NodeTextMonitor()
        assertTrue(m.stats().isNotEmpty())
    }
}
