package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航模式单测 —— 锁定 26 种导航的定义与切换
 */
class NavigationModeTest {

    @Test
    fun navigation_count_is_27() {
        // 天坦 X6/z 定义了 27 种导航（序号 0~26，逐条清点源码确认）
        assertEquals(27, NavigationMode.values().size)
    }

    @Test
    fun no_duplicate_type_codes() {
        val codes = NavigationMode.values().map { it.typeCode }
        assertEquals("类型码不能重复", codes.size, codes.toSet().size)
    }

    @Test
    fun no_duplicate_labels() {
        val labels = NavigationMode.values().map { it.label }
        assertEquals("导航名不能重复", labels.size, labels.toSet().size)
    }

    @Test
    fun type_codes_match_tantang() {
        // 关键类型码必须与天坦一致（会传给遍历策略工厂）
        assertEquals(0, NavigationMode.DEFAULT.typeCode)
        assertEquals(1, NavigationMode.CHARACTERS.typeCode)
        assertEquals(2, NavigationMode.WORDS.typeCode)
        assertEquals(8, NavigationMode.CONTROLS.typeCode)
        assertEquals(12, NavigationMode.SCROLLABLE_WIDGET.typeCode)
        assertEquals(16, NavigationMode.LIST_TRAVERSAL.typeCode)
        assertEquals(31, NavigationMode.ROW_AND_COLUMN_TRAVERSAL.typeCode)
        assertEquals(32, NavigationMode.SELECTOR_EDIT.typeCode)
    }

    @Test
    fun from_type_code_resolves() {
        assertEquals(NavigationMode.DEFAULT, NavigationMode.fromTypeCode(0))
        assertEquals(NavigationMode.CONTROLS, NavigationMode.fromTypeCode(8))
        assertEquals(NavigationMode.LIST_TRAVERSAL, NavigationMode.fromTypeCode(16))
        assertNull(NavigationMode.fromTypeCode(9999))
    }

    @Test
    fun labels_are_chinese() {
        assertEquals("默认", NavigationMode.DEFAULT.label)
        assertEquals("字符", NavigationMode.CHARACTERS.label)
        assertEquals("编辑", NavigationMode.SELECTOR_EDIT.label)
        assertEquals("列表遍历", NavigationMode.LIST_TRAVERSAL.label)
    }

    @Test
    fun switchable_only_contains_implemented() {
        val list = NavigationMode.switchable
        assertTrue("应有已实现的导航", list.isNotEmpty())
        assertTrue("切换列表里不能有未实现的", list.all { it.implemented })
    }

    @Test
    fun next_wraps_around() {
        val list = NavigationMode.switchable
        val last = list.last()
        assertEquals(list.first(), NavigationMode.next(last))
    }

    @Test
    fun previous_wraps_around() {
        val list = NavigationMode.switchable
        assertEquals(list.last(), NavigationMode.previous(list.first()))
    }

    @Test
    fun kinds_are_assigned() {
        assertEquals(NavigationKind.GRANULARITY, NavigationMode.CHARACTERS.kind)
        assertEquals(NavigationKind.FILTER, NavigationMode.LINKS.kind)
        assertEquals(NavigationKind.TRAVERSAL, NavigationMode.LIST_TRAVERSAL.kind)
        assertEquals(NavigationKind.SELECTOR, NavigationMode.SELECTOR_EDIT.kind)
        assertEquals(NavigationKind.TOOL, NavigationMode.VOLUME_CONTROL.kind)
    }

    @Test
    fun unimplemented_modes_are_marked() {
        // 依赖功能层的导航应标记未实现
        assertFalse(NavigationMode.SELECTOR_OCR.implemented)
        assertFalse(NavigationMode.GUIDEPOST.implemented)
        assertFalse(NavigationMode.CONTINUES_READING.implemented)
        // 框架已实现的
        assertTrue(NavigationMode.DEFAULT.implemented)
        assertTrue(NavigationMode.LINKS.implemented)
    }
}

/**
 * 导航管理器单测
 */
class NavigationManagerTest {

    @Test
    fun starts_at_default() {
        val m = NavigationManager()
        assertEquals(NavigationMode.DEFAULT, m.current)
    }

    @Test
    fun set_mode_changes_current() {
        val m = NavigationManager()
        assertTrue(m.setMode(NavigationMode.CHARACTERS))
        assertEquals(NavigationMode.CHARACTERS, m.current)
    }

    @Test
    fun set_same_mode_returns_false() {
        val m = NavigationManager()
        assertFalse(m.setMode(NavigationMode.DEFAULT))
    }

    @Test
    fun reject_unimplemented_mode() {
        val m = NavigationManager()
        assertFalse("未实现的导航应拒绝切换", m.setMode(NavigationMode.SELECTOR_OCR))
        assertEquals(NavigationMode.DEFAULT, m.current)
    }

    @Test
    fun on_mode_changed_fires() {
        val m = NavigationManager()
        var fired: NavigationMode? = null
        m.onModeChanged = { fired = it }
        m.setMode(NavigationMode.LINKS)
        assertEquals(NavigationMode.LINKS, fired)
    }

    @Test
    fun strategy_type_code_follows_mode() {
        val m = NavigationManager()
        m.setMode(NavigationMode.LIST_TRAVERSAL)
        assertEquals(16, m.strategyTypeCode())
    }

    @Test
    fun filter_is_available() {
        val m = NavigationManager()
        m.setMode(NavigationMode.LINKS)
        assertNotNull(m.filter())
    }

    @Test
    fun granularity_level_maps() {
        val m = NavigationManager()
        m.setMode(NavigationMode.CHARACTERS)
        assertEquals(GranularityNavigator.Level.CHARACTER, m.granularityLevel())

        m.setMode(NavigationMode.WORDS)
        assertEquals(GranularityNavigator.Level.WORD, m.granularityLevel())

        m.setMode(NavigationMode.DEFAULT)
        assertEquals(GranularityNavigator.Level.DEFAULT, m.granularityLevel())
    }

    @Test
    fun is_granularity_mode() {
        val m = NavigationManager()
        m.setMode(NavigationMode.LINES)
        assertTrue(m.isGranularityMode())
        m.setMode(NavigationMode.LINKS)
        assertFalse(m.isGranularityMode())
    }

    @Test
    fun next_advances() {
        val m = NavigationManager()
        val first = m.current
        m.next()
        assertTrue(m.current != first)
    }
}
