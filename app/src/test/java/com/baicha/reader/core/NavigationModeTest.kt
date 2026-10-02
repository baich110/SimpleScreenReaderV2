package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航模式单测 —— 锁定导航定义与切换
 */
class NavigationModeTest {

    @Test
    fun navigation_count_is_29() {
        // 天坦 X6/z 定义了 27 种导航（序号 0~26，逐条清点源码确认）。
        // 白茶在此基础上加 2 种（跳到顶部/底部，P1-1）：
        //   天坦把「滚动定位」放在手势里，白茶提为导航模式 ——
        //   对视障用户而言「我现在处于跳到顶部模式」比
        //   「记住某个手势是跳顶部」更符合心智模型。
        assertEquals(29, NavigationMode.values().size)
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

/**
 * P0-1 回归测试 —— 导航码 ≠ 遍历器码
 *
 * 【背景】之前 `strategyTypeCode()` 直接返回导航码，
 * 而导航码会撞上遍历器类型码：
 *   WORDS(2)    → TYPE_CACHED(2)
 *   CONTROLS(8) → TYPE_GRID(8)
 * 导致 3 个导航行为完全错误。
 *
 * 本测试锁定「两个码分离」的设计。
 */
class NavigationTraversalTypeTest {

    /**
     * ★ 核心断言：导航码与遍历器码必须是两个独立字段
     */
    @Test
    fun words_navigation_must_not_use_cached_traversal() {
        // 导航码是 2（天坦定义）
        assertEquals(2, NavigationMode.WORDS.typeCode)
        // 但遍历器类型必须是默认，不能是 TYPE_CACHED(=2)
        assertEquals(
            "「字词」导航必须用默认遍历，不能撞上缓存遍历器",
            TraversalStrategyFactory.TYPE_DEFAULT,
            NavigationMode.WORDS.traversalType
        )
    }

    @Test
    fun controls_navigation_must_not_use_grid_traversal() {
        assertEquals(8, NavigationMode.CONTROLS.typeCode)
        assertEquals(
            "「控件」导航必须用默认遍历，不能撞上网格遍历器",
            TraversalStrategyFactory.TYPE_DEFAULT,
            NavigationMode.CONTROLS.traversalType
        )
    }

    @Test
    fun vertical_traversal_uses_vertical_type() {
        assertEquals(17, NavigationMode.VERTICAL_TRAVERSAL.typeCode)
        assertEquals(
            "「垂直遍历」必须用垂直遍历器",
            TraversalStrategyFactory.TYPE_VERTICAL,
            NavigationMode.VERTICAL_TRAVERSAL.traversalType
        )
    }

    @Test
    fun list_traversal_uses_list_type() {
        assertEquals(TraversalStrategyFactory.TYPE_LIST, NavigationMode.LIST_TRAVERSAL.traversalType)
    }

    @Test
    fun row_column_uses_row_column_type() {
        assertEquals(
            TraversalStrategyFactory.TYPE_ROW_COLUMN,
            NavigationMode.ROW_AND_COLUMN_TRAVERSAL.traversalType
        )
    }

    /**
     * 粒度类导航都不改变遍历方式（都用默认遍历器）
     */
    @Test
    fun granularity_modes_all_use_default_traversal() {
        val granularityModes = listOf(
            NavigationMode.DEFAULT, NavigationMode.CHARACTERS,
            NavigationMode.WORDS, NavigationMode.SENTENCE,
            NavigationMode.PARAGRAPHS, NavigationMode.LINES
        )
        granularityModes.forEach {
            assertEquals(
                "${it.label} 应使用默认遍历",
                TraversalStrategyFactory.TYPE_DEFAULT,
                it.traversalType
            )
        }
    }

    /**
     * 过滤类导航也不改变遍历（只改变"停在哪里"）
     */
    @Test
    fun filter_modes_all_use_default_traversal() {
        val filterModes = listOf(
            NavigationMode.LINKS, NavigationMode.HEADINGS,
            NavigationMode.CONTROLS, NavigationMode.SCROLLABLE_WIDGET
        )
        filterModes.forEach {
            assertEquals(
                "${it.label} 应使用默认遍历",
                TraversalStrategyFactory.TYPE_DEFAULT,
                it.traversalType
            )
        }
    }

    /**
     * usesTraversal 属性应正确反映「是否换遍历器」
     */
    @Test
    fun uses_traversal_flag_is_accurate() {
        assertTrue("列表遍历应换遍历器", NavigationMode.LIST_TRAVERSAL.usesTraversal)
        assertTrue("垂直遍历应换遍历器", NavigationMode.VERTICAL_TRAVERSAL.usesTraversal)
        assertFalse("默认导航不换遍历器", NavigationMode.DEFAULT.usesTraversal)
        assertFalse("字词导航不换遍历器", NavigationMode.WORDS.usesTraversal)
    }

    // ==================== P0-3：窗口导航已实现 ====================

    @Test
    fun windows_navigation_is_now_implemented() {
        assertTrue(
            "窗口导航应标记为已实现（WindowTraversal 已就绪）",
            NavigationMode.WINDOWS_NAVIGATION.implemented
        )
    }

    // ==================== P0-3：未实现导航有反馈 ====================

    @Test
    fun unimplemented_mode_triggers_callback() {
        val m = NavigationManager()
        var notified: NavigationMode? = null
        m.onUnimplemented = { notified = it }

        val ok = m.setMode(NavigationMode.SELECTOR_OCR)

        assertFalse("未实现导航不应切换成功", ok)
        assertEquals("应回调通知用户", NavigationMode.SELECTOR_OCR, notified)
    }

    @Test
    fun implemented_mode_does_not_trigger_unimplemented_callback() {
        val m = NavigationManager()
        var notified: NavigationMode? = null
        m.onUnimplemented = { notified = it }

        m.setMode(NavigationMode.CHARACTERS)

        assertEquals("已实现导航不该触发未实现回调", null, notified)
    }
}

/**
 * 粒度级别测试 —— P0-2 / P1-4
 */
class GranularityLevelTest {

    @Test
    fun level_has_six_values() {
        assertEquals(6, GranularityNavigator.Level.values().size)
    }

    @Test
    fun sentence_and_paragraph_exist() {
        // 天坦/TalkBack 都有，原实现缺失
        val names = GranularityNavigator.Level.values().map { it.name }
        assertTrue(names.contains("SENTENCE"))
        assertTrue(names.contains("PARAGRAPH"))
    }

    /**
     * 系统粒度常量对齐 Android
     */
    @Test
    fun system_granularity_constants() {
        assertEquals(1, GranularityMover.MOVEMENT_GRANULARITY_CHARACTER)
        assertEquals(2, GranularityMover.MOVEMENT_GRANULARITY_WORD)
        assertEquals(4, GranularityMover.MOVEMENT_GRANULARITY_LINE)
        assertEquals(8, GranularityMover.MOVEMENT_GRANULARITY_PARAGRAPH)
    }

    @Test
    fun action_constants_match_android() {
        assertEquals(256, GranularityMover.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
        assertEquals(512, GranularityMover.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
    }

    @Test
    fun level_to_system_granularity_mapping() {
        assertEquals(
            GranularityMover.MOVEMENT_GRANULARITY_CHARACTER,
            GranularityMover.toSystemGranularity(GranularityNavigator.Level.CHARACTER)
        )
        assertEquals(
            GranularityMover.MOVEMENT_GRANULARITY_WORD,
            GranularityMover.toSystemGranularity(GranularityNavigator.Level.WORD)
        )
        assertEquals(
            GranularityMover.MOVEMENT_GRANULARITY_LINE,
            GranularityMover.toSystemGranularity(GranularityNavigator.Level.LINE)
        )
        assertEquals(
            GranularityMover.MOVEMENT_GRANULARITY_PARAGRAPH,
            GranularityMover.toSystemGranularity(GranularityNavigator.Level.PARAGRAPH)
        )
        // DEFAULT 无对应系统常量
        assertEquals(0, GranularityMover.toSystemGranularity(GranularityNavigator.Level.DEFAULT))
    }

    @Test
    fun sentence_has_no_system_constant() {
        // SENTENCE 在 Android 系统 API 中没有对应常量（TalkBack 用自定义实现）
        assertEquals(
            "SENTENCE 无系统常量，应返回 0 以便回退",
            0,
            GranularityMover.toSystemGranularity(GranularityNavigator.Level.SENTENCE)
        )
    }

    @Test
    fun move_with_null_node_is_safe() {
        assertFalse(GranularityMover.move(null, GranularityNavigator.Level.WORD, true))
    }

    @Test
    fun is_supported_with_null_node() {
        assertFalse(GranularityMover.isSupported(null, GranularityNavigator.Level.WORD))
    }
}
