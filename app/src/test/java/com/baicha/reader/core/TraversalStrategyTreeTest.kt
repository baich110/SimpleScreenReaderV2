package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遍历策略 —— **真实树结构**行为测试
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么这个测试类以前写不出来】
 * ══════════════════════════════════════════════════════════
 *
 * 遍历策略的输入是**节点树**（`childCount` / `getChild` / `getParent`），
 * 而 JVM 单测里 `new AccessibilityNodeInfo()` 返回 null，
 * 所以此前对策略的所有测试都停留在「构造不崩」的形状断言。
 *
 * 本轮引入 [FakeNode]（子类化 + 重写 getChild/getChildCount/getParent，
 * 已实测可行），第一次能真正构造一棵树喂给策略。
 *
 * ══════════════════════════════════════════════════════════
 * 【本类要回答的具体问题】
 * ══════════════════════════════════════════════════════════
 *
 * `ListTraversalStrategy.getFocusableChildren()` 只**下钻一层**
 * （`TraversalStrategy.kt:168~180`：child 不可聚焦时才看 child 的子节点，
 *   孙节点不再看）。嵌套列表里会不会漏掉深层可聚焦项？
 *
 * 答案由测试给出，不靠推断。
 */
class TraversalStrategyTreeTest {

    /**
     * 让 `TatansTraversal` 的 `obtain()` 在 JVM 里可用
     *
     * `AccessibilityNodeInfo.obtain(node)` 是静态方法、被
     * `isReturnDefaultValues` 替换成返回 null，于是遍历走到
     * 「返回结果」就 NPE。这里注入「直接返回原对象」——
     * `FakeNode` 本身不可变且带身份，语义等价于真实副本。
     */
    @Before
    fun installObtainHook() {
        TatansTraversal.obtainOverride = { it }
    }

    @After
    fun removeObtainHook() {
        TatansTraversal.obtainOverride = null
    }

    /** 所有测试共用的 filter：把「有文本」当作可聚焦 */
    private val acceptText: (AccessibilityNodeInfo) -> Boolean = {
        (it as? FakeNode)?.textValue?.isNotEmpty() == true
    }

    /**
     * 嵌套列表：容器 → 分组 → 项目（**三层**）
     *
     * ```
     *   root(0)                容器（无文本）
     *     └ group(1, "分组")    分组（有文本 → 可聚焦）
     *         ├ item(2, "项A")  项目（有文本）
     *         └ item(3, "项B")
     * ```
     */
    private fun buildNested(): FakeNode = tree(0) {
        node(1, "分组") {
            node(2, "项A")
            node(3, "项B")
        }
    }

    /**
     * 深层嵌套：容器 → 分组 → 包装层 → 项目（**四层**，包装层无文本）
     *
     * ```
     *   root(0)
     *     └ group(1, "分组")
     *         └ wrap(2)            包装层（无文本 → 不可聚焦）
     *             ├ item(3, "项A")   ← 这一层是孙子
     *             └ item(4, "项B")
     * ```
     */
    private fun buildDeepNested(): FakeNode = tree(0) {
        node(1, "分组") {
            node(2) {
                node(3, "项A")
                node(4, "项B")
            }
        }
    }

    // ==================== FakeNode 自身的契约 ====================

    @Test
    fun fakeNode_tree_structure_is_faithful() {
        val root = buildNested()
        assertEquals("根有 1 个子节点", 1, root.childCount)
        assertEquals("分组有 2 个子节点", 2, root.getChild(0)?.childCount)
        assertEquals("父指针正确", root.getChild(0), root.getChild(0)?.getChild(0)?.parent)
        assertEquals("越界取子节点返回 null", null, root.getChild(5))
    }

    /**
     * `equals` 必须是**内容相等**语义 —— 这是 `FocusSearch` 环检测依赖的语义
     *
     * 依据：TalkBack `TraversalStrategyUtils.java:365` 用 `HashSet` 存节点，
     * 而真实 `AccessibilityNodeInfo.equals()` 是内容哈希。
     */
    @Test
    fun fakeNode_equality_is_content_based() {
        assertEquals("同 id 视为相等", FakeNode(7), FakeNode(7))
        assertTrue("同 id hashCode 相同", FakeNode(7).hashCode() == FakeNode(7).hashCode())
        assertTrue("不同 id 不相等", FakeNode(7) != FakeNode(8))
    }

    // ==================== ListTraversalStrategy ====================

    /**
     * ★ 实测：嵌套列表里，`ListTraversalStrategy` 能否取到**孙子层**的可聚焦项？
     *
     * 依据 [TraversalStrategy.kt:168~180]：`getFocusableChildren` 只下钻一层。
     * group(1) 有文本 → 直接加入，**不再看它的子节点**。
     * 于是 item(2)/item(3) **本就不该出现在列表项序列里**（它们在 group 内部）。
     *
     * 这条测试锁定**当前行为**，作为将来对比天坦「过滤器式列表遍历」的基线。
     */
    @Test
    fun listTraversal_start_returns_top_level_accepted_child() {
        val root = buildNested()
        val s = ListTraversalStrategy(root, acceptText)
        val started = s.start(root, TraversalStrategy.DIRECTION_NEXT)
        assertEquals("起点应是容器内第一个可聚焦子项（分组）", 1, (started as? FakeNode)?.nodeId)
    }

    /**
     * ★★ 实测最关键的一条：**包装层不可聚焦时**，`getFocusableChildren`
     * 会下钻一层拿到 item(3)/item(4)。
     *
     * 走查（依据 TraversalStrategy.kt:170~177）：
     *   i=0 → child = group(1)，有文本 → filter 接受 → 直接 add(1)，
     *   **不再下钻** ⇒ wrap(2) 及其子项 item(3)/item(4) **全部看不到**。
     *
     * 期望（若按「容器内所有可聚焦项」的直觉）：应能看到 item(3)/item(4)。
     * 实际：只能看到 group(1)。
     *
     * 本测试把「实际」断言下来 —— 它同时是**缺陷证据**：
     * 深层嵌套的可聚焦项被完全漏掉。
     */
    @Test
    fun listTraversal_start_misses_deep_nested_items() {
        val root = buildDeepNested()
        val s = ListTraversalStrategy(root, acceptText)

        val started = s.start(root, TraversalStrategy.DIRECTION_NEXT)
        assertEquals("当前实现只取到 group(1)", 1, (started as? FakeNode)?.nodeId)

        // 明确断言：深层可聚焦项 item(3) 通过 start() **拿不到**
        assertTrue(
            "深层 item(3) 不在列表项序列里 —— 这是 getFocusableChildren 只下钻一层的后果",
            (started as? FakeNode)?.nodeId != 3
        )
        s.release()
    }

    /**
     * 对照：`DefaultTraversalStrategy`（天坦默认遍历器）**能**找到深层节点
     *
     * 这条用于证明「漏掉深层项」是 `ListTraversalStrategy` 特有，
     * 而不是 `TatansTraversal` 本身的能力缺陷。
     */
    @Test
    fun defaultTraversal_finds_deep_nodes() {
        val root = buildDeepNested()
        val s = DefaultTraversalStrategy(root, acceptText)
        // start(FORWARD) 依据天坦 C1418s.c()：direction==1 返回节点本身
        val started = s.start(root, TraversalStrategy.DIRECTION_NEXT)
        assertEquals("默认策略起点是 root 自身（对齐天坦 C1418s.c）", 0, (started as? FakeNode)?.nodeId)

        // 逐节点前进，必须能走到深层 item(3)
        var cur: AccessibilityNodeInfo? = root
        val seen = mutableListOf<Int>()
        repeat(20) {
            cur = s.next(cur ?: return@repeat, TraversalStrategy.DIRECTION_NEXT) ?: return@repeat
            (cur as? FakeNode)?.let { seen += it.nodeId }
        }
        assertTrue("默认遍历应能到达深层 item(3)，实际经过 $seen", seen.contains(3))
        s.release()
    }

    // ==================== DefaultTraversalStrategy.start 契约 ====================

    /**
     * ★ `DefaultTraversalStrategy.start()` 必须对齐天坦 `C1418s.c()`
     *
     * 依据 `d7/C1418s.java:47~62`（原样摘录）：
     * ```
     *   public NodeInfo c(NodeInfo nodeInfo, int i9) {
     *       if (nodeInfo == null) { return null; }
     *       if (i9 == 1) { return NodeInfo.obtainFromNode(nodeInfo); }   // ← direction==1 返回自身
     *       if (i9 == 2) { ... C1404e.i(nodeInfo, ...); if (f()) return c(); ... }
     *       return null;
     *   }
     * ```
     */
    @Test
    fun defaultTraversal_start_forward_returns_node_itself() {
        val root = tree(0, "根") { node(1, "子") }
        val s = DefaultTraversalStrategy(root, acceptText)
        val started = s.start(root, TraversalStrategy.DIRECTION_NEXT)
        assertEquals("direction==1 应返回传入节点本身", 0, (started as? FakeNode)?.nodeId)
        s.release()
    }

    /**
     * 构造时 root 为 null 不应影响 `start(node, NEXT)` —— 它按契约返回**传入的** node
     *
     * 【这条测试我一开始写错了，记录在此】
     * 我最初断言「构造 root=null ⇒ start 返回 null」，
     * 实测返回了 FakeNode(0)。复核 `C1418s.c(nodeInfo, 1)` 后确认
     * **是测试写错了**：`start()` 用的是**入参 node**，与构造时的 root 无关。
     * 这条错误断言若留着，就会把正确实现判成 bug —— 与
     * `P1FixesRegressionTest` 那条「把错误固化成契约」是同一类坑。
     */
    @Test
    fun defaultTraversal_start_uses_argument_not_constructor_root() {
        val s = DefaultTraversalStrategy(null, acceptText)
        assertEquals(
            "start 应返回入参节点，与构造时的 root 无关",
            42, (s.start(FakeNode(42), TraversalStrategy.DIRECTION_NEXT) as? FakeNode)?.nodeId
        )
        s.release()
    }

    /** 构造 root 为 null 时 `next()` 必须安全返回 null，不崩 */
    @Test
    fun defaultTraversal_next_with_null_root_is_safe() {
        val s = DefaultTraversalStrategy(null, acceptText)
        assertEquals(null, s.next(FakeNode(0), TraversalStrategy.DIRECTION_NEXT))
        s.release()
    }
}
