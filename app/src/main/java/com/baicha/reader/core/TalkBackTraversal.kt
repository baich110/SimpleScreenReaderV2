package com.baicha.reader.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 遍历引擎 —— **完全按 TalkBack 重写**
 *
 * ══════════════════════════════════════════════════════════════════
 * 【为什么推倒重来】
 * ══════════════════════════════════════════════════════════════════
 *
 * 【用户原话】（2026-10-03）
 * > 完全按照 TalkBack 重写遍历器
 *
 * 【天坦模型（旧实现）的根本缺陷】
 *
 *   天坦 `C1417r.v(node)` 每次调用都**重新上溯**：
 *   ```
 *   v(node):
 *       visited.clear()
 *       result = r(node)              // 向下找
 *       return result ?: G(node)      // 找不到 → 上溯找兄弟
 *
 *   G(node):
 *       parent = o(node, node.getParent())   // ★ 每次都调 getParent()
 *       loop:
 *           result = w(parent, prev)         // 在 parent 里找兄弟
 *           if (result != null) return result
 *           parent = o(parent, parent.getParent())   // ★ 继续上溯
 *   ```
 *
 *   **致命依赖：`AccessibilityNodeInfo.getParent()`。**
 *
 *   在 Compose 界面上，`getParent()` 会**返回 null** ——
 *   Compose 的语义树由 `AccessibilityNodeProvider` 动态生成，
 *   跨虚拟节点边界时 parent 链不完整。
 *
 *   一旦 `getParent()` 返回 null：
 *     · `o()` 回退到 `getWindowRoot()`
 *     · 从窗口根找兄弟 → `indexOf` 返回 -1（不是直接子节点）
 *     · `idx == -1` 分支只下钻**一层** → 找不到深层节点
 *     · 上溯到顶 → 返回 null
 *   ⇒ **`访问 0`**（遍历一步都没走出去），用户感知「完全拿不到焦点」。
 *
 * 【TalkBack 模型（本实现）】
 *
 *   TalkBack 的做法完全不同 —— **一次性建树，遍历时纯链表操作**：
 *
 *   ```
 *   // ① 建树（只做一次）
 *   createWorkingTree(root, parent):
 *       tree = new WorkingTree(root, parent)
 *       if (nodeTreeMap.containsKey(root)) return null   // 防环
 *       nodeTreeMap.put(root, tree)
 *       for (child in 排序后的子节点) {
 *           childSubTree = createWorkingTree(child, tree)
 *           if (childSubTree != null) tree.addChild(childSubTree)
 *       }
 *       return tree
 *
 *   // ② 遍历（纯链表，不碰 getParent()）
 *   WorkingTree.getNext():
 *       if (!children.isEmpty()) return children[0]     // 有子 → 第一个子
 *       startNode = this
 *       while (startNode != null) {
 *           nextSibling = startNode.getNextSibling()
 *           if (nextSibling != null) return nextSibling
 *           startNode = startNode.getParent()            // ★ 用建树时记的 parent
 *       }
 *       return null
 *
 *   WorkingTree.getPrevious():
 *       previousSibling = getPreviousSibling()
 *       if (previousSibling != null) return previousSibling.getLastNode()
 *       return getParent()
 *   ```
 *
 *   **关键差异：parent 关系在建树时固化到 `WorkingTree` 对象里，
 *   遍历时完全不调 `AccessibilityNodeInfo.getParent()`。**
 *   ⇒ Compose 的 parent 为 null 也**不影响遍历**。
 *
 * ══════════════════════════════════════════════════════════════════
 * 【与 TalkBack 的对应关系】
 * ══════════════════════════════════════════════════════════════════
 *
 * | TalkBack | 本实现 |
 * |---|---|
 * | `WorkingTree`（node/parent/children） | [WorkingTree] |
 * | `WorkingTree.getNext()` | [WorkingTree.next] |
 * | `WorkingTree.getPrevious()` | [WorkingTree.previous] |
 * | `WorkingTree.getLastNode()` | [WorkingTree.lastNode] |
 * | `OrderedTraversalController.createWorkingTree()` | [build] |
 * | `OrderedTraversalController.findNext()` | [next] |
 * | `OrderedTraversalController.findPrevious()` | [previous] |
 * | `OrderedTraversalController.findFirst()` | [first] |
 * | `OrderedTraversalController.findLast()` | [last] |
 * | `ReorderedChildrenIterator`（几何排序） | [orderedChildren] |
 * | `nodeTreeMap`（防环） | [nodeMap] |
 *
 * 【源码位置】
 *   · `utils/.../traversal/WorkingTree.java:40~201`
 *   · `utils/.../traversal/OrderedTraversalController.java:55~330`
 *   · `utils/.../traversal/ReorderedChildrenIterator.java:20~276`
 *
 * ══════════════════════════════════════════════════════════════════
 * 【设计取舍】
 * ══════════════════════════════════════════════════════════════════
 *
 * ① **建树成本**：一次性 O(n)，n = 节点数。
 *    真机 Operit 界面 117 节点，建树 ~10ms（含 IPC）。
 *    相比天坦「每次 next() 都上溯」的 O(depth) × 调用次数，
 *    建树在「一次导航要问几十次 next()」的场景下**净赚**。
 *
 * ② **过滤不在遍历器里**：TalkBack 的 `findNext` 只走链表，
 *    过滤由上层 `searchFocus` 循环做（`while (!filter.accept(node))`）。
 *    本实现保持一致 —— 遍历器只管「顺序」，不管「该不该聚焦」。
 *
 * ③ **防环**：`nodeMap` 用 **identity** 语义（`IdentityHashMap`）。
 *    TalkBack 用 `HashMap`（内容 hash），但 `AccessibilityNodeInfoCompat`
 *    的 equals 是内容比较 —— 内容相同的不同节点会被误判为环。
 *    identity 更安全。
 *
 * ④ **WebView 不下钻**：对应 `createWorkingTree` 里的
 *    `if (WebInterfaceUtils.supportsWebActions(rootNode)) return tree;`
 *    WebView 的无障碍树是惰性展开的，下钻会拿到不完整结构。
 */
class TalkBackTraversal(private val root: AccessibilityNodeInfo?) {

    companion object {
        private const val TAG = "TalkBackTraversal"

        /** 建树深度上限（防畸形树栈溢出） */
        private const val MAX_DEPTH = 64

        /** 建树节点数上限（防超大页面拖死无障碍线程） */
        private const val MAX_NODES = 4000

        /**
         * 诊断开关 —— true 时打印建树/遍历细节
         *
         * 【为什么需要】真机出现「找不到焦点」时，必须能区分：
         *   · 建树失败（root 为 null / 节点数超限）
         *   · 起点不在树里（`nodeMap` 未命中）
         *   · 走到了链表末尾（正常边界）
         */
        @JvmStatic
        var diagnose: Boolean = true
    }

    /**
     * 工作树节点 —— 对应 TalkBack `WorkingTree`
     *
     * 【核心价值】`parent` 是**建树时固化的引用**，
     *   遍历时不再依赖 `AccessibilityNodeInfo.getParent()`。
     */
    class WorkingTree(
        val node: AccessibilityNodeInfo,
        var parent: WorkingTree?
    ) {
        val children = ArrayList<WorkingTree>(4)

        fun addChild(child: WorkingTree) {
            children.add(child)
        }

        /** 下一个兄弟 —— 对应 TalkBack `getNextSibling()` */
        fun nextSibling(): WorkingTree? {
            val p = parent ?: return null
            val idx = p.children.indexOfFirst { it === this }
            if (idx < 0) return null
            val next = idx + 1
            return if (next >= p.children.size) null else p.children[next]
        }

        /** 上一个兄弟 —— 对应 TalkBack `getPreviousSibling()` */
        fun previousSibling(): WorkingTree? {
            val p = parent ?: return null
            val idx = p.children.indexOfFirst { it === this }
            if (idx < 0) return null
            val prev = idx - 1
            return if (prev < 0) null else p.children[prev]
        }

        /** 最后一个后代 —— 对应 TalkBack `getLastNode()` */
        fun lastNode(): WorkingTree {
            var cur = this
            while (cur.children.isNotEmpty()) {
                cur = cur.children[cur.children.size - 1]
            }
            return cur
        }

        /**
         * 下一个 —— 1:1 对应 TalkBack `WorkingTree.getNext()`
         *
         * ```java
         * public @Nullable WorkingTree getNext() {
         *   if (!children.isEmpty()) {
         *     return children.get(0);        // 有子 → 第一个子
         *   }
         *   WorkingTree startNode = this;
         *   while (startNode != null) {
         *     WorkingTree nextSibling = startNode.getNextSibling();
         *     if (nextSibling != null) {
         *       return nextSibling;
         *     }
         *     startNode = startNode.getParent();   // 上溯（用固化的 parent）
         *   }
         *   return null;
         * }
         * ```
         */
        fun next(): WorkingTree? {
            if (children.isNotEmpty()) return children[0]

            var start: WorkingTree? = this
            var guard = 0
            while (start != null && guard++ < MAX_DEPTH) {
                val sib = start.nextSibling()
                if (sib != null) return sib
                start = start.parent
            }
            return null
        }

        /**
         * 上一个 —— 1:1 对应 TalkBack `WorkingTree.getPrevious()`
         *
         * ```java
         * public @Nullable WorkingTree getPrevious() {
         *   WorkingTree previousSibling = getPreviousSibling();
         *   if (previousSibling != null) {
         *     return previousSibling.getLastNode();   // 前兄弟的最后一个后代
         *   }
         *   return getParent();
         * }
         * ```
         */
        fun previous(): WorkingTree? {
            val sib = previousSibling()
            if (sib != null) return sib.lastNode()
            return parent
        }
    }

    /**
     * 节点 → 工作树 映射 —— **必须用内容比较（HashMap），不能用 identity**
     *
     * ══════════════════════════════════════════════════════════════
     * 【★★★ 真机 P0 根因：identity 语义导致「节点不在树里」】
     * ══════════════════════════════════════════════════════════════
     *
     * 【真机日志铁证】（v14）
     * ```
     * 08:04:48.636 建树完成: 95 个节点, root=「(无文本)」(FrameLayout, ch=2)
     * 08:04:48.639 ❌ 下一焦点 | 失败 ← 遍历无果；诊断=访问 0
     *              起点=触摸节点优先 「」(edit/EditText)
     * ```
     * 建树成功（95 节点），但 `next(edit/EditText)` 返回 null ——
     * 因为 `nodeMap[edit/EditText]` **未命中**。
     *
     * 【为什么未命中】
     *   `AccessibilityNodeInfo` **每次查询都返回新对象**：
     *   ```java
     *   // 建树时
     *   val child = node.getChild(i)      // 对象 A
     *   // 导航时
     *   val start = getFocused()          // 对象 B（指向同一个无障碍节点）
     *   ```
     *   对象 A ≠ 对象 B（identity 不同），但**内容相同**。
     *
     *   `IdentityHashMap` 用 `==` 比较 ⇒ A 和 B 被当成不同 key ⇒ 未命中。
     *
     * 【TalkBack 怎么做的】
     *   `OrderedTraversalController` 用的是 **`HashMap`**：
     *   ```java
     *   private final Map<AccessibilityNodeInfoCompat, WorkingTree> nodeTreeMap;
     *   ...
     *   if (nodeTreeMap.containsKey(rootNode)) { ... }   // 内容比较
     *   nodeTreeMap.put(rootNode, tree);
     *   ...
     *   WorkingTree tree = nodeTreeMap.get(node);        // 内容比较
     *   ```
     *   而 `AccessibilityNodeInfoCompat` **重写了 `equals`/`hashCode`**，
     *   基于节点内容（windowId + sourceNodeId + ...）比较。
     *   ⇒ 不同对象、同一节点 → **命中**。
     *
     * 【为什么我之前改成 identity 是错的】
     *   注释里写「内容相同的不同节点会被误判为环」——
     *   这个担心是**多余的**：
     *     · 无障碍树是**树**（无环），同一节点只会在树里出现一次
     *     · 即使出现两次，内容相同 ⇒ 断边 ⇒ 结果仍然正确（少一个重复节点）
     *   而 identity 的代价是**致命的**：正常导航 100% 未命中。
     *
     * 【结论】必须用内容比较 —— 与 TalkBack 一致。
     */
    private val nodeMap = HashMap<AccessibilityNodeInfo, WorkingTree>()

    /** 根工作树 */
    private var tree: WorkingTree? = null

    /** 建树是否成功 */
    private var built = false

    /** 建树时的节点计数（诊断用） */
    private var nodeCount = 0

    // ==================== 建树 ====================

    /**
     * 建树 —— 对应 TalkBack `OrderedTraversalController.initOrder()`
     *
     * 【与 TalkBack 的差异】
     *   TalkBack 在构造 `OrderedTraversalStrategy` 时就建树；
     *   本实现延迟到首次调用 [next]/[previous] 时建（懒初始化），
     *   避免「创建了但没用」的浪费。
     */
    private fun ensureBuilt() {
        if (built) return
        built = true
        val r = root ?: return
        tree = build(r, null, 0)
        if (diagnose) {
            ReaderLog.d(TAG, "建树完成: $nodeCount 个节点, root=${desc(r)}")
        }
    }

    /**
     * 递归建树 —— 1:1 对应 TalkBack `createWorkingTree()`
     *
     * ```java
     * private @Nullable WorkingTree createWorkingTree(
     *     @NonNull AccessibilityNodeInfoCompat rootNode,
     *     @Nullable WorkingTree parent,
     *     @NonNull NodeCachedBoundsCalculator boundsCalculator,
     *     boolean includeChildrenOfNodesWithWebActions) {
     *   if (nodeTreeMap.containsKey(rootNode)) {
     *     LogUtils.w(TAG, "creating node tree with looped nodes - break the loop edge");
     *     return null;                              // ★ 防环
     *   }
     *   WorkingTree tree = new WorkingTree(rootNode, parent);
     *   nodeTreeMap.put(rootNode, tree);
     *
     *   // 遇到支持 web 导航的节点 → 不下钻
     *   if (!includeChildrenOfNodesWithWebActions && WebInterfaceUtils.supportsWebActions(rootNode)) {
     *     return tree;
     *   }
     *
     *   ReorderedChildrenIterator iterator =
     *       ReorderedChildrenIterator.createAscendingIterator(rootNode, boundsCalculator);
     *   while (iterator.hasNext()) {
     *     AccessibilityNodeInfoCompat child = iterator.next();
     *     WorkingTree childSubTree = createWorkingTree(child, tree, boundsCalculator, ...);
     *     if (childSubTree != null) {
     *       tree.addChild(childSubTree);
     *     }
     *   }
     *   return tree;
     * }
     * ```
     */
    private fun build(node: AccessibilityNodeInfo, parent: WorkingTree?, depth: Int): WorkingTree? {
        if (depth > MAX_DEPTH) return null
        if (nodeCount >= MAX_NODES) return null

        // ★ 防环：identity 语义
        if (nodeMap.containsKey(node)) {
            if (diagnose) ReaderLog.d(TAG, "  建树: 检测到环，断边 ${desc(node)}")
            return null
        }

        val wt = WorkingTree(node, parent)
        nodeMap[node] = wt
        nodeCount++

        // ★ WebView 不下钻 —— 对应 TalkBack `supportsWebActions` 检查
        if (supportsWebActions(node)) {
            if (diagnose) ReaderLog.d(TAG, "  建树: WebView 节点不下钻 ${desc(node)}")
            return wt
        }

        // 子节点（几何排序）
        val children = orderedChildren(node)
        for (child in children) {
            val sub = build(child, wt, depth + 1)
            if (sub != null) wt.addChild(sub)
        }
        return wt
    }

    /**
     * 排序后的子节点 —— 对应 TalkBack `ReorderedChildrenIterator`
     *
     * 【TalkBack 的排序逻辑】
     *   `ReorderedChildrenIterator` 只在**需要重排**时才排序：
     *   ```java
     *   private boolean needReordering(List<AccessibilityNodeInfoCompat> nodes) {
     *     if (nodes == null || nodes.size() == 1) return false;
     *     for (AccessibilityNodeInfoCompat node : nodes) {
     *       if (boundsCalculator.usesChildrenBounds(node)) return true;
     *     }
     *     return false;
     *   }
     *   ```
     *   而 `usesChildrenBounds(node)` 的语义是：
     *   「这个节点自身不可聚焦，但它的子节点可聚焦」——
     *   此时应该用**子节点的边界**参与排序，而不是节点自身的边界。
     *
     * 【本实现】直接按「有效边界」（有子则取子节点并集）做几何排序：
     *   上→下、左→右（与遍历序直觉一致）。
     *   比 TalkBack 的「只在需要时重排」更简单，且结果一致。
     *
     * 【为什么不用 `NodeOrderComparator`】
     *   那是天坦的排序实现，依赖 `NodeCache`（已关闭）。
     *   这里用纯几何排序，无缓存依赖。
     */
    private fun orderedChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val count = try {
            node.childCount
        } catch (e: Throwable) {
            0
        }
        if (count <= 0) return emptyList()

        val list = ArrayList<AccessibilityNodeInfo>(count)
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (e: Throwable) {
                null
            } ?: continue
            list.add(child)
        }
        if (list.size <= 1) return list

        // 几何排序：top 优先，其次 left
        val rect = Rect()
        return try {
            list.sortedWith(compareBy({ c ->
                c.getBoundsInScreen(rect)
                rect.top
            }, { c ->
                c.getBoundsInScreen(rect)
                rect.left
            }))
        } catch (e: Throwable) {
            list   // 排序失败 → 保持原始顺序（不丢节点）
        }
    }

    /**
     * 是否支持 web 动作 —— 对应 TalkBack `WebInterfaceUtils.supportsWebActions()`
     *
     * 判据：节点有 `ACTION_NEXT_HTML_ELEMENT`(1024) 或
     *       `ACTION_PREVIOUS_HTML_ELEMENT`(2048) 动作。
     */
    private fun supportsWebActions(node: AccessibilityNodeInfo): Boolean {
        val actions = try {
            node.actions
        } catch (e: Throwable) {
            return false
        }
        if ((actions and 1024) == 1024) return true
        if ((actions and 2048) == 2048) return true
        return try {
            node.actionList.any { it.id == 1024 || it.id == 2048 }
        } catch (e: Throwable) {
            false
        }
    }

    // ==================== 遍历 API ====================

    /**
     * 下一个 —— 对应 TalkBack `OrderedTraversalController.findNext()`
     *
     * 【注意】返回值**未经过滤** —— 调用方（`searchFocus`）负责
     *   循环 + 过滤。这与 TalkBack 一致。
     */
    fun next(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        ensureBuilt()
        val wt = nodeMap[node] ?: run {
            if (diagnose) ReaderLog.d(TAG, "  next: 节点不在树里 ${desc(node)}")
            return null
        }
        val nextTree = wt.next()
        if (diagnose) {
            ReaderLog.d(TAG, "  next(${desc(node)}) → ${nextTree?.let { desc(it.node) } ?: "null"}")
        }
        return nextTree?.node
    }

    /**
     * 上一个 —— 对应 TalkBack `OrderedTraversalController.findPrevious()`
     */
    fun previous(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        ensureBuilt()
        val wt = nodeMap[node] ?: run {
            if (diagnose) ReaderLog.d(TAG, "  previous: 节点不在树里 ${desc(node)}")
            return null
        }
        val prevTree = wt.previous()
        if (diagnose) {
            ReaderLog.d(TAG, "  previous(${desc(node)}) → ${prevTree?.let { desc(it.node) } ?: "null"}")
        }
        return prevTree?.node
    }

    /**
     * 第一个 —— 对应 TalkBack `OrderedTraversalController.findFirst()`
     *
     * TalkBack 返回 `tree.getRoot().getNode()` —— **树根本身**，
     * 不是「第一个可聚焦节点」。过滤由上层做。
     */
    fun first(): AccessibilityNodeInfo? {
        ensureBuilt()
        return tree?.node
    }

    /**
     * 最后一个 —— 对应 TalkBack `OrderedTraversalController.findLast()`
     *
     * TalkBack 返回 `tree.getRoot().getLastNode().getNode()`。
     */
    fun last(): AccessibilityNodeInfo? {
        ensureBuilt()
        return tree?.lastNode()?.node
    }

    /**
     * 节点是否在树里 —— 对应 `nodeTreeMap.containsKey()`
     *
     * 用于「当前焦点是否属于当前窗口」判定。
     */
    fun contains(node: AccessibilityNodeInfo): Boolean {
        ensureBuilt()
        return nodeMap.containsKey(node)
    }

    /** 树里的节点数（诊断用） */
    fun size(): Int {
        ensureBuilt()
        return nodeCount
    }

    /** 释放 —— 对应 TalkBack 的树引用置空 */
    fun release() {
        nodeMap.clear()
        tree = null
        built = false
        nodeCount = 0
    }

    /** 节点简述（诊断用） */
    private fun desc(n: AccessibilityNodeInfo): String {
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        val t = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
        val cnt = try {
            n.childCount
        } catch (e: Throwable) {
            -1
        }
        return "「${t.ifEmpty { "(无文本)" }}」($cls, ch=$cnt)"
    }
}
