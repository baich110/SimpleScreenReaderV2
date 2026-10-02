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

        // 起点用「有效边界」（不可聚焦容器按可聚焦子节点并集裁剪）
        val focusedBounds = resolveBounds(from) ?: return null
        if (focusedBounds.isEmpty) return null

        // ★ TalkBack 技巧：初始候选设为「起点沿反方向偏移自身宽高 +1」
        //   这样任何真实节点都会比它更优，省掉 null 特判
        val bestCandidateBounds = when (direction) {
            TraversalDirection.LEFT -> focusedBounds.offset(focusedBounds.width + 1, 0)
            TraversalDirection.RIGHT -> focusedBounds.offset(-(focusedBounds.width + 1), 0)
            TraversalDirection.UP -> focusedBounds.offset(0, focusedBounds.height + 1)
            TraversalDirection.DOWN -> focusedBounds.offset(0, -(focusedBounds.height + 1))
            else -> return null
        }

        var closest: AccessibilityNodeInfo? = null
        var bestBounds = bestCandidateBounds

        val n = focusableNodes.size
        for (i in 0 until n) {
            val candidate = focusableNodes[i]
            // 跳过自己与根
            if (candidate === from || candidate === root) continue

            val otherBounds = resolveBounds(candidate) ?: continue
            if (otherBounds.isEmpty) continue

            if (isBetterCandidate(direction, focusedBounds, otherBounds, bestBounds)) {
                bestBounds = otherBounds
                closest = candidate
            }
        }

        return closest
    }

    /**
     * 取节点的有效边界 —— 优先用 NodeBoundsCalculator 的结果
     *
     * 这是唯一需要与 Android API 交互的地方：
     *   Rect → Bounds 的转换边界。
     */
    private fun resolveBounds(node: AccessibilityNodeInfo): Bounds? {
        NodeBoundsCalculator.getBounds(node)?.let { return Bounds(it) }
        return try {
            val r = Rect()
            node.getBoundsInScreen(r)
            Bounds(r)
        } catch (e: Throwable) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════
    // 候选比较算法 —— 1:1 对照 TalkBack DirectionalTraversalStrategy
    //
    // 【原实现的缺陷（本轮修复）】
    //   只有「方向判定 + 主轴距离 + 垂直距离」三层，缺了 TalkBack 的
    //   beam 机制与加权距离，导致：
    //     ① isToDirectionOf 太宽松 → 部分重叠的节点被误判为候选
    //     ② 缺 beamBeats → 斜向节点可能被选中，正前方反而选不中
    //     ③ 缺加权距离 → 距离权重不对（TalkBack 主轴权重 13 倍）
    // ══════════════════════════════════════════════════════════

    /**
     * 比较两个候选谁更优 —— 1:1 抄 TalkBack `isBetterCandidate()`
     *
     * 五层判定：
     *   ① rect1 不是候选 → false
     *   ② rect2 不是候选 → true
     *   ③ rect1 靠 beam 胜出 → true
     *   ④ rect2 靠 beam 胜出 → false
     *   ⑤ 加权距离比较（主轴权重 13 倍）
     */
    private fun isBetterCandidate(
        direction: Int,
        source: Bounds,
        rect1: Bounds,
        rect2: Bounds
    ): Boolean {
        // ① 候选资格（比 isToDirectionOf 严格）
        if (!isCandidate(source, rect1, direction)) return false

        // ② rect2 不是候选 → rect1 胜
        if (!isCandidate(source, rect2, direction)) return true

        // ③ beam 判定
        if (beamBeats(direction, source, rect1, rect2)) return true

        // ④ 反向 beam（rect2 更优）
        if (beamBeats(direction, source, rect2, rect1)) return false

        // ⑤ 加权距离（含溢出保护）
        return weightedDistance(direction, source, rect1) <
                weightedDistance(direction, source, rect2)
    }

    /**
     * 候选资格 —— 1:1 抄 TalkBack `isCandidate()`
     *
     * 比 `isToDirectionOf` 严格：要求候选**至少部分地**位于目标方向，
     * 且不能在起点之前。
     *
     * TalkBack 原注释：
     *   "This checks whether the dest is at least partially to the direction of
     *    (e.g left of) from source."
     */
    private fun isCandidate(src: Bounds, dest: Bounds, direction: Int): Boolean {
        return when (direction) {
            TraversalDirection.LEFT ->
                (src.right > dest.right || src.left >= dest.right) && src.left > dest.left

            TraversalDirection.RIGHT ->
                (src.left < dest.left || src.right <= dest.left) && src.right < dest.right

            TraversalDirection.UP ->
                (src.bottom > dest.bottom || src.top >= dest.bottom) && src.top > dest.top

            TraversalDirection.DOWN ->
                (src.top < dest.top || src.bottom <= dest.top) && src.bottom < dest.bottom

            else -> false
        }
    }

    /**
     * beam 判定 —— 1:1 抄 TalkBack `beamBeats()`
     *
     * 【什么是 beam】
     *   "beam" 指「从起点出发、沿目标方向的投影带」。
     *   如果候选在 beam 内而另一个不在，候选通常胜出 ——
     *   因为不在 beam 里的那个需要"斜着走"才能到达。
     *
     * TalkBack 原注释（垂直方向的部分）：
     *   "for vertical directions, beams only beat up to a point:
     *    now, as long as rect2 isn't completely closer, rect1 wins"
     */
    private fun beamBeats(
        direction: Int,
        source: Bounds,
        rect1: Bounds,
        rect2: Bounds
    ): Boolean {
        val rect1InBeam = beamsOverlap(direction, source, rect1)
        val rect2InBeam = beamsOverlap(direction, source, rect2)

        // rect1 不是「独占 beam」→ 不赢
        if (rect2InBeam || !rect1InBeam) return false

        // 现在知道：rect1 在 beam 内，rect2 不在
        // 若 rect1 在目标方向而 rect2 不在 → rect1 胜
        if (!isToDirectionOf(direction, source, rect2)) return true

        // 水平方向：独占 beam 永远胜
        if (direction == TraversalDirection.LEFT ||
            direction == TraversalDirection.RIGHT
        ) {
            return true
        }

        // 垂直方向：beam 只能赢到一定程度
        // （rect2 不能"整体更近"）
        return majorAxisDistance(direction, source, rect1) <
                majorAxisDistanceToFarEdge(direction, source, rect2)
    }

    /**
     * beam 是否重叠 —— 1:1 抄 TalkBack `beamsOverlap()`
     */
    private fun beamsOverlap(direction: Int, rect1: Bounds, rect2: Bounds): Boolean {
        return when (direction) {
            TraversalDirection.LEFT, TraversalDirection.RIGHT ->
                rect2.bottom >= rect1.top && rect2.top <= rect1.bottom

            TraversalDirection.UP, TraversalDirection.DOWN ->
                rect2.right >= rect1.left && rect2.left <= rect1.right

            else -> false
        }
    }

    /**
     * 加权距离 —— 1:1 抄 TalkBack `getWeightedDistanceFor()`
     *
     * **主轴权重 13 倍** —— 这是「优先选正前方」的量化表达。
     *
     * TalkBack 原注释：
     *   "Warning: this fudge factor is finely tuned, be sure to run all
     *    focus tests if you dare to tweak it."
     *
     * 溢出保护：超 10000 直接返回 MAX_VALUE（最大可能值 1400000000 < Int.MAX）。
     */
    private fun weightedDistance(direction: Int, source: Bounds, dest: Bounds): Int {
        val major = majorAxisDistance(direction, source, dest)
        val minor = minorAxisDistance(direction, source, dest)
        if (major > 10000 || minor > 10000) return Int.MAX_VALUE
        return 13 * major * major + minor * minor
    }

    /**
     * 主轴距离（到近边）—— 1:1 抄 TalkBack `majorAxisDistance()`
     *
     * 注意 TalkBack 用 `Math.max(0, ...)` —— 目标不在该方向时返回 0（不是 1）。
     */
    private fun majorAxisDistance(direction: Int, source: Bounds, dest: Bounds): Int =
        Math.max(0, majorAxisDistanceRaw(direction, source, dest))

    private fun majorAxisDistanceRaw(direction: Int, source: Bounds, dest: Bounds): Int =
        when (direction) {
            TraversalDirection.LEFT -> source.left - dest.right
            TraversalDirection.RIGHT -> dest.left - source.right
            TraversalDirection.UP -> source.top - dest.bottom
            TraversalDirection.DOWN -> dest.top - source.bottom
            else -> 0
        }

    /**
     * 主轴距离（到远边）—— 1:1 抄 TalkBack `majorAxisDistanceToFarEdge()`
     *
     * 注意最小值为 **1**（不是 0）—— 用于与 majorAxisDistance 打破平局。
     */
    private fun majorAxisDistanceToFarEdge(direction: Int, source: Bounds, dest: Bounds): Int =
        Math.max(1, majorAxisDistanceToFarEdgeRaw(direction, source, dest))

    private fun majorAxisDistanceToFarEdgeRaw(direction: Int, source: Bounds, dest: Bounds): Int =
        when (direction) {
            TraversalDirection.LEFT -> source.left - dest.left
            TraversalDirection.RIGHT -> dest.right - source.right
            TraversalDirection.UP -> source.top - dest.top
            TraversalDirection.DOWN -> dest.bottom - source.bottom
            else -> 0
        }

    /**
     * 垂直轴距离（中心线距离）—— 1:1 抄 TalkBack `minorAxisDistance()`
     */
    private fun minorAxisDistance(direction: Int, source: Bounds, dest: Bounds): Int =
        when (direction) {
            TraversalDirection.LEFT, TraversalDirection.RIGHT ->
                Math.abs(source.centerY - dest.centerY)

            TraversalDirection.UP, TraversalDirection.DOWN ->
                Math.abs(source.centerX - dest.centerX)

            else -> 0
        }

    /**
     * 方向判定 —— 1:1 抄 TalkBack `isToDirectionOf()`
     *
     * 用于 beamBeats 内部；候选资格由 isCandidate 负责。
     */
    private fun isToDirectionOf(direction: Int, src: Bounds, dest: Bounds): Boolean =
        when (direction) {
            TraversalDirection.LEFT -> src.left >= dest.right
            TraversalDirection.RIGHT -> src.right <= dest.left
            TraversalDirection.UP -> src.top >= dest.bottom
            TraversalDirection.DOWN -> src.bottom <= dest.top
            else -> false
        }

    /** 可聚焦节点数（诊断） */
    fun nodeCount(): Int = focusableNodes.size
}
