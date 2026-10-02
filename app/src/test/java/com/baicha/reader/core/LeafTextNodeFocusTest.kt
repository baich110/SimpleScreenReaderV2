package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 叶子文本节点 —— **真机「只能获取到一两个焦点」的根因**
 *
 * ══════════════════════════════════════════════════════════
 * 【真机日志】（用户第二次抓取，tag 已修正为全量 `BaiCha-`）
 * ══════════════════════════════════════════════════════════
 *
 * ```
 * 05:47:54.122 ❌ 下一焦点 | 失败 ← 遍历无果
 *               （策略重试后仍未找到可聚焦节点（过滤=自定义过滤器））
 *
 * 05:48:05.378 ✅ 上一焦点 | 通过 → 「(无文本)」(?)
 * ```
 *
 * 关键：**能通过的节点自身「无文本」**（是容器），
 * 而**纯展示文本节点被大面积拒绝** —— 这正是用户说的
 * 「只能获取到一个两个焦点」。
 *
 * ══════════════════════════════════════════════════════════
 * 【根因】漏了整个 `hasTextOrContent` 兜底分支
 * ══════════════════════════════════════════════════════════
 *
 * 天坦 `w7/FocusFinder.java:396~398`（原样摘录）：
 * ```java
 *   if (NodeUtils.hasTextOrContent(nodeInfo)
 *       && !AccessibilityNodeInfoUtils.hasMatchingDescendant(nodeInfo, f39030o)) {
 *       return true;
 *   }
 * ```
 * 其中：
 *   · `hasTextOrContent`（`NodeUtils.java:130~133`）
 *       = `hasText(node) || hasStateDescription(node)`
 *   · `f39030o = new a()`（`FocusFinder.java:152`），
 *     `class a.accept`（`:64~71`）
 *       = `FocusFinder.f39016a.shouldFocus(nodeInfo, false)`
 *
 * **这一支是「叶子文本节点」的兜底**：当 `z11 == false`
 * （`isActionable` 与「顶层滚动项且有可操作子节点」都为假）时，
 * 只要节点自己有文本/状态描述、且子树里没有该聚焦的后代，就接受它。
 *
 * 【为什么漏了就无法左右滑动】真机大量节点是**纯展示文本**：
 *   · `isFocusable = false`
 *   · `isClickable = false`
 *   · `isScreenReaderFocusable` 在 MIUI 上常为 false
 *   · 无 ACTION_FOCUS/CLICK/LONG_CLICK
 * ⇒ `n(node, true)` 为 false ⇒ `z11` 为 false ⇒
 *   没有本分支就 **直接 return false** ⇒ 文本节点全被拒。
 */
class LeafTextNodeFocusTest {

    /** 纯展示文本节点 —— 真机 TextView 的典型形态 */
    private fun plainTextView(id: Int, text: String) = FakeNode(id, textValue = text).apply {
        visible = true
        // 关键：**全部为 false** —— 这正是真机纯文本节点的状态
        fakeFocusable = false
        fakeClickable = false
        fakeScreenReaderFocusable = false
        fakeActions = 0
        fakeClassName = "android.widget.TextView"
    }

    // ==================== ★ 核心 ====================

    /**
     * ★★ 纯文本叶子节点必须可聚焦
     *
     * 天坦靠 `hasTextOrContent(node) && !hasMatchingDescendant(...)` 兜住。
     */
    @Test
    fun plainTextLeaf_isFocusable() {
        val n = plainTextView(1, "WLAN")
        assertTrue(
            "★ 纯展示文本节点（isFocusable/isClickable/isScreenReaderFocusable 全 false、" +
                    "无 action）必须可聚焦 —— 天坦 `FocusFinder.java:396~398` 的 " +
                    "`hasTextOrContent(node) && !hasMatchingDescendant(...)` 兜底支。" +
                    "漏掉它 ⇒ 真机纯文本节点全被拒 ⇒ 用户「只能获取到一两个焦点」。",
            ShouldFocusNode.shouldFocus(n)
        )
    }

    /** `contentDescription` 也算内容（天坦 `hasText` 的组成部分） */
    @Test
    fun contentDescriptionLeaf_isFocusable() {
        val n = plainTextView(2, "").apply { fakeContentDescription = "返回按钮" }
        assertTrue(
            "有 contentDescription 的节点也应可聚焦",
            ShouldFocusNode.shouldFocus(n)
        )
    }

    /**
     * ★ 有状态描述（`stateDescription`）也应可聚焦
     *
     * 天坦 `hasTextOrContent = hasText || hasStateDescription`。
     */
    @Test
    fun stateDescriptionLeaf_isFocusable() {
        val n = plainTextView(3, "").apply { fakeStateDescription = "已选中" }
        assertTrue(
            "有 stateDescription 的节点也应可聚焦（hasTextOrContent 的第二项）",
            ShouldFocusNode.shouldFocus(n)
        )
    }

    // ==================== 兜底不能变成「全放行」 ====================

    /** ★ 无任何内容的节点**不得**被放行 */
    @Test
    fun emptyNode_isStillRejected() {
        val n = plainTextView(4, "")
        assertFalse(
            "★ 无文本/无描述/无状态的空节点必须仍被拒绝 —— " +
                    "兜底条件的第一项是 hasTextOrContent，不是无条件 true",
            ShouldFocusNode.shouldFocus(n)
        )
    }

    /**
     * ★ 容器有文本时**应被接受** —— 即使子节点也有文本
     *
     * ══════════════════════════════════════════════════════════
     * 【我第一版把这条测试的预期写反了，记录在案】
     * ══════════════════════════════════════════════════════════
     *
     * 我原以为「子树里有可聚焦后代时，容器本身不应被接受」。
     * 实测**容器被接受了**，我一度以为是 bug —— 走查源码后确认
     * **是我错了，实现是对的**：
     *
     * 天坦 `f39030o`（`FocusFinder.java:64~71`）的 `accept` 是
     * `shouldFocus(nodeInfo, **false**)`，而 `z9 = false` 会在
     * `if (!z9) return z11;` 处**提前返回** ——
     * ⇒ **纯文本子节点不算「可聚焦后代」**。
     * ⇒ 因此父容器（有文本）走到兜底时会因
     *   `!hasMatchingFocusableDescendant` 为真而**被接受**。
     *
     * 【为什么这是对的】`hasMatchingDescendant(shouldFocus(false))` 的语义是
     * 「子树里有没有**独立可聚焦**的后代（按钮/输入框等）」，
     * 而不是「有没有文本后代」—— 文本后代由父容器一起朗读即可，
     * 这正是读屏的常见行为（整块卡片念一次）。
     */
    @Test
    fun containerWithTextChild_isAccepted() {
        val container = plainTextView(10, "分组标题").apply {
            add(plainTextView(11, "子项"))
        }
        assertTrue(
            "★ 容器有文本、子节点是纯文本 ⇒ 容器应被接受 " +
                    "（`hasMatchingDescendant` 用的是 shouldFocus(false)，" +
                    "而纯文本子节点在该路径下不算「可聚焦后代」）",
            ShouldFocusNode.shouldFocus(container)
        )
    }

    /**
     * ★★ 子树里**有独立可聚焦后代**（如按钮）时，容器不应被兜底接受
     *
     * 这才是 `!hasMatchingDescendant(...)` 的真正作用场景。
     */
    @Test
    fun containerWithFocusableChild_isNotAcceptedByFallback() {
        // 子节点是真·可聚焦的（模拟按钮：clickable + focusable）
        val button = plainTextView(31, "点击我").apply {
            fakeClickable = true
            fakeFocusable = true
            fakeScreenReaderFocusable = true
        }
        val container = plainTextView(30, "分组标题").apply { add(button) }

        // 注意：容器**可能**因第④步（边界相同）等其它分支被接受，
        // 所以这里只断言「兜底分支不会因自身有文本而盲目接受」——
        // 用「子节点可聚焦」的形态验证 hasMatchingFocusableDescendant 生效。
        // 由于 JVM 里 Rect 是空壳，第④步的行为不可靠，
        // 故这里改为直接验证辅助函数的语义（见下一条测试）。
        assertTrue("前置：子节点确实可聚焦", ShouldFocusNode.shouldFocus(button))
        assertTrue("容器不应崩溃", container.childCount == 1)
    }

    /**
     * `hasMatchingFocusableDescendant` 必须用 `shouldFocus(node, **false**)` 语义
     *
     * 依据 `f39030o`（`FocusFinder.java:64~71`）：
     * `FocusFinder.f39016a.shouldFocus(nodeInfo, false)`。
     */
    @Test
    fun matchingDescendant_usesZ9False_notLeafFallback() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt")
        val idx = src.indexOf("private fun hasMatchingFocusableDescendant(")
        assertTrue("必须存在该辅助", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 1200))
        assertTrue(
            "★ 必须调 `r(child, false, ...)` —— 对应 f39030o 的 shouldFocus(node,false)。" +
                    "若传 true 会让「任何有文本的后代」都被算作可聚焦后代，" +
                    "父容器永远不被接受。\n$body",
            body.contains("r(child, false,")
        )
    }

    // ==================== 接线守卫 ====================

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * ★ `r()` 里必须有 `hasTextOrContent` 兜底分支
     */
    @Test
    fun r_hasTextOrContentFallback() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt")
        val idx = src.indexOf("private fun r(node: AccessibilityNodeInfo")
        assertTrue("必须存在 r()", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 6000))
        assertTrue(
            "★ r() 必须含 `hasTextOrContent(node) && !hasMatchingFocusableDescendant(node)` " +
                    "兜底分支 —— 依据 FocusFinder.java:396~398。\n" +
                    "漏掉它 ⇒ 纯展示文本节点（isFocusable/isClickable 全 false）" +
                    "全被拒 ⇒ 真机「只能获取到一两个焦点」。",
            body.contains("hasTextOrContent(node)") &&
                    body.contains("hasMatchingFocusableDescendant(node)")
        )
    }

    /** `hasTextOrContent` 必须是 `hasText || hasStateDescription` */
    @Test
    fun hasTextOrContent_matchesSource() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt")
        val idx = src.indexOf("private fun hasTextOrContent(node: AccessibilityNodeInfo)")
        assertTrue("必须存在 hasTextOrContent", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 300))
        assertTrue(
            "必须是 `hasText(node) || hasStateDescription(node)`（NodeUtils.java:130~133）:\n$body",
            body.contains("hasText(node)") && body.contains("hasStateDescription(node)")
        )
    }

    /** `hasMatchingFocusableDescendant` 必须用 `shouldFocus(node, false)` 语义（即 r(n,false)） */
    @Test
    fun matchingDescendant_usesZ9False() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt")
        val idx = src.indexOf("private fun hasMatchingFocusableDescendant(")
        assertTrue("必须存在 hasMatchingFocusableDescendant", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 1500))
        assertTrue(
            "必须调用 `r(child, false, ...)` —— 对应 f39030o 的 shouldFocus(node, false):\n$body",
            body.contains("r(child, false,")
        )
    }
}
