package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口几何分类 —— **边界测试**
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】天坦 `w7/E0.java`
 * ══════════════════════════════════════════════════════════
 *
 * 容差函数 `f()`（:95~97，原样摘录）：
 * ```java
 *   public static boolean f(int i9, int i10) {
 *       return i9 < i10 + 5 && i9 > i10 + (-5);
 *   }
 * ```
 * 即 **|a − b| < 5**（**严格小于**—— `a == b±5` 不算匹配）。
 *
 * 状态栏 `h()`（:99~113）：
 * ```java
 *   return f(rect.top, 0) && f(rect.left, 0)
 *       && f(rect.right, displayMetrics.widthPixels)
 *       && rect.bottom < displayMetrics.heightPixels / 5;
 * ```
 *
 * 导航栏 `e()`（:70~92）：
 * ```java
 *   横屏右侧条：rect.top==0 && rect.left > (h/4)*3 && rect.right==w && rect.bottom==h
 *   横屏左侧条：rect.top==0 && rect.left==0 && rect.right < w/4 && rect.bottom==h
 *   竖屏底部条：rect.top > (h/4)*3 && rect.left==0 && rect.right==w && rect.bottom==h
 * ```
 *
 * 【为什么测边界】这些判定决定「状态栏/导航栏是否进焦点序列」——
 * 判定错会让用户在浏览时**突然滑到状态栏或导航栏**上，
 * 或反之**永远滑不到**（某些 ROM 内容确实在导航栏区域）。
 * 而 `/5`、`*3/4`、`±5` 这三个数字正是最容易抄错的地方。
 */
class WindowClassifierBoundaryTest {

    private fun b(l: Int, t: Int, r: Int, bo: Int) = Bounds(l, t, r, bo)

    /** 1080×2400 竖屏（典型手机） */
    private val portrait = ScreenGeometry(1080, 2400, rotation = 0)

    /** 2400×1080 横屏 */
    private val landscape = ScreenGeometry(2400, 1080, rotation = 1)

    // ==================== 容差函数 ====================

    @Test
    fun matches_toleranceIsStrictlyLessThan5() {
        assertTrue("完全相等应匹配", WindowClassifierCore.matches(100, 100))
        assertTrue("差 4 应匹配", WindowClassifierCore.matches(104, 100))
        assertTrue("差 -4 应匹配", WindowClassifierCore.matches(96, 100))
        assertFalse("★ 差 5 应**不**匹配（天坦用严格小于）",
            WindowClassifierCore.matches(105, 100))
        assertFalse("★ 差 -5 应**不**匹配",
            WindowClassifierCore.matches(95, 100))
        assertFalse("差 6 不匹配", WindowClassifierCore.matches(106, 100))
    }

    // ==================== 状态栏 ====================

    @Test
    fun statusBar_typicalHeight_isDetected() {
        // 顶部对齐、左右撑满、高度 100 < 2400/5=480
        val bounds = b(0, 0, 1080, 100)
        assertTrue("典型状态栏应被识别",
            WindowClassifierCore.isStatusBar(bounds, portrait))
    }

    @Test
    fun statusBar_atHeightBoundary_isRejected() {
        // bottom == 2400/5 == 480 → 条件 `bottom < 480` 为假
        assertFalse("★ bottom 恰为屏高/5 时**不算**状态栏（严格小于）",
            WindowClassifierCore.isStatusBar(b(0, 0, 1080, 480), portrait))
        // 479 应算
        assertTrue("bottom=479 应算状态栏",
            WindowClassifierCore.isStatusBar(b(0, 0, 1080, 479), portrait))
    }

    @Test
    fun statusBar_withinTolerance_isDetected() {
        // 某些 ROM 有 1~2px 取整差异 —— 容差必须容忍
        assertTrue("top=3 应被容差接受",
            WindowClassifierCore.isStatusBar(b(0, 3, 1080, 100), portrait))
        assertTrue("right=1082 应被容差接受",
            WindowClassifierCore.isStatusBar(b(0, 0, 1082, 100), portrait))
    }

    @Test
    fun statusBar_fullScreenHeight_isNotStatusBar() {
        assertFalse("全屏窗口不是状态栏",
            WindowClassifierCore.isStatusBar(b(0, 0, 1080, 2400), portrait))
    }

    @Test
    fun statusBar_notAlignedTop_isRejected() {
        assertFalse("top 明显不为 0 的不是状态栏",
            WindowClassifierCore.isStatusBar(b(0, 200, 1080, 300), portrait))
    }

    // ==================== 导航栏：竖屏 ====================

    @Test
    fun portraitNavigationBar_bottomStrip_isDetected() {
        // top = 2300 > (2400/4)*3 = 1800
        val bounds = b(0, 2300, 1080, 2400)
        assertTrue("竖屏底部条应被识别为导航栏",
            WindowClassifierCore.isNavigationBar(bounds, portrait))
    }

    @Test
    fun portraitNavigationBar_atTopBoundary() {
        // top == (2400/4)*3 == 1800 → 条件 `top > 1800` 为假
        assertFalse("★ top 恰为 3/4 屏高时**不算**导航栏（严格大于）",
            WindowClassifierCore.isNavigationBar(b(0, 1800, 1080, 2400), portrait))
        assertTrue("top=1801 应算导航栏",
            WindowClassifierCore.isNavigationBar(b(0, 1801, 1080, 2400), portrait))
    }

    @Test
    fun portraitNavigationBar_mustReachBottom() {
        assertFalse("未撑到底部的不是导航栏",
            WindowClassifierCore.isNavigationBar(b(0, 2300, 1080, 2390), portrait))
    }

    @Test
    fun portraitNavigationBar_mustSpanFullWidth() {
        assertFalse("未撑满宽度的不是导航栏（可能是悬浮球）",
            WindowClassifierCore.isNavigationBar(b(0, 2300, 500, 2400), portrait))
    }

    // ==================== 导航栏：横屏 ====================

    @Test
    fun landscapeNavigationBar_rightStrip_isDetected() {
        // 横屏 2400×1080；右侧条：left > (1080/4)*3 = 810
        val bounds = b(2000, 0, 2400, 1080)
        assertTrue("横屏右侧条应被识别",
            WindowClassifierCore.isNavigationBar(bounds, landscape))
    }

    @Test
    fun landscapeNavigationBar_leftStrip_isDetected() {
        // 左侧条：left==0 且 right < 2400/4 = 600
        val bounds = b(0, 0, 400, 1080)
        assertTrue("横屏左侧条应被识别",
            WindowClassifierCore.isNavigationBar(bounds, landscape))
    }

    @Test
    fun landscapeNavigationBar_atLeftBoundary() {
        assertFalse("★ left 恰为 3/4 屏高时不算（严格大于）",
            WindowClassifierCore.isNavigationBar(b(810, 0, 2400, 1080), landscape))
        assertFalse("★ right 恰为 1/4 屏宽时不算（严格小于）",
            WindowClassifierCore.isNavigationBar(b(0, 0, 600, 1080), landscape))
    }

    // ==================== classify 综合 ====================

    @Test
    fun classify_inputMethodWinsOverGeometry() {
        // 即使边界看起来像状态栏，输入法类型优先
        assertEquals(
            WindowKind.INPUT_METHOD,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_INPUT_METHOD,
                b(0, 0, 1080, 100),
                portrait
            )
        )
    }

    @Test
    fun classify_imeInsetsAlsoMeansInputMethod() {
        assertEquals(
            "hasImeInsets 也应判定为输入法（系统报告了 IME 区域）",
            WindowKind.INPUT_METHOD,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_SYSTEM, b(0, 0, 1080, 100), portrait,
                hasImeInsets = true
            )
        )
    }

    @Test
    fun classify_applicationWindow() {
        assertEquals(
            WindowKind.APPLICATION,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_APPLICATION,
                b(0, 0, 1080, 2400), portrait
            )
        )
    }

    @Test
    fun classify_systemWindow_geometryDecidesKind() {
        assertEquals(
            "系统类型 + 状态栏几何 → STATUS_BAR",
            WindowKind.STATUS_BAR,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_SYSTEM, b(0, 0, 1080, 100), portrait
            )
        )
        assertEquals(
            "系统类型 + 导航栏几何 → NAVIGATION_BAR",
            WindowKind.NAVIGATION_BAR,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_SYSTEM, b(0, 2300, 1080, 2400), portrait
            )
        )
    }

    /**
     * ★ 系统类型但既非状态栏也非导航栏 → 归为**应用窗口**
     *
     * 依据源码注释：国产 ROM 的悬浮球/侧边栏是 TYPE_SYSTEM，
     * 但它们**承载真实可交互内容**，不应被当成系统栏滤掉。
     */
    @Test
    fun classify_systemWindow_neitherBar_isApplication() {
        assertEquals(
            "悬浮球等系统窗口应归为应用窗口（否则用户无法操作它）",
            WindowKind.APPLICATION,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_SYSTEM, b(900, 1200, 1000, 1300), portrait
            )
        )
    }

    @Test
    fun classify_unknownType_isUnknown() {
        assertEquals(
            WindowKind.UNKNOWN,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_UNKNOWN, b(0, 0, 100, 100), portrait
            )
        )
    }

    // ==================== WindowKind 语义 ====================

    @Test
    fun windowKind_isSystemBar_onlyForTwoKinds() {
        assertTrue(WindowKind.STATUS_BAR.isSystemBar)
        assertTrue(WindowKind.NAVIGATION_BAR.isSystemBar)
        assertFalse("应用窗口不是系统栏", WindowKind.APPLICATION.isSystemBar)
        assertFalse("输入法不是系统栏（它是独立上下文）", WindowKind.INPUT_METHOD.isSystemBar)
        assertFalse(WindowKind.UNKNOWN.isSystemBar)
    }

    // ==================== ScreenGeometry ====================

    @Test
    fun screenGeometry_landscapeDetection() {
        assertFalse(ScreenGeometry(1080, 2400, 0).isLandscape)
        assertTrue(ScreenGeometry(2400, 1080, 1).isLandscape)
        assertFalse("rotation=2 是倒置竖屏", ScreenGeometry(1080, 2400, 2).isLandscape)
        assertTrue(ScreenGeometry(2400, 1080, 3).isLandscape)
    }
}
