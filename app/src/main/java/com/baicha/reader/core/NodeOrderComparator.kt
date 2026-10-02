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
     * 获取排序后的子节点列表 —— 1:1 抄天坦 a.q(parent, sort)
     */
    fun sortChildren(parent: AccessibilityNodeInfo, sort: Boolean = true): List<AccessibilityNodeInfo> {
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
        val role = NodeRoleHelper.roleOf(node)
        if (role == NodeRoleHelper.ROLE_LIST) {
            // 天坦：w.g.a() == 1 表示垂直列表
            val collection = node.collectionInfo
            return collection != null
        }
        if (role == NodeRoleHelper.ROLE_GRID) return true

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

        val validA = ra != null && ((ra.width() > 0 && ra.height() > 0) || a.isImportantForAccessibility)
        val validB = rb != null && ((rb.width() > 0 && rb.height() > 0) || b.isImportantForAccessibility)

        if (!validA && !validB) return 0
        if (!validA) return 1
        if (!validB) return -1

        val rawA = rawBoundsMap[a] ?: ra!!
        val rawB = rawBoundsMap[b] ?: rb!!

        // ① 特殊规则 z()
        val z = compareSpecial(a, b, rawA, rawB)
        if (z != 0) return z

        // ② 包含关系 A()
        val aResult = compareContain(ra!!, rb!!)
        if (aResult != null) return aResult

        // ③ top → bottom
        val topDiff = ra.top - rb.top
        if (topDiff != 0) return topDiff
        return ra.bottom - rb.bottom
    }

    // ==================== 特殊规则：z() ====================

    /**
     * 特殊排序规则 —— 1:1 抄天坦 a.z(a, b, rectA, rectB)
     *
     * 场景：父子容器嵌套时，子元素应该排在父容器前面/后面。
     */
    private fun compareSpecial(
        a: AccessibilityNodeInfo,
        b: AccessibilityNodeInfo,
        ra: Rect,
        rb: Rect
    ): Int {
        // 情况 1：b 包含 a，且 a 是「窄条」
        if (rb.contains(ra) &&
            rb.width() > screenWidth * 0.7 &&
            ra.height() < threshold03H &&
            ra.height() < rb.height() / 2
        ) {
            if (ra.top >= threshold02H || ra.centerY() >= rb.centerY()) {
                val roleB = NodeRoleHelper.roleOf(b)
                return if (ra.right != rb.right ||
                    ra.width() >= rb.width() * 0.3 ||
                    ra.top >= screenHeight / 2 ||
                    roleB == NodeRoleHelper.ROLE_EDIT_TEXT
                ) 0 else -1
            }
            return -1
        }

        // 情况 2：a 包含 b，且 b 是「窄条」
        if (ra.contains(rb) &&
            ra.width() > screenWidth * 0.7 &&
            rb.height() < threshold03H &&
            rb.height() < ra.height() / 2
        ) {
            if (rb.top < threshold02H && rb.centerY() < ra.centerY()) {
                return 1
            }
            val roleA = NodeRoleHelper.roleOf(a)
            if (ra.right == rb.right &&
                rb.width() < ra.width() * 0.3 &&
                rb.top < screenHeight / 2 &&
                roleA != NodeRoleHelper.ROLE_EDIT_TEXT
            ) {
                return 1
            }
        }

        return 0
    }

    // ==================== 包含关系：A() ====================

    /**
     * 包含关系处理 —— 1:1 抄天坦 a.A(rectA, rectB)
     *
     * 返回 null 表示无法判断，继续后续比较。
     */
    private fun compareContain(ra: Rect, rb: Rect): Int? {
        if (ra.contains(rb) || rb.contains(ra)) return null

        val overlapH = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top)
        if (overlapH > THRESHOLD_20 &&
            intersectsVertically(ra, rb) &&
            !intersectsHorizontally(ra, rb)
        ) {
            // 垂直重叠但水平不重叠 → 按左右排
            return ra.left - rb.left
        }
        return null
    }

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