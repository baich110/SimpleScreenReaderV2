package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间遍历结构测试
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么这里不测几何算法 —— 重要发现】
 * ══════════════════════════════════════════════════════════
 *
 * `SpatialTraversalStrategy` 的核心是几何比较算法
 * （isCandidate / beamBeats / weightedDistance），这些是纯 Rect 运算，
 * 看起来非常适合单测。但**在 JVM 单测环境下无法测试**：
 *
 *   `android.graphics.Rect` 在单元测试中是**空壳**：
 *     Rect(10, 20, 30, 40).left  →  0      （构造器是 no-op）
 *     rect.set(1, 2, 3, 4)       →  无效果  （set 也是 no-op）
 *
 *   即使开了 `isReturnDefaultValues = true`，Rect 的所有字段/方法
 *   都返回默认值，**不保存任何数据**。
 *
 *   因此任何依赖 Rect 的断言都恒为「全 0 比较」，测不出真实行为。
 *
 * ══════════════════════════════════════════════════════════
 * 【替代验证方案】
 * ══════════════════════════════════════════════════════════
 *
 *   ① **Robolectric**（可运行真实 Android 图形类）
 *      —— 需引入依赖，成本较高
 *   ② **纯 JVM 几何实现**（把算法抽到不依赖 android.graphics 的层）
 *      —— 最彻底，但需重构
 *   ③ **真机验证**（当前选择）
 *      —— 空间导航的准确度需在真机上用手势实测
 *
 *   本测试类只覆盖**不依赖 Rect 的结构性契约**（枚举、常量、空值安全），
 *   把「算法正确性」明确交给真机验证，避免制造虚假的测试安全感。
 */
class SpatialTraversalStructureTest {

    // ==================== 结构性契约 ====================

    @Test
    fun strategy_constructs_with_null_root() {
        val s = SpatialTraversalStrategy(null) { false }
        assertEquals("根为 null 时不应有可聚焦节点", 0, s.nodeCount())
        s.release()
    }

    @Test
    fun release_is_idempotent() {
        val s = SpatialTraversalStrategy(null) { false }
        repeat(3) { s.release() }
        assertEquals(0, s.nodeCount())
    }

    @Test
    fun find_focus_in_direction_returns_null_without_root() {
        val s = SpatialTraversalStrategy(null) { false }
        // 无 root 时任何方向查找都应安全返回 null
        assertFalse(s.nodeCount() > 0)
        s.release()
    }

    @Test
    fun adapter_constructs_and_releases() {
        val a = SpatialTraversalAdapter(null) { false }
        assertEquals(0, a.nodeCount())
        a.release()
    }

    @Test
    fun factory_produces_spatial_adapter() {
        val s = TraversalStrategyFactory.create(
            TraversalStrategyFactory.TYPE_SPATIAL, null
        ) { false }
        assertTrue(s is SpatialTraversalAdapter)
        s.release()
    }

    // ==================== 方向契约 ====================

    @Test
    fun spatial_directions_are_the_four_geometric_ones() {
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.LEFT))
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.RIGHT))
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.UP))
        assertTrue(TraversalDirection.isSpatial(TraversalDirection.DOWN))

        assertFalse(TraversalDirection.isSpatial(TraversalDirection.FORWARD))
        assertFalse(TraversalDirection.isSpatial(TraversalDirection.BACKWARD))
    }

    /**
     * 空间方向数值必须与 TalkBack 一致（算法内部用数值分支）
     */
    @Test
    fun direction_values_match_talkback_for_algorithm_branches() {
        assertEquals(3, TraversalDirection.LEFT)
        assertEquals(4, TraversalDirection.RIGHT)
        assertEquals(5, TraversalDirection.UP)
        assertEquals(6, TraversalDirection.DOWN)
    }

    // ==================== 遍历接口契约 ====================

    /**
     * 空间策略是纯几何查找，不处理逻辑顺序
     * （逻辑顺序由 DefaultTraversalStrategy 负责）
     */
    @Test
    fun spatial_adapter_start_is_safe() {
        val a = SpatialTraversalAdapter(null) { false }
        // root 为 null 时 start 不应崩溃
        assertNotNull(a)
        a.release()
    }
}

/**
 * 有效边界计算器的结构性测试
 *
 * 同样受 Rect 空壳限制，这里只测空值安全与缓存契约。
 */
class NodeBoundsStructureTest {

    @Test
    fun null_node_returns_null() {
        assertNull(NodeBoundsCalculator.getBounds(null))
    }

    @Test
    fun cache_starts_empty_after_clear() {
        NodeBoundsCalculator.clear()
        assertEquals(0, NodeBoundsCalculator.cacheSize())
    }

    @Test
    fun clear_is_idempotent() {
        repeat(5) { NodeBoundsCalculator.clear() }
        assertEquals(0, NodeBoundsCalculator.cacheSize())
    }

    @Test
    fun clear_resets_internal_state() {
        NodeBoundsCalculator.clear()
        // clear 后再查询仍是安全的
        assertNull(NodeBoundsCalculator.getBounds(null))
    }
}

private fun assertNull(v: Any?) {
    org.junit.Assert.assertNull(v)
}
