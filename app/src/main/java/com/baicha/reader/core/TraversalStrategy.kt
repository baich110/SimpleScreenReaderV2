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
 * 默认遍历策略 —— **完全按 TalkBack 实现**
 *
 * ══════════════════════════════════════════════════════════════════
 * 【★★★ 2026-10-03 重写：从天坦模型换成 TalkBack 模型】
 * ══════════════════════════════════════════════════════════════════
 *
 * 【用户原话】
 * > 完全按照 TalkBack 重写遍历器
 *
 * 【旧实现（天坦 C1424s）的致命问题】
 *   底层是 `TatansTraversal`，每次 `next()` 都重新上溯，
 *   **依赖 `AccessibilityNodeInfo.getParent()`**。
 *   Compose 界面上 `getParent()` 返回 null ⇒ 上溯链断 ⇒ `访问 0`
 *   ⇒ 用户感知「完全拿不到焦点」。
 *
 * 【新实现（TalkBack OrderedTraversalStrategy）】
 *   底层是 [TalkBackTraversal]，**一次性建树**，
 *   遍历时纯链表操作，**完全不碰 `getParent()`**。
 *
 * 【与 TalkBack 的对应】
 *   · `OrderedTraversalStrategy` → 本类
 *   · `OrderedTraversalController` → [TalkBackTraversal]
 *   · `WorkingTree` → [TalkBackTraversal.WorkingTree]
 *
 * 【start() 的语义修正】
 *   TalkBack 的 `focusFirst(root, direction)`：
 *   ```java
 *   if (direction == SEARCH_FOCUS_FORWARD) {
 *     return controller.findFirst(root);     // 返回 root 对应的 WorkingTree 节点
 *   } else if (direction == SEARCH_FOCUS_BACKWARD) {
 *     return controller.findLast(root);      // 返回 root 的最后一个后代
 *   }
 *   ```
 *   **FORWARD 返回 root 本身**，BACKWARD 返回 root 的最后一个后代。
 *   这与旧实现（`root` / `traversal.previous(root)`）语义一致，
 *   但新实现不依赖 `previous()` 的上溯。
 */
class DefaultTraversalStrategy(
    private val root: AccessibilityNodeInfo?,
    private val filter: (AccessibilityNodeInfo) -> Boolean
) : TraversalStrategy {

    /** TalkBack 遍历引擎（懒建树） */
    private val traversal = TalkBackTraversal(root)

    override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        return if (direction == TraversalStrategy.DIRECTION_NEXT) {
            traversal.next(node)
        } else {
            traversal.previous(node)
        }
    }

    /**
     * 定位起始 —— 对应 TalkBack `focusFirst(root, direction)`
     *
     * ```java
     * public @Nullable AccessibilityNodeInfoCompat focusFirst(
     *     AccessibilityNodeInfoCompat root, @SearchDirection int direction) {
     *   if (direction == SEARCH_FOCUS_FORWARD) {
     *     return controller.findFirst(root);      // = tree.getNode()  ← root 自己
     *   } else if (direction == SEARCH_FOCUS_BACKWARD) {
     *     return controller.findLast(root);       // = tree.getLastNode().getNode()
     *   }
     *   return null;
     * }
     * ```
     */
    override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        return if (direction == TraversalStrategy.DIRECTION_NEXT) {
            traversal.first() ?: root
        } else {
            traversal.last() ?: root
        }
    }

    override fun release() {
        traversal.release()
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
 * 带缓存的遍历策略 —— 对应天坦 d7/C1406a
 *
 * 【天坦 C1406a 的设计】
 *   构造时传入 root + 缓存对象 N，遍历时复用缓存的索引，
 *   避免在大列表上重复遍历。
 *
 * 【本实现的差异（更高性能）】
 *   天坦的缓存是「索引缓存」，仍然要逐节点比较。
 *   本实现改为「**整条可聚焦节点序列的缓存**」：
 *   首次遍历时把整棵树的访问顺序展开成一个数组，
 *   后续 next/previous 变成 O(1) 数组索引查找。
 *
 *   代价：需要一次性遍历全树（首次慢）。
 *   收益：之后每次移动都是 O(1)，大列表滚动时优势明显。
 *
 *   适合场景：节点数 > 50 的界面（列表、设置页）。
 *   不适合：节点数很少的简单界面（遍历开销 > 收益）。
 */
class CachedTraversalStrategy(
    private val root: AccessibilityNodeInfo?,
    private val filter: (AccessibilityNodeInfo) -> Boolean
) : TraversalStrategy {

    companion object {
        /** 低于此节点数时不用缓存策略（遍历开销不划算） */
        const val MIN_NODES_FOR_CACHE = 50

        /** 展开上限，防止超大页面爆内存 */
        private const val MAX_FLATTEN = 2000
    }

    /** 展开后的线性序列 */
    private var flattened: ArrayList<AccessibilityNodeInfo>? = null

    /** 每个节点在序列中的索引（identity 语义） */
    private val indexMap = java.util.IdentityHashMap<AccessibilityNodeInfo, Int>()

    /** 已加入序列的节点（防重复/防环） */
    private val added = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()

    /** 是否已构建 */
    private var built = false

    private val fallback = DefaultTraversalStrategy(root, filter)

    private fun ensureBuilt() {
        if (built) return
        built = true
        val list = ArrayList<AccessibilityNodeInfo>(128)
        val top = root ?: return
        flatten(top, list, 0)
        flattened = list
        for (i in list.indices) {
            indexMap[list[i]] = i
        }
    }

    /** 深度优先展开，只收集可聚焦节点 */
    private fun flatten(node: AccessibilityNodeInfo, out: ArrayList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 40 || out.size >= MAX_FLATTEN) return
        // 【修正】原实现没去重：树中出现重复引用或环时同一节点会被加入两次，
        // indexMap 后写覆盖前写 → 索引错位 → 焦点跳错位置。
        if (added.containsKey(node)) return
        added[node] = true
        if (filter(node)) {
            out.add(node)
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            flatten(child, out, depth + 1)
            if (out.size >= MAX_FLATTEN) return
        }
    }

    override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        ensureBuilt()
        val list = flattened
        if (list == null || list.size < MIN_NODES_FOR_CACHE) {
            // 节点少 → 退回默认策略
            return fallback.next(node, direction)
        }
        val idx = indexMap[node] ?: return fallback.next(node, direction)
        val target = if (direction == TraversalStrategy.DIRECTION_NEXT) idx + 1 else idx - 1
        return list.getOrNull(target)
    }

    override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        ensureBuilt()
        val list = flattened ?: return null
        return if (direction == TraversalStrategy.DIRECTION_NEXT) {
            list.firstOrNull()
        } else {
            list.lastOrNull()
        }
    }

    override fun release() {
        flattened = null
        indexMap.clear()
        added.clear()
        built = false
        fallback.release()
    }

    /** 当前缓存的节点数（诊断用） */
    fun cachedSize(): Int {
        ensureBuilt()
        return flattened?.size ?: 0
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

    /** 空间查找（按几何方向找最近节点）—— 对应 TalkBack DirectionalTraversalStrategy */
    const val TYPE_SPATIAL = 100

    /** 垂直遍历 —— 只沿垂直方向移动（跳过同行的其他节点）*/
    const val TYPE_VERTICAL = 17

    /**
     * 创建遍历策略
     *
     * ⚠️ **必须用具名参数调用**，或按 (strategyType, root, filter) 顺序传全三个参数。
     *
     * 【为什么强调这点】
     *   本方法的第一个参数是 `strategyType: Int`，不是 `root`。
     *   如果写成 `create(root) { ... }`（漏掉 strategyType），
     *   编译器报的是「root 位置收到 Int」这类**难以定位**的错误。
     *
     *   更危险的情况：如果将来把 filter 改成有默认值，
     *   漏参数**不会报错**，而是静默用错配置。
     *
     *   推荐写法：
     *     TraversalStrategyFactory.create(
     *         strategyType = TraversalStrategyFactory.TYPE_DEFAULT,
     *         root = root,
     *         filter = { ShouldFocusNode.shouldFocus(it) }
     *     )
     */
    fun createDefault(
        root: AccessibilityNodeInfo?,
        filter: (AccessibilityNodeInfo) -> Boolean
    ): TraversalStrategy = create(TYPE_DEFAULT, root, filter)

    fun create(
        strategyType: Int,
        root: AccessibilityNodeInfo?,
        filter: (AccessibilityNodeInfo) -> Boolean
    ): TraversalStrategy {
        return when (strategyType) {
            TYPE_GRID, TYPE_ROW_COLUMN -> GridTraversalStrategy(root, filter)
            TYPE_LIST -> ListTraversalStrategy(root, filter)
            TYPE_CACHED -> CachedTraversalStrategy(root, filter)
            TYPE_SPATIAL -> SpatialTraversalAdapter(root, filter)
            TYPE_VERTICAL -> VerticalTraversalStrategy(root, filter)
            else -> DefaultTraversalStrategy(root, filter)
        }
    }
}


/**
 * 空间遍历策略适配器
 *
 * 让 [SpatialTraversalStrategy] 也能通过 [TraversalStrategy] 接口使用，
 * 这样调用方可以统一走「策略 + FocusSearch」的流程。
 */
class SpatialTraversalAdapter(
    private val root: AccessibilityNodeInfo?,
    private val filter: (AccessibilityNodeInfo) -> Boolean
) : TraversalStrategy {

    private val spatial = SpatialTraversalStrategy(root, filter)

    /** 记住上次的方向，供 next() 使用（接口只传节点不传方向语义） */
    private var lastDirection = TraversalDirection.FORWARD

    override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        lastDirection = direction
        // 空间方向 → 几何查找
        if (TraversalDirection.isSpatial(direction)) {
            return spatial.findFocusInDirection(node, direction)
        }
        // 逻辑方向 → 退回默认遍历（空间策略不处理逻辑顺序）
        return null
    }

    override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? =
        spatial.start(root, direction)

    override fun release() = spatial.release()

    /** 可聚焦节点数（诊断） */
    fun nodeCount(): Int = spatial.nodeCount()
}


/**
 * 垂直遍历策略 —— 只沿垂直方向移动
 *
 * 【与默认遍历的区别】
 *   默认遍历按「上→下，左→右」的几何顺序走全部节点。
 *   垂直遍历**忽略水平位置**，只按垂直方向推进 ——
 *   适合「一行有多个元素，但用户只想逐行浏览」的场景。
 *
 * 对应天坦导航码 17（VERTICAL_TRAVERSAL），
 * TalkBack 用 `SEARCH_FOCUS_UP/DOWN` 的空间查找实现类似效果。
 *
 * 【实现方式】
 *   用空间方向（UP/DOWN）做几何查找，而不是走树序。
 *   复用 [SpatialTraversalStrategy] 的算法。
 */
class VerticalTraversalStrategy(
    private val root: AccessibilityNodeInfo?,
    private val filter: (AccessibilityNodeInfo) -> Boolean
) : TraversalStrategy {

    private val spatial = SpatialTraversalStrategy(root, filter)

    override fun next(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        // 逻辑方向映射为空间方向：前进=下，后退=上
        val spatialDir = if (direction == TraversalStrategy.DIRECTION_NEXT) {
            TraversalDirection.DOWN
        } else {
            TraversalDirection.UP
        }
        val found = spatial.findFocusInDirection(node, spatialDir)
        if (found != null) return found

        // 空间查找失败（可能同行无可比节点）→ 退回默认遍历
        return DefaultTraversalStrategy(root, filter).next(node, direction)
    }

    override fun start(root: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        // 起点：垂直方向的第一个/最后一个
        return spatial.start(root, direction)
    }

    override fun release() = spatial.release()

    /** 可聚焦节点数（诊断）*/
    fun nodeCount(): Int = spatial.nodeCount()
}
