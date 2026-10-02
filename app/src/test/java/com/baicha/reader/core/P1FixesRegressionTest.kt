package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 修复回归测试（全项目 Bug 排查报告的 5 项）
 *
 * ══════════════════════════════════════════════════════════
 * 【共同模式：全部是「声明了但没接线 / 抄漏一条路径」】
 * ══════════════════════════════════════════════════════════
 *
 * 这五项都不是逻辑写错，而是**漏了**：
 *   · 漏注入（钩子声明后无人赋值）
 *   · 漏删除（把实例快照写回静态字段）
 *   · 漏限定（快路径没限定方向）
 *   · 漏路径（双路判定只实现一路）
 *   · 漏清空（见上一批的 seenChildren）
 *
 * 「漏」比「错」更难发现 —— 代码看起来完整、能编译、测试全绿。
 * 所以只能用**结构性断言**锁住：必须存在某调用、必须不存在某写法。
 */
class P1FixesRegressionTest {

    private fun readFile(path: String) = java.io.File(path).readText()

    private fun codeOf(path: String) = readFile(path).lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val svcCode by lazy {
        codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
    }
    private val traversalCode by lazy {
        codeOf("src/main/java/com/baicha/reader/core/TatansTraversal.kt")
    }
    private val focusSearchCode by lazy {
        codeOf("src/main/java/com/baicha/reader/core/FocusSearch.kt")
    }

    // ==================== P1-1 系统手势注入 ====================

    /**
     * ★★ `performGlobalAction` 必须被注入
     *
     * 原来：`GestureActionHandler` 声明可空钩子（:64）、在 `global()`（:128）
     * 读取，但**全项目从未赋值** ⇒ BACK / HOME / RECENTS /
     * NOTIFICATIONS / SCREENSHOT / 分屏 **6 个系统手势全部静默失败**。
     * 用户按三指左滑（返回）毫无反应、也无提示。
     */
    @Test
    fun perform_global_action_is_injected() {
        assertTrue(
            "服务必须注入 gestureActions.performGlobalAction，否则 6 个系统手势静默失败",
            svcCode.contains("gestureActions.performGlobalAction = ")
        )
        assertTrue(
            "应调用 AccessibilityService.performGlobalAction",
            svcCode.contains("performGlobalAction(action)")
        )
    }

    // ==================== P1-2 静态代数回写 ====================

    /**
     * ★★ 不得把实例快照写回静态 `cacheGeneration`
     *
     * 原来 `Companion.cacheGeneration = generation`（实例构造时抓的快照）
     * 会把 `invalidateIndexCache()` 的递增**还原**，
     * 使代数校验永远成立、缓存永不失效 → 索引错位、焦点跳错位置。
     */
    @Test
    fun static_generation_is_never_written_back() {
        assertFalse(
            "不得写回静态 cacheGeneration（会让 invalidateIndexCache 失效）",
            traversalCode.contains("Companion.cacheGeneration = generation")
        )
        assertTrue(
            "但仍需读取它做比较",
            traversalCode.contains("Companion.cacheGeneration == generation")
        )
    }

    // ==================== P1-3 BACKWARD 快路径 ====================

    /**
     * ★★ `findFirstFocus` 的快路径必须限定 FORWARD
     *
     * 原来 `if (accept(root)) return root` 在方向分支之前 ——
     * BACKWARD 时直接返回根节点，而语义是「找**最后**一个」。
     */
    @Test
    fun first_focus_fast_path_is_forward_only() {
        assertTrue(
            "快路径必须限定 FORWARD",
            focusSearchCode.contains("if (direction == TraversalDirection.FORWARD && accept(root)) return root")
        )
        assertFalse(
            "不能无条件用快路径（BACKWARD 会返回根节点）",
            focusSearchCode.contains("\n        if (accept(root)) return root")
        )
    }

    // ==================== P1-4 HTML 元素双路判定 ====================

    /**
     * ★★ `hasHtmlElementAction` 必须同时查 1024 和 2048，且走位掩码 + actionList
     *
     * 天坦 `NodeUtils.matchActionMask2(node, 1024, 2048)`：
     *   `(actions & id) == id || hasAction(id)`
     */
    @Test
    fun html_element_action_checks_both_ids_and_both_paths() {
        val start = traversalCode.indexOf("private fun hasHtmlElementAction(")
        val body = traversalCode.substring(start, minOf(traversalCode.length, start + 800))

        assertTrue("必须查 1024：$body".take(300), body.contains("ACTION_NEXT_HTML_ELEMENT"))
        assertTrue("必须查 2048", body.contains("ACTION_PREVIOUS_HTML_ELEMENT"))
        assertTrue("必须走位掩码路径 (actions and id)", body.contains("actions and ACTION_NEXT_HTML_ELEMENT"))
        assertTrue("必须走 actionList 路径", body.contains("actionList"))
    }

    @Test
    fun html_element_constants_match_android() {
        assertTrue(
            "1024 = ACTION_NEXT_HTML_ELEMENT",
            traversalCode.contains("ACTION_NEXT_HTML_ELEMENT = 1024")
        )
        assertTrue(
            "2048 = ACTION_PREVIOUS_HTML_ELEMENT",
            traversalCode.contains("ACTION_PREVIOUS_HTML_ELEMENT = 2048")
        )
    }
}
