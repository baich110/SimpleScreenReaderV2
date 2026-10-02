package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FocusSearch` 遍历语义忠实度测试
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么必须单独立这个测试类】
 * ══════════════════════════════════════════════════════════
 *
 * 原有测试（`FrameworkIntegrityTest` / `WindowTraversalTest.FocusSearchRegressionTest`）
 * 只覆盖「参数为 null 时返回 null」的形状断言 —— **搜索逻辑一行没跑到**。
 * 于是下面的错误能一路绿灯活到今天。
 *
 * 这正是本项目反复踩的同一类坑：**「测试全绿 ≠ 逻辑正确」**，
 * 因为测试根本没碰到逻辑。
 *
 * 本类依赖新引入的 [FakeNode]（子类化 `AccessibilityNodeInfo`，
 * 实测可实例化），首次让遍历控制流可被真实断言。
 *
 * ══════════════════════════════════════════════════════════
 * 【依据的源码】
 * ══════════════════════════════════════════════════════════
 *
 * TalkBack `utils/src/main/java/com/google/android/accessibility/utils/traversal/TraversalStrategyUtils.java`
 *
 * `searchFocus()` 第 351~380 行（原样摘录）：
 * ```
 *     AccessibilityNodeInfoCompat targetNode = currentFocus;
 *     Set<AccessibilityNodeInfoCompat> seenNodes = new HashSet<>();
 *
 *     do {
 *       seenNodes.add(targetNode);
 *       targetNode = traversal.findFocus(targetNode, direction);
 *       if (seenNodes.contains(targetNode)) {
 *         LogUtils.e(TAG, "Found duplicate during traversal: %s", targetNode);
 *         return null;
 *       }
 *     } while (targetNode != null && !filter.accept(targetNode));
 *
 *     return targetNode;
 * ```
 *
 * `findFirstFocusInNodeTree()` 第 396~410 行（原样摘录）：
 * ```
 *     if (root == null) { return null; }
 *     AccessibilityNodeInfoCompat firstNode = traversalStrategy.focusFirst(root, direction);
 *
 *     if (nodeFilter.accept(firstNode)) {
 *       return firstNode;
 *     }
 *     return TraversalStrategyUtils.searchFocus(traversalStrategy, firstNode, direction, nodeFilter);
 * ```
 *
 * 【这两段推出的唯一正确实现】
 *   `firstNode` **必须**来自 `traversal.focusFirst(root, direction)`（白茶对应 `start()`），
 *   **绝不能**直接用传入的 `root`。
 */
class FocusSearchSemanticsTest {

    /**
     * 可控假遍历器 —— 按 **序号** 建模遍历序，记录每次调用的实参
     *
     * `order`     = 该策略下的遍历序（等价于真实遍历器吐出的顺序）
     * `focusable` = 哪些节点应被 filter 接受
     */
    private class FakeTraversal(
        private val order: List<Int>,
        private val focusable: Set<Int>
    ) : TraversalStrategy {

        val nextCalls = mutableListOf<Pair<Int, Int>>()
        val startCalls = mutableListOf<Pair<Int, Int>>()

        override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
            val from = (node as? FakeNode)?.nodeId ?: return null
            nextCalls += from to direction
            val idx = order.indexOf(from)
            if (idx < 0) return null
            val step = if (direction == TraversalStrategy.DIRECTION_NEXT) 1 else -1
            val target = idx + step
            if (target < 0 || target >= order.size) return null
            return FakeNode(order[target])
        }

        override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
            val from = (root as? FakeNode)?.nodeId ?: return null
            startCalls += from to direction
            if (order.isEmpty()) return null
            return FakeNode(
                if (direction == TraversalStrategy.DIRECTION_NEXT) order.first() else order.last()
            )
        }

        override fun release() = Unit

        fun acceptable(key: Int) = focusable.contains(key)
    }

    /** filter：只接受 `focusable` 里的序号 */
    private fun filterOf(t: FakeTraversal): (AccessibilityNodeInfo) -> Boolean =
        { node -> (node as? FakeNode)?.let { t.acceptable(it.nodeId) } ?: false }

    private fun idOf(node: AccessibilityNodeInfo?): Int =
        (node as? FakeNode)?.nodeId ?: -1

    // ==================== searchFocus 契约 ====================

    @Test
    fun searchFocus_returns_first_accepted_node() {
        val t = FakeTraversal(listOf(1, 2, 3), setOf(2, 3))
        val r = FocusSearch.searchFocus(
            t, FakeNode(1), TraversalStrategy.DIRECTION_NEXT, filterOf(t)
        )
        assertEquals("应返回第一个满足 filter 的节点", 2, idOf(r))
    }

    @Test
    fun searchFocus_skips_unaccepted_nodes() {
        val t = FakeTraversal(listOf(1, 2, 3, 4), setOf(4))
        val r = FocusSearch.searchFocus(
            t, FakeNode(1), TraversalStrategy.DIRECTION_NEXT, filterOf(t)
        )
        assertEquals("应跳过 2、3 直到 4", 4, idOf(r))
    }

    @Test
    fun searchFocus_returns_null_at_boundary() {
        val t = FakeTraversal(listOf(1, 2), setOf(1, 2))
        val r = FocusSearch.searchFocus(
            t, FakeNode(2), TraversalStrategy.DIRECTION_NEXT, { true }
        )
        assertNull("走到末尾应返回 null", r)
    }

    /**
     * 环检测 —— 依据 `seenNodes.contains(targetNode)`（第 372 行）
     *
     * 自环策略下必须优雅返回 null，而不是死循环
     * （真机死循环会卡死无障碍线程 → 服务被系统禁用）。
     */
    @Test
    fun searchFocus_detects_cycle_and_returns_null() {
        val selfLoop = object : TraversalStrategy {
            override fun next(node: AccessibilityNodeInfo, direction: Int) = node
            override fun start(root: AccessibilityNodeInfo, direction: Int) = root
            override fun release() = Unit
        }
        assertNull(
            "自环必须被检测并返回 null",
            FocusSearch.searchFocus(selfLoop, FakeNode(9), TraversalStrategy.DIRECTION_NEXT) { false }
        )
    }

    /**
     * 两节点互指成环（A→B→A）也必须被检测
     */
    @Test
    fun searchFocus_detects_two_node_cycle() {
        val pingPong = object : TraversalStrategy {
            override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? =
                FakeNode(if (id(node) == 1) 2 else 1)

            override fun start(root: AccessibilityNodeInfo, direction: Int) = root
            override fun release() = Unit
            private fun id(n: AccessibilityNodeInfo) = (n as? FakeNode)?.nodeId ?: -1
        }
        assertNull(
            "A→B→A 必须被检测",
            FocusSearch.searchFocus(pingPong, FakeNode(1), TraversalStrategy.DIRECTION_NEXT) { false }
        )
    }

    // ==================== findFirstFocus：本轮修复点 ====================

    /**
     * FORWARD、首节点可聚焦 → 直接返回首节点，**不应**推进遍历
     *
     * 依据：第 403~405 行 `if (nodeFilter.accept(firstNode)) { return firstNode; }`
     */
    @Test
    fun findFirstFocus_forward_returns_first_when_accepted() {
        val t = FakeTraversal(listOf(5, 6, 7), setOf(5, 6, 7))
        val r = FocusSearch.findFirstFocus(
            t, FakeNode(5), TraversalDirection.FORWARD, filterOf(t)
        )
        assertEquals(5, idOf(r))
        assertTrue("首节点即命中时不应推进遍历", t.nextCalls.isEmpty())
    }

    /**
     * FORWARD、首节点不可聚焦 → 必须从它出发继续向后搜索（不是返回 null）
     */
    @Test
    fun findFirstFocus_forward_searches_on_when_first_rejected() {
        val t = FakeTraversal(listOf(1, 2, 3), setOf(3))
        val r = FocusSearch.findFirstFocus(
            t, FakeNode(1), TraversalDirection.FORWARD, filterOf(t)
        )
        assertEquals("首节点被拒后应继续找到 3", 3, idOf(r))
    }

    /**
     * ★★★ 本轮定位的核心缺陷 #1：
     * FORWARD 的「root 即命中」快路径**绕过了策略起点**。
     *
     * 依据：TalkBack 一律先 `focusFirst(root, direction)`，
     * **从不**直接拿 `root` 当候选项。
     *
     * 走查（本测试）：order=[1,2,3]，focusable={1,2,3}，root=FakeNode(99)
     *   正确：start(FORWARD) → 1 → 返回 1
     *   原实现：`accept(root=99)` 为 false（99 不在 focusable）
     *           → 落到 else 分支 start() → 1，**这条恰好偶然正确**
     *   所以本用例用 root=2（在 order 里且可聚焦）来暴露：
     *           start(FORWARD) 应给 1，原实现直接返回 root=2  ❌
     */
    @Test
    fun findFirstFocus_forward_must_use_strategy_start_not_root() {
        val t = FakeTraversal(listOf(1, 2), setOf(1, 2))
        val r = FocusSearch.findFirstFocus(
            t, FakeNode(2), TraversalDirection.FORWARD, filterOf(t)
        )
        assertEquals("即使 root 可聚焦，也必须返回策略起点 1", 1, idOf(r))
    }

    /**
     * ★★★ 本轮定位的核心缺陷 #1 的对照：根确为策略起点时行为不变
     *
     * 保证修复不会把原本正确的情形改坏。
     */
    @Test
    fun findFirstFocus_forward_root_equals_start_still_works() {
        val t = FakeTraversal(listOf(1, 2, 3), setOf(1, 2, 3))
        val r = FocusSearch.findFirstFocus(
            t, FakeNode(1), TraversalDirection.FORWARD, filterOf(t)
        )
        assertEquals("root 就是起点时应返回 1", 1, idOf(r))
    }

    /**
     * BACKWARD 返回**遍历序最后一个**可聚焦节点
     *
     * 走查：order=[1,2,3]，focusable={3}
     */
    @Test
    fun findFirstFocus_backward_returns_last_accepted() {
        val t = FakeTraversal(listOf(1, 2, 3), setOf(3))
        val r = FocusSearch.findFirstFocus(
            t, FakeNode(2), TraversalDirection.BACKWARD, filterOf(t)
        )
        assertEquals("BACKWARD 应返回最后一个（3）", 3, idOf(r))
    }

    /**
     * BACKWARD 且末尾不可聚焦 → 一路回退到头部可聚焦节点
     *
     * 走查：order=[1,2,3]，focusable={1}
     *   start(BACKWARD) → 3，被拒
     *   searchFocus(from=3, BACKWARD)：next(3)=2 被拒 → next(2)=1 接受 → 1
     */
    @Test
    fun findFirstFocus_backward_walks_to_head() {
        val t = FakeTraversal(listOf(1, 2, 3), setOf(1))
        val r = FocusSearch.findFirstFocus(
            t, FakeNode(1), TraversalDirection.BACKWARD, filterOf(t)
        )
        assertEquals("应回退到头部可聚焦节点 1", 1, idOf(r))
    }

    /**
     * BACKWARD 时**不应**因 root 可聚焦而短路（root 与起点无关）
     *
     * 走查：order=[1,2,3]，focusable={1,2,3}，root=2
     *   正确：start(BACKWARD) → 3 → 返回 3
     */
    @Test
    fun findFirstFocus_backward_ignores_root_fast_path() {
        val t = FakeTraversal(listOf(1, 2, 3), setOf(1, 2, 3))
        val r = FocusSearch.findFirstFocus(
            t, FakeNode(2), TraversalDirection.BACKWARD, filterOf(t)
        )
        assertEquals("BACKWARD 必须返回 3，不能因 root=2 可聚焦而短路", 3, idOf(r))
    }

    /**
     * ★★★ 本轮定位的核心缺陷 #2：
     * `findFirstFocus` 必须**始终**调用一次 `traversal.start()`
     * （TalkBack 的 `focusFirst`），否则「策略起点 ≠ root」时定位错误。
     *
     * 这是「接线缺失」型缺陷：以前的测试从不检查 start 是否被调用。
     */
    @Test
    fun findFirstFocus_always_calls_start() {
        val t1 = FakeTraversal(listOf(1, 2, 3), setOf(1, 2, 3))
        FocusSearch.findFirstFocus(t1, FakeNode(1), TraversalDirection.FORWARD, filterOf(t1))
        assertEquals("FORWARD 必须调用一次 start()", 1, t1.startCalls.size)

        val t2 = FakeTraversal(listOf(1, 2, 3), setOf(1, 2, 3))
        FocusSearch.findFirstFocus(t2, FakeNode(1), TraversalDirection.BACKWARD, filterOf(t2))
        assertEquals("BACKWARD 必须调用一次 start()", 1, t2.startCalls.size)
    }

    /** 空遍历序（策略无起点）→ 返回 null，不崩 */
    @Test
    fun findFirstFocus_empty_order_is_safe() {
        val t = FakeTraversal(emptyList(), emptySet())
        assertNull(FocusSearch.findFirstFocus(t, FakeNode(1), TraversalDirection.FORWARD, filterOf(t)))
        assertNull(FocusSearch.findFirstFocus(t, FakeNode(1), TraversalDirection.BACKWARD, filterOf(t)))
    }
}
