package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 焦点搜索工具 —— 对应 TalkBack `TraversalStrategyUtils`（456 行）
 *
 * 【TalkBack 的核心 API 契约】
 *
 *   searchFocus(traversal, currentFocus, direction, filter):
 *       targetNode = currentFocus
 *       seenNodes = new HashSet()
 *       do {
 *           seenNodes.add(targetNode)
 *           targetNode = traversal.findFocus(targetNode, direction)
 *           if (seenNodes.contains(targetNode)) {
 *               log("Found duplicate during traversal")
 *               return null            // ★ 检测到环 → 优雅退出
 *           }
 *       } while (targetNode != null && !filter.accept(targetNode))
 *       return targetNode
 *
 * 【为什么要单独抽这一层】
 *
 * 原来的 `FocusManager.searchFocus()` 把这个循环内联在方法里，
 * 而且**缺少 seenNodes 防环**（只有 TatansTraversal 内部的 visited）。
 * 抽出来后：
 *   ① 防环职责明确（seenNodes 与遍历器内部 visited 是两层防护）
 *   ② 可复用于「找第一个焦点」「按方向找」「网格查找」等所有场景
 *   ③ 与 TalkBack 的 API 形状一致，便于对照维护
 *
 * 【性能设计】
 *
 * ① **身份集合**：seenNodes 用 IdentityHashMap（节点身份语义，避免内容哈希碰撞）
 * ② **无分配快路径**：第一个节点就满足 filter 时直接返回，不建集合
 * ③ **迭代上限**：即使防环失效也不会死循环
 */
object FocusSearch {

    private const val TAG = "FocusSearch"

    /** 迭代上限（防环的兜底，正常情况远用不到） */
    private const val MAX_ITERATIONS = 2000

    /**
     * 搜索焦点 —— 1:1 对应 TalkBack `searchFocus()`
     *
     * @param traversal 遍历策略
     * @param currentFocus 起点
     * @param direction 方向（TraversalDirection 常量）
     * @param filter 候选过滤器
     * @return 下一个满足条件的节点；null = 找不到或检测到环
     */
    fun searchFocus(
        traversal: TraversalStrategy,
        currentFocus: AccessibilityNodeInfo?,
        direction: Int,
        filter: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        if (currentFocus == null) return null

        val accept: (AccessibilityNodeInfo) -> Boolean =
            filter ?: { ShouldFocusNode.shouldFocus(it) }

        var target: AccessibilityNodeInfo = currentFocus
        var seen: java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>? = null
        var iterations = 0

        while (iterations++ < MAX_ITERATIONS) {
            // 惰性创建 seen（第一个就命中的常见场景零分配）
            if (seen == null) {
                seen = java.util.IdentityHashMap(16)
            }
            seen[target] = true

            val next = traversal.next(target, direction)
            if (next == null) return null

            // ★ 防环：回到访问过的节点 → 说明有环，优雅退出
            if (seen.containsKey(next)) {
                ReaderLog.e(TAG, "遍历中发现重复节点，判定为环，退出")
                return null
            }

            if (accept(next)) return next
            target = next
        }

        ReaderLog.w(TAG, "遍历超过 $MAX_ITERATIONS 次迭代，强制退出")
        return null
    }

    /**
     * 找树里的第一个焦点 —— 对应 TalkBack `findFirstFocusInNodeTree()`
     *
     * @param direction FORWARD 返回遍历序第一个；BACKWARD 返回最后一个
     */
    fun findFirstFocus(
        traversal: TraversalStrategy,
        root: AccessibilityNodeInfo?,
        direction: Int = TraversalDirection.FORWARD,
        filter: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        val accept: (AccessibilityNodeInfo) -> Boolean =
            filter ?: { ShouldFocusNode.shouldFocus(it) }

        // 根自身满足 → 直接用（省掉一次遍历）
        if (accept(root)) return root

        val start = if (direction == TraversalDirection.BACKWARD) {
            TraversalDirection.BACKWARD
        } else {
            TraversalDirection.FORWARD
        }

        // 从根开始按方向找第一个
        return searchFocus(traversal, root, start, accept)
    }

    /**
     * 按空间方向找最近焦点（走 SpatialTraversalStrategy）
     *
     * 对应 TalkBack `DirectionalTraversalStrategy.findFocus()`
     */
    fun findFocusInDirection(
        root: AccessibilityNodeInfo?,
        currentFocus: AccessibilityNodeInfo?,
        direction: Int,
        filter: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        if (root == null || currentFocus == null) return null
        if (!TraversalDirection.isSpatial(direction)) return null

        val strategy = SpatialTraversalStrategy(root) {
            filter?.invoke(it) ?: ShouldFocusNode.shouldFocus(it)
        }
        return try {
            strategy.findFocusInDirection(currentFocus, direction)
        } finally {
            strategy.release()
        }
    }

    /**
     * 判断节点是否在树里（用遍历器判定）
     *
     * 用于「窗口变化后，原焦点是否还在当前树里」
     */
    fun containsNode(root: AccessibilityNodeInfo?, target: AccessibilityNodeInfo): Boolean {
        if (root == null) return false
        // 从 target 向上找到 root 即说明在树里
        var cur: AccessibilityNodeInfo? = target
        var depth = 0
        while (cur != null && depth < 40) {
            if (cur === root) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    /**
     * 找节点的自节点或最近的匹配祖先 —— 对应 TalkBack `getSelfOrMatchingAncestor()`
     *
     * TalkBack 在「无焦点时找输入焦点」等场景使用。
     */
    fun getSelfOrMatchingAncestor(
        node: AccessibilityNodeInfo?,
        filter: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth < 40) {
            if (filter(cur)) return cur
            cur = cur.parent
            depth++
        }
        return null
    }
}
