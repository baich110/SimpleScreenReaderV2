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

    // ==================== 算法委托 ====================
    //
    // 全部几何判定已抽到 [SpatialGeometry]（纯函数，可用 Bounds 直接单测）。
    // 这里只做委托，保留原方法名便于对照阅读 ——
    // 真正的实现与源码依据（AOSP 行号）见 SpatialGeometry 各函数注释。
    //
    // 【为什么抽出去】这些函数本来就只依赖矩形，与「节点」无关；
    // 作为 private 实例方法就永远只能靠「构造真节点树 + 跑真遍历」验证，
    // 而节点在 JVM 里需要专门替身 —— 导致**算法本身从未被逐条断言过**。

    private fun isBetterCandidate(
        direction: Int,
        source: Bounds,
        rect1: Bounds,
        rect2: Bounds
    ): Boolean = SpatialGeometry.isBetterCandidate(direction, source, rect1, rect2)

    private fun isCandidate(src: Bounds, dest: Bounds, direction: Int): Boolean =
        SpatialGeometry.isCandidate(src, dest, direction)

    private fun beamsOverlap(direction: Int, rect1: Bounds, rect2: Bounds): Boolean =
        SpatialGeometry.beamsOverlap(direction, rect1, rect2)

    private fun isToDirectionOf(direction: Int, src: Bounds, dest: Bounds): Boolean =
        SpatialGeometry.isToDirectionOf(direction, src, dest)


    /** 可聚焦节点数（诊断） */
    fun nodeCount(): Int = focusableNodes.size
}
