package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「节点是否可操作」判定 —— **忠实度核查**
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】天坦 `w7/NodeUtils.java:136~139`（原样摘录）
 * ══════════════════════════════════════════════════════════
 *
 * ```java
 *   public static final boolean isActionable(NodeInfo nodeInfo) {
 *       Intrinsics.checkNotNullExpressionValue(nodeInfo, "<this>");
 *       return nodeInfo.isFocusable() || nodeInfo.isClickable()
 *           || nodeInfo.isLongClickable()
 *           || matchActionMask3(nodeInfo, 1, 16, 32)
 *           || matchActionMask2(nodeInfo, com.vivo.speechsdk.module.vad.c.f23604z, 2048);
 *   }
 * ```
 *
 * `matchActionMask3`（`:291~294`）：
 * ```java
 *   public static final boolean matchActionMask3(NodeInfo nodeInfo, int i9, int i10, int i11) {
 *       int actions = nodeInfo != null ? nodeInfo.getActions() : 0;
 *       return (actions & i9) == i9 || (actions & i10) == i10 || (actions & i11) == i11
 *           || hasAction(nodeInfo, i9) || hasAction(nodeInfo, i10) || hasAction(nodeInfo, i11);
 *   }
 * ```
 *
 * `hasAction`（`:263~274`）：遍历 `getActionList()`，比对每个动作的 id。
 *
 * ⇒ **两个独立路径**：
 *   ① **位掩码**：`node.getActions() & id`
 *   ② **动作表**：`node.getActionList()` 里存在该 id
 *   任一为真即算「有该动作」。
 *
 * ══════════════════════════════════════════════════════════
 * 【本轮发现的两个偏差】
 * ══════════════════════════════════════════════════════════
 *
 * 白茶原实现（`AccessibilityEventHandler.isActionable`）：
 * ```kotlin
 *   node.actionList.any {
 *       it.id == 1 || it.id == 16 || it.id == 32 || it.id == 131072 || it.id == 2048
 *   }
 * ```
 *
 * **偏差 A：131072 应为 1024**
 *   天坦用的是 `f23604z`，其值由 `o0/NodeInfo.java` 确认：
 *   ```
 *    case com.vivo.speechsdk.module.vad.c.f23604z /* 1024 */:
 *        return "ACTION_NEXT_HTML_ELEMENT";
 *   ```
 *   即 **1024 = ACTION_NEXT_HTML_ELEMENT**（与 2048 =
 *   `ACTION_PREVIOUS_HTML_ELEMENT` 成对）。
 *   白茶写的 **131072** 是**事件**常量
 *   （`TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY`），
 *   作为 action id 毫无意义 —— 该条件**永远为假**。
 *
 * **偏差 B：缺少位掩码路径**
 *   白茶只查 `actionList`，没查 `getActions()`。
 *   两者是**独立字段**：部分 App 把动作只放在位掩码里。
 *   ⇒ 那些节点被判「不可操作」→ `isActionable` 为假 →
 *   **用户滑不到它们**（漏掉可聚焦元素）。
 */
class IsActionableFidelityTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val handler by lazy {
        codeOf("src/main/java/com/baicha/reader/core/AccessibilityEventHandler.kt")
    }

    /** 取出 isActionable 的函数体 */
    private fun body(): String {
        val idx = handler.indexOf("private fun isActionable(")
        assertTrue("必须存在 isActionable", idx >= 0)
        return handler.substring(idx, minOf(handler.length, idx + 1400))
    }

    // ==================== 偏差 A：131072 应为 1024 ====================

    /**
     * ★★ 不得再出现 131072 —— 它是事件常量，不是动作 ID
     *
     * 【依据】天坦用 `f23604z`（= 1024，见 `o0/NodeInfo.java:477`），
     * 官方 `AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT = 1024`。
     */
    @Test
    fun doesNotUseEventConstantAsActionId() {
        assertFalse(
            "★ isActionable 里不得出现 131072 —— 它是**事件**常量" +
                    "（TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY），" +
                    "作为 action id 永远为假。天坦用的是 1024" +
                    "（ACTION_NEXT_HTML_ELEMENT）。\n${body()}",
            body().contains("131072")
        )
    }

    @Test
    fun usesActionNextHtmlElement1024() {
        // 允许两种写法：字面量 1024 或具名常量 ACTION_NEXT_HTML_ELEMENT
        // （用常量更好 —— 但**取值必须真的是 1024**，
        //   由 actionIdsMatchPlatformConstants 与官方常量锁定）
        val b = body()
        assertTrue(
            "必须检查 1024（ACTION_NEXT_HTML_ELEMENT）—— 与 2048 成对:\n$b",
            b.contains("1024") || b.contains("ACTION_NEXT_HTML_ELEMENT")
        )
        // 且常量定义处必须是 1024（不是 131072）
        val all = handler
        assertTrue(
            "ACTION_NEXT_HTML_ELEMENT 必须定义为 1024（天坦 f23604z = 1024）",
            all.contains("ACTION_NEXT_HTML_ELEMENT = 1024")
        )
    }

    @Test
    fun actionIdsMatchPlatformConstants() {
        // 官方取值（AOSP AccessibilityNodeInfo）
        assertEquals(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
        assertEquals(16, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        assertEquals(32, android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
        assertEquals(1024, android.view.accessibility.AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT)
        assertEquals(2048, android.view.accessibility.AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT)
    }

    // ==================== 偏差 B：必须有位掩码路径 ====================

    /**
     * ★★ 必须同时查 `getActions()` 位掩码
     *
     * 依据 `matchActionMask3` 的第一行：
     * `int actions = nodeInfo.getActions();`
     * 然后 `(actions & i9) == i9`。
     */
    @Test
    fun checksActionBitmaskNotOnlyActionList() {
        val b = body()
        val hasBitmask = b.contains("actions") || b.contains("hasAction(") ||
                b.contains("bitmask") || b.contains("动作位掩码")
        assertTrue(
            "★ isActionable 必须同时查 `getActions()` 位掩码 —— " +
                    "天坦 matchActionMask3 的第一条就是 `(actions & i9) == i9`。\n" +
                    "部分 App 只把动作放在位掩码里、不放进 actionList，" +
                    "只查 actionList 会把那些节点判为不可操作 → **用户滑不到它们**。\n$b",
            hasBitmask
        )
        assertTrue(
            "必须真的读 node.actions（而不是只查 actionList）:\n$b",
            b.contains("node.actions") || b.contains(".actions")
        )
    }

    // ==================== 其他条件不得丢 ====================

    @Test
    fun keepsFocusableClickableLongClickable() {
        val b = body()
        assertTrue("必须保留 isFocusable 检查", b.contains("isFocusable"))
        assertTrue("必须保留 isClickable 检查", b.contains("isClickable"))
        assertTrue("必须保留 isLongClickable 检查", b.contains("isLongClickable"))
    }

    /** 异常必须被吞掉（节点已回收时读属性会抛 IllegalStateException） */
    @Test
    fun handlesRecycledNodeSafely() {
        val b = body()
        assertTrue("必须有 try/catch（节点回收时会抛异常）:\n$b", b.contains("catch"))
    }
}
