package com.baicha.reader.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点有效边界计算器 —— 对应 TalkBack `NodeCachedBoundsCalculator`（153 行）
 *
 * 【为什么需要「有效边界」】
 *
 * 一个容器的屏幕边界可能比它实际内容大得多（比如一个铺满屏幕的
 * FrameLayout 里只放了几个小按钮）。如果按容器边界做几何导航，
 * 会算出「最近节点」是那个大容器，而不是用户看到的那个按钮。
 *
 * TalkBack 的解法：**对不可聚焦的容器，用其可聚焦子节点的并集边界**
 * （并裁剪到容器自身边界内）。
 *
 * 【TalkBack 的算法（本类 1:1 实现）】
 *
 *   fetchBound(node):
 *       if (!可见) return EMPTY
 *       if (shouldFocusNode(node)) return node 自身边界      // 可聚焦 → 用自己的
 *       // 不可聚焦 → 收集子节点边界的并集
 *       hasChildBounds = false
 *       for (child in children) {
 *           b = getBoundsInternal(child)
 *           if (b != EMPTY) { 累计 minTop/minLeft/maxRight/maxBottom; hasChildBounds = true }
 *       }
 *       bounds = node 自身边界
 *       if (hasChildBounds) {
 *           bounds.top    = max(minTop, bounds.top)         // ★ 裁剪到自身边界内
 *           bounds.left   = max(minLeft, bounds.left)
 *           bounds.right  = min(maxRight, bounds.right)
 *           bounds.bottom = min(maxBottom, bounds.bottom)
 *       }
 *       return bounds
 *
 * 【防环】
 *   TalkBack 用 `calculatingNodes` 集合检测递归环（"node tree loop detected"）。
 *
 * 【性能（本类相对 TalkBack 的强化）】
 *
 * ① **接入 NodeCache**：TalkBack 的 `boundsMap` 是普通 HashMap 且只在
 *    单次 `OrderedTraversalController` 生命周期内有效。
 *    本类把结果缓存到 `NodeCache`，跨调用复用（窗口变化才失效）。
 * ② **身份语义**：TalkBack 用 `HashMap<AccessibilityNodeInfoCompat, Rect>`
 *    → 键是内容哈希。本类用 IdentityHashMap，避免内容相同的节点互相污染。
 * ③ **Rect 复用**：减少分配。
 */
object NodeBoundsCalculator {

    private const val TAG = "NodeBounds"

    /**
     * 空边界哨兵（代表「无效」）
     *
     * 注意：不用 isEmpty 判定 —— 真实节点也可能有零尺寸边界，
     * 用**引用相等**（===）区分「哨兵」与「真实空矩形」。
     */
    private val EMPTY_RECT = Rect(0, 0, 0, 0)

    /** 边界缓存（身份语义） */
    private val boundsMap = java.util.IdentityHashMap<AccessibilityNodeInfo, Rect>(128)

    /** 正在计算中的节点 —— 防环 */
    private val calculatingNodes = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()

    /** 临时 Rect */
    private val tempRect = Rect()

    /** 缓存上限 */
    private const val MAX_CACHE = 1024

    /**
     * 取有效边界 —— 对应 TalkBack `getBounds()`
     *
     * @return 有效边界；null = 节点无效或不可见
     */
    fun getBounds(node: AccessibilityNodeInfo?): Rect? {
        val bounds = getBoundsInternal(node)
        // ★ 用引用相等判定哨兵，而不是 isEmpty()
        //   （真实节点可能有零尺寸边界，那是有效值）
        return if (bounds === EMPTY_RECT) null else bounds
    }

    /**
     * 取有效边界（内部递归版）—— 对应 TalkBack `getBoundsInternal()`
     */
    private fun getBoundsInternal(node: AccessibilityNodeInfo?): Rect {
        if (node == null) return EMPTY_RECT

        // 防环：节点树有环（异常情况）
        if (calculatingNodes.containsKey(node)) {
            ReaderLog.w(TAG, "检测到节点树环，计算边界中止")
            return EMPTY_RECT
        }

        // 缓存命中
        boundsMap[node]?.let { return it }

        calculatingNodes[node] = true
        val bounds = try {
            fetchBound(node)
        } finally {
            calculatingNodes.remove(node)
        }

        // 写缓存（带容量控制）
        if (boundsMap.size >= MAX_CACHE) {
            boundsMap.clear()
        }
        boundsMap[node] = bounds
        return bounds
    }

    /**
     * 计算边界 —— 对应 TalkBack `fetchBound()`
     */
    private fun fetchBound(node: AccessibilityNodeInfo): Rect {
        // ① 不可见 → 空
        if (!isVisible(node)) return EMPTY_RECT

        // ② 可聚焦 → 用它自己的边界
        if (ShouldFocusNode.shouldFocus(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return bounds
        }

        // ③ 不可聚焦 → 用可聚焦子节点的并集
        val childCount = node.childCount
        var minTop = Int.MAX_VALUE
        var minLeft = Int.MAX_VALUE
        var maxBottom = Int.MIN_VALUE
        var maxRight = Int.MIN_VALUE
        var hasChildBounds = false

        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val childBounds = getBoundsInternal(child)
            if (childBounds !== EMPTY_RECT && !childBounds.isEmpty) {
                hasChildBounds = true
                if (childBounds.top < minTop) minTop = childBounds.top
                if (childBounds.left < minLeft) minLeft = childBounds.left
                if (childBounds.right > maxRight) maxRight = childBounds.right
                if (childBounds.bottom > maxBottom) maxBottom = childBounds.bottom
            }
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // ★ 裁剪到自身边界内 —— 子节点并集不能超出父容器
        if (hasChildBounds) {
            bounds.top = Math.max(minTop, bounds.top)
            bounds.left = Math.max(minLeft, bounds.left)
            bounds.right = Math.min(maxRight, bounds.right)
            bounds.bottom = Math.min(maxBottom, bounds.bottom)
        }

        return bounds
    }

    /**
     * 是否使用子节点边界 —— 对应 TalkBack `usesChildrenBounds()`
     *
     * 用于判断一个节点在几何导航时应按自己的边界还是子节点边界处理。
     */
    fun usesChildrenBounds(node: AccessibilityNodeInfo): Boolean {
        if (ShouldFocusNode.shouldFocus(node)) return false

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            if (ShouldFocusNode.shouldFocus(child)) return true
        }
        return false
    }

    /**
     * 取容器的前置边界（用于排序）—— 对应 TalkBack `getBoundsForReorder()`
     */
    fun getBoundsForReorder(node: AccessibilityNodeInfo): Rect? {
        return getBounds(node)
    }

    /** 清空缓存（窗口/内容变化时调用） */
    fun clear() {
        boundsMap.clear()
        calculatingNodes.clear()
    }

    /** 缓存条目数（诊断） */
    fun cacheSize(): Int = boundsMap.size

    private fun isVisible(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }
}
