package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 矩形中心公式 —— **AOSP 与常规写法的差异**
 *
 * ══════════════════════════════════════════════════════════
 * 【要钉住的事实】
 * ══════════════════════════════════════════════════════════
 *
 * AOSP `DirectionalTraversalStrategy.minorAxisDistance()` 用：
 * ```java
 *   Math.abs(((source.top + source.height() / 2) - ((dest.top + dest.height() / 2))));
 * ```
 * 即中心 = `top + height / 2`（**先除后加**）。
 *
 * 常规写法是 `(top + bottom) / 2`（先加后除）。
 *
 * 两者在**非负坐标**下恒等，在**负坐标**下差 1 ——
 * 因为 Kotlin/Java 的整数除法**向零截断**：
 * ```
 *   top=-20, bottom=-19 (height=1)
 *     AOSP:    -20 + 1/2 = -20 + 0 = -20
 *     常规:    (-20 + -19)/2 = -39/2 = -19        ← 差 1
 * ```
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么这对读屏是真问题】
 * ══════════════════════════════════════════════════════════
 *
 * Android 屏幕坐标**可以为负**：视图滚出屏幕上方/左侧时，
 * `getBoundsInScreen()` 返回的 top/left 就是负数。
 * 长列表滚动、横向滚动、被状态栏遮挡的内容 —— 全都产生负坐标。
 *
 * 加权距离 = `13 * major² + minor²`。当两个候选的 major 相同时
 * （同一行/同一列的常见情形），**minor 就是唯一决定因素** ——
 * 差 1 像素足以翻转比较结果，表现为
 * 「上下滑动时焦点跳到了斜对面的元素上」。
 */
class BoundsCenterFormulaTest {

    private fun b(left: Int, top: Int, right: Int, bottom: Int) = Bounds(left, top, right, bottom)

    // ==================== 非负坐标：两式恒等 ====================

    @Test
    fun nonNegative_coordinates_formulasAgree() {
        val samples = listOf(
            b(0, 0, 10, 20),
            b(100, 200, 300, 500),
            b(1, 1, 2, 2),
            b(0, 0, 1, 1),
            b(50, 50, 51, 100)
        )
        for (s in samples) {
            assertEquals(
                "非负坐标下两式必须一致: $s",
                s.centerY, s.aospCenterY
            )
            assertEquals(
                "非负坐标下两式必须一致: $s",
                s.centerX, s.aospCenterX
            )
        }
    }

    // ==================== 负坐标：差 1 ====================

    /**
     * ★★ 核心断言：负坐标下两式**不同**
     *
     * 这条测试存在的意义就是把「它们其实不一样」钉成事实 ——
     * 否则未来有人会以为 `centerY` 可以互换使用。
     */
    @Test
    fun negativeCoordinates_formulasDiffer() {
        val s = b(0, -20, 10, -19)   // height = 1

        assertEquals("AOSP 公式：top + height/2 = -20 + 0", -20, s.aospCenterY)
        assertEquals("常规公式：(top+bottom)/2 = -39/2 = -19", -19, s.centerY)
        assertNotEquals(
            "负坐标下两式必须不同（这正是本轮修复的原因）",
            s.centerY, s.aospCenterY
        )
    }

    /** 逐项走查一个更长的负坐标样本 */
    @Test
    fun negativeCoordinates_knownSamples() {
        // top=-20, bottom=-19
        assertEquals(-20, b(0, -20, 1, -19).aospCenterY)
        assertEquals(-19, b(0, -20, 1, -19).centerY)

        // top=-10, bottom=10  (height=20) —— 跨越零点
        assertEquals("height/2 = 10，-10+10 = 0", 0, b(0, -10, 1, 10).aospCenterY)
        assertEquals("(top+bottom)/2 = 0", 0, b(0, -10, 1, 10).centerY)

        // top=-5, bottom=-1 (height=4)
        assertEquals("height/2 = 2，-5+2 = -3", -3, b(0, -5, 1, -1).aospCenterY)
        assertEquals("(-6)/2 = -3", -3, b(0, -5, 1, -1).centerY)
    }

    // ==================== 水平轴同理 ====================

    @Test
    fun negativeCoordinates_horizontalAxisAlsoDiffers() {
        val s = b(-30, 0, -29, 10)
        assertEquals("AOSP: left + width/2 = -30 + 0", -30, s.aospCenterX)
        assertEquals("常规: (-30 + -29)/2 = -59/2 = -29", -29, s.centerX)
    }

    // ==================== 与 AOSP 公式的直接对照 ====================

    /**
     * 用 AOSP 的确切表达式独立算一遍，逐样本比对我方实现
     *
     * 表达式原样照抄：
     * `top + (bottom - top) / 2`
     */
    @Test
    fun aospCenterY_matchesAospExpressionExactly() {
        val cases = listOf(
            b(0, 0, 10, 20), b(0, -20, 10, -19), b(0, -10, 10, 10),
            b(0, -5, 10, -1), b(0, 100, 10, 250), b(0, -250, 10, -100),
            b(0, -1, 10, 0), b(0, -3, 10, -2)
        )
        for (c in cases) {
            val expected = c.top + (c.bottom - c.top) / 2      // AOSP: top + height/2
            assertEquals("AOSP 表达式不一致: $c", expected, c.aospCenterY)
        }
    }

    @Test
    fun aospCenterX_matchesAospExpressionExactly() {
        val cases = listOf(
            b(0, 0, 20, 10), b(-30, 0, -29, 10), b(-10, 0, 10, 10),
            b(-5, 0, -1, 10), b(100, 0, 250, 10), b(-250, 0, -100, 10)
        )
        for (c in cases) {
            val expected = c.left + (c.right - c.left) / 2
            assertEquals("AOSP 表达式不一致: $c", expected, c.aospCenterX)
        }
    }

    // ==================== 原有 centerX/centerY 不得被改动 ====================

    /**
     * 反向守卫：修复**不得**改动 `centerX`/`centerY` 的语义
     *
     * 它们被其他算法依赖（如 `Bounds.distanceTo`），
     * 改了会引入新偏差。本轮采取的是「新增独立访问器」而非「改原方法」。
     */
    @Test
    fun existingCenterAccessors_unchanged() {
        val s = b(10, 20, 30, 40)
        assertEquals("centerX 必须仍是 (left+right)/2", 20, s.centerX)
        assertEquals("centerY 必须仍是 (top+bottom)/2", 30, s.centerY)
    }
}
