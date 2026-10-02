package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口跨窗口导航 —— **真实行为测试**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么此前测不了】
 * ══════════════════════════════════════════════════════════
 *
 * 原实现在 `WindowTraversal.navigateToNextWindow(service, ...)` 内部
 * 直接操作 `AccessibilityWindowInfo` 列表。而该类是
 *
 * ```java
 *   public final class android.view.accessibility.AccessibilityWindowInfo
 * ```
 *
 * **final** —— 既不能实例化（JVM 里构造器返回 null），
 * 也不能像 `AccessibilityNodeInfo` 那样子类化。
 * 于是「环绕 / 越界 / 当前窗口不在候选列表」这些边界
 * 只能靠读代码，无法验证。
 *
 * 本轮把算法抽到 [WindowOrdering]（纯 Int 列表运算），
 * 首次可做真实断言。
 *
 * ══════════════════════════════════════════════════════════
 * 【对照的既有测试】
 * ══════════════════════════════════════════════════════════
 *
 * `WindowTraversalTest.middle_index_does_not_wrap` 里的做法是：
 * ```kotlin
 *   @Test fun middle_index_does_not_wrap() {
 *       val size = 5
 *       assertEquals(2, 1 + 1)      // ← 在测「1+1 == 2」
 *       assertEquals(0, 1 - 1)
 *       assertTrue(1 + 1 < size)
 *   }
 * ```
 * 它**完全没有调用被测代码**，只验证了算术恒等式，
 * 永远不可能失败。本类取代它。
 */
class WindowOrderingTest {

    private val w1 = 1
    private val w2 = 2
    private val w3 = 3

    // ==================== 前向 ====================

    @Test
    fun forward_advances_by_one() {
        assertEquals(1, WindowOrdering.nextIndex(w1, listOf(w1, w2, w3), forward = true))
    }

    /** 末尾环绕到开头 */
    @Test
    fun forward_wraps_from_last_to_first() {
        assertEquals(0, WindowOrdering.nextIndex(w3, listOf(w1, w2, w3), forward = true))
    }

    // ==================== 后向 ====================

    @Test
    fun backward_moves_back_by_one() {
        assertEquals(1, WindowOrdering.nextIndex(w3, listOf(w1, w2, w3), forward = false))
    }

    /** 开头环绕到末尾 */
    @Test
    fun backward_wraps_from_first_to_last() {
        assertEquals(2, WindowOrdering.nextIndex(w1, listOf(w1, w2, w3), forward = false))
    }

    // ==================== 单窗口 ====================

    /** 只有一个窗口时，两个方向都环绕回它自己（下标 0） */
    @Test
    fun single_window_wraps_to_itself() {
        assertEquals(0, WindowOrdering.nextIndex(w1, listOf(w1), forward = true))
        assertEquals(0, WindowOrdering.nextIndex(w1, listOf(w1), forward = false))
    }

    @Test
    fun empty_list_returns_minus_one() {
        assertEquals(-1, WindowOrdering.nextIndex(w1, emptyList(), forward = true))
        assertEquals(-1, WindowOrdering.nextIndex(w1, emptyList(), forward = false))
    }

    // ==================== 当前窗口不在候选列表里 ====================

    /**
     * ★ 易错点：当前窗口**被过滤掉**（如它没有可聚焦内容）时，
     * 前向必须从**第一个**开始，后向必须从**最后一个**开始。
     *
     * 这类场景真机上很常见：当前焦点在输入法窗口，
     * 而 `applicationWindows` 过滤器把输入法排除了。
     */
    @Test
    fun current_not_in_list_forward_starts_from_first() {
        assertEquals(0, WindowOrdering.nextIndex(999, listOf(w1, w2, w3), forward = true))
    }

    @Test
    fun current_not_in_list_backward_starts_from_last() {
        assertEquals(2, WindowOrdering.nextIndex(999, listOf(w1, w2, w3), forward = false))
    }

    // ==================== 提前返回条件 ====================

    @Test
    fun single_current_window_is_detected() {
        assertTrue(
            "只有当前窗口一个 → 应提前返回，不跨窗口",
            WindowOrdering.isSingleCurrentWindow(w1, listOf(w1))
        )
    }

    /**
     * ★ 关键：判断依据是**筛选后**的列表。
     * 屏幕上有 3 个窗口、但只有 1 个可聚焦且它就是当前窗口 → 仍应提前返回。
     */
    @Test
    fun single_current_window_uses_filtered_list() {
        assertTrue(
            "筛选后只剩当前窗口一个 → 提前返回",
            WindowOrdering.isSingleCurrentWindow(w1, listOf(w1))
        )
        assertFalse(
            "筛选后有 2 个 → 不提前返回",
            WindowOrdering.isSingleCurrentWindow(w1, listOf(w1, w2))
        )
    }

    @Test
    fun single_window_but_not_current_is_not_early_return() {
        assertFalse(
            "只有一个窗口但不是当前窗口 → 应跨过去",
            WindowOrdering.isSingleCurrentWindow(w1, listOf(w2))
        )
    }

    @Test
    fun empty_list_is_not_single_current() {
        assertFalse(WindowOrdering.isSingleCurrentWindow(w1, emptyList()))
    }

    // ==================== 层级排序 ====================

    /**
     * 按 layer 升序（layer 越大越靠上/越靠后）
     *
     * 依据 `WindowTraversal.sortedWindows()`：`getWindows()` 返回顺序
     * 不保证稳定，必须自己排。
     */
    @Test
    fun sort_by_layer_ascending() {
        val input = listOf(10 to 3, 20 to 1, 30 to 2)   // id to layer
        assertEquals(
            "应按 layer 升序：1(id20) → 2(id30) → 3(id10)",
            listOf(20, 30, 10),
            WindowOrdering.sortByLayer(input)
        )
    }

    /**
     * layer 相同时必须**保持原相对顺序**（`sortedBy` 是稳定排序）
     *
     * 这条很重要：若不稳定，同层窗口的跨窗口方向会随机翻转。
     */
    @Test
    fun sort_by_layer_is_stable_for_equal_layers() {
        val input = listOf(10 to 5, 20 to 5, 30 to 5)
        assertEquals("同 layer 应保持原顺序", listOf(10, 20, 30), WindowOrdering.sortByLayer(input))
    }

    @Test
    fun sort_empty_is_empty() {
        assertEquals(emptyList<Int>(), WindowOrdering.sortByLayer(emptyList()))
    }

    // ==================== 与真实序列的端到端走查 ====================

    /**
     * 端到端：三个窗口连续前向 3 次应回到起点（环绕闭合）
     */
    @Test
    fun forward_full_cycle_returns_to_start() {
        val ids = listOf(w1, w2, w3)
        var cur = w1
        repeat(3) { cur = ids[WindowOrdering.nextIndex(cur, ids, forward = true)] }
        assertEquals("前向走满一圈应回到起点", w1, cur)
    }

    @Test
    fun backward_full_cycle_returns_to_start() {
        val ids = listOf(w1, w2, w3)
        var cur = w1
        repeat(3) { cur = ids[WindowOrdering.nextIndex(cur, ids, forward = false)] }
        assertEquals("后向走满一圈应回到起点", w1, cur)
    }

    /** 前向一次 + 后向一次 = 回到原处 */
    @Test
    fun forward_then_backward_is_identity() {
        val ids = listOf(w1, w2, w3)
        val afterForward = ids[WindowOrdering.nextIndex(w1, ids, forward = true)]
        val afterBack = ids[WindowOrdering.nextIndex(afterForward, ids, forward = false)]
        assertEquals("前向再后向应回到起点", w1, afterBack)
    }
}
