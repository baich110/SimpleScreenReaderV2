package com.baicha.reader.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.IdentityHashMap

/**
 * 节点排序器 —— 1:1 抄天坦 d7/C1423r.java（1307 行）
 *
 * 天坦的排序比 TalkBack 复杂得多，专门针对中文 App 布局优化。
 * 完整照抄，不做减法。
 *
 * 【核心流程】
 *   sortChildren(parent, children):
 *     ① 如果子节点 > 1 且需要排序：
 *        - 非 RecyclerView：
 *            info = analyzeLayout(children)       // l()
 *            if (info.count >= 3 && !vivoLauncher) {
 *                sortRange(children, info)        // L()
 *            } else {
 *                children.sort(comparator(info))  // m() + n()
 *            }
 *        - RecyclerView：sortRecycler(parent, children)   // J()
 *
 * 【比较器 n()】
 *   ① 有效性检查（宽高 > 0 或 isImportantForAccessibility）
 *   ② z() 特殊规则（父子包含关系调整）
 *   ③ A() 包含关系处理
 *   ④ o() top 比较 → p() bottom 比较
 */
object NodeOrderComparator {

    /** 屏幕宽高（天坦 M() 里初始化） */
    @Volatile
    var screenWidth = 1080

    @Volatile
    var screenHeight = 1920

    /** 天坦 f23904f = 20 */
    private const val THRESHOLD_20 = 20

    /** 天坦 f23905g = 10dp（像素值，M() 里算） */
    @Volatile
    var threshold10dp = 10

    /** 天坦 f23909k = 屏幕高 * 0.2 */
    private val threshold02H: Double get() = screenHeight * 0.2

    /** 天坦 f23910l = 屏幕高 * 0.3 */
    private val threshold03H: Double get() = screenHeight * 0.3

    /** 不需要排序的包（天坦 f23903e 是类名，这里是包名特判） */
    private val SKIP_PACKAGES = setOf(
        "com.bbk.launcher2",
        "com.android.systemui"
    )

    /**
     * 初始化屏幕尺寸 —— 对应天坦 a.M(Context)
     */
    fun initScreen(width: Int, height: Int, dp10: Int) {
        screenWidth = width
        screenHeight = height
        threshold10dp = dp10
        // ★ 同步到纯几何引擎（NodeBoundsComparator 用它算阈值）
        NodeBoundsComparator.screenWidth = width
        NodeBoundsComparator.screenHeight = height
    }

    /**
     * 从 Context 初始化 —— 对应天坦 `C1423r.a.M(Context)`
     *
     * 【为什么必须调用】
     *   排序算法用屏幕尺寸算三个阈值：
     *     threshold02H = 屏高 * 0.2
     *     threshold03H = 屏高 * 0.3
     *     threshold10dp = 10dp 对应的像素数
     *   不初始化就会用硬编码的 1080x1920 —— 在其他分辨率设备上
     *   排序判定会系统性偏差。
     *
     * 天坦的调用时机（源码 4 处）：
     *   ① 横竖屏切换（onConfigurationChanged）
     *   ② 服务重启 / 恢复
     *   ③ 服务连接
     *   ④ 窗口变化后的重建
     */
    fun initFromContext(context: android.content.Context) {
        try {
            val metrics = context.resources.displayMetrics
            val density = metrics.density
            val dp10 = (10 * density).toInt()
            initScreen(metrics.widthPixels, metrics.heightPixels, dp10)
            ReaderLog.d("NodeOrder", "屏幕参数: ${metrics.widthPixels}x${metrics.heightPixels} dp10=$dp10")
        } catch (e: Throwable) {
            ReaderLog.w("NodeOrder", "屏幕参数初始化失败: ${e.message}")
        }
    }

    // ==================== 入口：q() ====================

    /**
     * 是否需要排序 —— 1:1 抄天坦 a.I(node)
     *
     * 天坦原版：
     *   I(node):
     *       if (node.pkg == "com.bbk.launcher2") return true;
     *       if (!GlobalConfig.g0() || node.childCount >= 60) return false;
     *       if (node.pkg == "com.android.launcher" && roleOf(node) == 8) return false;
     *       parent = node.parent;
     *       return roleOf(parent) != 8 || roleOf(node) == 8;
     *
     * 【修正】GlobalConfig.g0() 是"排序开关"，默认 true（开启）。
     * 原来写成 `if (childCount >= 60) return false` 漏了开关判断，
     * 且注释说"子节点 >= 60 不排序"是对的，但整体条件顺序需要对齐。
     */
    fun needsSort(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString().orEmpty()

        // ① vivo 桌面强制排序
        if (pkg == "com.bbk.launcher2") return true

        // ② 排序总开关关闭 或 子节点过多（>=60）→ 不排序
        if (!sortEnabled || node.childCount >= 60) return false

        // ③ 原生桌面里的列表节点不排序
        if (pkg == "com.android.launcher" && NodeRoleHelper.roleOf(node) == NodeRoleHelper.ROLE_LIST) {
            return false
        }

        // ④ 父节点是列表 → 只有当自己也是列表时才排序
        val parent = node.parent ?: return true
        val parentRole = NodeRoleHelper.roleOf(parent)
        val nodeRole = NodeRoleHelper.roleOf(node)
        return parentRole != NodeRoleHelper.ROLE_LIST || nodeRole == NodeRoleHelper.ROLE_LIST
    }

    /** 排序总开关 —— 天坦 GlobalConfig.g0()，默认开 */
    @Volatile
    var sortEnabled = true

    /**
     * 外部注入的缓存 —— 由服务在启动时设置
     *
     * 这是**本项目独有的性能优化**：天坦每次遍历都重新排序子节点，
     * 同一次滑动浏览里同一棵树被反复排序。加上缓存后，
     * 同一父节点在一次窗口生命周期内只排一次。
     */
    @Volatile
    var cache: NodeCache? = null

    /**
     * 获取排序后的子节点列表 —— 1:1 抄天坦 a.q(parent, sort)
     *
     * 【性能】带缓存：同一父节点直接命中排序结果
     */
    fun sortChildren(parent: AccessibilityNodeInfo, sort: Boolean = true): List<AccessibilityNodeInfo> {
        // 走缓存（只缓存需要排序的路径，不排序的直接构造）
        val c = cache
        if (c != null && sort) {
            return c.getSortedChildren(parent) { sortChildrenUncached(parent, true) }
        }
        return sortChildrenUncached(parent, sort)
    }

    /** 无缓存的排序实现 */
    private fun sortChildrenUncached(parent: AccessibilityNodeInfo, sort: Boolean): List<AccessibilityNodeInfo> {
        val count = parent.childCount
        if (count <= 0) return emptyList()

        val list = ArrayList<AccessibilityNodeInfo>(count)
        for (i in 0 until count) {
            val child = parent.getChild(i) ?: continue
            list.add(child)
        }

        // 天坦：iP > 1 && z9 才排序
        if (count <= 1 || !sort) return list

        val pkg = parent.packageName?.toString().orEmpty()

        if (!isRecyclerView(parent)) {
            // 非 RecyclerView：分析布局
            val info = analyzeLayout(list)
            if (info.count >= 3 && pkg != "com.bbk.launcher2") {
                sortRange(list, info)
                return list
            }
            try {
                list.sortWith(buildComparator(info.boundsMap, info.rawBoundsMap))
            } catch (e: IllegalArgumentException) {
                // ignore
            }
        } else if (pkg != "com.android.systemui") {
            // RecyclerView：专用排序
            sortRecycler(parent, list)
            return list
        }

        return list
    }

    // ==================== 判断：x() ====================

    /**
     * 是否 RecyclerView —— 1:1 抄天坦 a.x(node)
     */
    private fun isRecyclerView(node: AccessibilityNodeInfo): Boolean {
        // ★★ 本轮修正（可读版对照，报告 B-4）—— 角色码 5/8 原本**完全对调**
        //
        // 【天坦原文】`d7/C1421r.java:566~581` 的 `x(nodeInfo)`：
        // ```java
        // public final boolean x(NodeInfo nodeInfo) {
        //     int nodeType = EventUtils.getNodeType(nodeInfo);
        //     if (nodeType == 5) {                          // 5 = GRID
        //         NodeInfo.g gVarS = nodeInfo.s();
        //         return gVarS != null && gVarS.a() == 1;   // columnCount == 1
        //     }
        //     if (nodeType == 8) {                          // 8 = LIST
        //         return true;                              // 无条件
        //     }
        //     CharSequence className = nodeInfo.getClassName();
        //     if (className == null) return false;
        //     return E.U(className.toString(), "RecyclerView", false, 2, null);
        // }
        // ```
        // 【我原来的实现】把两个分支的**行为整个对调**了，且漏了 columnCount == 1：
        //   · ROLE_GRID → 无条件 true（天坦是 columnCount==1 才 true）
        //   · ROLE_LIST → collectionInfo != null（天坦是无条件 true）
        //
        // 【连锁影响】这是排序总闸 —— `needsSort` → 污染
        //   findDown / findNextSibling / findLastDescendant / sortChildren
        //   四处遍历分支的选择，进而影响整个焦点遍历顺序。
        //   真机表现：长列表滚动时焦点乱跳、GridView 完全不排序。
        val role = NodeRoleHelper.roleOf(node)
        if (role == NodeRoleHelper.ROLE_GRID) {
            // 天坦 `gVarS.a()` = columnCount
            val collection = node.collectionInfo ?: return false
            return collection.columnCount == 1
        }
        if (role == NodeRoleHelper.ROLE_LIST) {
            return true   // 无条件 —— 列表一定走 RecyclerView 专用排序
        }

        val cls = node.className?.toString() ?: return false
        return cls.contains("RecyclerView")
    }

    // ==================== 布局分析：l() ====================

    /** 布局分析结果 —— 对应天坦 C0330a */
    data class LayoutInfo(
        /** 节点 → 修正后边界 */
        val boundsMap: IdentityHashMap<AccessibilityNodeInfo, Rect>,
        /** 节点 → 原始边界（仅 grid 类节点） */
        val rawBoundsMap: IdentityHashMap<AccessibilityNodeInfo, Rect>,
        /** 起始索引 */
        val start: Int,
        /** 结束索引 */
        val end: Int,
        /** 连续块计数 */
        val count: Int
    )

    /**
     * 分析子节点布局 —— 1:1 抄天坦 a.l(List)
     *
     * 天坦变量对照（i13/i14/i15/i16/i17/i18/size/i21/i22）：
     *   i13 = 0            循环内的常量 0
     *   i14 = -1           firstSmallIdx（当前连续小块区间起点）
     *   i15 = 0            smallCount（当前连续小块计数）
     *   i16 = 0            遍历索引
     *   i17 = 0            maxBlockCount（历史最大连续块计数）
     *   i18 = -1           maxBlockStart（历史最大块起点）
     *   size = -1          maxBlockEnd（历史最大块终点）
     *   rect               lastRect（上一个块的边界）
     *
     * 判定「小块」：!(宽 >= 屏宽*0.5 || 高 >= 屏高*0.3 || !importantForAccessibility
     *                 || (!rect.isEmpty && (!v(rect,rect2) || t(rect,rect2))))
     *
     * 收尾：
     *   if (i15 < 3 || i15 <= i17) { count=i17; start=i22; end=size }
     *   else                       { count=i15; start=i21; end=list.size-1 }
     */
    private fun analyzeLayout(list: List<AccessibilityNodeInfo>): LayoutInfo {
        val boundsMap = IdentityHashMap<AccessibilityNodeInfo, Rect>()
        val rawBoundsMap = IdentityHashMap<AccessibilityNodeInfo, Rect>()
        val lastRect = Rect()

        var i13 = 0
        var firstSmallIdx = -1      // i14
        var smallCount = 0          // i15
        var idx = 0                 // i16
        var maxBlockCount = 0       // i17
        var maxBlockStart = -1      // i18
        var maxBlockEnd = -1        // size

        for (node in list) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            if (node.childCount == 1) {
                val child = node.getChild(0)
                if (child == null || !child.isImportantForAccessibility) {
                    boundsMap[node] = rect
                } else {
                    val childRect = Rect()
                    child.getBoundsInScreen(childRect)
                    val tooBig = rect.width().toDouble() * rect.height() >
                            screenWidth.toDouble() * screenHeight * 0.6
                    if (tooBig || !intersectsVertically(rect, childRect) ||
                        !intersectsHorizontally(rect, childRect)
                    ) {
                        rect.set(childRect)
                    }
                    boundsMap[node] = rect
                }
            } else {
                if (NodeRoleHelper.roleOf(node) == NodeRoleHelper.ROLE_GRID) {
                    rawBoundsMap[node] = Rect(rect)
                    for (i in 0 until node.childCount) {
                        val c = node.getChild(i) ?: continue
                        if (c.isImportantForAccessibility) {
                            c.getBoundsInScreen(rect)
                            break
                        }
                    }
                    boundsMap[node] = rect
                } else {
                    boundsMap[node] = rect
                }
            }

            // ★ 天坦是一整个取反条件（大块），不是拆开的 isSmall
            val isBig = rect.width() >= screenWidth * 0.5 ||
                    rect.height() >= threshold03H ||
                    !node.isImportantForAccessibility ||
                    (!lastRect.isEmpty &&
                            (!intersectsHorizontally(lastRect, rect) || overlapsVertically(lastRect, rect)))

            if (isBig) {
                // 大块：结算当前连续区间
                if (smallCount >= 3 && smallCount > maxBlockCount) {
                    maxBlockCount = smallCount
                    maxBlockEnd = idx - 1
                    maxBlockStart = firstSmallIdx
                }
                lastRect.setEmpty()
                idx++
                firstSmallIdx = -1
                i13 = 0
                smallCount = 0
            } else {
                smallCount++
                if (smallCount == 1) firstSmallIdx = idx
                lastRect.set(rect)
                idx++
                i13 = 0
            }
        }

        // ★ 收尾：天坦最后一次结算不在循环里
        val start: Int
        val end: Int
        val count: Int
        if (smallCount >= 3 && smallCount > maxBlockCount) {
            end = list.size - 1
            count = smallCount
            start = firstSmallIdx
        } else {
            count = maxBlockCount
            start = maxBlockStart
            end = maxBlockEnd
        }

        return LayoutInfo(boundsMap, rawBoundsMap, start, end, count)
    }

    // ==================== 排序执行：L() ====================

    /**
     * 排序指定区间 —— 1:1 抄天坦 a.L(list, info)
     */
    private fun sortRange(list: MutableList<AccessibilityNodeInfo>, info: LayoutInfo) {
        if (info.start < 0 || info.end >= list.size || info.start > info.end) return
        try {
            val sub = list.subList(info.start, info.end + 1)
            val sorted = sub.sortedWith(buildComparator(info.boundsMap, info.rawBoundsMap))
            for (i in sorted.indices) {
                sub[i] = sorted[i]
            }
        } catch (e: IllegalArgumentException) {
            // ignore
        }
    }

    // ==================== 比较器：m() ====================

    /**
     * 构建比较器 —— 1:1 抄天坦 a.m(boundsMap, rawBoundsMap)
     */
    private fun buildComparator(
        boundsMap: IdentityHashMap<AccessibilityNodeInfo, Rect>,
        rawBoundsMap: IdentityHashMap<AccessibilityNodeInfo, Rect>
    ): Comparator<AccessibilityNodeInfo> {
        return Comparator { a, b -> compareNodes(boundsMap, rawBoundsMap, a, b) }
    }

    // ==================== 核心比较：n() ====================

    /**
     * 完整比较 —— 1:1 抄天坦 a.n(boundsMap, rawBoundsMap, a, b)
     */
    private fun compareNodes(
        boundsMap: IdentityHashMap<AccessibilityNodeInfo, Rect>,
        rawBoundsMap: IdentityHashMap<AccessibilityNodeInfo, Rect>,
        a: AccessibilityNodeInfo,
        b: AccessibilityNodeInfo
    ): Int {
        val ra = boundsMap[a]
        val rb = boundsMap[b]

        val validA = NodeBoundsComparator.isValid(
            ra?.let { Bounds(it) }, a.isImportantForAccessibility
        )
        val validB = NodeBoundsComparator.isValid(
            rb?.let { Bounds(it) }, b.isImportantForAccessibility
        )

        // 边界缺失 → 交给纯引擎按「无效」处理
        val bA = ra?.let { Bounds(it) } ?: Bounds.EMPTY
        val bB = rb?.let { Bounds(it) } ?: Bounds.EMPTY
        val rawA = rawBoundsMap[a]?.let { Bounds(it) } ?: bA
        val rawB = rawBoundsMap[b]?.let { Bounds(it) } ?: bB

        // ★ 委托给纯几何引擎（可单测）
        return NodeBoundsComparator.compare(
            boundsA = bA,
            boundsB = bB,
            rawA = rawA,
            rawB = rawB,
            roleA = NodeRoleHelper.roleOf(a),
            roleB = NodeRoleHelper.roleOf(b),
            validA = validA,
            validB = validB
        )
    }

    // ==================== 特殊规则：z() ====================

    // ==================== 特殊规则 / 包含关系 ====================
    //
    // 【重构说明】原先这里有两个方法：
    //   compareSpecial(a, b, ra, rb)   → 天坦 a.z()
    //   compareContain(ra, rb)         → 天坦 a.A()
    //
    // 它们已迁到 `NodeBoundsComparator`（纯几何引擎，不依赖 android.graphics）。
    // 原因：这两个算法决定「焦点顺序」，是核心逻辑，但原先因
    // `android.graphics.Rect` 在 JVM 单测中是空壳而**完全无法测试**。
    //
    // 迁出后：
    //   ① 算法可穷举测试（见 NodeBoundsComparatorTest）
    //   ② 本类只剩「数据采集 + 调用引擎」的职责
    //   ③ 避免了「两套几何逻辑并存」导致的行为漂移

    // ==================== RecyclerView 排序：J() ====================

    /**
     * RecyclerView 专用排序 —— 1:1 抄天坦 a.J(parent, list)
     *
     * 用「内容高度」修正节点边界，再按 top/left 排序。
     */
    private fun sortRecycler(parent: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (list.size < 2 || !isSingleColumn(list)) return

        val boundsMap = IdentityHashMap<AccessibilityNodeInfo, Rect>()
        val parentRect = Rect()
        parent.getBoundsInScreen(parentRect)

        for (node in list) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // 用「内容高度」修正边界
            // 天坦 J()：rect2 = new Rect(); wVar2.j(rect2) 是 contentBounds
            val contentRect = Rect()
            try {
                @Suppress("DEPRECATION")
                node.getBoundsInScreen(contentRect)
            } catch (e: Throwable) {
                contentRect.set(rect)
            }

            if (contentRect.height() != rect.height()) {
                val adjusted = Rect(rect)
                val delta = contentRect.height() - rect.height()
                if (Math.abs(parentRect.top - rect.top) < threshold10dp) {
                    adjusted.top -= delta
                } else {
                    adjusted.bottom += delta
                }
                boundsMap[node] = adjusted
            } else {
                boundsMap[node] = rect
            }
        }

        try {
            val sorted = list.sortedWith { a, b ->
                val ra = boundsMap[a]
                val rb = boundsMap[b]
                if (ra == null || rb == null) return@sortedWith 0
                val topDiff = ra.top - rb.top
                if (topDiff != 0) return@sortedWith topDiff
                ra.left - rb.left
            }
            for (i in sorted.indices) list[i] = sorted[i]
        } catch (e: IllegalArgumentException) {
            // ignore
        }
    }

    /**
     * 是否单列布局 —— 1:1 抄天坦 a.u(list)
     */
    private fun isSingleColumn(list: List<AccessibilityNodeInfo>): Boolean {
        val size = list.size
        if (size < 2) return false

        val tops = IntArray(size)
        var maxLeft = Int.MIN_VALUE
        var minRight = Int.MAX_VALUE

        for (i in 0 until size) {
            val r = Rect()
            list[i].getBoundsInScreen(r)
            tops[i] = r.top
            maxLeft = Math.max(maxLeft, r.left)
            minRight = Math.min(minRight, r.right)
        }

        tops.sort()

        var run = 1
        var groups = 0
        for (i in 1 until size) {
            if (tops[i] - tops[i - 1] <= THRESHOLD_20) {
                run++
            } else {
                if (run >= 2) groups++
                run = 1
            }
        }
        if (run >= 2) groups++

        return groups < 2 && maxLeft >= minRight
    }

    // ==================== 几何工具 ====================

    /** 垂直相交 —— 天坦 a.w() */
    private fun intersectsVertically(r1: Rect, r2: Rect): Boolean =
        r1.top < r2.bottom && r2.top < r1.bottom

    /** 水平相交 —— 天坦 a.v() */
    private fun intersectsHorizontally(r1: Rect, r2: Rect): Boolean =
        r1.left < r2.right && r2.left < r1.right

    /** 垂直重叠度 >= 60% —— 天坦 a.t() */
    private fun overlapsVertically(r1: Rect, r2: Rect): Boolean {
        if (!intersectsVertically(r1, r2)) return false
        val overlap = Math.min(r1.bottom, r2.bottom) - Math.max(r1.top, r2.top)
        val minH = Math.min(r1.height(), r2.height())
        return minH > 0 && overlap >= minH * 0.6
    }
}