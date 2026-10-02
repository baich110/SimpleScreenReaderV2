package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色判定忠实度测试 —— 用**可读版反编译**核出的四处修正
 *
 * ══════════════════════════════════════════════════════════
 * 【背景】
 * ══════════════════════════════════════════════════════════
 *
 * `NodeRoleHelper.roleOf()` 对应天坦 `w7/EventUtils.getNodeType()`
 * （可读版 `w7/EventUtils.java:112~160`）。
 *
 * 用可读版逐分支对照后查出**四处语义错**。它们的共同特征：
 * 都是我**换用了另一个同名近似函数** ——
 *   · `isActionable` 被误用在需要 `hasRangeInfo` 的地方（2 处）
 *   · `isClickable` 被误用在需要 `isActionableForAccessibility` 的地方
 *   · `hasScrollAction` 被误用在需要 4 个 PAGE action 的地方
 *
 * 这类错误的可怕之处：**函数都存在、都能编译、语义却完全不同**。
 * 行为测试测不出（JVM 里 AccessibilityNodeInfo 是空壳），
 * 因此只能锁定源码结构。
 */
class NodeRoleHelperFidelityTest {

    private val src: String by lazy {
        java.io.File("src/main/java/com/baicha/reader/core/NodeRoleHelper.kt").readText()
    }

    /** 去注释的代码（避免注释里引用的源码把我自己的断言带偏） */
    private val code: String by lazy {
        src.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    // ==================== ① SeekBar 分支 ====================

    /**
     * ★★ 第 ③ 分支必须用 `hasRangeInfo`，**不是** `isActionable`
     *
     * 天坦 `EventUtils.java:122~124`：
     *   `hasRangeInfo(nodeInfo) && hasActionById(nodeInfo, R.id.accessibilityActionSetProgress)`
     *
     * 语义差别：`hasRangeInfo` = 「有 max/min/current 且区间有效」，
     * 滑块**必须**有它才能读出百分比；`isActionable` 是「可点击或可长按」，
     * 一个纯按钮也满足 —— 用它会让普通可点击控件被当成滑块。
     */
    @Test
    fun seekbar_branch_uses_hasRangeInfo_not_isActionable() {
        assertTrue(
            "SeekBar 分支应用 hasRangeInfo",
            code.contains("if (hasRangeInfo(node) && hasAction(node, android.R.id.accessibilityActionSetProgress))")
        )
        assertFalse(
            "不该用 isActionable（那是「可点击/长按」，与区间信息无关）",
            code.contains("if (isActionable(node) && hasAction(node, android.R.id.accessibilityActionSetProgress))")
        )
    }

    // ==================== ② ImageView 分支 ====================

    /**
     * ★★ ImageView 必须用 `isActionableForAccessibility`
     *
     * 天坦 `EventUtils.java:136~138`：
     *   `isActionableForAccessibility(nodeInfo) ? 7 : 6`
     * = `isClickable || hasActionMask(16)`
     *
     * 比 `isClickable` 宽：**没有 isClickable 但声明了 click action
     * 的图片**也算可点击图片（如 RecyclerView 里的卡片封面）。
     */
    @Test
    fun imageview_branch_uses_isActionableForAccessibility() {
        assertTrue(
            "ImageView 分支应用 isActionableForAccessibility",
            code.contains("isActionableForAccessibility(node)) ROLE_IMAGE_BUTTON")
        )
        assertFalse(
            "不该只判 isClickable（会漏掉「只声明 click action」的图片）",
            code.contains("if (isClickable(node)) ROLE_IMAGE_BUTTON")
        )
    }

    // ==================== ③ ProgressBar 分支 ====================

    /**
     * ★★ 第 ⑨ 分支同样用 `hasRangeInfo`
     * 天坦 `EventUtils.java:129~131`
     */
    @Test
    fun progressbar_branch_uses_hasRangeInfo() {
        assertTrue(
            "ProgressBar 分支应用 hasRangeInfo",
            code.contains("if (hasRangeInfo(node) && !hasAction(node, android.R.id.accessibilityActionSetProgress))")
        )
        assertFalse(
            "不该用 isActionable",
            code.contains("if (isActionable(node) && !hasAction(node, android.R.id.accessibilityActionSetProgress))")
        )
    }

    // ==================== ④ Pager 分支 ====================

    /**
     * ★★ Pager 必须判 4 个 PAGE action，**不是**滚动 action
     *
     * 天坦 `EventUtils.java:148~151` 判的是
     * `ACTION_PAGE_UP / PAGE_DOWN / PAGE_LEFT / PAGE_RIGHT`
     * （`o0/NodeInfo.java:188~191` 已核实）。
     *
     * 【我原来的错误】判「有滚动 action」——
     *   会把**所有可滚动容器**都当成 Pager。
     *   这是最严重的一处：列表页会被标成「多页面视图」，
     *   影响朗读角色词与依赖角色码的焦点判定分支。
     */
    @Test
    fun pager_branch_uses_page_actions_not_scroll() {
        assertTrue("Pager 分支应用 PAGE action", code.contains("hasAction(node, ACTION_PAGE_UP, ACTION_PAGE_DOWN)") ||
            code.contains("hasAction(node, ACTION_PAGE_UP"))
        assertFalse(
            "不该用 hasScrollAction 判 Pager（会误判所有滚动容器）",
            code.contains("if (hasScrollAction(node)) return ROLE_PAGER")
        )
    }

    @Test
    fun page_action_constants_match_android() {
        assertTrue("应有 ACTION_PAGE_UP", code.contains("ACTION_PAGE_UP = 16908360"))
        assertTrue("应有 ACTION_PAGE_DOWN", code.contains("ACTION_PAGE_DOWN = 16908361"))
        assertTrue("应有 ACTION_PAGE_LEFT", code.contains("ACTION_PAGE_LEFT = 16908362"))
        assertTrue("应有 ACTION_PAGE_RIGHT", code.contains("ACTION_PAGE_RIGHT = 16908363"))
    }

    // ==================== ⑤ 辅助函数必须存在且语义正确 ====================

    @Test
    fun required_helpers_exist() {
        assertTrue("需要 hasRangeInfo", code.contains("private fun hasRangeInfo("))
        assertTrue("需要 isActionableForAccessibility", code.contains("private fun isActionableForAccessibility("))
        assertTrue("需要 hasActionMask", code.contains("private fun hasActionMask("))
    }

    /**
     * ★ `hasRangeInfo` 必须校验 current ∈ [min, max]
     * 依据 `AccessibilityNodeInfoUtils.java:348~357`
     */
    @Test
    fun hasRangeInfo_validates_bounds() {
        val start = code.indexOf("private fun hasRangeInfo(")
        val body = code.substring(start, minOf(code.length, start + 400))
        assertTrue("应校验 current >= min", body.contains("range.current >= range.min"))
        assertTrue("应校验 current <= max", body.contains("range.current <= range.max"))
        assertTrue("应校验区间有效", body.contains("(range.max - range.min) > 0f"))
    }

    /**
     * ★ `isActionableForAccessibility` = `isClickable || hasAction(16)`
     */
    @Test
    fun isActionableForAccessibility_semantics() {
        val start = code.indexOf("private fun isActionableForAccessibility(")
        val body = code.substring(start, minOf(code.length, start + 300))
        assertTrue("应含 isClickable", body.contains("node.isClickable"))
        assertTrue("应含 action 16", body.contains("16"))
    }
}
