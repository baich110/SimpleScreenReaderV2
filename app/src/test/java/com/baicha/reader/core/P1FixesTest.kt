package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 修复回归测试（第三轮报告）
 *
 * P1-1 跳到顶部/底部 —— ScrollToPosition
 * P1-3 网页元素导航   —— WebElementNavigation / WebElementNavigator
 * P1-5 导航持久化     —— NavigationStore / NavigationMode.fromTypeCode / restore
 */
class ScrollToPositionTest {

    /**
     * 动作 ID 必须与 Android 系统一致。
     * 写错不会有编译错误，只会静默不动作 —— 必须锁死。
     */
    @Test
    fun action_id_matches_android() {
        assertEquals(16908358, ScrollToPosition.ACTION_SCROLL_TO_POSITION)
    }

    @Test
    fun argument_keys_match_android() {
        assertEquals(
            "android.view.accessibility.action.ARGUMENT_ROW_INT",
            ScrollToPosition.ARGUMENT_ROW_INT
        )
        assertEquals(
            "android.view.accessibility.action.ARGUMENT_COLUMN_INT",
            ScrollToPosition.ARGUMENT_COLUMN_INT
        )
    }

    @Test
    fun null_node_is_safe() {
        assertNull(ScrollToPosition.findScrollableContainer(null))
        assertFalse(ScrollToPosition.scrollToTop(null))
        assertFalse(ScrollToPosition.scrollToBottom(null))
        assertFalse(ScrollToPosition.supportsScrollToPosition(null))
    }

    @Test
    fun scroll_container_to_null_is_safe() {
        assertFalse(ScrollToPosition.scrollContainerTo(null, 0, 0))
    }
}

class WebElementNavigationTest {

    /**
     * 动作 ID 必须与 Android 一致
     */
    @Test
    fun action_ids_match_android() {
        assertEquals(1024, WebElementNavigation.ACTION_NEXT_HTML_ELEMENT)
        assertEquals(2048, WebElementNavigation.ACTION_PREVIOUS_HTML_ELEMENT)
    }

    @Test
    fun argument_key_matches_android() {
        assertEquals(
            "ACTION_ARGUMENT_HTML_ELEMENT_STRING",
            WebElementNavigation.ARGUMENT_HTML_ELEMENT
        )
    }

    /**
     * ★ 元素类型字符串必须与 TalkBack WebInterfaceUtils 完全一致。
     *
     * WebView 按字符串匹配，大小写/拼写错了就静默无动作 ——
     * 这是最隐蔽的一类 bug，必须逐字锁死。
     */
    @Test
    fun element_strings_match_talkback_web_interface_utils() {
        assertEquals("HEADING", WebElementNavigation.ELEMENT_HEADING)
        assertEquals("LINK", WebElementNavigation.ELEMENT_LINK)
        assertEquals("LIST", WebElementNavigation.ELEMENT_LIST)
        assertEquals("LIST_ITEM", WebElementNavigation.ELEMENT_LIST_ITEM)
        assertEquals("CONTROL", WebElementNavigation.ELEMENT_CONTROL)
        assertEquals("BUTTON", WebElementNavigation.ELEMENT_BUTTON)
        assertEquals("CHECKBOX", WebElementNavigation.ELEMENT_CHECKBOX)
        assertEquals("RADIO", WebElementNavigation.ELEMENT_RADIO)
        assertEquals("TEXT_FIELD", WebElementNavigation.ELEMENT_TEXT_FIELD)
        assertEquals("COMBOBOX", WebElementNavigation.ELEMENT_COMBOBOX)
        assertEquals("GRAPHIC", WebElementNavigation.ELEMENT_GRAPHIC)
        assertEquals("TABLE", WebElementNavigation.ELEMENT_TABLE)
        assertEquals("LANDMARK", WebElementNavigation.ELEMENT_LANDMARK)
        assertEquals("FOCUSABLE", WebElementNavigation.ELEMENT_FOCUSABLE)
        assertEquals("VISITED_LINK", WebElementNavigation.ELEMENT_VISITED_LINK)
        assertEquals("UNVISITED_LINK", WebElementNavigation.ELEMENT_UNVISITED_LINK)
    }

    @Test
    fun navigation_mode_maps_to_element() {
        assertEquals(
            WebElementNavigation.ELEMENT_LINK,
            WebElementNavigation.elementFor(NavigationMode.LINKS)
        )
        assertEquals(
            WebElementNavigation.ELEMENT_HEADING,
            WebElementNavigation.elementFor(NavigationMode.HEADINGS)
        )
        assertEquals(
            WebElementNavigation.ELEMENT_CONTROL,
            WebElementNavigation.elementFor(NavigationMode.CONTROLS)
        )
    }

    /**
     * 无网页语义的导航返回 null → 调用方回退普通遍历
     */
    @Test
    fun non_web_modes_return_null() {
        assertNull(WebElementNavigation.elementFor(NavigationMode.DEFAULT))
        assertNull(WebElementNavigation.elementFor(NavigationMode.CHARACTERS))
        assertNull(WebElementNavigation.elementFor(NavigationMode.WORDS))
        assertNull(WebElementNavigation.elementFor(NavigationMode.SCROLLABLE_WIDGET))
        assertNull(WebElementNavigation.elementFor(NavigationMode.SCROLL_TOP))
    }

    @Test
    fun null_node_is_safe() {
        assertFalse(WebElementNavigation.isSupported(null))
        assertFalse(WebElementNavigation.navigate(null, WebElementNavigation.ELEMENT_LINK, true))
    }
}

class WebElementNavigatorTest {

    @Test
    fun try_navigate_rejects_non_web_mode_without_touching_cache() {
        val nav = WebElementNavigator()
        // 默认导航在网页里没有语义 → 直接 false，且不该产生缓存访问
        assertFalse(nav.tryNavigate(null, NavigationMode.DEFAULT, true))
        assertEquals(0L, nav.hits)
        assertEquals(0L, nav.misses)
    }

    @Test
    fun clear_resets_cache_and_bumps_epoch() {
        val nav = WebElementNavigator()
        val before = nav.epoch
        nav.clear()
        assertEquals(before + 1, nav.epoch)
    }

    @Test
    fun null_node_is_safe() {
        val nav = WebElementNavigator()
        assertFalse(nav.tryNavigate(null, NavigationMode.LINKS, true))
    }
}

class NavigationStoreTest {

    /**
     * ★ typeCode 是持久化的唯一依据 —— 必须稳定且唯一。
     *
     * 若两个导航共用同一个 typeCode，持久化会「恢复成另一个导航」，
     * 而且不会有任何报错。
     */
    @Test
    fun all_type_codes_are_unique() {
        val codes = NavigationMode.values().map { it.typeCode }
        val dup = codes.groupBy { it }.filter { it.value.size > 1 }
        assertTrue("存在重复的 typeCode：$dup", dup.isEmpty())
    }

    /**
     * 每个导航都能从自己的 typeCode 查回来（往返一致）
     */
    @Test
    fun type_code_round_trips() {
        NavigationMode.values().forEach { mode ->
            assertEquals(
                "「${mode.label}」typeCode 往返失败",
                mode,
                NavigationMode.fromTypeCode(mode.typeCode)
            )
        }
    }

    @Test
    fun unknown_type_code_returns_null() {
        assertNull(NavigationMode.fromTypeCode(9999))
        assertNull(NavigationMode.fromTypeCode(-1))
    }

    // ==================== restore 语义 ====================

    @Test
    fun restore_does_not_trigger_on_mode_changed() {
        val m = NavigationManager()
        var fired = 0
        m.onModeChanged = { fired++ }

        m.restore(NavigationMode.HEADINGS)

        assertEquals("恢复不该播报导航名", 0, fired)
        assertEquals(NavigationMode.HEADINGS, m.current)
    }

    @Test
    fun set_mode_does_trigger_on_mode_changed() {
        val m = NavigationManager()
        var fired = 0
        m.onModeChanged = { fired++ }

        m.setMode(NavigationMode.HEADINGS)

        assertEquals("用户主动切换必须播报", 1, fired)
    }

    @Test
    fun restore_same_mode_is_noop() {
        val m = NavigationManager()
        m.restore(NavigationMode.HEADINGS)
        m.restore(NavigationMode.HEADINGS)
        assertEquals(NavigationMode.HEADINGS, m.current)
    }

    /**
     * restore 不能恢复未实现的导航（防止存了脏值后卡在空实现上）
     */
    @Test
    fun restore_rejects_unimplemented_mode() {
        val m = NavigationManager()
        m.restore(NavigationMode.SELECTOR_OCR)   // implemented = false
        assertEquals(
            "未实现导航不该被恢复",
            NavigationMode.DEFAULT,
            m.current
        )
    }

    /**
     * 新导航必须是「已实现」的，否则用户切不过去
     */
    @Test
    fun new_scroll_jump_modes_are_implemented() {
        assertTrue(NavigationMode.SCROLL_TOP.implemented)
        assertTrue(NavigationMode.SCROLL_BOTTOM.implemented)
    }

    @Test
    fun scroll_jump_modes_are_in_switchable_list() {
        val switchable = NavigationMode.switchable
        assertTrue(switchable.contains(NavigationMode.SCROLL_TOP))
        assertTrue(switchable.contains(NavigationMode.SCROLL_BOTTOM))
    }

    @Test
    fun scroll_jump_kind_is_scroll_jump() {
        assertEquals(NavigationKind.SCROLL_JUMP, NavigationMode.SCROLL_TOP.kind)
        assertEquals(NavigationKind.SCROLL_JUMP, NavigationMode.SCROLL_BOTTOM.kind)
    }
}
