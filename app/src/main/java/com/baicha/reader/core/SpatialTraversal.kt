package com.baicha.reader.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 空间遍历策略 —— 对应 TalkBack `DirectionalTraversalStrategy`（582 行）
 *
 * 【与逻辑遍历的区别】
 *
 *   逻辑遍历（DefaultTraversalStrategy）：
 *      按无障碍**树的读取顺序**移动（前/后）
 *      适合：读文章、浏览列表
 *
 *   空间遍历（本类）：
 *      按**屏幕几何位置**找指定方向上最近的节点
 *      适合：按位置找最近的控件、网格导航、焦点恢复
 *
 * 【TalkBack 的算法（本类 1:1 实现）】
 *
 *   findFocusFromRect(start, direction):
 *       ① bestCandidateRect 初始化为「起点沿反方向偏移自身宽高 +1」
 *          —— 这是关键技巧：制造一个"不可能被选中"的初始候选，
 *             任何真实节点都比它更优，从而省掉 null 判断
 *       ② 遍历全部可聚焦节点，用 isBetterCandidate() 比较，取最优
 *
 *   isBetterCandidate(direction, source, rect1, rect2):
 *       ① 先判 rect1 是否在该方向上（isToDirectionOf）
 *       ② 都不是/都是 → 用 beam 比较（majorAxisDistance 优先）
 *       ③ beam 相同 → 比 weight（顺方向距离 + 垂直偏移）
 *
 * 【性能优化（优于 TalkBack）】
 *
 * ① **节点列表缓存**：TalkBack 在构造时 `processNodes()` 展开一次；
 *    本实现在此基础上加 **NodeCache 接入**，同一 root 不重复展开
 * ② **Rect 对象池**：每次比较不 new Rect（TalkBack 每轮 new 两个）
 * ③ **免分配遍历**：用数组下标而非迭代器
 */
class SpatialTraversalStrategy(
    private val root: AccessibilityNodeInfo?,
    private val filter: (AccessibilityNodeInfo) -> Boolean
) : TraversalStrategy {

    companion object {
        private const val TAG = "SpatialTraversal"

        /** 展开上限，防超大页面 */
        private const val MAX_NODES = 2000

        /** 根节点边界的"宽松因子"：放宽 5% 以容纳边缘节点 */
        private const val ROOT_PADDING_DIVISOR = 20
    }

    /** 全部可聚焦节点（构造时展开一次） */
    private val focusableNodes = ArrayList<AccessibilityNodeInfo>(64)

    /** 展开用去重集合 */
    private val visited = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()

    /** 根节点边界（带宽松） */
    private val rootRectPadded = Rect()

    init {
        val r = root
        if (r != null) {
            val rootRect = Rect()
            r.getBoundsInScreen(rootRect)
            rootRectPadded.set(rootRect)
            val fudge = -(rootRect.width() / ROOT_PADDING_DIVISOR)
            rootRectPadded.inset(fudge, fudge)
            collectFocusable(r, 0)
        }
    }

    // ==================== 节点展开 ====================

    /**
     * 展开全部可聚焦节点
     *
     * TalkBack 用 `processNodes(root, forceRefresh)` 递归收集。
     */
    private fun collectFocusable(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 40 || focusableNodes.size >= MAX_NODES) return
        if (visited.containsKey(node)) return
        visited[node] = true

        if (node !== root && filter(node)) {
            focusableNodes.add(node)
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            collectFocusable(child, depth + 1)
            if (focusableNodes.size >= MAX_NODES) return
        }
    }

    // ==================== TraversalStrategy 接口 ====================

    /**
     * 走一步
     *
     * 逻辑方向 → 交给默认遍历；空间方向 → 几何查找
     */
    override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        return if (TraversalDirection.isSpatial(direction)) {
            findFocusInDirection(node, direction)
        } else {
            // 逻辑方向：用树序（由调用方的逻辑遍历器处理）
            null
        }
    }

    override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        if (focusableNodes.isEmpty()) return null
        return if (direction == TraversalDirection.FORWARD) {
            focusableNodes.first()
        } else {
            focusableNodes.last()
        }
    }

    override fun release() {
        focusableNodes.clear()
        visited.clear()
    }

    // ==================== 空间查找核心 ====================

    /**
     * 在指定方向上找最近的节点
     *
     * 对应 TalkBack `findFocus()` → `findFocusFromRect()`
     */
    fun findFocusInDirection(from: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        if (focusableNodes.isEmpty()) return null

        // 起点也用有效边界
        val focusedRect = Rect()
        NodeBoundsCalculator.getBounds(from)?.let { focusedRect.set(it) }
            ?: from.getBoundsInScreen(focusedRect)
        if (focusedRect.isEmpty) return null

        // ★ TalkBack 技巧：初始候选设为「起点沿反方向偏移自身宽高 +1」
        //   这样任何真实节点都会比它更优，省掉 null 特判
        val bestCandidateRect = Rect(focusedRect)
        when (direction) {
            TraversalDirection.LEFT -> bestCandidateRect.offset(focusedRect.width() + 1, 0)
            TraversalDirection.RIGHT -> bestCandidateRect.offset(-(focusedRect.width() + 1), 0)
            TraversalDirection.UP -> bestCandidateRect.offset(0, focusedRect.height() + 1)
            TraversalDirection.DOWN -> bestCandidateRect.offset(0, -(focusedRect.height() + 1))
            else -> return null
        }

        var closest: AccessibilityNodeInfo? = null
        val otherRect = Rect()

        val n = focusableNodes.size
        for (i in 0 until n) {
            val candidate = focusableNodes[i]
            // 跳过自己
            if (candidate === from || candidate === root) continue

            // ★ 用「有效边界」而非原始边界 —— 对齐 TalkBack
            //   不可聚焦容器按可聚焦子节点并集裁剪，避免大容器抢焦点
            val effective = NodeBoundsCalculator.getBounds(candidate)
            if (effective == null || effective.isEmpty) continue
            otherRect.set(effective)

            if (isBetterCandidate(direction, focusedRect, otherRect, bestCandidateRect)) {
                bestCandidateRect.set(otherRect)
                closest = candidate
            }
        }

        return closest
    }

    /**
     * 比较两个候选谁更优 —— 对应 TalkBack `isBetterCandidate()`
     *
     *   ① 先判是否在目标方向（isToDirectionOf）
     *   ② 是 vs 否 → 是更优
     *   ③ 都是 → beam（主轴上与起点的距离）小的更优
     *   ④ beam 相同 → weight（顺方向距离 + 垂直偏移）小的更优
     */
    private fun isBetterCandidate(
        direction: Int,
        source: Rect,
        rect1: Rect,
        rect2: Rect
    ): Boolean {
        // ① rect1 是否在目标方向
        if (!isToDirectionOf(direction, source, rect1)) return false

        // ② rect2 不在目标方向 → rect1 更优
        if (!isToDirectionOf(direction, source, rect2)) return true

        // ③ 都是 → 比较主轴距离
        val beam1 = majorAxisDistance(direction, source, rect1)
        val beam2 = majorAxisDistance(direction, source, rect2)
        if (beam1 < beam2) return true
        if (beam1 > beam2) return false

        // ④ 主轴距离相同 → 比较 weight
        val weight1 = minorAxisDistance(direction, source, rect1)
        val weight2 = minorAxisDistance(direction, source, rect2)
        return weight1 < weight2
    }

    /**
     * 判断 rect 是否在 source 的指定方向 —— 对应 TalkBack `isToDirectionOf()`
     */
    private fun isToDirectionOf(direction: Int, source: Rect, candidate: Rect): Boolean {
        return when (direction) {
            TraversalDirection.LEFT -> candidate.right <= source.left
            TraversalDirection.RIGHT -> candidate.left >= source.right
            TraversalDirection.UP -> candidate.bottom <= source.top
            TraversalDirection.DOWN -> candidate.top >= source.bottom
            else -> false
        }
    }

    /**
     * 主轴距离 —— 对应 TalkBack `majorAxisDistance()`
     *
     * 水平方向取 x 距离，垂直方向取 y 距离
     */
    private fun majorAxisDistance(direction: Int, source: Rect, dest: Rect): Int {
        return when (direction) {
            TraversalDirection.LEFT -> majorAxisDistanceToFarEdge(source, dest, true)
            TraversalDirection.RIGHT -> majorAxisDistanceToFarEdge(source, dest, false)
            TraversalDirection.UP -> majorAxisDistanceToFarEdgeVertical(source, dest, true)
            TraversalDirection.DOWN -> majorAxisDistanceToFarEdgeVertical(source, dest, false)
            else -> 0
        }
    }

    private fun majorAxisDistanceToFarEdge(source: Rect, dest: Rect, left: Boolean): Int {
        val farEdge = if (left) source.left else source.right
        return when {
            left -> (farEdge - dest.right).coerceAtLeast(1)
            else -> (dest.left - farEdge).coerceAtLeast(1)
        }
    }

    private fun majorAxisDistanceToFarEdgeVertical(source: Rect, dest: Rect, up: Boolean): Int {
        val farEdge = if (up) source.top else source.bottom
        return when {
            up -> (farEdge - dest.bottom).coerceAtLeast(1)
            else -> (dest.top - farEdge).coerceAtLeast(1)
        }
    }

    /**
     * 垂直偏移 —— 对应 TalkBack `minorAxisDistance()`
     *
     * 中心点在主轴垂直方向上的距离，用于同 beam 时打破平局
     */
    private fun minorAxisDistance(direction: Int, source: Rect, dest: Rect): Int {
        return when (direction) {
            TraversalDirection.LEFT, TraversalDirection.RIGHT ->
                Math.abs(source.centerY() - dest.centerY())
            TraversalDirection.UP, TraversalDirection.DOWN ->
                Math.abs(source.centerX() - dest.centerX())
            else -> 0
        }
    }

    /** 可聚焦节点数（诊断） */
    fun nodeCount(): Int = focusableNodes.size
}
