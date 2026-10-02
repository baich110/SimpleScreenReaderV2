package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作判定 —— **必须同时查位掩码与动作表**
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】天坦 `w7/NodeUtils.java` 有**两组不同**的函数
 * ══════════════════════════════════════════════════════════
 *
 * ```java
 *   // :263~274  hasAction —— **只**扫 actionList
 *   public static final boolean hasAction(NodeInfo nodeInfo, int i9) {
 *       List actionList = nodeInfo.getActionList();
 *       ... 遍历比对 id ...
 *   }
 *
 *   // :276~282  matchActionMask —— 位掩码 **OR** 动作表
 *   public static final boolean matchActionMask(NodeInfo nodeInfo, int i9) {
 *       if (((nodeInfo != null ? nodeInfo.getActions() : 0) & i9) == i9) return true;
 *       return hasAction(nodeInfo, i9);
 *   }
 *
 *   // :285~289  matchActionMask2 —— 两个动作，各自双路
 *   public static final boolean matchActionMask2(NodeInfo nodeInfo, int i9, int i10) {
 *       int actions = nodeInfo != null ? nodeInfo.getActions() : 0;
 *       return (actions & i9) == i9 || (actions & i10) == i10
 *           || hasAction(nodeInfo, i9) || hasAction(nodeInfo, i10);
 *   }
 * ```
 *
 * 【白茶原来的缺陷】`ShouldFocusNode.hasAction(vararg)`
 * **只做了 actionList 一路**，而它的调用方全部按 `matchActionMask*`
 * （双路）语义使用：
 * | 行 | 用途 | 天坦对应 |
 * |---|---|---|
 * | :287 | 滑块角色（4096/8192/SET_PROGRESS） | `matchActionMask3` |
 * | :399 | `n(node, true)` 的 `hasAction(1,16,32)` | `matchActionMask3` |
 * | :402 | `n(node, false)` 的 `hasAction(16,32)` | `matchActionMask2` |
 * | :1184 | WebView 判定（4096/8192） | `NodeUtils.hasAction4096` |
 *
 * 其中 :1184 最有说服力 —— 天坦自己就写着：
 * ```java
 *   public static final boolean hasAction4096(NodeInfo nodeInfo) {
 *       return matchActionMask2(nodeInfo, 4096, SoundTouch.f23227h);
 *   }
 * ```
 * `matchActionMask2` 是**双路**，白茶只做单路 ⇒
 * **WebView 判定会漏掉「只填位掩码」的节点**。
 *
 * 【真机后果】网页内容 / 滑块 / 部分可点击元素判定偏严 →
 * 被判「不可聚焦」→ **用户滑不到**（漏读内容）。
 */
class HasActionTwoPathTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val sf by lazy {
        codeOf("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt")
    }

    /** 取出 vararg 版 hasAction 的函数体 */
    private fun varargBody(): String {
        val idx = sf.indexOf("private fun hasAction(node: AccessibilityNodeInfo, vararg ids: Int)")
        assertTrue("必须存在 vararg 版 hasAction", idx >= 0)
        return sf.substring(idx, minOf(sf.length, idx + 1200))
    }

    /**
     * ★★ 必须查位掩码（`node.actions`）
     */
    @Test
    fun varargHasAction_checksBitmask() {
        val b = varargBody()
        assertTrue(
            "★ vararg 版 hasAction 必须查 `node.actions` 位掩码 —— " +
                    "天坦对应的是 matchActionMask*（双路），" +
                    "只查 actionList 会漏掉「只填位掩码」的节点 → **用户滑不到**。\n$b",
            b.contains("actions")
        )
        assertTrue(
            "必须有位与运算 `(actions and id) == id`:\n$b",
            b.contains("and") && b.contains("==")
        )
    }

    /** 必须仍查动作表（不得修一个丢一个） */
    @Test
    fun varargHasAction_stillChecksActionList() {
        val b = varargBody()
        assertTrue(
            "必须仍查 actionList（不得为了补位掩码而丢掉动作表）:\n$b",
            b.contains("actionList")
        )
    }

    /** 必须有异常保护（节点回收时会抛 IllegalStateException） */
    @Test
    fun varargHasAction_isSafeOnRecycledNode() {
        assertTrue("必须有 try/catch:\n${varargBody()}", varargBody().contains("catch"))
    }

    // ==================== y()/z() 仍必须是双路（不得被简化）====================

    /**
     * `y()` 必须与天坦 `matchActionMask2` 同构（两个掩码 + 两个动作表）
     */
    @Test
    fun y_matchesMatchActionMask2() {
        val idx = sf.indexOf("private fun y(node: AccessibilityNodeInfo, id1: Int, id2: Int)")
        assertTrue("必须存在 y()", idx >= 0)
        val body = sf.substring(idx, minOf(sf.length, idx + 400))
        assertTrue("y() 必须查两个位掩码:\n$body", body.contains("hasActionMask(node, id1)"))
        assertTrue(body.contains("hasActionMask(node, id2)"), )
        assertTrue("y() 必须查两个动作:\n$body", body.contains("hasAction(node, id1)"))
    }

    /** `z()` 必须是三个掩码 + 三个动作表 */
    @Test
    fun z_matchesMatchActionMask3() {
        val idx = sf.indexOf("private fun z(node: AccessibilityNodeInfo, id1: Int, id2: Int, id3: Int)")
        assertTrue("必须存在 z()", idx >= 0)
        val body = sf.substring(idx, minOf(sf.length, idx + 500))
        for (i in 1..3) {
            assertTrue("z() 必须查 hasActionMask(node, id$i):\n$body", body.contains("hasActionMask(node, id$i)"))
        }
    }

    /** `hasActionMask` 本身必须是纯位掩码（不得混入 actionList 造成重复） */
    @Test
    fun hasActionMask_isPureBitmask() {
        val idx = sf.indexOf("private fun hasActionMask(node: AccessibilityNodeInfo, id: Int)")
        assertTrue("必须存在 hasActionMask", idx >= 0)
        val body = sf.substring(idx, minOf(sf.length, idx + 300))
        assertTrue("必须做位与:\n$body", body.contains("and"))
        assertFalse("hasActionMask 不应查 actionList（那是 hasAction 的职责）", body.contains("actionList"))
    }

    // ==================== 与 NodeRoleHelper 的一致性 ====================

    /**
     * ★ `NodeRoleHelper` 里的同名函数**已经是双路**，两处语义必须一致
     *
     * 【为什么检查这个】本项目已多次出现「同一函数名在两个文件里
     * 各有实现、其中一个错」的情形（如 `isActionable` 两份拷贝）。
     */
    @Test
    fun nodeRoleHelper_varargHasAction_alsoTwoPath() {
        val nrh = codeOf("src/main/java/com/baicha/reader/core/NodeRoleHelper.kt")
        val idx = nrh.indexOf("private fun hasAction(node: AccessibilityNodeInfo, vararg actionIds: Int)")
        assertTrue("NodeRoleHelper 里应有 vararg 版 hasAction", idx >= 0)
        val body = nrh.substring(idx, minOf(nrh.length, idx + 300))
        assertTrue(
            "NodeRoleHelper 的 vararg 版必须也是双路（它已经是对的，作为参照）:\n$body",
            body.contains("hasActionMask") && body.contains("hasAction(")
        )
    }
}
