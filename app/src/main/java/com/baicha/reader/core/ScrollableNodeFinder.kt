package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 可滚动容器查找 —— 对应 TalkBack
 * `FocusProcessorForLogicalNavigation.searchScrollableNodeFromBfs()`（约 3060 行处）
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要 BFS 而不是向上找】
 * ══════════════════════════════════════════════════════════
 *
 * 本项目原实现 `findScrollableAncestor()` 是**向上逐层找最近的可滚动祖先**。
 * 在嵌套滚动场景会选错：
 *
 *   外层纵向列表
 *     └─ 内层横向列表  ← 从内层项向上找，会选中它
 *
 *   用户想滚动整个页面（外层），却滚动了内层横向列表。
 *
 * TalkBack 用 **BFS 从根往下找**，再配合「最大滚动容器」选择策略：
 *
 *   ① BFS 找第一个可滚动节点（保证只滚"最外层"）
 *   ② 若启用 Pager 降权：
 *      - 第一个结果是 Pager → 初始 accumulator 为 null
 *        （只有屏上再无其他可滚动节点时才用它）
 *      - 否则以它为初始值，但**排除 Pager**
 *   ③ 第二次 BFS 找出**面积最大**的可滚动节点
 *   ④ 有最大值 → 用最大值；否则回退到第一次的结果
 *
 * ══════════════════════════════════════════════════════════
 * 【TalkBack 原注释的设计说明】
 * ══════════════════════════════════════════════════════════
 *
 *   "Returns the biggest scroll view or pager view on the screen...
 *    If the view hierarchy is complicated, for example, a pager view
 *    contains a long scroll view, even though the pager is bigger than
 *    the scroll view, we may prefer to select the scroll view since the
 *    content user interested should be inside it. So this method also
 *    supports to give the pager view the lower priority. In that case
 *    the pager will be selected only if there is no other scrollable
 *    view on the screen."
 */
object ScrollableNodeFinder {

    private const val TAG = "ScrollableFinder"

    /** BFS 节点数上限（防超大页面） */
    private const val MAX_BFS_NODES = 2000

    /** BFS 深度上限 */
    private const val MAX_DEPTH = 60

    // ==================== 主入口 ====================

    /**
     * BFS 查找可滚动容器 —— 对应 TalkBack `searchScrollableNodeFromBfs()`
     *
     * @param root 遍历起点（通常是窗口根）
     * @param forward 滚动方向（true = 向下/向右）
     * @param pagerWithLowPriority 是否给 Pager 降权
     * @param isPager 判断节点是否 Pager（注入以便测试）
     * @return 选中的可滚动节点；null = 屏上没有可滚动节点
     */
    fun search(
        root: AccessibilityNodeInfo?,
        forward: Boolean = true,
        pagerWithLowPriority: Boolean = true,
        isPager: (AccessibilityNodeInfo) -> Boolean = ::isPagerRole
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        // ① 第一次 BFS：找第一个可滚动节点（保证只滚最外层）
        val first = searchFirstByBfs(root, forward) ?: run {
            ReaderLog.d(TAG, "屏上无可滚动节点")
            return null
        }

        // ② 决定「最大容器」搜索的初始值与排除规则
        val excludePager: (AccessibilityNodeInfo) -> Boolean = { node ->
            !(pagerWithLowPriority && isPager(node))
        }

        val hasInitial = if (pagerWithLowPriority) !isPager(first) else true

        // ③ 第二次 BFS：找面积最大的可滚动节点
        val maxNode = findMaxSizeScrollable(
            root = root,
            forward = forward,
            excludePager = excludePager,
            hasInitial = hasInitial,
            firstNode = first
        )

        // ④ 有最大值 → 用它；否则回退第一次结果
        return maxNode ?: first
    }

    /**
     * 向上找最近的可滚动祖先 —— 本项目的原有实现（保留为快速路径）
     *
     * 适用场景：用户已经在某个滚动容器内（往上找比 BFS 快得多）。
     * TalkBack 的 BFS 用于「不知道在哪个容器里」的通用场景。
     */
    fun findScrollableAncestor(
        node: AccessibilityNodeInfo?,
        maxDepth: Int = 15
    ): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth < maxDepth) {
            if (isScrollable(cur)) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    // ==================== BFS 实现 ====================

    /**
     * BFS 找第一个满足条件的可滚动节点 —— 对应 TalkBack
     * `AccessibilityNodeInfoUtils.searchFromBfs()`
     */
    private fun searchFirstByBfs(
        root: AccessibilityNodeInfo,
        forward: Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        queue.addLast(root)
        var count = 0

        while (queue.isNotEmpty() && count++ < MAX_BFS_NODES) {
            val node = queue.removeFirst()
            if (visited.containsKey(node)) continue
            visited[node] = true

            if (isScrollableForDirection(node, forward)) return node

            val childCount = try {
                node.childCount
            } catch (e: Throwable) {
                0
            }
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Throwable) {
                    null
                } ?: continue
                if (!visited.containsKey(child)) queue.addLast(child)
            }
        }
        return null
    }

    /**
     * BFS 找面积最大的可滚动节点 —— 对应 TalkBack `MaxSizeNodeAccumulator`
     *
     * @param hasInitial 是否已有初始候选（决定"最大值"的下界）
     * @param firstNode 第一次 BFS 的结果（作为初始值的来源）
     */
    private fun findMaxSizeScrollable(
        root: AccessibilityNodeInfo,
        forward: Boolean,
        excludePager: (AccessibilityNodeInfo) -> Boolean,
        hasInitial: Boolean,
        firstNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = if (hasInitial) firstNode else null
        var bestArea = if (hasInitial) areaOf(firstNode) else -1

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        queue.addLast(root)
        var count = 0

        while (queue.isNotEmpty() && count++ < MAX_BFS_NODES) {
            val node = queue.removeFirst()
            if (visited.containsKey(node)) continue
            visited[node] = true

            if (isScrollableForDirection(node, forward) && excludePager(node)) {
                val area = areaOf(node)
                if (area > bestArea) {
                    bestArea = area
                    best = node
                }
            }

            val childCount = try {
                node.childCount
            } catch (e: Throwable) {
                0
            }
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Throwable) {
                    null
                } ?: continue
                if (!visited.containsKey(child)) queue.addLast(child)
            }
        }
        return best
    }

    // ==================== 判定工具 ====================

    /**
     * 是否可滚动（且方向匹配）
     *
     * 横向滚动只接受横向可滚动节点，反之亦然。
     */
    fun isScrollableForDirection(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        if (!isScrollable(node)) return false
        // 简化：不区分横纵（原生 API 无法直接取滚动轴）
        // TalkBack 用 getScrollOrPageActionFilter 按 action 过滤
        return true
    }

    /** 是否可滚动 */
    fun isScrollable(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isScrollable) return true
            node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                        it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 是否 Pager 角色 —— 对应 TalkBack `Role.ROLE_PAGER` */
    fun isPagerRole(node: AccessibilityNodeInfo): Boolean =
        NodeRoleHelper.roleOf(node) == NodeRoleHelper.ROLE_PAGER

    /**
     * 节点面积 —— 用 Bounds 计算（可测）
     */
    fun areaOf(node: AccessibilityNodeInfo): Int {
        return try {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val b = Bounds(r)
            if (b.isEmpty) 0 else b.width * b.height
        } catch (e: Throwable) {
            0
        }
    }

    // ==================== 纯逻辑辅助（可测）====================

    /**
     * 从候选列表里选最大的 —— 把纯逻辑抽出来便于测试
     *
     * @param candidates 候选（索引 → 面积）
     * @param initialIndex 初始候选下标（-1 = 无）
     * @return 选中的下标；-1 = 无候选
     */
    fun pickMaxIndex(candidates: List<Int>, initialIndex: Int = -1): Int {
        var bestIdx = -1
        var bestArea = -1

        if (initialIndex >= 0 && initialIndex < candidates.size) {
            bestIdx = initialIndex
            bestArea = candidates[initialIndex]
        }

        for (i in candidates.indices) {
            if (candidates[i] > bestArea) {
                bestArea = candidates[i]
                bestIdx = i
            }
        }
        return bestIdx
    }
}
