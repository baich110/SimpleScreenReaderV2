package com.baicha.reader.core

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯几何类型测试
 *
 * 这是「可测几何」的基础设施 —— 它的正确性决定了
 * SpatialTraversal / NodeBoundsCalculator 的测试是否可信。
 */
class BoundsTest {

    // ==================== 基础属性 ====================

    @Test
    fun width_and_height() {
        val b = Bounds(10, 20, 110, 220)
        assertEquals(100, b.width)
        assertEquals(200, b.height)
    }

    @Test
    fun center() {
        val b = Bounds(0, 0, 100, 200)
        assertEquals(50, b.centerX)
        assertEquals(100, b.centerY)
    }

    @Test
    fun isEmpty_for_degenerate_rects() {
        assertTrue(Bounds(0, 0, 0, 0).isEmpty)
        assertTrue(Bounds(10, 10, 10, 20).isEmpty)   // 宽为 0
        assertTrue(Bounds(10, 10, 20, 10).isEmpty)   // 高为 0
        assertTrue(Bounds(20, 20, 10, 10).isEmpty)   // 反向
        assertFalse(Bounds(0, 0, 1, 1).isEmpty)
    }

    // ==================== 数据类语义 ====================

    @Test
    fun equality_by_value() {
        assertEquals(Bounds(1, 2, 3, 4), Bounds(1, 2, 3, 4))
        assertNotEquals(Bounds(1, 2, 3, 4), Bounds(1, 2, 3, 5))
    }

    @Test
    fun to_string_is_readable() {
        assertTrue(Bounds(1, 2, 3, 4).toString().contains("1"))
    }

    // ==================== 包含判定 ====================

    @Test
    fun contains_bounds() {
        val outer = Bounds(0, 0, 100, 100)
        assertTrue(outer.contains(Bounds(10, 10, 50, 50)))
        assertTrue(outer.contains(outer))                    // 自包含
        assertFalse(outer.contains(Bounds(50, 50, 150, 150))) // 超出
    }

    @Test
    fun contains_point() {
        val b = Bounds(10, 10, 20, 20)
        assertTrue(b.contains(15, 15))
        assertTrue(b.contains(10, 10))        // 左上是包含的
        assertFalse(b.contains(20, 20))       // 右下不含（半开区间）
        assertFalse(b.contains(5, 15))
    }

    // ==================== 相交判定 ====================

    @Test
    fun intersects() {
        val a = Bounds(0, 0, 100, 100)
        assertTrue(a.intersects(Bounds(50, 50, 150, 150)))
        assertFalse(a.intersects(Bounds(200, 200, 300, 300)))
        assertFalse(a.intersects(a.offset(100, 0)))   // 仅边界接触不算相交
    }

    @Test
    fun intersects_horizontally() {
        val a = Bounds(0, 0, 100, 100)
        assertTrue(a.intersectsHorizontally(Bounds(50, 500, 150, 600)))
        assertFalse(a.intersectsHorizontally(Bounds(200, 0, 300, 100)))
    }

    @Test
    fun intersects_vertically() {
        val a = Bounds(0, 0, 100, 100)
        assertTrue(a.intersectsVertically(Bounds(500, 50, 600, 150)))
        assertFalse(a.intersectsVertically(Bounds(0, 200, 100, 300)))
    }

    // ==================== 天坦的 60% 重叠判定 ====================

    /**
     * 对应天坦 `a.t()`：垂直重叠度 >= 60%
     */
    @Test
    fun overlaps_vertically_by_60() {
        val a = Bounds(0, 0, 100, 100)      // 高 100

        // 重叠 80 → 80% >= 60% → true
        assertTrue(a.overlapsVerticallyBy60(Bounds(0, 20, 100, 120)))

        // 重叠 40 → 40% < 60% → false
        assertFalse(a.overlapsVerticallyBy60(Bounds(0, 60, 100, 160)))
    }

    @Test
    fun overlaps_vertically_by_60_requires_intersection() {
        val a = Bounds(0, 0, 100, 100)
        assertFalse(a.overlapsVerticallyBy60(Bounds(0, 200, 100, 300)))
    }

    // ==================== 变换 ====================

    @Test
    fun inset_positive_shrinks() {
        val b = Bounds(0, 0, 100, 100).inset(10, 20)
        assertEquals(Bounds(10, 20, 90, 80), b)
    }

    @Test
    fun inset_negative_expands() {
        val b = Bounds(10, 10, 90, 90).inset(-10, -10)
        assertEquals(Bounds(0, 0, 100, 100), b)
    }

    @Test
    fun offset_translates() {
        val b = Bounds(0, 0, 100, 100).offset(10, 20)
        assertEquals(Bounds(10, 20, 110, 120), b)
        // 尺寸不变
        assertEquals(100, b.width)
    }

    // ==================== 集合运算 ====================

    @Test
    fun intersect_with() {
        val a = Bounds(0, 0, 100, 100)
        val b = Bounds(50, 50, 150, 150)
        assertEquals(Bounds(50, 50, 100, 100), a.intersectWith(b))
    }

    @Test
    fun intersect_with_disjoint_returns_empty() {
        val a = Bounds(0, 0, 100, 100)
        val b = Bounds(200, 200, 300, 300)
        assertEquals(Bounds.EMPTY, a.intersectWith(b))
    }

    @Test
    fun union_with() {
        val a = Bounds(0, 0, 100, 100)
        val b = Bounds(50, 50, 150, 150)
        assertEquals(Bounds(0, 0, 150, 150), a.unionWith(b))
    }

    @Test
    fun center_distance_to() {
        val a = Bounds(0, 0, 100, 100)     // center (50,50)
        val b = Bounds(100, 0, 200, 100)   // center (150,50)
        assertEquals(100, a.centerDistanceTo(b))
    }

    // ==================== Android Rect 互转 ====================

    /**
     * ⚠️ 注意：在 JVM 单测中 `android.graphics.Rect` 是空壳，
     * 所以这里只验证「转换路径不崩溃」，不验证数值。
     * 真实数值需在真机上验证。
     */
    @Test
    fun to_rect_does_not_crash() {
        val b = Bounds(1, 2, 3, 4)
        val r = b.toRect()
        // JVM 环境下 Rect 是空壳，这里只确认不抛异常
        assertNotEquals(null, r)
    }

    @Test
    fun from_null_rect_returns_null() {
        assertEquals(null, Bounds.from(null))
    }

    @Test
    fun from_rect_does_not_crash() {
        // 空壳 Rect → 转换后应是全 0（不崩溃即可）
        val b = Bounds.from(Rect())
        assertNotEquals(null, b)
    }
}

/**
 * 边界累加器测试 —— 用于「子节点并集」计算
 */
class BoundsAccumulatorTest {

    @Test
    fun empty_accumulator_has_no_value() {
        val acc = BoundsAccumulator()
        assertFalse(acc.hasValue)
        assertEquals(Bounds.EMPTY, acc.toBounds())
    }

    @Test
    fun accumulates_union() {
        val acc = BoundsAccumulator()
        assertTrue(acc.add(Bounds(10, 10, 20, 20)))
        assertTrue(acc.add(Bounds(0, 0, 5, 5)))
        assertTrue(acc.add(Bounds(30, 30, 40, 40)))

        assertEquals(Bounds(0, 0, 40, 40), acc.toBounds())
    }

    @Test
    fun ignores_empty_bounds() {
        val acc = BoundsAccumulator()
        assertFalse(acc.add(Bounds(0, 0, 0, 0)))
        assertFalse(acc.hasValue)
    }

    /**
     * 裁剪到外边界 —— 对应天坦的
     * `bounds.top = max(minTop, bounds.top)` 等四行
     */
    @Test
    fun clamp_to_outer() {
        val acc = BoundsAccumulator()
        acc.add(Bounds(10, 10, 50, 50))     // 部分超出外框
        val clamped = acc.clampTo(Bounds(0, 0, 30, 30))
        assertEquals(Bounds(10, 10, 30, 30), clamped)
    }

    @Test
    fun clamp_without_value_returns_outer() {
        val acc = BoundsAccumulator()
        assertEquals(Bounds(0, 0, 10, 10), acc.clampTo(Bounds(0, 0, 10, 10)))
    }

    @Test
    fun reset_clears_state() {
        val acc = BoundsAccumulator()
        acc.add(Bounds(1, 1, 2, 2))
        assertTrue(acc.hasValue)
        acc.reset()
        assertFalse(acc.hasValue)
        assertEquals(Bounds.EMPTY, acc.toBounds())
    }
}

/**
 * 有效边界裁剪算法测试 —— TalkBack `NodeCachedBoundsCalculator` 的核心
 *
 * 【为什么这个算法重要】
 *   一个铺满屏幕的容器里只放几个小按钮时，按容器自身边界做几何导航
 *   会选到那个大容器而不是用户看到的按钮。
 *   有效边界 = 子节点并集 ∩ 自身边界。
 */
class EffectiveBoundsTest {

    private fun acc(vararg rects: Bounds): BoundsAccumulator {
        val a = BoundsAccumulator()
        rects.forEach { a.add(it) }
        return a
    }

    /**
     * 子节点完全在内部 → 用子节点并集
     */
    @Test
    fun clamps_to_children_when_inside() {
        val own = Bounds(0, 0, 1000, 1000)
        val children = acc(Bounds(100, 100, 200, 200), Bounds(300, 300, 400, 400))

        val result = NodeBoundsCalculator.clampChildBounds(own, children)
        assertEquals("应裁剪到子节点并集", Bounds(100, 100, 400, 400), result)
    }

    /**
     * 子节点超出自身边界 → 裁剪到自身
     */
    @Test
    fun clamps_to_own_when_children_overflow() {
        val own = Bounds(100, 100, 200, 200)
        val children = acc(Bounds(0, 0, 300, 300))

        val result = NodeBoundsCalculator.clampChildBounds(own, children)
        assertEquals("超出部分应裁掉", Bounds(100, 100, 200, 200), result)
    }

    /**
     * 部分重叠 → 取交集
     */
    @Test
    fun clamps_partial_overlap() {
        val own = Bounds(0, 0, 100, 100)
        val children = acc(Bounds(50, 50, 150, 150))

        val result = NodeBoundsCalculator.clampChildBounds(own, children)
        assertEquals(Bounds(50, 50, 100, 100), result)
    }

    /**
     * 没有子节点边界 → 返回自身（不裁剪）
     */
    @Test
    fun no_children_returns_own_bounds() {
        val own = Bounds(0, 0, 100, 100)
        val empty = BoundsAccumulator()

        assertEquals(own, NodeBoundsCalculator.clampChildBounds(own, empty))
    }

    /**
     * 子节点全为空矩形 → 等同没有子节点
     */
    @Test
    fun empty_children_returns_own_bounds() {
        val own = Bounds(0, 0, 100, 100)
        val children = acc(Bounds(0, 0, 0, 0))

        assertEquals(own, NodeBoundsCalculator.clampChildBounds(own, children))
    }

    /**
     * 多个子节点分散在不同角落 → 并集覆盖全部
     */
    @Test
    fun union_covers_all_children() {
        val own = Bounds(0, 0, 1000, 1000)
        val children = acc(
            Bounds(10, 10, 50, 50),       // 左上
            Bounds(900, 900, 990, 990)    // 右下
        )

        val result = NodeBoundsCalculator.clampChildBounds(own, children)
        assertEquals(Bounds(10, 10, 990, 990), result)
    }
}
