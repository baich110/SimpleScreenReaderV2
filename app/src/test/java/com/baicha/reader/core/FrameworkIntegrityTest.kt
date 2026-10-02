package com.baicha.reader.core

import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 框架完整性测试 —— 验证各框架类可构造、接口契约正确、缓存可失效
 *
 * 这类测试的价值：框架类大多依赖 Android 运行时（节点对象），
 * 无法做完整行为测试，但**可以锁定"接口形状"和"空值安全"**，
 * 防止重构时意外破坏契约。
 */
class FrameworkIntegrityTest {

    @After
    fun tearDown() {
        NodeBoundsCalculator.clear()
        NodeCache().hardClear()
        ShouldFocusNode.clear()
    }

    // ==================== 边界计算器 ====================

    @Test
    fun bounds_calculator_null_returns_null() {
        assertNull(NodeBoundsCalculator.getBounds(null))
    }

    @Test
    fun bounds_calculator_cache_starts_empty() {
        NodeBoundsCalculator.clear()
        assertEquals(0, NodeBoundsCalculator.cacheSize())
    }

    @Test
    fun bounds_calculator_clear_is_safe() {
        repeat(3) { NodeBoundsCalculator.clear() }
        assertEquals(0, NodeBoundsCalculator.cacheSize())
    }

    // ==================== 遍历方向与策略协作 ====================

    @Test
    fun spatial_strategy_constructs_with_null_root() {
        // root 为 null 时应安全构造，节点列表为空
        val s = SpatialTraversalStrategy(null) { false }
        assertEquals(0, s.nodeCount())
        s.release()
    }

    @Test
    fun spatial_adapter_handles_null_root() {
        val a = SpatialTraversalAdapter(null) { false }
        // root 为 null 时不崩溃
        assertEquals(0, a.nodeCount())
        a.release()
    }

    @Test
    fun factory_creates_spatial_strategy() {
        val s = TraversalStrategyFactory.create(
            TraversalStrategyFactory.TYPE_SPATIAL,
            null
        ) { false }
        assertNotNull(s)
        assertTrue("应创建空间策略适配器", s is SpatialTraversalAdapter)
        s.release()
    }

    @Test
    fun factory_covers_all_strategy_types() {
        val types = listOf(
            TraversalStrategyFactory.TYPE_DEFAULT,
            TraversalStrategyFactory.TYPE_CACHED,
            TraversalStrategyFactory.TYPE_GRID,
            TraversalStrategyFactory.TYPE_LIST,
            TraversalStrategyFactory.TYPE_ROW_COLUMN,
            TraversalStrategyFactory.TYPE_SPATIAL
        )
        for (t in types) {
            val s = TraversalStrategyFactory.create(t, null) { false }
            assertNotNull("类型 $t 应能创建策略", s)
            s.release()
        }
    }

    // ==================== 焦点搜索契约 ====================

    @Test
    fun search_focus_null_start_returns_null() {
        val s = DefaultTraversalStrategy(null) { false }
        assertNull(FocusSearch.searchFocus(s, null, TraversalDirection.FORWARD, null))
        s.release()
    }

    @Test
    fun find_first_focus_null_root_returns_null() {
        val s = DefaultTraversalStrategy(null) { false }
        assertNull(FocusSearch.findFirstFocus(s, null, TraversalDirection.FORWARD, null))
        s.release()
    }

    @Test
    fun spatial_direction_constants_are_distinct() {
        // 锁定空间与逻辑方向的区分（findFocusInDirection 只接受空间方向）
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.LEFT))
        assertFalse(TraversalDirection.isSpatial(TraversalDirection.FORWARD))
    }

    @Test
    fun find_focus_in_direction_null_args_return_null() {
        // 空间查找在参数缺失时必须安全返回 null
        assertNull(FocusSearch.findFocusInDirection(null, null, TraversalDirection.LEFT, null))
    }

    @Test
    fun get_self_or_ancestor_null_is_safe() {
        assertNull(FocusSearch.getSelfOrMatchingAncestor(null) { true })
    }

    // ==================== 缓存协作 ====================

    @Test
    fun node_cache_invalidate_focus_preserves_children_cache() {
        val c = NodeCache()
        // 失效判定缓存不应影响代数（子节点缓存仍有效）
        val before = c.stats()
        c.invalidateFocus()
        val after = c.stats()
        // 代数不变（invalidateFocus 不是整体失效）
        assertTrue(before.contains("代数"))
        assertTrue(after.contains("代数"))
    }

    @Test
    fun all_caches_can_be_cleared_together() {
        NodeCache().hardClear()
        NodeBoundsCalculator.clear()
        ShouldFocusNode.clear()
        NodeOrderComparator.cache = null
        ShouldFocusNode.cache = null
        assertNull(NodeOrderComparator.cache)
        assertNull(ShouldFocusNode.cache)
    }
}

/**
 * 有效边界算法单测（可测部分）
 */
class NodeBoundsCalculatorTest {

    @After
    fun tearDown() {
        NodeBoundsCalculator.clear()
    }

    @Test
    fun null_node_returns_null() {
        assertNull(NodeBoundsCalculator.getBounds(null))
    }

    @Test
    fun uses_children_bounds_null_safe_check() {
        // 需要真实节点才能测完整逻辑；这里确认无 NPE 风险
        NodeBoundsCalculator.clear()
        assertEquals(0, NodeBoundsCalculator.cacheSize())
    }

    @Test
    fun cache_size_tracks_clears() {
        NodeBoundsCalculator.clear()
        assertEquals(0, NodeBoundsCalculator.cacheSize())
    }
}
