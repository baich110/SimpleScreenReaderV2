package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可见性判定 —— **真机「左右滑动找不到焦点」的根因**
 *
 * ══════════════════════════════════════════════════════════
 * 【真机现象】（用户实装后的原话，2026-10-03）
 * ══════════════════════════════════════════════════════════
 *
 * > 左右滑动还是失效，它只能获取到一个两个焦点，
 * > 而且很多焦点它都（获取不到）
 * > 它的焦点就完全弄拿不到。天坦把它引导过去，它都不读
 *
 * 日志指纹：
 * ```
 *   05:28:21.204  onGesture(Event): 4      ← 手势到了
 *   05:28:22.263  onGesture(Event): 3      ← 连续 9 次
 *   ...                                     ← **零导航输出**
 *
 *   05:31:32.181  触摸: 添加附件            ← 触摸探索**能读**
 *   05:31:33.422  触摸: 设置选项
 * ```
 *
 * **「触摸能读、滑动不能」这个反常组合就是本 bug 的指纹** ——
 * 触摸探索不经过 `shouldFocus`，它直接对触摸到的节点播报。
 *
 * ══════════════════════════════════════════════════════════
 * 【根因】原来用裸 `node.isVisibleToUser` 判否
 * ══════════════════════════════════════════════════════════
 *
 * 天坦 `w7/AccessibilityNodeInfoUtils.java:570~578`（原样摘录）：
 * ```java
 *   public static boolean isVisible(NodeInfo nodeInfo) {
 *       if (nodeInfo == null) { return false; }
 *       if (nodeInfo.isVisibleToUser()) { return true; }
 *       return NodeNavigator.isInRecyclerView(nodeInfo)
 *           && EventUtils.getNodeType(nodeInfo) != 15;
 *   }
 * ```
 *
 * **第二条兜底是关键**：`isVisibleToUser()` 为 false 时，
 * 只要节点属于 RecyclerView 且不是 type 15，**仍视为可见**。
 *
 * 白茶原来只做了第一条 ⇒ 真机上 `isVisibleToUser` 的
 * **大量假阴性**（列表项未进入视口 / 滚动中 / 被父容器裁剪 /
 * 自绘 UI）一律被判「不可见」⇒ 遍历找不到任何候选 ⇒
 * `moveFocus` 返回 null ⇒ `haptic.boundary()`（**只震动、不写日志**）
 * ⇒ 用户听到的正是「获取不到焦点的那种震动反馈」。
 *
 * 【`isInRecyclerView` 的实现】`w7/NodeNavigator.java:106~114`：
 * ```java
 *   isInRecyclerView = isInWebView(node) || isInListView(node)
 *   isInWebView      = isInScrollView = hasActionMask2(node, 1024, 2048)
 *   isInListView     = pkg.startsWith("org.mozilla.")
 * ```
 * 而 `matchActionMask2` 是**位掩码 OR actionList 双路**。
 */
class VisibilityFallbackTest {

    private fun node(
        id: Int,
        visible: Boolean,
        actions: Int = 0,
        pkg: String? = null,
        focusable: Boolean = true,
        text: String? = null,
        cls: String = "android.widget.TextView"
    ) = FakeNode(id, textValue = text).apply {
        this.visible = visible
        fakeActions = actions
        fakePackageName = pkg
        fakeFocusable = focusable
        fakeClassName = cls
    }

    /** `ACTION_NEXT_HTML_ELEMENT` —— 天坦用它判「在 RecyclerView/WebView 里」 */
    private val htmlNext = 1024

    /** `ACTION_PREVIOUS_HTML_ELEMENT` */
    private val htmlPrev = 2048

    // ==================== ★ 核心：RecyclerView 兜底 ====================

    /**
     * ★★ `isVisibleToUser=false` 但带 HTML 元素动作 → **应判可聚焦**
     *
     * 这是真机列表项的最典型形态，也是本次 P0 的直接修复点。
     */
    @Test
    fun invisibleButHasHtmlAction_isFocusable() {
        val n = node(1, visible = false, actions = htmlNext, text = "列表项")
        assertTrue(
            "★ 天坦 `isVisible()` 的第二条支路：isVisibleToUser 为 false 时，" +
                    "只要属于 RecyclerView（hasActionMask2(1024,2048)）且非 type 15，" +
                    "**仍算可见**。白茶原来只做第一条 ⇒ 真机整片列表被判不可见 ⇒ " +
                    "左右滑动找不到任何焦点。",
            ShouldFocusNode.shouldFocus(n)
        )
    }

    /** 2048 同样应触发兜底（`matchActionMask2` 是「任一」） */
    @Test
    fun invisibleButHasPreviousHtmlAction_isFocusable() {
        val n = node(2, visible = false, actions = htmlPrev, text = "列表项")
        assertTrue("2048 也应触发兜底", ShouldFocusNode.shouldFocus(n))
    }

    /** mozilla 包名（`isInListView`）同样触发兜底 */
    @Test
    fun invisibleButMozillaPackage_isFocusable() {
        val n = node(3, visible = false, pkg = "org.mozilla.firefox", text = "网页项")
        assertTrue(
            "`isInListView` = pkg.startsWith(\"org.mozilla.\") —— 也应兜底",
            ShouldFocusNode.shouldFocus(n)
        )
    }

    /** ★ 无任何 HTML 动作且不可见 → **仍应拒绝**（兜底不能变成"全都放行"） */
    @Test
    fun invisibleWithoutHtmlAction_isStillRejected() {
        val n = node(4, visible = false, actions = 0, text = "屏外元素")
        assertFalse(
            "★ 兜底不能变成「一律放行」—— 无 HTML 动作且不可见的节点仍应拒绝，" +
                    "否则会把屏外垃圾收进焦点序列",
            ShouldFocusNode.shouldFocus(n)
        )
    }

    /** 可见节点照常通过（不得为了修 bug 而改变正常路径） */
    @Test
    fun visibleNode_isFocusable() {
        assertTrue(ShouldFocusNode.shouldFocus(node(5, visible = true, text = "正常")))
    }

    // ==================== 接线守卫 ====================

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * ★★ `r()` 里**不得**再出现裸 `isVisibleToUser` 判否
     */
    @Test
    fun r_usesIsVisibleHelperNotBareCall() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt")
        val idx = src.indexOf("private fun r(node: AccessibilityNodeInfo")
        assertTrue("必须存在 r()", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 3000))
        assertFalse(
            "★ r() 里不得用裸 `!node.isVisibleToUser` 判否 —— " +
                    "必须走 isVisible() 辅助（含 RecyclerView 兜底）。" +
                    "这正是真机「左右滑动找不到焦点」的根因。\n$body",
            body.contains("!node.isVisibleToUser)")
        )
        assertTrue("必须调用 isVisible(node)", body.contains("!isVisible(node)"))
    }

    /** `isVisible` 辅助必须真的实现了两条支路 */
    @Test
    fun isVisibleHelper_hasBothBranches() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt")
        val idx = src.indexOf("private fun isVisible(node: AccessibilityNodeInfo)")
        assertTrue("必须存在 isVisible 辅助", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 800))
        assertTrue("支路①：isVisibleToUser", body.contains("isVisibleToUser"))
        assertTrue("支路②：isInRecyclerView 兜底", body.contains("isInRecyclerView"))
        assertTrue("支路②必须排除 type 15", body.contains("isType15"))
    }

    /** `isInRecyclerView` 必须同时覆盖两条来源 */
    @Test
    fun isInRecyclerView_coversBothSources() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt")
        val idx = src.indexOf("private fun isInRecyclerView(node: AccessibilityNodeInfo)")
        assertTrue("必须存在 isInRecyclerView", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 900))
        assertTrue(
            "必须覆盖 isInListView（mozilla 包名）",
            body.contains("org.mozilla.")
        )
        assertTrue(
            "必须覆盖 isInWebView（HTML 元素动作）",
            body.contains("ACTION_NEXT_HTML_ELEMENT") &&
                    body.contains("ACTION_PREVIOUS_HTML_ELEMENT")
        )
    }
}
