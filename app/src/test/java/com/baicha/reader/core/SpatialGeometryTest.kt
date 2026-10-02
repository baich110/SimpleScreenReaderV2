package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间方向查找算法 —— **真实行为测试**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么以前测不了】
 * ══════════════════════════════════════════════════════════
 *
 * 这些函数（`isCandidate` / `beamBeats` / `weightedDistance` …）
 * 实际上**只依赖矩形**，与「节点」无关，但此前是
 * `SpatialTraversal` 的 private 实例方法 —— 要测它们就得先构造一棵真节点树
 * 并跑真遍历，而 `AccessibilityNodeInfo` 在 JVM 里需要专门替身。
 *
 * 本轮抽到 [SpatialGeometry]（入参是 [Bounds]，纯 JVM 类型），
 * 算法本身**第一次**可以被逐条断言。
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】AOSP `DirectionalTraversalStrategy.java`
 * ══════════════════════════════════════════════════════════
 * 逐函数的行号见 `SpatialGeometry` 内的注释。
 */
class SpatialGeometryTest {

    private fun b(l: Int, t: Int, r: Int, bo: Int) = Bounds(l, t, r, bo)

    // ==================== isCandidate ====================

    @Test
    fun candidate_left_requiresDestToTheLeft() {
        val src = b(100, 0, 200, 50)
        assertTrue("正左侧应是候选", SpatialGeometry.isCandidate(src, b(0, 0, 50, 50), TraversalDirection.LEFT))
        assertFalse("右侧不该是 LEFT 候选", SpatialGeometry.isCandidate(src, b(300, 0, 400, 50), TraversalDirection.LEFT))
    }

    @Test
    fun candidate_right_requiresDestToTheRight() {
        val src = b(100, 0, 200, 50)
        assertTrue(SpatialGeometry.isCandidate(src, b(300, 0, 400, 50), TraversalDirection.RIGHT))
        assertFalse(SpatialGeometry.isCandidate(src, b(0, 0, 50, 50), TraversalDirection.RIGHT))
    }

    @Test
    fun candidate_up_requiresDestAbove() {
        val src = b(0, 100, 50, 200)
        assertTrue(SpatialGeometry.isCandidate(src, b(0, 0, 50, 50), TraversalDirection.UP))
        assertFalse(SpatialGeometry.isCandidate(src, b(0, 300, 50, 400), TraversalDirection.UP))
    }

    @Test
    fun candidate_down_requiresDestBelow() {
        val src = b(0, 100, 50, 200)
        assertTrue(SpatialGeometry.isCandidate(src, b(0, 300, 50, 400), TraversalDirection.DOWN))
        assertFalse(SpatialGeometry.isCandidate(src, b(0, 0, 50, 50), TraversalDirection.DOWN))
    }

    /** 非法方向必须安全返回 false（不得抛异常 —— 读屏是常驻服务，不能崩） */
    @Test
    fun candidate_invalidDirection_returnsFalseWithoutThrowing() {
        val src = b(0, 0, 10, 10)
        assertFalse(SpatialGeometry.isCandidate(src, b(20, 20, 30, 30), 999))
        assertFalse(SpatialGeometry.isCandidate(src, b(20, 20, 30, 30), TraversalDirection.FORWARD))
    }

    // ==================== isToDirectionOf ====================

    @Test
    fun isToDirectionOf_semantics() {
        val src = b(100, 100, 200, 200)
        assertTrue(SpatialGeometry.isToDirectionOf(TraversalDirection.LEFT, src, b(0, 0, 100, 50)))
        assertTrue(SpatialGeometry.isToDirectionOf(TraversalDirection.RIGHT, src, b(200, 0, 300, 50)))
        assertTrue(SpatialGeometry.isToDirectionOf(TraversalDirection.UP, src, b(0, 0, 50, 100)))
        assertTrue(SpatialGeometry.isToDirectionOf(TraversalDirection.DOWN, src, b(0, 200, 50, 300)))
    }

    // ==================== beamsOverlap ====================

    @Test
    fun beamsOverlap_verticalAxisForHorizontalDirections() {
        val r1 = b(0, 100, 50, 200)
        assertTrue("垂直投影重叠", SpatialGeometry.beamsOverlap(TraversalDirection.LEFT, r1, b(300, 150, 400, 250)))
        assertFalse("垂直投影不重叠", SpatialGeometry.beamsOverlap(TraversalDirection.LEFT, r1, b(300, 500, 400, 600)))
    }

    @Test
    fun beamsOverlap_horizontalAxisForVerticalDirections() {
        val r1 = b(100, 0, 200, 50)
        assertTrue("水平投影重叠", SpatialGeometry.beamsOverlap(TraversalDirection.DOWN, r1, b(150, 300, 250, 400)))
        assertFalse("水平投影不重叠", SpatialGeometry.beamsOverlap(TraversalDirection.DOWN, r1, b(500, 300, 600, 400)))
    }

    // ==================== 主轴距离 ====================

    /**
     * `majorAxisDistance` 下界是 **0**（dest 不在该方向时）
     * 依据 AOSP `:509` `Math.max(0, ...)`
     */
    @Test
    fun majorAxisDistance_clampsToZero() {
        val src = b(100, 100, 200, 200)
        assertEquals("RIGHT 方向的近边距离 = 300-200", 100,
            SpatialGeometry.majorAxisDistance(TraversalDirection.RIGHT, src, b(300, 0, 400, 50)))
        assertEquals("左侧元素在 RIGHT 方向上距离应钳为 0", 0,
            SpatialGeometry.majorAxisDistance(TraversalDirection.RIGHT, src, b(0, 0, 50, 50)))
    }

    /**
     * `majorAxisDistanceToFarEdge` 下界是 **1**（不是 0）—— 用于打破平局
     * 依据 AOSP `:536` `Math.max(1, ...)`
     */
    @Test
    fun majorAxisDistanceToFarEdge_clampsToOne() {
        val src = b(100, 100, 200, 200)
        assertEquals("右侧元素的远边距离 = 400-200", 200,
            SpatialGeometry.majorAxisDistanceToFarEdge(TraversalDirection.RIGHT, src, b(300, 0, 400, 50)))
        assertEquals("★ 下界必须是 1，不是 0（依据 AOSP :536）", 1,
            SpatialGeometry.majorAxisDistanceToFarEdge(TraversalDirection.RIGHT, src, b(0, 0, 50, 50)))
    }

    // ==================== ★ 负坐标下的中心公式（本轮修复）====================

    /**
     * ★★ 核心：`minorAxisDistance` 必须用 AOSP 公式 `top + height/2`
     *
     * 负坐标下与 `(top+bottom)/2` **差 1**，而 Android 屏幕坐标可为负
     * （内容滚出屏幕上方时 top 就是负数）。
     *
     * 构造：两候选 major 相同、minor 只差 1 —— 修复前会选错。
     */
    @Test
    fun minorAxisDistance_usesAospFormulaForNegativeCoordinates() {
        // 源：左列，垂直位置顶部为负（滚出屏幕上方）
        val src = b(0, -20, 100, -19)          // height = 1，aospCenterY = -20

        // 目标：同一行的元素，中心线与源差 0
        val dst = b(200, -20, 300, -19)         // aospCenterY = -20
        assertEquals(
            "同一行元素的垂直中心距应为 0",
            0, SpatialGeometry.minorAxisDistance(TraversalDirection.RIGHT, src, dst)
        )

        // 若用 (top+bottom)/2：src=-19, dst=-19 → 仍为 0（本项目这条测不出差异）
        // 真正的差异在「高度不同」时暴露，见下一条
    }

    /**
     * ★★ 负坐标 + 不同高度 —— 新旧公式给出不同结果
     *
     * 走查：
     *   src: top=-20, bottom=-19  (height=1)
     *        AOSP centerY = -20 + 0 = -20
     *        常规 centerY = (-39)/2  = -19
     *   dst: top=-10, bottom=-9   (height=1)
     *        AOSP centerY = -10 + 0 = -10
     *        常规 centerY = (-19)/2  = -9
     *   两者差值均为 10 → 这条仍相同。
     *
     * 用 height 为**奇数且较大**的矩形的确能造出差异，见下条。
     */
    @Test
    fun minorAxisDistance_differsBetweenFormulas() {
        // src: height = 3 → AOSP: -20 + 1 = -19 ；常规: (-20 + -17)/2 = -37/2 = -18
        val src = b(0, -20, 100, -17)
        assertEquals("AOSP 中心 Y", -19, src.aospCenterY)
        assertEquals("常规中心 Y", -18, src.centerY)

        // dst: 同样的尺寸，位置相差 0
        val dst = b(200, -20, 300, -17)

        // 两个公式给出的距离**都要是 0**（同位置），所以这条也测不出差别 ——
        // 真正要测的是「跨两个不同高度矩形时，两种公式的相对大小关系可能翻转」。
        // 下面构造一个能区分的最小例子：
        val src2 = b(0, -20, 100, -17)     // AOSP -19 / 常规 -18
        val dst2 = b(200, -18, 300, -15)   // AOSP -17 / 常规 -16

        assertEquals("AOSP 公式距离 = |-19 - (-17)| = 2", 2,
            SpatialGeometry.minorAxisDistance(TraversalDirection.RIGHT, src2, dst2))

        // 常规公式给 |-18 - (-16)| = 2 —— 相同。
        // ⇒ 结论：**距离本身常相同**，差异发生在加权比较的边界上。
        // 本测试锁定「AOSP 公式已被采用」这一事实（见下方公式断言）。
        assertEquals(
            "minorAxisDistance 必须使用 AOSP 中心公式",
            Math.abs(src2.aospCenterY - dst2.aospCenterY),
            SpatialGeometry.minorAxisDistance(TraversalDirection.RIGHT, src2, dst2)
        )
    }

    /**
     * ★ 直接锁定公式选择：把两种公式的结果都算出来，
     *   断言实现用的是 **AOSP** 那一个（而不是 `(top+bottom)/2`）
     *
     * 这是防「有人改回旧写法」的守卫 ——
     * 用一个两种公式结果**必然不同**的构造。
     */
    @Test
    fun minorAxisDistance_picksAospNotConventional() {
        // 构造：使 aospCenter 与 center 相差 1 的两个矩形
        //
        // src: top=-20 bottom=-19 → AOSP -20, 常规 -19
        // dst: top=  0 bottom=  1 → AOSP   0, 常规   0
        //
        // AOSP 距离 = |-20 - 0| = 20
        // 常规距离 = |-19 - 0| = 19
        val src = b(0, -20, 100, -19)
        val dst = b(200, 0, 300, 1)

        assertEquals("确认两种公式确实不同", -20, src.aospCenterY)
        assertEquals("确认两种公式确实不同", -19, src.centerY)

        assertEquals(
            "★ 必须用 AOSP 公式（距离 20），不是常规公式（距离 19）",
            20, SpatialGeometry.minorAxisDistance(TraversalDirection.RIGHT, src, dst)
        )
    }

    // ==================== 加权距离与溢出保护 ====================

    @Test
    fun weightedDistance_weightsMajorAxis13x() {
        // major = 1, minor = 0 → 13*1 + 0 = 13
        val src = b(0, 0, 10, 10)
        val dst = b(11, 0, 21, 10)
        assertEquals(13, SpatialGeometry.weightedDistance(TraversalDirection.RIGHT, src, dst))
    }

    /**
     * ★ 溢出保护 —— 依据 AOSP `:421~422`
     *
     * 没有它，`13 * major * major` 会溢出成负数，
     * 于是「很远的元素」被算成「最近」，焦点跳到屏幕外。
     */
    @Test
    fun weightedDistance_overflowGuardReturnsMaxValue() {
        val src = b(0, 0, 10, 10)
        val far = b(20000, 0, 20010, 10)     // major = 19990 > 10000
        assertEquals(
            "主轴距离超 10000 必须返回 MAX_VALUE（防溢出成负数）",
            Int.MAX_VALUE,
            SpatialGeometry.weightedDistance(TraversalDirection.RIGHT, src, far)
        )
    }

    // ==================== isBetterCandidate 五层顺序 ====================

    @Test
    fun isBetterCandidate_nonCandidate1_loses() {
        val src = b(100, 100, 200, 200)
        // rect1 在左侧（不是 RIGHT 候选）
        assertFalse(
            SpatialGeometry.isBetterCandidate(
                TraversalDirection.RIGHT, src, b(0, 0, 50, 50), b(300, 0, 400, 50)
            )
        )
    }

    @Test
    fun isBetterCandidate_nonCandidate2_winsFor1() {
        val src = b(100, 100, 200, 200)
        // rect2 在左侧（不是右侧候选）→ rect1 胜
        assertTrue(
            SpatialGeometry.isBetterCandidate(
                TraversalDirection.RIGHT, src, b(300, 0, 400, 50), b(0, 0, 50, 50)
            )
        )
    }

    /** 同一列上的两个候选：较近的胜出 */
    @Test
    fun isBetterCandidate_nearerWinsOnSameBeam() {
        val src = b(100, 100, 200, 200)
        val near = b(250, 100, 300, 200)     // 正右方，近
        val far = b(500, 100, 600, 200)      // 正右方，远

        assertTrue(
            "近者胜",
            SpatialGeometry.isBetterCandidate(TraversalDirection.RIGHT, src, near, far)
        )
        assertFalse(
            "远者不胜",
            SpatialGeometry.isBetterCandidate(TraversalDirection.RIGHT, src, far, near)
        )
    }

    /**
     * beam 优先于距离：正右方的**较远**元素，胜过斜向的**较近**元素
     *
     * 这是 beam 机制的核心目的 —— 按方向导航时不该跳到斜对面。
     */
    @Test
    fun isBetterCandidate_inBeamBeatsCloserButOffAxis() {
        val src = b(100, 100, 200, 200)

        // inBeam：正右方但较远（垂直投影完全重叠）
        val inBeam = b(600, 100, 700, 200)
        // offAxis：更近但垂直方向完全错开（在下方）
        val offAxis = b(210, 600, 300, 700)

        assertTrue(
            "正右方（beam 内）应胜过斜下方的更近元素",
            SpatialGeometry.isBetterCandidate(TraversalDirection.RIGHT, src, inBeam, offAxis)
        )
    }

    /** 对称性：两个候选都不在 beam 内时，按加权距离决定 */
    @Test
    fun isBetterCandidate_offBeamFallsBackToDistance() {
        val src = b(100, 100, 200, 200)
        val near = b(210, 500, 300, 600)
        val far = b(210, 900, 300, 1000)

        assertTrue(SpatialGeometry.isBetterCandidate(TraversalDirection.RIGHT, src, near, far))
        assertFalse(SpatialGeometry.isBetterCandidate(TraversalDirection.RIGHT, src, far, near))
    }
}
