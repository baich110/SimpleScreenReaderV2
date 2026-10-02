package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 遍历策略接口 —— 对应天坦 d7/InterfaceC1426u（12 行）
 *
 * 【天坦的设计】
 *
 *   interface InterfaceC1426u {
 *       void a();                                  释放
 *       o0.w b(o0.w node, int direction);          走一步
 *       o0.w c(o0.w node, int direction);          定位起始
 *   }
 *
 *   4 个实现（由 C1427v.h() 工厂按 strategyType 分发）：
 *       C1424s   默认（内部用 C1410e 迭代器）
 *       C1409d   带父容器上下文
 *       C1406a   带缓存索引
 *       C1412g   网格/特殊布局
 *
 * 【为什么需要策略抽象】
 *
 * 不同场景需要不同的遍历规则：
 *   - 普通界面：按几何顺序（上→下，左→右）遍历全部可聚焦节点
 *   - 列表：只遍历列表项，跳过列表容器本身
 *   - 网格：行列遍历，支持上下左右四个方向
 *   - 带缓存：大数据量列表，复用上次的索引避免重复遍历
 *
 * 把遍历规则从「焦点控制器」抽出来，焦点控制器就只管
 * 「调用策略 + 设焦点 + 播报」，职责清晰。
 */
interface TraversalStrategy {

    /**
     * 走一步 —— 对应天坦 InterfaceC1426u.b()
     *
     * @param node 当前节点
     * @param direction 方向（DIRECTION_NEXT / DIRECTION_PREVIOUS）
     * @return 下一个节点；null = 走到头了
     */
    fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo?

    /**
     * 定位起始 —— 对应天坦 InterfaceC1426u.c()
     *
     * 给定根节点，返回该策略下的第一个节点
     */
    fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo?

    /**
     * 释放 —— 对应天坦 InterfaceC1426u.a()
     */
    fun release()

    companion object {
        const val DIRECTION_NEXT = 1
        const val DIRECTION_PREVIOUS = 2
    }
}

/**
 * 默认遍历策略 —— 对应天坦 d7/C1424s
 *
 * 【天坦原版内部用的是 C1410e 迭代器，它和 C1423r 共用同一套：
 *   排序（C1423r.a.q）、可聚焦判定（a()）】
 *
 * 本实现直接走 TatansTraversal（C1423r 的移植），
 * 因为 TatansTraversal 已经是完整的迭代实现。
 */
class DefaultTraversalStrategy(
    private val root: AccessibilityNodeInfo?,
    private val filter: (AccessibilityNodeInfo) -> Boolean
) : TraversalStrategy {

    private val traversal = TatansTraversal(root).apply {
        setFilter(filter)
    }

    override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        return if (direction == TraversalStrategy.DIRECTION_NEXT) {
            traversal.next(node)
        } else {
            traversal.previous(node)
        }
    }

    override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        // 天坦 C1424s.c()：direction == 1 时返回节点副本本身
        return if (direction == TraversalStrategy.DIRECTION_NEXT) {
            root
        } else {
            traversal.previous(root)
        }
    }

    override fun release() {
        // TatansTraversal 无显式释放（天坦的 x0() 也是空实现）
    }
}

/**
 * 列表遍历策略 —— 只遍历列表项，跳过中间容器
 *
 * 对应天坦的 LIST_TRAVERSAL 导航（typeCode 16）
 *
 * 行为差异：默认策略会走"父容器 → 子项 → 下一个容器"，
 * 列表策略直接"当前项 → 下一个项"。
 */
class ListTraversalStrategy(
    private val root: AccessibilityNodeInfo?,
    private val filter: (AccessibilityNodeInfo) -> Boolean
) : TraversalStrategy {

    private val traversal = TatansTraversal(root).apply {
        setFilter(filter)
    }

    override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        val forward = direction == TraversalStrategy.DIRECTION_NEXT

        // 向上找列表容器；找不到就退回默认遍历
        val container = findScrollableContainer(node)
            ?: return if (forward) traversal.next(node) else traversal.previous(node)

        // 在列表容器内按项移动：定位当前项，取它的下一个/上一个兄弟项
        val items = getFocusableChildren(container)
        if (items.size >= 2) {
            val idx = items.indexOfFirst { it == node }
            if (idx >= 0) {
                val target = if (forward) idx + 1 else idx - 1
                items.getOrNull(target)?.let { return it }
            }
        }

        // 容器内到边界 → 交给默认遍历跨容器移动
        return if (forward) traversal.next(node) else traversal.previous(node)
    }

    override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        val container = findScrollableContainer(root) ?: root
        val children = getFocusableChildren(container)
        return if (direction == TraversalStrategy.DIRECTION_NEXT) {
            children.firstOrNull()
        } else {
            children.lastOrNull()
        }
    }

    override fun release() = Unit

    /** 向上找可滚动容器 */
    private fun findScrollableContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 15) {
            if (cur.isScrollable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    /** 取容器内全部可聚焦的直接后代 */
    private fun getFocusableChildren(container: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(16)
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            if (filter(child)) {
                out.add(child)
            } else {
                // 递归下钻一层
                for (j in 0 until child.childCount) {
                    val sub = child.getChild(j) ?: continue
                    if (filter(sub)) out.add(sub)
                }
            }
        }
        return out
    }
}

/**
 * 网格/行列遍历策略 —— 支持上下左右四个方向
 *
 * 对应天坦的 ROW_AND_COLUM_TRAVERSAL 导航（typeCode 31）
 *
 * 天坦用 `filter f39047g`（collectionInfo.rowCount > 1 && columnCount > 1）
 * 判定网格容器。
 */
class GridTraversalStrategy(
    private val root: AccessibilityNodeInfo?,
    private val filter: (AccessibilityNodeInfo) -> Boolean
) : TraversalStrategy {

    override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        val container = findGridContainer(node) ?: return null
        val items = getGridItems(container)
        if (items.isEmpty()) return null

        val idx = items.indexOfFirst { it == node }
        if (idx < 0) return items.firstOrNull()

        val columns = getColumnCount(container).coerceAtLeast(1)
        val targetIdx = when (direction) {
            TraversalStrategy.DIRECTION_NEXT -> idx + 1
            TraversalStrategy.DIRECTION_PREVIOUS -> idx - 1
            DIRECTION_DOWN -> idx + columns
            DIRECTION_UP -> idx - columns
            DIRECTION_RIGHT -> idx + 1
            DIRECTION_LEFT -> idx - 1
            else -> idx + 1
        }

        return items.getOrNull(targetIdx)
    }

    override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        val container = findGridContainer(root) ?: return null
        val items = getGridItems(container)
        return if (direction == TraversalStrategy.DIRECTION_NEXT) items.firstOrNull() else items.lastOrNull()
    }

    override fun release() = Unit

    private fun findGridContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 15) {
            val info = try {
                cur.collectionInfo
            } catch (e: Throwable) {
                null
            }
            if (info != null && info.rowCount > 1 && info.columnCount > 1) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun getColumnCount(container: AccessibilityNodeInfo): Int {
        return try {
            container.collectionInfo?.columnCount ?: 1
        } catch (e: Throwable) {
            1
        }
    }

    private fun getGridItems(container: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(16)
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            if (filter(child)) {
                out.add(child)
            } else {
                for (j in 0 until child.childCount) {
                    val sub = child.getChild(j) ?: continue
                    if (filter(sub)) out.add(sub)
                }
            }
        }
        return out
    }

    companion object {
        const val DIRECTION_UP = 3
        const val DIRECTION_DOWN = 4
        const val DIRECTION_LEFT = 5
        const val DIRECTION_RIGHT = 6
    }
}

/**
 * 遍历策略工厂 —— 对应天坦 d7/C1427v.h()
 *
 *   h(direction, strategyType, node, cache, anchor):
 *       if (strategyType != 2) {
 *           if (strategyType != 8) {
 *               return anchor == null ? new C1424s() : new C1409d(anchor)
 *           }
 *           return new C1412g()
 *       }
 *       ...
 *
 * strategyType 取值（对应天坦的导航类型码）：
 *   0/1  → 默认
 *   2    → 带缓存
 *   8    → 网格
 *   16   → 列表
 *   31   → 行列
 */
object TraversalStrategyFactory {

    const val TYPE_DEFAULT = 0
    const val TYPE_CACHED = 2
    const val TYPE_GRID = 8
    const val TYPE_LIST = 16
    const val TYPE_ROW_COLUMN = 31

    fun create(
        strategyType: Int,
        root: AccessibilityNodeInfo?,
        filter: (AccessibilityNodeInfo) -> Boolean
    ): TraversalStrategy {
        return when (strategyType) {
            TYPE_GRID, TYPE_ROW_COLUMN -> GridTraversalStrategy(root, filter)
            TYPE_LIST -> ListTraversalStrategy(root, filter)
            // TYPE_CACHED 暂用默认实现（缓存优化在功能层按需加）
            else -> DefaultTraversalStrategy(root, filter)
        }
    }
}
