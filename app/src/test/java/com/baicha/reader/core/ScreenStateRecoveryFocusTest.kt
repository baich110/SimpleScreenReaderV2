package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕状态恢复 —— **焦点严格性测试**
 *
 * ══════════════════════════════════════════════════════════
 * 【本条测试要锁住的缺陷】
 * ══════════════════════════════════════════════════════════
 *
 * TalkBack `FocusProcessorForScreenStateChange.java:172~185`：
 * ```java
 *   private boolean hasValidAccessibilityFocusInWindow(AccessibilityWindowInfo window) {
 *     if (!window.isAccessibilityFocused()) { ... return false; }
 *     AccessibilityNodeInfoCompat currentFocus =
 *         accessibilityFocusMonitor.getAccessibilityFocus(useInputFocusIfEmpty = false);
 *     return (currentFocus != null)
 *         && AccessibilityNodeInfoUtils.isVisible(currentFocus)
 *         && (currentFocus.getWindowId() == window.getId());
 *   }
 * ```
 *
 * 白茶 `ScreenStateRecovery` 原来接的是 `FocusManager.getFocused()`，
 * 而它内部第一步是 `getAccessibilityFocus(useInputFocusIfEmpty = true)`
 * —— **多了一条退回输入焦点的回退链**。
 *
 * 【后果】「当前窗口**有没有**无障碍焦点」这个问题的答案被输入焦点污染：
 * 编辑框场景下会误判 `FAIL_HAS_VALID_FOCUS` → **不恢复焦点** →
 * 用户从锁屏/切窗口回来时焦点丢失且无任何提示。
 *
 * 【修复】新增 `FocusManager.getAccessibilityFocusStrict()`（`false`），
 * 服务侧接线改为它。本类用 [FakeNode] 做**真实行为断言**，
 * 证明两个入口在编辑框场景下**确实给出不同答案**。
 */
class ScreenStateRecoveryFocusTest {

    /** 可注入的假环境 —— 关闭回退时它必须被完全忽略 */
    private class Env {
        var a11yFocus: android.view.accessibility.AccessibilityNodeInfo? = null
        var inputFocus: android.view.accessibility.AccessibilityNodeInfo? = null
        var imeOnScreen: Boolean = false
    }

    private fun build(env: Env) = FocusMonitor(
        findAccessibilityFocus = { env.a11yFocus },
        findInputFocus = { env.inputFocus },
        isImeWindowOnScreen = { env.imeOnScreen }
    )

    /**
     * 输入框节点
     *
     * 【注意】`fakeWindowId` 必须显式给出 ——
     * 屏幕状态恢复的第③重检查要求「焦点节点属于**当前窗口**」
     * （依据 `FocusScreenStateChange.java:182`）。
     * 我第一版忘了设，于是「窗口 ID 不匹配」先把它拦下，
     * 测试表现为 SUCCESS（恢复被调用），与预期相反 ——
     * 是**测试准备不完整**，不是实现有问题。
     */
    private fun inputNode(
        id: Int,
        focused: Boolean = true,
        editable: Boolean = true,
        windowId: Int = 7
    ) = FakeNode(id, textValue = "输入框").apply {
        fakeInputFocused = focused
        fakeEditable = editable
        fakeWindowId = windowId
    }

    // ==================== ① 两个入口的语义差异（核心）====================

    /**
     * ★★ 编辑框场景：
     *   无障碍焦点 = 无
     *   输入焦点   = 有（编辑框）
     *   ⇒ 放宽入口返回节点；严格入口返回 null
     *
     * 这就是「屏幕状态恢复用错入口」会出问题的完整链条。
     */
    @Test
    fun editableInputFocus_relaxed_hits_strict_misses() {
        val env = Env()
        env.inputFocus = inputNode(42)
        val m = build(env)

        // 放宽（getFocused 用的）：回退到输入焦点 → 拿到节点
        val relaxed = m.getAccessibilityFocus(useInputFocusIfEmpty = true, requireEditable = true)
        assertNotNull("放宽入口应回退到输入焦点", relaxed)

        // 严格（屏幕状态恢复必须用的）：不回退 → null
        val strict = m.getAccessibilityFocus(useInputFocusIfEmpty = false)
        assertEquals("严格入口不得回退到输入焦点", null, strict)

        assertEquals("放宽路径应记账一次输入回退", 1L, m.inputFocusFallback)
        assertEquals("严格路径应记账一次未命中", 1L, m.missCount)
    }

    /**
     * 若屏幕状态恢复用了放宽入口，本场景会误判「已有有效焦点」
     * ⇒ `FAIL_HAS_VALID_FOCUS` ⇒ **不恢复焦点**。
     *
     * 本测试把这个错误后果**显式断言**出来，作为修复的反面证据。
     */
    @Test
    fun wrongEntryPoint_would_skipRecovery() {
        val env = Env()
        env.inputFocus = inputNode(42)

        var restoreCalled = false
        val recovery = ScreenStateRecovery(
            // ⚠️ 故意用**放宽**入口 —— 复现修复前的错误接线
            getCurrentFocus = {
                build(env).getAccessibilityFocus(useInputFocusIfEmpty = true, requireEditable = true)
            },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { true },
            onRestoreFocus = { restoreCalled = true },
            getLastFocusActionTime = { 0L },
            getLastFocusActionSource = { 0 }
        )

        recovery.onScreenStateChanged(0L)
        assertFalse(
            "用放宽入口时会误判「已有有效焦点」→ 不恢复（这就是修复前的症状）",
            restoreCalled
        )
        assertEquals(
            "结果应为 FAIL_HAS_VALID_FOCUS",
            ScreenStateRecovery.Result.FAIL_HAS_VALID_FOCUS,
            recovery.lastResult
        )
    }

    /**
     * ★ 用**正确**入口（严格）时：
     * 编辑框的输入焦点不算「已有无障碍焦点」⇒ **应当恢复**
     */
    @Test
    fun strictEntryPoint_wouldPerformRecovery() {
        val env = Env()
        env.inputFocus = inputNode(42)

        var restoreCalled = false
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { build(env).getAccessibilityFocus(useInputFocusIfEmpty = false) },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { true },
            onRestoreFocus = { restoreCalled = true },
            getLastFocusActionTime = { 0L },
            getLastFocusActionSource = { 0 }
        )

        recovery.onScreenStateChanged(0L)
        assertTrue("用严格入口时应执行恢复", restoreCalled)
        assertEquals(
            "结果应为 SUCCESS",
            ScreenStateRecovery.Result.SUCCESS,
            recovery.lastResult
        )
    }

    // ==================== ② 三重检查的其余两支 ====================

    /** 窗口未获无障碍焦点 → 直接判无有效焦点（依据 :173~176） */
    @Test
    fun windowWithoutAccessibilityFocus_returns_false() {
        val env = Env().apply { a11yFocus = FakeNode(1, "某节点") }
        var restoreCalled = false
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { build(env).getAccessibilityFocus(false) },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { false },      // ← 第①重检查不通过
            onRestoreFocus = { restoreCalled = true },
            getLastFocusActionTime = { 0L },
            getLastFocusActionSource = { 0 }
        )
        recovery.onScreenStateChanged(0L)
        assertTrue("窗口无无障碍焦点时应恢复", restoreCalled)
    }

    /**
     * ★ 节点必须属于**该窗口**（依据 :182 `getWindowId() == window.getId()`）
     *
     * 这条防的是「焦点节点属于别的窗口」——例如悬浮窗里还有焦点时，
     * 当前应用窗口不该被认为「已有有效焦点」。
     */
    @Test
    fun focusNodeInDifferentWindow_isNotValid() {
        val otherWindowNode = FakeNode(1, "别处的节点").apply { fakeWindowId = 99 }
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { otherWindowNode },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { true },
            onRestoreFocus = { },
            getLastFocusActionTime = { 0L },
            getLastFocusActionSource = { 0 }
        )
        recovery.onScreenStateChanged(0L)
        assertEquals(
            "焦点节点在别的窗口（99≠7）→ 不算有效焦点，应恢复",
            ScreenStateRecovery.Result.SUCCESS,
            recovery.lastResult
        )
    }

    @Test
    fun focusNodeInSameWindow_isValid() {
        val sameWindowNode = FakeNode(1, "本窗口节点").apply { fakeWindowId = 7 }
        var restoreCalled = false
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { sameWindowNode },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { true },
            onRestoreFocus = { restoreCalled = true },
            getLastFocusActionTime = { 0L },
            getLastFocusActionSource = { 0 }
        )
        recovery.onScreenStateChanged(0L)
        assertFalse("同窗口已有有效焦点 → 不抢焦点", restoreCalled)
        assertEquals(
            ScreenStateRecovery.Result.FAIL_HAS_VALID_FOCUS,
            recovery.lastResult
        )
    }

    /** 不可见节点不算有效焦点（依据 `AccessibilityNodeInfoUtils.isVisible`） */
    @Test
    fun invisibleFocusNode_isNotValid() {
        val invisible = FakeNode(1, "不可见").apply {
            fakeWindowId = 7
            visible = false
        }
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { invisible },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { true },
            onRestoreFocus = { },
            getLastFocusActionTime = { 0L },
            getLastFocusActionSource = { 0 }
        )
        recovery.onScreenStateChanged(0L)
        assertEquals(
            "不可见节点不算有效焦点",
            ScreenStateRecovery.Result.SUCCESS,
            recovery.lastResult
        )
    }

    // ==================== ③ 竞态保护仍然生效 ====================

    /**
     * 用户在切换期间已操作 → 不抢焦点（依据 :127~141）
     *
     * 这是 `FAIL_HAS_INTERACTION` 分支，本轮修复**不得**破坏它。
     */
    @Test
    fun userInteractionDuringTransition_blocksRecovery() {
        var restoreCalled = false
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { null },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { true },
            onRestoreFocus = { restoreCalled = true },
            getLastFocusActionTime = { 200L },            // 晚于切换开始
            getLastFocusActionSource = {
                ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION
            }
        )
        recovery.onScreenStateChanged(100L)               // 切换开始于 100
        assertFalse("用户在切换期间操作过 → 不抢焦点", restoreCalled)
        assertEquals(
            ScreenStateRecovery.Result.FAIL_HAS_INTERACTION,
            recovery.lastResult
        )
    }

    /** 操作发生在切换**之前** → 不影响（边界） */
    @Test
    fun interactionBeforeTransition_doesNotBlock() {
        var restoreCalled = false
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { null },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { true },
            onRestoreFocus = { restoreCalled = true },
            getLastFocusActionTime = { 100L },            // 与切换同时或更早
            getLastFocusActionSource = {
                ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION
            }
        )
        recovery.onScreenStateChanged(100L)
        assertTrue("切换前的操作不应拦下恢复", restoreCalled)
    }

    /** 非用户主动来源（如系统触发）→ 不拦 */
    @Test
    fun nonUserInitiatedSource_doesNotBlock() {
        var restoreCalled = false
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { null },
            getActiveWindowId = { 7 },
            isWindowAccessibilityFocused = { true },
            onRestoreFocus = { restoreCalled = true },
            getLastFocusActionTime = { 200L },
            getLastFocusActionSource = { 99 }             // 非 TOUCH_EXPLORATION / LOGICAL_NAVIGATION
        )
        recovery.onScreenStateChanged(100L)
        assertTrue("非用户主动来源不应拦下恢复", restoreCalled)
    }

    @Test
    fun noActiveWindow_returnsFailNoActiveWindow() {
        val recovery = ScreenStateRecovery(
            getCurrentFocus = { null },
            getActiveWindowId = { -1 },
            isWindowAccessibilityFocused = { false },
            onRestoreFocus = { },
            getLastFocusActionTime = { 0L },
            getLastFocusActionSource = { 0 }
        )
        recovery.onScreenStateChanged(0L)
        assertEquals(
            ScreenStateRecovery.Result.FAIL_NO_ACTIVE_WINDOW,
            recovery.lastResult
        )
    }
}

/**
 * 接线守卫 —— **方法名出现次数审计**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么必须有这个类（一条变异逃逸换来的）】
 * ══════════════════════════════════════════════════════════
 *
 * 上面的行为测试全部针对 `ScreenStateRecovery` 的**内部逻辑**，
 * 用注入的 lambda 喂数据 —— 它们证明了「逻辑对」，
 * 但**完全没有约束服务侧实际接了哪个入口**。
 *
 * 实测：把 `ReaderAccessibilityService` 的接线改回
 * `focus.getFocused()`（修复前的错误写法），上面 11 条测试
 * **全部照常通过** —— 变异逃逸。
 *
 * 这正是本项目反复出现的「漏接线」缺陷类型：
 * 引擎写对了、测试全绿，但**没人把它接上去**。
 *
 * 【本类的方法】方法名在**主代码**中的出现次数审计 ——
 * 严格入口 `getAccessibilityFocusStrict` 必须
 *   ① 有定义（`FocusManager`）
 *   ② 有且仅有**一处**服务侧调用点，且该调用点在
 *      `ScreenStateRecovery` 的构造实参里。
 */
class RecoveryWiringGuardTest {

    private fun readFile(path: String) = java.io.File(path).readText()

    /**
     * 剥掉注释后再断言 —— **这一步是必须的**
     *
     * 【为什么】第一版没剥注释，两条断言立刻假失败：
     *   · 「不得含 getFocused()」被**注释里**的说明文字命中
     *     （我在服务接线处写了一大段注释解释"不能用 getFocused()"）；
     *   · 「必须含 useInputFocusIfEmpty = true」同样被注释干扰。
     *
     * 注释掉代码 / 代码写在注释里，是完全不同的两件事。
     * 本项目已多次踩这个坑（`P1FixesRegressionTest` 把错误行断言成契约、
     * 早前 `@Volatile` 变异因注释含该词而逃逸）。
     * 规则：**结构性断言一律先剥注释**。
     */
    private fun codeOf(path: String) = readFile(path).lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val svcCode by lazy {
        codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
    }

    private val fmCode by lazy {
        codeOf("src/main/java/com/baicha/reader/core/FocusManager.kt")
    }

    @Test
    fun strictAccessor_isDefined() {
        assertTrue(
            "FocusManager 必须定义 getAccessibilityFocusStrict（对应 TalkBack 的 useInputFocusIfEmpty=false）",
            fmCode.contains("fun getAccessibilityFocusStrict()")
        )
    }

    @Test
    fun strictAccessor_isCalledByService() {
        assertTrue(
            "服务必须调用 getAccessibilityFocusStrict —— 否则严格入口是死代码",
            svcCode.contains("focus.getAccessibilityFocusStrict()")
        )
    }

    /**
     * ★ 关键守卫：屏幕状态恢复的接线**不得**用放宽入口
     *
     * 截取 `ScreenStateRecovery(` 构造块，断言其中不含 `getFocused()`。
     */
    @Test
    fun screenRecovery_doesNotUseRelaxedAccessor() {
        val start = svcCode.indexOf("screenRecovery = ScreenStateRecovery(")
        assertTrue("找不到 ScreenStateRecovery 构造点", start >= 0)

        // 构造块到下一个顶层赋值（约定用 "\n        )" 收尾）
        val end = svcCode.indexOf("\n        )", start)
        assertTrue("找不到构造块结尾", end > start)
        val block = svcCode.substring(start, end)

        assertFalse(
            "屏幕状态恢复不得用 getFocused()（含输入焦点回退，会误判「已有有效焦点」）:\n$block",
            block.contains("getFocused()")
        )
        assertTrue(
            "屏幕状态恢复必须用严格入口:\n$block",
            block.contains("getAccessibilityFocusStrict()")
        )
    }

    /**
     * 反向守卫：[getFocused] 仍必须存在且仍供导航使用
     *
     * 收紧恢复判定**不等于**把导航也改成严格的 ——
     * 编辑框里导航时**必须**能靠输入焦点回退找到焦点，
     * 否则用户在输入框里滑动会找不到位置。
     */
    @Test
    fun relaxedAccessor_stillExistsForNavigation() {
        assertTrue("getFocused 必须保留", fmCode.contains("fun getFocused()"))
        assertTrue(
            "getFocused 必须仍走 useInputFocusIfEmpty = true（编辑框导航依赖它）",
            fmCode.contains("getAccessibilityFocus(useInputFocusIfEmpty = true)")
        )
    }
}
