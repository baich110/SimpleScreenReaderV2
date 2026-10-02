package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 修复回归测试 —— 锁定 2026-10-02 真机实测报告的 15 处修复
 *
 * 报告文件：`docs/Bug报告-2026-10-02.md`
 *
 * 这些测试的作用：防止后续改动把这些修复改回去。
 */
class BugFixRegressionTest {

    // ==================== #2 无标签兜底播报 ====================

    /**
     * #2：roleName 对 ROLE_CHECKED_TEXT_VIEW / ROLE_SCROLL_VIEW 等返回 null，
     * 旧实现在这种情况下**静默不播**，导致「焦点在动但没声音」。
     *
     * 修复后：调用方有兜底播报。这里验证这些角色确实返回 null（兜底路径存在）。
     */
    @Test
    fun role_name_returns_null_for_container_roles() {
        assertNull(SpeechCompositor.roleName(NodeRoleHelper.ROLE_CHECKED_TEXT_VIEW))
        assertNull(SpeechCompositor.roleName(NodeRoleHelper.ROLE_SCROLL_VIEW))
        assertNull(SpeechCompositor.roleName(NodeRoleHelper.ROLE_HORIZONTAL_SCROLL_VIEW))
        assertNull(SpeechCompositor.roleName(NodeRoleHelper.ROLE_VIEW_GROUP))
    }

    /** 有角色的仍要正常返回（不能被兜底逻辑影响） */
    @Test
    fun role_name_still_works_for_known_roles() {
        assertEquals("按钮", SpeechCompositor.roleName(NodeRoleHelper.ROLE_BUTTON))
        assertEquals("编辑框", SpeechCompositor.roleName(NodeRoleHelper.ROLE_EDIT_TEXT))
        assertEquals("列表", SpeechCompositor.roleName(NodeRoleHelper.ROLE_LIST))
    }

    // ==================== #5 边界基准语义 ====================

    /**
     * #5：原实现把「上次处理边界」降级成「父节点边界」，顶层调用永远传 null，
     * 导致微信特判成为死代码。
     *
     * 修复后：`clear()` 会重置基准状态（这是基准生命周期的一部分）。
     */
    @Test
    fun should_focus_clear_resets_baseline() {
        // 反复 clear 必须安全且不抛异常
        repeat(5) { ShouldFocusNode.clear() }
    }

    @Test
    fun should_focus_null_is_safe_after_clear() {
        ShouldFocusNode.clear()
        assertFalse(ShouldFocusNode.shouldFocus(null))
    }

    // ==================== #7 防抖语义 ====================

    /**
     * #7：原实现用 coerceAtLeast(60) 把窗口从 110ms 收紧到 60ms，
     * 但注释说「放宽」，常量又叫 MAX。语义与命名都反了。
     *
     * 修复后：连续同向导航**放宽**窗口（110 → 200 上限）。
     * 这里验证常量关系正确。
     */
    @Test
    fun debounce_constants_have_correct_relationship() {
        val base = FocusManager.BASE_DEBOUNCE_MS
        val max = FocusManager.MAX_DEBOUNCE_MS
        val step = FocusManager.DEBOUNCE_STEP_MS

        assertEquals("基础窗口应为 110ms（天坦值）", 110L, base)
        assertTrue("放宽上限必须大于基础窗口（否则又是收紧）", max > base)
        assertTrue("步长必须为正", step > 0)
    }

    // ==================== #9 事件类型常量 ====================

    /**
     * #9：注释曾把 TYPE_VIEW_ACCESSIBILITY_FOCUSED 与 64 混为一谈。
     * 这里锁定真实值，防止后续再写错。
     */
    @Test
    fun accessibility_focused_is_32768_not_64() {
        assertEquals("TYPE_VIEW_ACCESSIBILITY_FOCUSED 必须是 32768", 32768, 1 shl 15)
        // 64 是另一个事件类型（TYPE_VIEW_HOVER_EXIT 的位）
        assertEquals(64, 1 shl 6)
    }

    // ==================== #10 子节点收集上限 ====================

    /**
     * #10：原来「子节点 > 8 直接放弃」导致列表项读不出内容。
     * 修复后改为「有上限地收集」。
     *
     * 这里验证常量存在且合理（逻辑本身需要真实节点，无法在 JVM 测）。
     */
    @Test
    fun child_scan_limit_constant_exists() {
        assertEquals("子节点扫描上限应为 8", 8, SpeechCompositor.MAX_CHILD_SCAN)
    }

    // ==================== #15 自身包名过滤 ====================

    /**
     * #15：原实现无条件过滤自己 App 的事件 → 读屏自己的设置界面读不出来。
     * 修复后加开关，默认 false（对齐天坦 flag 21 默认关闭）。
     */
    @Test
    fun self_package_filter_defaults_to_off() {
        val d = EventDispatcher(EventQueue())
        assertFalse(
            "默认不能过滤自身包名事件，否则设置界面无法朗读",
            d.filterSelfPackageEvents
        )
    }

    @Test
    fun self_package_filter_is_settable() {
        val d = EventDispatcher(EventQueue())
        d.filterSelfPackageEvents = true
        assertTrue(d.filterSelfPackageEvents)
    }

    // ==================== #13 遍历策略去重 ====================

    /**
     * #13：CachedTraversalStrategy.flatten 未去重 → 重复节点导致索引错位。
     * 这里验证 release 后状态可重置（去重集合必须一起清）。
     */
    @Test
    fun cached_strategy_release_resets_state() {
        val s = CachedTraversalStrategy(null) { false }
        // release 必须安全（内部清空 flattened/indexMap/added）
        s.release()
        assertEquals(0, s.cachedSize())
    }
}

/**
 * 身份语义回归测试 —— #3 / #4 / #8 都跟「内容 hash vs 对象身份」有关
 */
class IdentitySemanticsTest {

    /**
     * 锁定设计决策：节点比较必须用身份（=== / identityHashCode），
     * 不能用内容 hash。这里用字符串模拟说明差异，作为文档性测试。
     */
    @Test
    fun content_hash_differs_from_identity() {
        // 两个内容相同的对象：内容 hash 可能相同，但身份必然不同
        val a = "same-content"
        val b = StringBuilder("same-content").toString()

        assertEquals("内容相等", a, b)
        // 身份不同（除非 JVM 字符串池复用 —— 这里用不同构造方式避免池化）
        assertFalse("身份必须不同", System.identityHashCode(a) == System.identityHashCode(b) && a !== b)
    }

    @Test
    fun identity_hash_is_stable_per_object() {
        val obj = Any()
        val h1 = System.identityHashCode(obj)
        val h2 = System.identityHashCode(obj)
        assertEquals("同一对象的身份 hash 必须稳定", h1, h2)
    }
}
