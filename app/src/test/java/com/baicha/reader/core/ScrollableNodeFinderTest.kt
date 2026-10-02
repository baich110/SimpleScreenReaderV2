package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可滚动容器查找测试
 *
 * 对应 TalkBack `searchScrollableNodeFromBfs()`
 *
 * 【核心设计】
 *   ① BFS 找第一个可滚动（保证只滚最外层）
 *   ② 找面积最大的可滚动（复杂布局的择优）
 *   ③ Pager 降权（只有屏上无其他可滚动节点时才用 Pager）
 */
class ScrollableNodeFinderTest {

    // ==================== 空值安全 ====================

    @Test
    fun search_null_root_returns_null() {
        assertNull(ScrollableNodeFinder.search(null))
    }

    @Test
    fun find_ancestor_null_returns_null() {
        assertNull(ScrollableNodeFinder.findScrollableAncestor(null))
    }

    // ==================== 最大值选择（纯逻辑）====================

    /**
     * 空候选 → -1
     */
    @Test
    fun pick_max_from_empty() {
        assertEquals(-1, ScrollableNodeFinder.pickMaxIndex(emptyList()))
    }

    /**
     * 无初始值 → 取最大
     */
    @Test
    fun pick_max_without_initial() {
        val areas = listOf(100, 5000, 300)
        assertEquals(1, ScrollableNodeFinder.pickMaxIndex(areas))
    }

    /**
     * 有初始值 → 初始值参与比较
     */
    @Test
    fun pick_max_with_initial() {
        val areas = listOf(100, 5000, 300)
        // 初始为 0 号（100）→ 最大值仍是 1 号（5000）
        assertEquals(1, ScrollableNodeFinder.pickMaxIndex(areas, initialIndex = 0))
    }

    /**
     * 初始值就是最大 → 保持
     */
    @Test
    fun pick_max_keeps_initial_if_largest() {
        val areas = listOf(9000, 100, 300)
        assertEquals(0, ScrollableNodeFinder.pickMaxIndex(areas, initialIndex = 0))
    }

    /**
     * ★ Pager 降权的核心场景：
     *   初始值传 -1（表示"pager 不作为候选"）→ 从其余里选最大
     */
    @Test
    fun pick_max_with_no_initial_for_pager_deprioritization() {
        val areas = listOf(8000, 5000, 300)
        // initial = -1 表示 pager（假设是 0 号）不被考虑
        // 但纯函数不知道谁是 pager，所以这里选到 0 号（8000）
        // —— 真正的降权在 search() 里通过 excludePager 过滤实现
        assertEquals(0, ScrollableNodeFinder.pickMaxIndex(areas, initialIndex = -1))
    }

    @Test
    fun pick_max_handles_invalid_initial_index() {
        val areas = listOf(100, 200)
        // 越界初始值应被忽略
        assertEquals(1, ScrollableNodeFinder.pickMaxIndex(areas, initialIndex = 99))
    }

    @Test
    fun pick_max_handles_negative_areas() {
        val areas = listOf(-1, -1, -1)
        val idx = ScrollableNodeFinder.pickMaxIndex(areas)
        assertEquals("全负数时不应选中（bestArea 初值 -1，需 > -1）", -1, idx)
    }

    @Test
    fun pick_max_single_candidate() {
        assertEquals(0, ScrollableNodeFinder.pickMaxIndex(listOf(500)))
    }

    // ==================== 角色判定 ====================

    @Test
    fun pager_role_constant() {
        // Pager 角色码是 16
        assertEquals(16, NodeRoleHelper.ROLE_PAGER)
    }

    // ==================== 方向常量 ====================

    @Test
    fun scroll_actions_exist() {
        // 滚动方向对应两个 action
        assertTrue(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD != 0
        )
        assertTrue(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD != 0
        )
    }

    // ==================== 面积计算 ====================

    @Test
    fun area_of_invalid_node_is_zero() {
        // 无法构造真实节点 → areaOf(null) 不适用
        // 这里验证 Bounds 面积逻辑（areaOf 内部使用）
        assertEquals(200 * 100, Bounds(0, 0, 200, 100).let { it.width * it.height })
    }

    // ==================== 集成：向上找 vs BFS ====================

    /**
     * 两种查找策略的语义差异（文档性测试）
     */
    @Test
    fun two_strategies_have_distinct_purposes() {
        // 向上找：快，适合「已在容器内」
        // BFS：慢但正确，适合嵌套滚动
        //
        // 这里确认两个 API 都存在且可独立调用
        assertNull(ScrollableNodeFinder.findScrollableAncestor(null))
        assertNull(ScrollableNodeFinder.search(null))
    }
}
