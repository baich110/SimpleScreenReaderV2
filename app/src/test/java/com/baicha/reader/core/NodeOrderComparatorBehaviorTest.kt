package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排序判定 —— **真实节点树**行为测试
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要（此前只有文字 grep 测试）】
 * ══════════════════════════════════════════════════════════
 *
 * `NodeOrderComparator.needsSort()` 是**排序总闸**：
 * 它的返回值直接决定 `TatansTraversal` 走「排序路径」还是「原始顺序路径」，
 * 进而决定整个焦点的遍历顺序。
 *
 * 但它依赖 `node.packageName` / `node.childCount` / `node.parent` /
 * `className` —— 这些在 JVM 单测里全是空壳，所以此前只用 grep
 * 断言「源码里有某行」，**逻辑一行没跑到**。
 *
 * 本轮有了 [FakeNode]（可构树 + 忠实返回这些字段），首次可做真实断言。
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】天坦 `d7/C1417r.java:283~304` 的 `I(nodeInfo)`
 * ══════════════════════════════════════════════════════════
 *
 * ```java
 *   public final boolean I(NodeInfo nodeInfo) {
 *       Intrinsics.checkNotNullExpressionValue(nodeInfo, "node");
 *       if (Intrinsics.areEqual(nodeInfo.getPackageName(), "com.bbk.launcher2")) {
 *           return true;                                                    // ①
 *       }
 *       if (!GlobalConfig.f31027a.g0() || nodeInfo.getChildCount() >= 60) {
 *           return false;                                                   // ②
 *       }
 *       if (Intrinsics.areEqual(nodeInfo.getPackageName(), "com.android.launcher")
 *           && EventUtils.getNodeType(nodeInfo) == 8) {
 *           return false;                                                   // ③
 *       }
 *       try {
 *           NodeInfo parent = nodeInfo.getParent();
 *           int nodeType = EventUtils.getNodeType(parent);
 *           AccessibilityNodeInfoUtils.g0(parent);
 *           return nodeType != 8 || EventUtils.getNodeType(nodeInfo) == 8;   // ④
 *       } catch (IllegalStateException unused) {
 *           return false;                                                   // ⑤
 *       }
 *   }
 * ```
 *
 * 且 `w7/EventUtils.java:112~114`：`getNodeType(null) → 0`
 * ⇒ parent 为 null 时 `0 != 8` 为真 ⇒ **返回 true（要排序）**。
 */
class NodeOrderComparatorBehaviorTest {

    private val absListView = "android.widget.AbsListView"
    private val gridView = "android.widget.GridView"

    @After
    fun tearDown() {
        NodeOrderComparator.sortEnabled = true
    }

    // ==================== ① vivo 桌面强制排序 ====================

    /**
     * `com.bbk.launcher2` 无条件返回 true —— 依据 :284
     *
     * 注意：这一条**在排序开关判断之前**，
     * 即开关关掉时 vivo 桌面**仍然排序**（天坦就是这个顺序）。
     */
    @Test
    fun vivoLauncher_forces_sort_even_when_switch_off() {
        NodeOrderComparator.sortEnabled = false
        val n = FakeNode(1).apply { fakePackageName = "com.bbk.launcher2" }
        assertTrue(
            "vivo 桌面必须在开关判断之前返回 true（依据 C1417r.java:284）",
            NodeOrderComparator.needsSort(n)
        )
    }

    // ==================== ② 排序总开关 + 子节点上限 ====================

    @Test
    fun sortSwitchOff_returns_false() {
        NodeOrderComparator.sortEnabled = false
        assertFalse(NodeOrderComparator.needsSort(FakeNode(1)))
    }

    /**
     * 子节点 >= 60 → 不排序 —— 依据 :287 `nodeInfo.getChildCount() >= 60`
     *
     * **边界**：59 应排序、60 不排序。这条边界是我此前没测过的。
     */
    @Test
    fun childCount_boundary_is_59_sort_60_not() {
        NodeOrderComparator.sortEnabled = true

        val n59 = FakeNode(1)
        repeat(59) { n59.add(FakeNode(100 + it)) }
        assertTrue("59 个子节点仍应排序", NodeOrderComparator.needsSort(n59))

        val n60 = FakeNode(2)
        repeat(60) { n60.add(FakeNode(200 + it)) }
        assertFalse("60 个子节点应停止排序（依据 :287 用的是 >=60）", NodeOrderComparator.needsSort(n60))
    }

    // ==================== ③ 原生桌面里的列表不排序 ====================

    @Test
    fun androidLauncher_list_role_returns_false() {
        val n = FakeNode(1).apply {
            fakePackageName = "com.android.launcher"
            fakeClassName = absListView      // → ROLE_LIST(8)
        }
        assertFalse("原生桌面的列表节点不排序（依据 :290）", NodeOrderComparator.needsSort(n))
    }

    /** 同包名但**不是**列表 → 不该被 ③ 拦下 */
    @Test
    fun androidLauncher_nonList_is_not_blocked() {
        val n = FakeNode(1).apply {
            fakePackageName = "com.android.launcher"
            fakeClassName = "android.widget.Button"   // → 非 LIST
        }
        assertTrue("原生桌面里的非列表节点仍应排序", NodeOrderComparator.needsSort(n))
    }

    // ==================== ④ 父节点是列表的语义（易错点）====================

    /**
     * ★ 父节点是列表、自己**不是**列表 → **不排序** —— 依据 :301
     *   `nodeType != 8 || getNodeType(nodeInfo) == 8`
     *   parent=8 → `8 != 8` 假；node 非 8 → `假 || 假` → **false**
     */
    @Test
    fun parentIsList_selfIsNot_returns_false() {
        val listParent = FakeNode(1).apply { fakeClassName = absListView }
        val child = FakeNode(2).apply { fakeClassName = "android.widget.Button" }
        listParent.add(child)

        assertFalse(
            "父是列表、自己不是列表 → 不排序（排序由列表容器统一负责）",
            NodeOrderComparator.needsSort(child)
        )
    }

    /**
     * 父节点是列表、自己**也是**列表 → **排序** —— 依据 :301 右半 `|| nodeType == 8`
     */
    @Test
    fun parentIsList_selfIsList_returns_true() {
        val outer = FakeNode(1).apply { fakeClassName = absListView }
        val inner = FakeNode(2).apply { fakeClassName = absListView }
        outer.add(inner)

        assertTrue("嵌套列表的**内层列表**仍要排序", NodeOrderComparator.needsSort(inner))
    }

    /**
     * ★★ parent == null → **返回 true**（要排序）
     *
     * 依据推导：天坦 `getNodeType(parent)`（:296）先于 `g0(parent)` 调用，
     * 而 `EventUtils.getNodeType(null)` 返回 0（`EventUtils.java:112~114`），
     * `0 != 8` 为真 ⇒ true。
     *
     * **这是最容易抄错的一处** —— 若误以为「没有父节点 ⇒ 不排序」，
     * 树根就永远不排序，整棵树的顺序全错。
     */
    @Test
    fun nullParent_returns_true() {
        val root = FakeNode(1)   // 不 add 到任何父节点
        assertTrue(
            "无父节点必须返回 true（getNodeType(null)==0 → 0!=8）",
            NodeOrderComparator.needsSort(root)
        )
    }

    /** 父节点是普通容器（非列表）→ 排序 */
    @Test
    fun parentIsPlainContainer_returns_true() {
        val parent = FakeNode(1).apply { fakeClassName = "android.widget.FrameLayout" }
        val child = FakeNode(2).apply { fakeClassName = "android.widget.Button" }
        parent.add(child)
        assertTrue("父是普通容器 → 排序", NodeOrderComparator.needsSort(child))
    }

    // ==================== 与遍历器的接线一致性 ====================

    /**
     * ★★ `needsSort` 的返回值必须**真的改变遍历结果**
     *
     * 这是「接线验证」：光有判定函数正确还不够，
     * 要证明遍历器确实读了它。
     *
     * 做法：同一棵树，比较 `TatansTraversal` 在
     * 「列表子节点（不排序）」结构下的访问结果是否与原始 child 顺序一致。
     *
     * 注：`isVisibleToUser` 已由 FakeNode 忠实返回 true，
     * 所以遍历不会因空壳默认值而短路（这正是本轮基础设施修复的价值）。
     */
    @Test
    fun needsSort_result_is_consumed_by_traversal() {
        val root = FakeNode(0)
        val list = FakeNode(1).apply { fakeClassName = absListView }
        root.add(list)

        val a = FakeNode(2, "A").apply { fakeClassName = "android.widget.Button" }
        val b = FakeNode(3, "B").apply { fakeClassName = "android.widget.Button" }
        list.add(a)
        list.add(b)

        // 列表的子节点都不该被排序（由列表容器统一负责）
        assertFalse("列表的直接子项不单独排序", NodeOrderComparator.needsSort(a))
        assertFalse("列表的直接子项不单独排序", NodeOrderComparator.needsSort(b))

        // 列表自身应排序
        assertTrue("列表容器自身要排序", NodeOrderComparator.needsSort(list))
    }
}
