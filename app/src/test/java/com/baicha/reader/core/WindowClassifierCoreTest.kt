package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口几何分类测试 —— 对应天坦 `w7/E0.e()` / `E0.h()`
 *
 * 【为什么这批测试有价值】
 *   天坦的几何判定直接调 DisplayManager/WindowManager，**完全无法单测** ——
 *   这也是它那套逻辑长期没被验证的原因。
 *   白茶把判定抽成纯函数后，以下每条天坦的几何规则都能被逐条锁定。
 *
 * 测试用典型屏幕 1080x2400（主流手机竖屏）。
 */
class WindowClassifierCoreTest {

    private val portrait = ScreenGeometry(widthPixels = 1080, heightPixels = 2400, rotation = 0)
    private val landscape = ScreenGeometry(widthPixels = 1080, heightPixels = 2400, rotation = 1)

    // ==================== 状态栏（天坦 E0.h）====================

    /**
     * 天坦规则：top≈0 且 left≈0 且 right≈屏宽 且 bottom < 屏高/5
     * 典型状态栏：1080x80，贴顶
     */
    @Test
    fun status_bar_is_detected() {
        val bar = Bounds(0, 0, 1080, 80)
        assertTrue(WindowClassifierCore.isStatusBar(bar, portrait))
        assertEquals(
            WindowKind.STATUS_BAR,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_SYSTEM, bar, portrait
            )
        )
    }

    /**
     * ★ 边界：高度恰好等于 屏高/5 时**不算**状态栏
     * 天坦用 `rect.bottom < heightPixels / 5`（严格小于）
     */
    @Test
    fun status_bar_height_boundary_is_exclusive() {
        // 2400/5 = 480
        val justUnder = Bounds(0, 0, 1080, 479)
        val exactly = Bounds(0, 0, 1080, 480)

        assertTrue("479 < 480 应是状态栏", WindowClassifierCore.isStatusBar(justUnder, portrait))
        assertFalse("480 不满足严格小于", WindowClassifierCore.isStatusBar(exactly, portrait))
    }

    /** 不贴顶 → 不是状态栏 */
    @Test
    fun floating_bar_is_not_status_bar() {
        val floating = Bounds(0, 200, 1080, 280)
        assertFalse(WindowClassifierCore.isStatusBar(floating, portrait))
    }

    /** 不撑满宽度 → 不是状态栏（如居中悬浮条） */
    @Test
    fun narrow_top_bar_is_not_status_bar() {
        val narrow = Bounds(100, 0, 900, 80)
        assertFalse(WindowClassifierCore.isStatusBar(narrow, portrait))
    }

    // ==================== 导航栏（天坦 E0.e）====================

    /**
     * 竖屏底部导航栏：top > 屏高*3/4 且 left≈0 且 right≈屏宽 且 bottom≈屏高
     * 2400*3/4 = 1800
     */
    @Test
    fun portrait_navigation_bar_is_detected() {
        val nav = Bounds(0, 2280, 1080, 2400)
        assertTrue(WindowClassifierCore.isNavigationBar(nav, portrait))
        assertEquals(
            WindowKind.NAVIGATION_BAR,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_SYSTEM, nav, portrait
            )
        )
    }

    /** 底部条但不到 3/4 → 不是导航栏 */
    @Test
    fun mid_screen_bottom_bar_is_not_navigation_bar() {
        val mid = Bounds(0, 1000, 1080, 1200)
        assertFalse(WindowClassifierCore.isNavigationBar(mid, portrait))
    }

    /**
     * ★ 横屏右侧竖条
     * 天坦：top≈0 且 left > (heightPixels/4)*3 且 right≈屏宽 且 bottom≈屏高
     */
    @Test
    fun landscape_right_navigation_bar_is_detected() {
        // 2400/4*3 = 1800
        val nav = Bounds(2280, 0, 1080, 2400)
        assertTrue(
            "横屏右侧竖条应被识别：$nav",
            WindowClassifierCore.isNavigationBar(nav, landscape)
        )
    }

    /**
     * ★ 横屏左侧竖条
     * 天坦：top≈0 且 left≈0 且 right < 屏宽/4 且 bottom≈屏高
     */
    @Test
    fun landscape_left_navigation_bar_is_detected() {
        // 屏宽/4 = 270
        val nav = Bounds(0, 0, 200, 2400)
        assertTrue(
            "横屏左侧竖条应被识别：$nav",
            WindowClassifierCore.isNavigationBar(nav, landscape)
        )
    }

    /** 竖屏下不该走横屏分支 */
    @Test
    fun landscape_rules_do_not_apply_in_portrait() {
        val landscapeStyle = Bounds(2280, 0, 1080, 2400)
        assertFalse(
            "竖屏下不适用横屏规则",
            WindowClassifierCore.isNavigationBar(landscapeStyle, portrait)
        )
    }

    // ==================== 输入法 ====================

    @Test
    fun input_method_by_type_is_detected() {
        val ime = Bounds(0, 1400, 1080, 2400)
        assertEquals(
            WindowKind.INPUT_METHOD,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_INPUT_METHOD, ime, portrait
            )
        )
    }

    /**
     * ★ 输入法判定优先于几何判定
     * 输入法窗口可能恰好是底部长条，会被误判成导航栏 ——
     * 系统类型必须优先，否则输入法会被当成导航栏跳过。
     */
    @Test
    fun ime_type_takes_priority_over_geometry() {
        val bottomBarLike = Bounds(0, 2280, 1080, 2400)
        assertEquals(
            "系统已声明是输入法时不应按几何判成导航栏",
            WindowKind.INPUT_METHOD,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_INPUT_METHOD, bottomBarLike, portrait
            )
        )
        // 对照：同样几何的系统窗口确实是导航栏
        assertEquals(
            WindowKind.NAVIGATION_BAR,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_SYSTEM, bottomBarLike, portrait
            )
        )
    }

    @Test
    fun ime_insets_flag_forces_input_method() {
        val any = Bounds(0, 0, 1080, 2400)
        assertEquals(
            WindowKind.INPUT_METHOD,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_APPLICATION, any, portrait, hasImeInsets = true
            )
        )
    }

    // ==================== 应用窗口 ====================

    @Test
    fun application_window_is_detected() {
        val app = Bounds(0, 80, 1080, 2280)
        assertEquals(
            WindowKind.APPLICATION,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_APPLICATION, app, portrait
            )
        )
    }

    /**
     * ★ 系统窗口但不是状态栏/导航栏 → 归为应用窗口
     *
     * 理由：国产 ROM 的悬浮球、侧边栏都挂在 TYPE_SYSTEM 下，
     * 但可能承载真实可交互内容。判成「系统栏」会让用户
     * 永远够不到它们。
     */
    @Test
    fun unknown_system_window_becomes_application() {
        val floatingBall = Bounds(900, 1200, 1020, 1320)
        assertEquals(
            "系统窗口但非状态栏/导航栏应归为应用窗口",
            WindowKind.APPLICATION,
            WindowClassifierCore.classify(
                WindowClassifierCore.TYPE_SYSTEM, floatingBall, portrait
            )
        )
    }

    @Test
    fun unknown_type_returns_unknown() {
        val b = Bounds(0, 0, 100, 100)
        assertEquals(
            WindowKind.UNKNOWN,
            WindowClassifierCore.classify(WindowClassifierCore.TYPE_UNKNOWN, b, portrait)
        )
    }

    // ==================== 容差（天坦 E0.f）====================

    /**
     * 天坦容差：|a - b| < 5
     *
     * 【为什么需要】不同 ROM 的状态栏取整差 1~2px，
     * 严格相等会让判定在部分机型整体失效。
     */
    @Test
    fun tolerance_accepts_within_5px() {
        assertTrue(WindowClassifierCore.matches(0, 0))
        assertTrue(WindowClassifierCore.matches(4, 0))
        assertTrue(WindowClassifierCore.matches(-4, 0))
        assertTrue(WindowClassifierCore.matches(1082, 1080))
    }

    @Test
    fun tolerance_rejects_at_or_beyond_5px() {
        assertFalse("恰好 5px 不满足严格小于", WindowClassifierCore.matches(5, 0))
        assertFalse(WindowClassifierCore.matches(-5, 0))
        assertFalse(WindowClassifierCore.matches(10, 0))
    }

    /**
     * ★ 真实场景：某 ROM 状态栏是 1080x78（差 2px），
     * 容差让它仍被正确识别为贴顶
     */
    @Test
    fun real_world_rounding_is_tolerated() {
        val slightlyOff = Bounds(2, 1, 1078, 78)
        assertTrue(
            "1~2px 取整差异不应影响判定",
            WindowClassifierCore.isStatusBar(slightlyOff, portrait)
        )
    }

    // ==================== 常量 ====================

    @Test
    fun constants_match_tantang() {
        assertEquals("天坦 E0.f 用 5px 容差", 5, WindowClassifierCore.TOLERANCE_PX)
        assertEquals("天坦 E0.h 用 屏高/5", 5, WindowClassifierCore.STATUS_BAR_HEIGHT_DIVISOR)
    }

    @Test
    fun window_type_constants_match_android() {
        assertEquals(1, WindowClassifierCore.TYPE_APPLICATION)
        assertEquals(2, WindowClassifierCore.TYPE_INPUT_METHOD)
        assertEquals(3, WindowClassifierCore.TYPE_SYSTEM)
    }

    @Test
    fun system_bar_flag_is_accurate() {
        assertTrue(WindowKind.STATUS_BAR.isSystemBar)
        assertTrue(WindowKind.NAVIGATION_BAR.isSystemBar)
        assertFalse(WindowKind.APPLICATION.isSystemBar)
        assertFalse(WindowKind.INPUT_METHOD.isSystemBar)
        assertFalse(WindowKind.UNKNOWN.isSystemBar)
    }

    @Test
    fun all_kinds_have_labels() {
        WindowKind.values().forEach {
            assertTrue("${it.name} 应有中文标签", it.label.isNotEmpty())
        }
    }

    @Test
    fun screen_geometry_landscape_detection() {
        assertFalse(portrait.isLandscape)
        assertTrue(landscape.isLandscape)
        assertTrue(ScreenGeometry(1080, 2400, 3).isLandscape)
        assertFalse(ScreenGeometry(1080, 2400, 2).isLandscape)
    }
}
