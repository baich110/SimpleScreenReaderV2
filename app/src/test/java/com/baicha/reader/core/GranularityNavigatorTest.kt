package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粒度导航单测 —— 锁定「粒度不再死功能」这个修复
 */
class GranularityNavigatorTest {

    @Test
    fun default_level_returns_whole_text() {
        val result = GranularityNavigator.split("你好世界", GranularityNavigator.Level.DEFAULT)
        assertEquals(listOf("你好世界"), result)
    }

    @Test
    fun character_level_splits_cjk_per_char() {
        val result = GranularityNavigator.split("你好世界", GranularityNavigator.Level.CHARACTER)
        assertEquals(listOf("你", "好", "世", "界"), result)
    }

    @Test
    fun character_level_keeps_english_word_whole() {
        val result = GranularityNavigator.split("hi你好", GranularityNavigator.Level.CHARACTER)
        assertEquals(listOf("hi", "你", "好"), result)
    }

    @Test
    fun word_level_groups_cjk_in_pairs() {
        val result = GranularityNavigator.split("白茶读屏", GranularityNavigator.Level.WORD)
        assertEquals(listOf("白茶", "读屏"), result)
    }

    @Test
    fun line_level_splits_and_trims() {
        val result = GranularityNavigator.split("第一行\n\n第二行\n", GranularityNavigator.Level.LINE)
        assertEquals(listOf("第一行", "第二行"), result)
    }

    @Test
    fun empty_text_yields_empty_list() {
        assertTrue(GranularityNavigator.split("", GranularityNavigator.Level.CHARACTER).isEmpty())
    }

    @Test
    fun cycle_walks_all_six_levels_and_wraps() {
        GranularityNavigator.setLevel(GranularityNavigator.Level.DEFAULT)
        assertEquals(GranularityNavigator.Level.CHARACTER, GranularityNavigator.cycle())
        assertEquals(GranularityNavigator.Level.WORD, GranularityNavigator.cycle())
        assertEquals(GranularityNavigator.Level.SENTENCE, GranularityNavigator.cycle())
        assertEquals(GranularityNavigator.Level.LINE, GranularityNavigator.cycle())
        assertEquals(GranularityNavigator.Level.PARAGRAPH, GranularityNavigator.cycle())
        assertEquals(GranularityNavigator.Level.DEFAULT, GranularityNavigator.cycle())
    }

    @Test
    fun level_count_is_six() {
        assertEquals(6, GranularityNavigator.Level.values().size)
    }

    @Test
    fun level_names_are_chinese() {
        assertEquals("默认", GranularityNavigator.levelName(GranularityNavigator.Level.DEFAULT))
        assertEquals("按字", GranularityNavigator.levelName(GranularityNavigator.Level.CHARACTER))
        assertEquals("按词", GranularityNavigator.levelName(GranularityNavigator.Level.WORD))
        assertEquals("按句", GranularityNavigator.levelName(GranularityNavigator.Level.SENTENCE))
        assertEquals("按行", GranularityNavigator.levelName(GranularityNavigator.Level.LINE))
        assertEquals("按段", GranularityNavigator.levelName(GranularityNavigator.Level.PARAGRAPH))
    }

    // ==================== 新增粒度的切分 ====================

    @Test
    fun sentence_splitting_works() {
        val result = GranularityNavigator.split("你好。世界！", GranularityNavigator.Level.SENTENCE)
        assertEquals(listOf("你好。", "世界！"), result)
    }

    @Test
    fun sentence_splitting_handles_english() {
        val result = GranularityNavigator.split("Hello. World!", GranularityNavigator.Level.SENTENCE)
        assertEquals(listOf("Hello.", "World!"), result)
    }

    @Test
    fun paragraph_splitting_works() {
        val text = "第一段\n\n第二段\n\n第三段"
        val result = GranularityNavigator.split(text, GranularityNavigator.Level.PARAGRAPH)
        assertEquals(3, result.size)
    }

    @Test
    fun empty_text_returns_original_for_new_levels() {
        assertEquals(listOf("x"), GranularityNavigator.split("x", GranularityNavigator.Level.SENTENCE))
    }

    @Test
    fun granularity_is_applied_to_current_level() {
        GranularityNavigator.setLevel(GranularityNavigator.Level.CHARACTER)
        val segments = GranularityNavigator.split("你好", GranularityNavigator.currentLevel)
        assertEquals(listOf("你", "好"), segments)
        GranularityNavigator.setLevel(GranularityNavigator.Level.DEFAULT)
    }
}
