package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间遍历几何算法测试 —— 对照 TalkBack DirectionalTraversalStrategy
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么现在能测了】
 * ══════════════════════════════════════════════════════════
 *
 * 上一轮发现 `android.graphics.Rect` 在 JVM 单测中是空壳
 * （构造器与 set() 都是 no-op），导致几何算法无法测试。
 *
 * 本轮把算法的参数类型从 `android.graphics.Rect` 换成纯 JVM 的
 * [Bounds]（`data class`），边界处只做一次转换。
 * 现在这些算法**可以真正测试了**。
 */
class SpatialAlgorithmTest {

    private val strategy = SpatialTraversalStrategy(null) { false }

    private fun callBool(name: String, vararg args: Any?): Boolean {
        val m = strategy.javaClass.declaredMethods.first { it.name == name }
        m.isAccessible = true
        return m.invoke(strategy, *args) as Boolean
    }

    private fun callInt(name: String, vararg args: Any?): Int {
        val m = strategy.javaClass.declaredMethods.first { it.name == name }
        m.isAccessible = true
        return m.invoke(strategy, *args) as Int
    }

    private fun b(l: Int, t: Int, r: Int, bt: Int) = Bounds(l, t, r, bt)

    // ==================== isCandidate ====================

    @Test
    fun candidate_left_requires_target_on_left() {
        val src = b(100, 0, 200, 100)
        assertTrue(callBool("isCandidate", src, b(0, 0, 50, 100), TraversalDirection.LEFT))
        assertFalse(callBool("isCandidate", src, b(300, 0, 400, 100), TraversalDirection.LEFT))
    }

    @Test
    fun candidate_right_requires_target_on_right() {
        val src = b(100, 0, 200, 100)
        assertTrue(callBool("isCandidate", src, b(300, 0, 400, 100), TraversalDirection.RIGHT))
        assertFalse(callBool("isCandidate", src, b(0, 0, 50, 100), TraversalDirection.RIGHT))
    }

    @Test
    fun candidate_up_requires_target_above() {
        val src = b(0, 100, 100, 200)
        assertTrue(callBool("isCandidate", src, b(0, 0, 100, 50), TraversalDirection.UP))
        assertFalse(callBool("isCandidate", src, b(0, 300, 100, 400), TraversalDirection.UP))
    }

    @Test
    fun candidate_down_requires_target_below() {
        val src = b(0, 100, 100, 200)
        assertTrue(callBool("isCandidate", src, b(0, 300, 100, 400), TraversalDirection.DOWN))
        assertFalse(callBool("isCandidate", src, b(0, 0, 100, 50), TraversalDirection.DOWN))
    }

    @Test
    fun candidate_accepts_partial_overlap_in_direction() {
        val src = b(100, 100, 200, 200)
        // 左边界在 src 左侧，右边界与 src 重叠 → 部分在左，应算候选
        assertTrue(callBool("isCandidate", src, b(50, 100, 150, 200), TraversalDirection.LEFT))
    }

    // ==================== majorAxisDistance ====================

    @Test
    fun major_axis_distance_left() {
        // src.left=100, dest.right=50 → 50
        assertEquals(50, callInt("majorAxisDistance", TraversalDirection.LEFT,
            b(100, 0, 200, 100), b(0, 0, 50, 100)))
    }

    @Test
    fun major_axis_distance_right() {
        // dest.left=300, src.right=200 → 100
        assertEquals(100, callInt("majorAxisDistance", TraversalDirection.RIGHT,
            b(100, 0, 200, 100), b(300, 0, 400, 100)))
    }

    /**
     * 方向不对时返回 0（TalkBack 的 Math.max(0, ...)）
     * —— 这是与「coerceAtLeast(1)」的关键区别
     */
    @Test
    fun major_axis_distance_is_zero_when_wrong_direction() {
        assertEquals(0, callInt("majorAxisDistance", TraversalDirection.LEFT,
            b(0, 0, 50, 100), b(100, 0, 200, 100)))
    }

    // ==================== majorAxisDistanceToFarEdge ====================

    @Test
    fun far_edge_distance_left() {
        // src.left=100, dest.left=0 → 100
        assertEquals(100, callInt("majorAxisDistanceToFarEdge", TraversalDirection.LEFT,
            b(100, 0, 200, 100), b(0, 0, 50, 100)))
    }

    /**
     * 最小值为 1（TalkBack 的 Math.max(1, ...)）
     * —— 用于与 majorAxisDistance 打破平局
     */
    @Test
    fun far_edge_distance_has_minimum_one() {
        val r = b(100, 0, 200, 100)
        assertEquals(1, callInt("majorAxisDistanceToFarEdge", TraversalDirection.LEFT, r, r))
    }

    // ==================== minorAxisDistance ====================

    @Test
    fun minor_axis_distance_horizontal_uses_center_y() {
        // 左右方向 → 比较中心 Y：50 vs 150 → 100
        assertEquals(100, callInt("minorAxisDistance", TraversalDirection.LEFT,
            b(100, 0, 200, 100), b(300, 100, 400, 200)))
    }

    @Test
    fun minor_axis_distance_vertical_uses_center_x() {
        // 上下方向 → 比较中心 X：50 vs 200 → 150
        assertEquals(150, callInt("minorAxisDistance", TraversalDirection.UP,
            b(0, 100, 100, 200), b(150, 300, 250, 400)))
    }

    // ==================== weightedDistance（13 倍权重）====================

    @Test
    fun weighted_distance_overflow_protection() {
        assertEquals(
            Int.MAX_VALUE,
            callInt("weightedDistance", TraversalDirection.RIGHT,
                b(0, 0, 10, 10), b(100000, 100000, 100100, 100100))
        )
    }

    /**
     * 主轴权重 13 倍的验证：
     *   候选 A：主轴 50，副轴 0    → 13*2500 + 0 = 32500
     *   候选 B：主轴 20，副轴 200  → 13*400 + 40000 = 45200
     *   → A 胜（尽管 B 的主轴更近）
     */
    @Test
    fun weighted_distance_prioritizes_major_axis() {
        val src = b(0, 0, 100, 100)
        val ahead = b(150, 0, 200, 100)       // 主轴 50，副轴 0
        val diagonal = b(120, 200, 170, 300)  // 主轴 20，副轴 200

        val wA = callInt("weightedDistance", TraversalDirection.RIGHT, src, ahead)
        val wB = callInt("weightedDistance", TraversalDirection.RIGHT, src, diagonal)
        assertEquals(32500, wA)
        assertEquals(45200, wB)
        assertTrue("副轴偏移过大时应输给正前方", wB > wA)
    }

    // ==================== beamsOverlap ====================

    @Test
    fun beams_overlap_horizontal_uses_vertical_span() {
        val a = b(0, 0, 100, 100)
        assertTrue(callBool("beamsOverlap", TraversalDirection.LEFT, a, b(200, 50, 300, 150)))
        assertFalse(callBool("beamsOverlap", TraversalDirection.LEFT, a, b(200, 200, 300, 300)))
    }

    @Test
    fun beams_overlap_vertical_uses_horizontal_span() {
        val a = b(0, 0, 100, 100)
        assertTrue(callBool("beamsOverlap", TraversalDirection.UP, a, b(50, 200, 150, 300)))
        assertFalse(callBool("beamsOverlap", TraversalDirection.UP, a, b(200, 200, 300, 300)))
    }

    // ==================== isBetterCandidate 综合 ====================

    /**
     * 核心场景：正前方 vs 斜向 —— beam 机制 + 13 倍权重应让正前方胜出
     */
    @Test
    fun better_candidate_prefers_in_beam() {
        val src = b(0, 100, 100, 200)
        val inBeam = b(200, 100, 300, 200)
        val offBeam = b(200, 500, 300, 600)

        assertTrue(callBool("isBetterCandidate", TraversalDirection.RIGHT, src, inBeam, offBeam))
        assertFalse(callBool("isBetterCandidate", TraversalDirection.RIGHT, src, offBeam, inBeam))
    }

    @Test
    fun better_candidate_prefers_closer() {
        val src = b(0, 0, 100, 100)
        val near = b(150, 0, 250, 100)
        val far = b(1500, 0, 1600, 100)

        assertTrue(callBool("isBetterCandidate", TraversalDirection.RIGHT, src, near, far))
        assertFalse(callBool("isBetterCandidate", TraversalDirection.RIGHT, src, far, near))
    }

    @Test
    fun better_candidate_rejects_wrong_direction() {
        val src = b(0, 0, 100, 100)
        val left = b(-200, 0, -100, 100)
        val right = b(200, 0, 300, 100)

        assertFalse("LEFT 方向下右侧不该胜出",
            callBool("isBetterCandidate", TraversalDirection.LEFT, src, right, left))
        assertTrue("LEFT 方向下左侧应胜出",
            callBool("isBetterCandidate", TraversalDirection.LEFT, src, left, right))
    }

    // ==================== isToDirectionOf ====================

    @Test
    fun to_direction_of_is_strict() {
        val src = b(100, 100, 200, 200)
        assertTrue(callBool("isToDirectionOf", TraversalDirection.LEFT, src, b(0, 100, 50, 200)))
        // 重叠 → false（比 isCandidate 严格）
        assertFalse(callBool("isToDirectionOf", TraversalDirection.LEFT, src, b(50, 100, 150, 200)))
    }
}
