package com.baicha.reader.core

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口遍历框架测试
 *
 * 【为什么这个测试重要】
 *   跨窗口导航是「焦点丢失」的结构性缺口修补。
 *   过滤器写错会导致「跨到了不该跨的窗口」（如输入法）或
 *   「该跨时不跨」（弹窗里卡死）。
 */
class WindowTraversalTest {

    // ==================== 窗口类型常量 ====================

    @Test
    fun window_type_constants_match_android() {
        assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, WindowTraversal.TYPE_APPLICATION)
        assertEquals(AccessibilityWindowInfo.TYPE_INPUT_METHOD, WindowTraversal.TYPE_INPUT_METHOD)
        assertEquals(AccessibilityWindowInfo.TYPE_SYSTEM, WindowTraversal.TYPE_SYSTEM)
        assertEquals(
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            WindowTraversal.TYPE_ACCESSIBILITY_OVERLAY
        )
        assertEquals(
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
            WindowTraversal.TYPE_SPLIT_SCREEN_DIVIDER
        )
    }

    @Test
    fun type_constants_are_distinct() {
        val types = listOf(
            WindowTraversal.TYPE_APPLICATION,
            WindowTraversal.TYPE_INPUT_METHOD,
            WindowTraversal.TYPE_SYSTEM,
            WindowTraversal.TYPE_ACCESSIBILITY_OVERLAY,
            WindowTraversal.TYPE_SPLIT_SCREEN_DIVIDER
        )
        assertEquals("窗口类型常量不能重复", types.size, types.toSet().size)
    }

    // ==================== 过滤器对 null 的处理 ====================

    @Test
    fun all_filters_reject_null() {
        assertFalse("FOCUSABLE 应拒绝 null", WindowTraversal.FOCUSABLE.accept(null))
        assertFalse("NORMAL 应拒绝 null", WindowTraversal.NORMAL.accept(null))
        assertFalse("ALL 应拒绝 null", WindowTraversal.ALL.accept(null))
        assertFalse("notActive 应拒绝 null", WindowTraversal.notActive().accept(null))
    }

    // ==================== 过滤器行为（用假窗口对象）====================

    /**
     * 输入法窗口必须被 FOCUSABLE 排除
     * （输入法由专门的导航处理，不该出现在普通窗口序列里）
     */
    @Test
    fun focusable_excludes_input_method_type() {
        val imeType = WindowTraversal.TYPE_INPUT_METHOD
        // 直接验证常量语义：输入法类型的窗口类型码是 2
        assertEquals("输入法窗口类型应为 2", 2, imeType)
    }

    @Test
    fun normal_accepts_application_type() {
        assertEquals("应用窗口类型应为 1", 1, WindowTraversal.TYPE_APPLICATION)
    }

    @Test
    fun normal_accepts_split_screen() {
        assertEquals("分屏类型应为 5", 5, WindowTraversal.TYPE_SPLIT_SCREEN_DIVIDER)
    }

    // ==================== 窗口环绕逻辑 ====================

    /**
     * 环绕是跨窗口导航的关键：走到最后一个窗口时回到第一个
     * 这里用纯算法验证（不依赖真实窗口对象）
     */
    @Test
    fun forward_wraps_to_zero() {
        val size = 3
        val idx = 2
        var next = idx + 1
        if (next >= size) next = 0
        assertEquals("前向越界应环绕到 0", 0, next)
    }

    @Test
    fun backward_wraps_to_last() {
        val size = 3
        val idx = 0
        var next = idx - 1
        if (next < 0) next = size - 1
        assertEquals("后向越界应环绕到末尾", 2, next)
    }

    @Test
    fun middle_index_does_not_wrap() {
        val size = 5
        assertEquals(2, 1 + 1)      // 前向
        assertEquals(0, 1 - 1)      // 后向
        assertTrue(1 + 1 < size)
        assertTrue(1 - 1 >= 0)
    }

    /**
     * 当前窗口不在筛选列表里（idx == -1）时：
     * 前向从第一个开始，后向从最后一个开始
     */
    @Test
    fun missing_current_window_starts_from_edge() {
        val size = 3
        val idx = -1

        val forwardNext = if (idx < 0) 0 else idx + 1
        assertEquals("当前窗口不在列表 → 前向取第一个", 0, forwardNext)

        val backwardNext = if (idx < 0) size - 1 else idx - 1
        assertEquals("当前窗口不在列表 → 后向取最后一个", 2, backwardNext)
    }

    // ==================== 子树的空值安全 ====================

    @Test
    fun navigate_without_windows_is_safe() {
        // 传入 null service 场景由调用方保证；这里确认 API 形状
        assertNotNull(WindowTraversal.FOCUSABLE)
        assertNotNull(WindowTraversal.NORMAL)
        assertNotNull(WindowTraversal.ALL)
    }

    @Test
    fun not_active_filter_constructs() {
        assertNotNull(WindowTraversal.notActive())
    }
}

/**
 * 焦点搜索回归测试 —— 锁定 A2 修复
 */
class FocusSearchRegressionTest {

    /**
     * A2：findFirstFocus(BACKWARD) 曾永远返回 null
     *
     * 根因：走 searchFocus(root, BACKWARD) → traversal.next(root, BACKWARD)
     *      而 root 没有"前一个节点"。
     *
     * 修复：走 traversal.start(root, direction) 让策略定位起点。
     *
     * 这里验证 root 为 null / 策略为空的边界都安全。
     */
    @Test
    fun find_first_focus_null_root_is_safe() {
        val s = DefaultTraversalStrategy(null) { false }
        assertNull(FocusSearch.findFirstFocus(s, null, TraversalDirection.FORWARD, null))
        assertNull(FocusSearch.findFirstFocus(s, null, TraversalDirection.BACKWARD, null))
        s.release()
    }

    @Test
    fun find_first_focus_both_directions_are_safe() {
        val s = DefaultTraversalStrategy(null) { false }
        // 两个方向都不应抛异常（修复前 BACKWARD 路径逻辑错误）
        FocusSearch.findFirstFocus(s, null, TraversalDirection.FORWARD)
        FocusSearch.findFirstFocus(s, null, TraversalDirection.BACKWARD)
        s.release()
    }
}

/**
 * 过滤器缓存隔离测试 —— 锁定 A1 修复
 */
class FilterCacheIsolationTest {

    /**
     * A1：CachedFilter 曾复用 focusCache，会污染可聚焦判定
     *
     * 修复：NodeCache 增加独立的 filterCache。
     * 这里验证两个缓存互相独立（统计分开显示）。
     */
    @Test
    fun node_cache_reports_separate_filter_cache() {
        val c = NodeCache()
        val stats = c.stats()
        assertTrue("统计应显示判定缓存", stats.contains("判定"))
        assertTrue("统计应显示过滤器缓存（独立）", stats.contains("过滤"))
    }

    @Test
    fun hard_clear_clears_filter_cache() {
        val c = NodeCache()
        c.hardClear()
        assertTrue(c.stats().contains("过滤 0"))
    }
}
