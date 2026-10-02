package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 遍历引擎 —— 1:1 抄天坦 d7/C1423r.java（1307 行）
 *
 * 【这是天坦真正的遍历核心】C1410e 是废弃路径，天坦实际走 C1423r。
 *
 * 【天坦的三层结构（关键）】
 *   a(node) = X(node) && filter.accept(node)
 *      ├─ X(node)        基础可聚焦：可见 || (WebView 相关 && 角色 != 15)
 *      └─ filter.accept  M 接口，默认 b 实现 = AbstractC3693m.X(node)
 *
 *   遍历（v/y/r/u/D/C/o/p/q/w/x/A/B/G）与过滤是**分开的两层**。
 *   遍历内部只用 X() 判可见性，filter 由外部注入。
 *
 * 【找下一个 v(node)】
 *   v(node):
 *       visited.clear()                    ← 每次调用清空
 *       result = r(node)                   ← ① 向下找
 *       return result ?: G(node)           ← ② 找不到 → 上溯
 *
 *   r(node):                              向下找（注意 I() 双分支！）
 *       if (I(node)) {
 *           list = q(node, false)          ← 排序后的子节点列表
 *           for (child in list) { D(child) }
 *       } else {
 *           for (i in 0 until childCount) { D(node.getChild(i)) }
 *       }
 *
 *   D(node):                              递归下降
 *       if (!visited.add(node)) return null
 *       return if (a(node)) b0(node) else r(node)
 *
 *   G(node):                              上溯找下一个兄弟
 *       parent = o(node, node.parent)
 *       loop:
 *           if (target != null && prev == target) return null
 *           if (!seen.add(parent)) return null
 *           result = w(parent, prev)
 *           if (result != null) return result
 *           prev = parent
 *           parent = o(parent, parent.parent)
 *
 * 【找上一个 y(node)】
 *   y(node):
 *       visited.clear()
 *       result = z(node)                   ← ① 前一个兄弟
 *       if (result != null) return result
 *       parent = node.parent
 *       if (parent == null) return null
 *       if ((target == null || parent != target) && a(parent)) return parent
 *       loop:                              ← ② 上溯
 *           if (target != null && prev == target) return null
 *           if (!seen.add(parent)) return null
 *           result = A(parent, prev)
 *           if (result != null) return result
 *           if ((target == null || parent != target) && a(parent)) return b0(parent)
 *           prev = parent
 *           parent = o(parent, parent.parent)
 *
 * 【关键辅助（全部带 I() 双分支）】
 *   w(parent, node)  后一个可聚焦兄弟     → 见 findNextSibling
 *   x(parent, node)  同上（非回溯版）     → 见 findNextSiblingNoTrace
 *   A(parent, node)  前一个可聚焦兄弟     → 见 findPrevSibling
 *   B(parent, node)  同上（非回溯版）     → 见 findPrevSiblingNoTrace
 *   C(node)          最后一个可聚焦后代
 *   D(node)          递归下降
 *   u(node)          从后往前找
 *   r(node)          向下找
 *   p(parent, child) 找 child 的索引（带缓存 + 双向搜索）
 *   q(list, node)    找哪个直接子节点包含 node
 *   o(node, parent)  取父节点
 *   a(node)          = X(node) && filter.accept(node)
 *
 * 【修正记录 2026-10-02】
 *   ① visited 必须用 **identity** 语义。天坦 HashSet<o0.w> 没重写 hashCode/equals
 *      → 默认 identity。之前用 HashSet<AccessibilityNodeInfo>（内容 hash）是错的：
 *      内容相同的不同节点会被误判为已访问 → 遍历提前返回 null → 焦点卡住。
 *   ② r()/u() 里的 I() 双分支必须补全，且 indexOf 必须用**同一顺序空间**。
 *      之前 findDown 用排序列表、indexOf 用 getChild(i) 原始顺序 → 索引错位。
 *   ③ filter 必须与 FocusController.searchFocus 里用的判定保持一致。
 */
class TatansTraversal(private val root: AccessibilityNodeInfo?) {

    companion object {
        /** 窗口根上溯深度上限（防畸形树死循环） */
        private const val MAX_WINDOW_ROOT_DEPTH = 64

        /** `ACTION_NEXT_HTML_ELEMENT` —— 可读版 AccessibilityNodeInfoUtils:657 标注 */
        private const val ACTION_NEXT_HTML_ELEMENT = 1024

        /** `ACTION_PREVIOUS_HTML_ELEMENT` —— 同上 :659 */
        private const val ACTION_PREVIOUS_HTML_ELEMENT = 2048

        private const val TAG = "TatansTraversal"

        /** 索引缓存（天坦 f23911m/f23912n/f23913o） */
        private var cacheParentHash = -1
        private var cacheChildIndex = -1
        private var cacheChildCount = -1

        /**
         * 缓存代数 —— 窗口/内容变化时自增，强制缓存失效
         *
         * 天坦只靠内容哈希自动失效；本项目额外加一代数校验，
         * 覆盖「内容哈希恰好相同但结构已变」的边界情况。
         */
        @Volatile
        private var cacheGeneration = 0

        /**
         * 使索引缓存失效 —— 窗口/内容变化时由服务调用
         */
        fun invalidateIndexCache() {
            cacheGeneration++
            cacheParentHash = -1
            cacheChildIndex = -1
            cacheChildCount = -1
        }

        /** 天坦 f23906h —— q() 找不到时置 true，s() 读 */
        @Volatile
        var lastSearchFailed = false
            private set

        /**
         * identity 包装 —— 天坦 HashSet<o0.w> 是 identity 语义
         *
         * AccessibilityNodeInfo 重写了 equals/hashCode（基于内容），
         * 直接用会误判。这里用 IdentityHashMap 做真正的 identity 集合。
         */
        private class IdentitySet {
            private val map = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()

            fun add(node: AccessibilityNodeInfo): Boolean {
                if (map.containsKey(node)) return false
                map[node] = true
                return true
            }

            fun clear() = map.clear()
        }
    }

    /** 已访问集合（天坦 f23914a）—— identity 语义防环 */
    private val visited = IdentitySet()

    /** 本实例创建时的缓存代数快照 */
    private val generation: Int = cacheGeneration

    /** 目标节点（天坦 f23915b）—— 遇到就停 */
    private var target: AccessibilityNodeInfo? = null

    /** 可聚焦过滤器（天坦 f23916c，默认 b = AbstractC3693m.X） */
    private var focusFilter: (AccessibilityNodeInfo) -> Boolean = { isNodeFocusable(it) }

    init {
        // 天坦默认过滤器：AbstractC3693m.X(node)
        focusFilter = { node -> isNodeFocusable(node) }
    }

    /** 设置目标节点 —— 天坦 F(w) */
    fun setTarget(node: AccessibilityNodeInfo?) {
        target = node
    }

    /** 设置过滤器 —— 天坦 E(m) */
    fun setFilter(filter: (AccessibilityNodeInfo) -> Boolean) {
        focusFilter = filter
    }

    // ==================== 入口：v() / y() ====================

    /**
     * 找下一个 —— 1:1 抄天坦 v(node)
     */
    fun next(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        visited.clear()
        val result = findDown(node)
        return result ?: findNextSiblingUp(node)
    }

    /**
     * 找上一个 —— 1:1 抄天坦 y(node)
     */
    fun previous(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        visited.clear()

        // ① z(node) —— 前一个兄弟
        val prevSibling = findPrevSiblingOfSelf(node)
        if (prevSibling != null) return prevSibling

        // ② 父节点
        var parent = node.parent ?: return null
        if ((target == null || parent != target) && isFocusable(parent)) {
            return obtain(parent)
        }

        // ③ 上溯
        var prev = parent
        val seen = IdentitySet()
        while (true) {
            if (target != null && prev == target) return null
            if (!seen.add(parent)) return null

            val result = findPrevSibling(parent, prev)
            if (result != null) return result

            // 天坦：a(parent) 时返回 b0(parent)，不是 parent 本身
            if ((target == null || parent != target) && isFocusable(parent)) {
                return obtain(parent)
            }

            prev = parent
            parent = getParent(parent) ?: return null
        }
    }

    // ==================== r() 向下找（带 I() 双分支）====================

    /**
     * 向下找第一个可聚焦 —— 1:1 抄天坦 r(node)
     *
     * 天坦关键：I(node) 为真时用排序列表 q(node, false)，
     * 否则用 getChild(i) 原始顺序。两条路径必须分别处理。
     */
    private fun findDown(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val count = node.childCount
        if (count <= 0) return null

        val sort = needsSort(node)

        if (sort) {
            // 排序路径
            val children = sortedChildren(node)
            for (child in children) {
                val result = descend(child)
                if (result != null) return result
            }
        } else {
            // 原始顺序路径
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                val result = descend(child)
                if (result != null) return result
            }
        }
        return null
    }

    // ==================== D() 递归下降 ====================

    /**
     * 递归下降 —— 1:1 抄天坦 D(node)
     */
    private fun descend(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!visited.add(node)) return null
        return if (isFocusable(node)) obtain(node) else findDown(node)
    }

    // ==================== G() 上溯找下一个兄弟 ====================

    /**
     * 上溯找下一个 —— 1:1 抄天坦 G(node)
     */
    private fun findNextSiblingUp(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var prev = node
        var parent = getParent(node) ?: return null
        val seen = IdentitySet()

        while (true) {
            if (target != null && prev == target) return null
            if (!seen.add(parent)) return null

            val result = findNextSibling(parent, prev)
            if (result != null) return result

            prev = parent
            parent = getParent(parent) ?: return null
        }
    }

    // ==================== w() 找后一个兄弟（双分支）====================

    /**
     * 找 node 在 parent 里的后一个可聚焦兄弟 —— 1:1 抄天坦 w(parent, node)
     */
    private fun findNextSibling(
        parent: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val count = parent.childCount
        if (count <= 0) return null

        return if (needsSort(parent)) {
            val children = sortedChildren(parent)
            val idx = indexOfList(children, node)

            if (idx != -1 && idx < children.size - 1) {
                // 从 idx+1 往后找
                for (i in idx + 1 until children.size) {
                    val result = descend(children[i])
                    if (result != null) return result
                }
                null
            } else if (idx == -1) {
                // 不在直接子节点里 → 递归找
                val childIdx = findChildContaining(children, node)
                if (childIdx != -1) {
                    val child = children[childIdx]
                    val result = findNextSibling(child, node)
                    if (result == null && (target == null || child != target)) {
                        if (isFocusable(child)) return obtain(child)
                        for (i in childIdx + 1 until children.size) {
                            val r = descend(children[i])
                            if (r != null) return r
                        }
                    }
                    result
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            val idx = indexOf(parent, node)

            if (idx != -1 && idx < count - 1) {
                for (i in idx + 1 until count) {
                    val child = parent.getChild(i) ?: continue
                    val result = descend(child)
                    if (result != null) return result
                }
                null
            } else if (idx == -1 && count > 0) {
                // 天坦 w() 的 idx == -1 分支：顺序扫描，找到包含 node 的子节点
                var found = false
                for (i in 0 until count) {
                    val child = parent.getChild(i) ?: continue
                    if (found) {
                        val r = descend(child)
                        if (r != null) return r
                    } else {
                        if (findChildIndex(child, node) == -1) {
                            // 该子节点不含 node，跳过
                            continue
                        }
                        val r = findNextSiblingWithoutTrace(child, node)
                        val targetReached = target == null || child != target
                        if (r != null) return r
                        found = targetReached
                    }
                }
                null
            } else {
                null
            }
        }
    }

    /** 天坦 x(parent, node) —— w() 用的无回溯版本 */
    private fun findNextSiblingWithoutTrace(
        parent: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val count = parent.childCount
        if (count <= 0) return null

        val idx = indexOf(parent, node)
        if (idx != -1 && idx < count - 1) {
            for (i in idx + 1 until count) {
                val child = parent.getChild(i) ?: continue
                val result = descend(child)
                if (result != null) return result
            }
        }
        return null
    }

    /** 天坦 s(parent, child) —— 找 child 在 parent 里的直接索引（不缓存） */
    private fun findChildIndex(parent: AccessibilityNodeInfo, node: AccessibilityNodeInfo): Int {
        val count = parent.childCount
        for (i in 0 until count) {
            val child = parent.getChild(i) ?: continue
            if (child === node) return i
        }
        return -1
    }

    // ==================== A() 找前一个兄弟（双分支）====================

    /**
     * 找 node 在 parent 里的前一个可聚焦兄弟 —— 1:1 抄天坦 A(parent, node)
     */
    private fun findPrevSibling(
        parent: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val count = parent.childCount
        if (count <= 0) return null

        return if (needsSort(parent)) {
            val children = sortedChildren(parent)
            val idx = indexOfList(children, node)

            if (idx > 0) {
                for (i in idx - 1 downTo 0) {
                    val result = findLastDescendant(children[i])
                    if (result != null) return result
                }
                null
            } else if (idx == -1) {
                val childIdx = findChildContaining(children, node)
                if (childIdx != -1) {
                    val child = children[childIdx]
                    val result = findPrevSibling(child, node)
                    if (result == null && (target == null || child != target)) {
                        if (isFocusable(child)) return obtain(child)
                        for (i in childIdx - 1 downTo 0) {
                            val r = findLastDescendant(children[i])
                            if (r != null) return r
                        }
                    }
                    result
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            val idx = indexOf(parent, node)

            if (idx > 0) {
                for (i in idx - 1 downTo 0) {
                    val child = parent.getChild(i) ?: continue
                    val result = findLastDescendant(child)
                    if (result != null) return result
                }
                null
            } else if (idx == -1 && count > 0) {
                // 天坦 A() 的 idx == -1 分支
                var foundIdx = -1
                var targetReached = target == null
                for (i in 0 until count) {
                    val child = parent.getChild(i) ?: continue
                    val sub = findChildIndex(child, node)
                    if (sub != -1) {
                        val r = findPrevSiblingWithoutTrace(child, node)
                        targetReached = target == null || child != target
                        if (r != null) return r
                        foundIdx = i
                        if (!targetReached) break
                    }
                }
                if (foundIdx > 0) {
                    for (i in foundIdx - 1 downTo 0) {
                        val child = parent.getChild(i) ?: continue
                        val r = findLastDescendant(child)
                        if (r != null) return r
                    }
                }
                null
            } else {
                null
            }
        }
    }

    /** 天坦 B(parent, node) —— A() 用的无回溯版本 */
    private fun findPrevSiblingWithoutTrace(
        parent: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val count = parent.childCount
        if (count <= 0) return null
        val idx = indexOf(parent, node)
        if (idx > 0) {
            for (i in idx - 1 downTo 0) {
                val child = parent.getChild(i) ?: continue
                val result = findLastDescendant(child)
                if (result != null) return result
            }
        }
        return null
    }

    // ==================== z() 找当前节点的前一个兄弟 ====================

    /**
     * 找当前节点的前一个兄弟 —— 1:1 抄天坦 z(node)
     *
     *   z(node):
     *       parent = o(node, node.parent)
     *       if (parent == null) return null
     *       result = A(parent, node)
     *       parent.x0()                    ← 释放
     *       return result
     */
    private fun findPrevSiblingOfSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = getParent(node) ?: return null
        return findPrevSibling(parent, node)
    }

    // ==================== C() 找最后一个可聚焦后代 ====================

    /**
     * 找最后一个可聚焦后代 —— 1:1 抄天坦 C(node)（带 I() 双分支）
     */
    private fun findLastDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!visited.add(node)) return null

        val count = node.childCount
        if (count <= 0) {
            return if (isFocusable(node)) obtain(node) else null
        }

        // u(node)：从后往前
        if (needsSort(node)) {
            val children = sortedChildren(node)
            for (i in children.indices.reversed()) {
                val result = findLastDescendant(children[i])
                if (result != null) return result
            }
        } else {
            for (i in count - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                val result = findLastDescendant(child)
                if (result != null) return result
            }
        }

        return if (isFocusable(node)) obtain(node) else null
    }

    // ==================== p() 找索引（缓存 + 双向搜索）====================

    /**
     * 找 child 在 parent 里的索引 —— 1:1 抄天坦 p(parent, child)
     *
     * 天坦：命中缓存后，从上次索引位置**向两侧扩展**搜索。
     * 这是列表滚动的性能关键。
     *
     * 【缓存键的选择 —— 这是个陷阱】
     *
     * 天坦用 `wVar.hashCode()`（**内容**哈希）做缓存键，这带来了一个关键性质：
     * **内容变化会自动让缓存失效**（hash 变了 → 不匹配 → 走完整线性搜索）。
     *
     * 如果改用 `System.identityHashCode`（对象身份），身份在对象存活期间恒定，
     * 子节点增删后**缓存依然命中** → 用旧的中心索引做双向扩展搜索
     * → 搜不到就漏掉，导致索引错位、焦点跳错位置。
     *
     * 所以这里**故意用内容哈希**（与天坦一致）：
     *   ① 内容哈希变化 → 自动失效（安全性）
     *   ② 加 generation 校验 → 窗口/内容变化时强制失效（双保险）
     */
    private fun indexOf(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        val count = parent.childCount
        var result = -1

        try {
            val hash = parent.hashCode()
            // ★ 双保险：内容哈希匹配 + 代数匹配（窗口/内容变化时代数自增）
            if (hash != -1 && hash == cacheParentHash &&
                count == cacheChildCount && Companion.cacheGeneration == generation
            ) {
                val center = cacheChildIndex
                var offset = 0
                while (true) {
                    val lo = center - offset
                    val hi = center + offset
                    if (lo < 0 && hi >= count) break

                    if (lo >= 0) {
                        val c = parent.getChild(lo)
                        if (c === child) {
                            result = lo
                            cacheChildIndex = lo
                            return lo
                        }
                    }
                    if (offset != 0 && hi < count) {
                        val c = parent.getChild(hi)
                        if (c === child) {
                            result = hi
                            cacheChildIndex = hi
                            return hi
                        }
                    }
                    offset++
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        for (i in 0 until count) {
            val c = parent.getChild(i) ?: continue
            // 【修正】身份比较，对齐天坦 o0.w 的语义
            if (c === child) {
                try {
                    cacheParentHash = parent.hashCode()
                } catch (e: Exception) {
                    cacheParentHash = -1
                }
                cacheChildIndex = i
                cacheChildCount = count
                // ★★ 本轮修正（P1）：**删除** `Companion.cacheGeneration = generation`
                //
                // 【为什么必须删】`generation` 是本实例创建时抓取的快照
                // （`:145 private val generation: Int = cacheGeneration`）。
                // 把它写回静态字段的后果：
                //   · `invalidateIndexCache()` 递增 `cacheGeneration` 之后，
                //     只要再调一次本函数，就把静态字段**还原成旧值**；
                //   · 于是 `:562` 的 `Companion.cacheGeneration == generation`
                //     变成**永远成立** —— 代数校验彻底失效，
                //     `invalidateIndexCache()`（窗口/内容变化时调用）形同虚设。
                //   · 结果：索引缓存读到过期结果 → 焦点跳错位置。
                //
                // 【正确的语义】静态 `cacheGeneration` 是**单向递增的版本号**，
                // 只应由 `invalidateIndexCache()` 修改；
                // 实例只**读**它做比较，绝不写回。
                return i
            }
        }
        return -1
    }

    /** 在已排序列表里找索引（identity 语义） */
    private fun indexOfList(list: List<AccessibilityNodeInfo>, child: AccessibilityNodeInfo): Int {
        for (i in list.indices) {
            if (list[i] === child) return i
        }
        return -1
    }

    /**
     * 找哪个直接子节点包含 node —— 1:1 抄天坦 q(list, node)
     *
     * 注意：天坦找不到时设 f23906h = true（s() 会读这个标志）
     */
    private fun findChildContaining(list: List<AccessibilityNodeInfo>, node: AccessibilityNodeInfo): Int {
        for (i in list.indices) {
            val child = list[i]
            val count = child.childCount
            for (j in 0 until count) {
                val sub = child.getChild(j) ?: continue
                if (sub === node) return i
            }
        }
        lastSearchFailed = true
        return -1
    }

    // ==================== o() 取父节点 ====================

    /**
     * 取父节点 —— 对应天坦 `d7/C1421r.java:890~903` 的 `o(node, parent)`
     *
     * ★★ 本轮修正（真机「焦点卡死」的成因之一）
     *
     * 【天坦原文】
     * ```java
     * public final NodeInfo o(NodeInfo nodeInfo, NodeInfo nodeInfo2) {
     *     if (nodeInfo2 != null) {
     *         return nodeInfo2;
     *     }
     *     NodeInfo windowRoot = AccessibilityNodeInfoUtils.getWindowRoot(nodeInfo);
     *     if (windowRoot != null && !Intrinsics.areEqual(windowRoot, nodeInfo)) {
     *         return windowRoot;          // ← 窗口根回退
     *     }
     *     if (windowRoot == null) return null;
     *     windowRoot.x0();
     *     return null;
     * }
     * ```
     * 它是**两参数**函数。调用方传的是 `o(node, node.parent)`：
     *   · `node.parent != null` → 直接返回它（Kotlin 原来抄对了）
     *   · `node.parent == null` → **回退到 `getWindowRoot(node)`**，
     *     而不是返回 null（Kotlin 原来**漏了这一整支**）
     *
     * 【为什么这一支重要】当焦点需要在窗口顶部继续「上溯找下一个兄弟」时
     * （典型场景：**弹窗 / 浮层 / 输入法窗口的最后一个可聚焦元素**），
     * 天坦回退到窗口根继续找 → 能跨出当前子树继续遍历；
     * 直接返回 null 会让上溯链在窗口边界**断掉** →
     * 焦点卡死、滑动无反应（正是用户报的现象类型）。
     *
     * 【注意】`previous()` 里 `:187` 那处 `node.parent ?: return null`
     * **不改** —— 天坦 `C1421r.java:1262` 那里就是裸 `getParent()`，
     * 未走 `o()`。两处语义不同，不能统一。
     */
    private fun getParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val direct = try {
            node.parent
        } catch (e: Throwable) {
            null
        }
        if (direct != null) return direct

        // 窗口根回退 —— 对应 `AccessibilityNodeInfoUtils.getWindowRoot(node)`
        val windowRoot = getWindowRoot(node) ?: return null
        // 天坦：若窗口根就是本节点，返回 null（避免自环）
        if (windowRoot === node) return null
        return windowRoot
    }

    /**
     * `AccessibilityNodeInfoUtils.getWindowRoot(node)` —— 沿 parent 一路上溯到顶
     *
     * 天坦实现在 `w7/AccessibilityNodeInfoUtils`（可读版）。
     * 语义：从 node 出发不断取 parent，直到 parent 为 null，
     * 返回最后那个非 null 节点（即窗口根）。
     *
     * 【防环】加访问集合与深度上限 —— 无障碍树理论上无环，
     * 但异常 App 可能构造出畸形树，读屏不能因此死循环。
     */
    private fun getWindowRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo = node
        var depth = 0
        val seen = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(8)
        while (depth < MAX_WINDOW_ROOT_DEPTH) {
            if (seen.put(cur, true) != null) return cur   // 检测到环，返回当前
            val p = try {
                cur.parent
            } catch (e: Throwable) {
                null
            } ?: return cur
            cur = p
            depth++
        }
        return cur
    }

    // ==================== 排序与可聚焦判定 ====================

    /**
     * 是否需要排序 —— 天坦 a.I(node)
     */
    private fun needsSort(node: AccessibilityNodeInfo): Boolean =
        NodeOrderComparator.needsSort(node)

    /**
     * 排序后的子节点 —— 天坦 a.q(parent, false)
     */
    private fun sortedChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        NodeOrderComparator.sortChildren(node, true)

    /**
     * 可聚焦判定 —— 1:1 抄天坦 a(node)
     *
     *   a(node) = node != null && X(node) && filter.accept(node)
     */
    private fun isFocusable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (!isNodeFocusable(node)) return false
        return focusFilter.invoke(node)
    }

    /**
     * 天坦 AbstractC3693m.X(node) —— 基础可聚焦判定
     *
     *   X(node):
     *       if (node == null) return false
     *       if (node.s0()) return true                      ← isVisibleToUser
     *       return A0.f(node) && roleOf(node) != 15         ← WebView 特判
     */
    private fun isNodeFocusable(node: AccessibilityNodeInfo): Boolean {
        if (isVisibleToUser(node)) return true
        return isWebViewRelated(node) && NodeRoleHelper.roleOf(node) != NodeRoleHelper.ROLE_WEB_VIEW
    }

    private fun isVisibleToUser(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }

    /**
     * WebView 相关节点 —— 对应天坦 `NodeNavigator.isInRecyclerView`（`:106~124`）
     *
     * ★★ 本轮修正（P1）：原来只查了一个 actionList，漏了两条路径
     *
     * 【天坦原文】
     * ```java
     * // w7/NodeNavigator.java:106~114
     * public static boolean isInRecyclerView(NodeInfo nodeInfo) {
     *     if (nodeInfo == null) return false;
     *     return isInWebView(nodeInfo) || isInListView(nodeInfo);
     * }
     * // :105  isInWebView  → isInScrollView
     * // :128  isInScrollView(nodeInfo)
     * //         = AccessibilityNodeInfoUtils.hasActionMask2(nodeInfo, 1024, 2048)
     * // :110  isInListView → pkg.startsWith("org.mozilla.")
     * ```
     * 而 `hasActionMask2`（`NodeUtils.matchActionMask2`，`:285~289`）是
     * **位掩码 OR actionList 双路**：
     * ```java
     * (actions & 1024) == 1024 || (actions & 2048) == 2048
     *  || hasAction(1024) || hasAction(2048)
     * ```
     *
     * 【我原来漏了三条】
     *   ① 只查 2048，**漏了 1024**（ACTION_NEXT_HTML_ELEMENT）
     *   ② 只扫 actionList，**漏了 `(actions & id) == id` 位掩码路径**
     *   ③ 完全没有 mozilla 之外的分支 —— 这点我原来有（pkg 判断），保留
     *
     * 【真机影响】网页里可聚焦节点识别不全 → 触摸探索/焦点判定漏节点。
     *
     * 【注意】天坦这里有个**同名陷阱**：
     *   · `AccessibilityNodeInfoUtils.hasActionMask2`（:1091）= 只有位掩码
     *   · `NodeUtils.matchActionMask2`（:285）        = 位掩码 OR actionList
     *   天坦 `isInScrollView` 实际调用的是后者。白茶按后者实现。
     */
    private fun isWebViewRelated(node: AccessibilityNodeInfo): Boolean {
        // isInListView
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg.startsWith("org.mozilla.")) return true
        // isInWebView → isInScrollView → matchActionMask2(1024, 2048)
        return hasHtmlElementAction(node)
    }

    /**
     * `NodeUtils.matchActionMask2(node, 1024, 2048)` —— 双路判定
     *
     * 位掩码路径 `(actions & id) == id` **或** actionList 扫描。
     */
    private fun hasHtmlElementAction(node: AccessibilityNodeInfo): Boolean {
        val actions = try {
            node.actions
        } catch (e: Throwable) {
            0
        }
        // ① 位掩码路径
        if ((actions and ACTION_NEXT_HTML_ELEMENT) == ACTION_NEXT_HTML_ELEMENT) return true
        if ((actions and ACTION_PREVIOUS_HTML_ELEMENT) == ACTION_PREVIOUS_HTML_ELEMENT) return true
        // ② actionList 路径
        return try {
            node.actionList.any {
                it.id == ACTION_NEXT_HTML_ELEMENT || it.id == ACTION_PREVIOUS_HTML_ELEMENT
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 天坦 AbstractC3693m.b0(node) = o0.w.v0(node) —— 取节点副本 */
    private fun obtain(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain(node)

}