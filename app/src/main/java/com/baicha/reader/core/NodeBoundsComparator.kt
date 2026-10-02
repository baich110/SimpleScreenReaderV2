package com.baicha.reader.core

/**
 * 节点几何比较引擎 —— **纯几何，零 Android 依赖**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么抽出来】
 * ══════════════════════════════════════════════════════════
 *
 * `NodeOrderComparator` 是天坦 `d7/C1423r.a` 的完整移植（598 行），
 * 包含大量几何比较逻辑。但这些逻辑**无法单测** —— 因为
 * `android.graphics.Rect` 在 JVM 测试中是空壳（构造器和 set 都是 no-op）。
 *
 * 本类把「比较算法」抽到纯几何层，[Bounds] 作为数据载体，
 * **节点角色以 Int 参数传入**（而不是在算法内部查 Android API）。
 *
 * 这样：
 *   ① 比较算法可以穷举测试（各种矩形组合）
 *   ② `NodeOrderComparator` 只剩「数据采集 + 调用本引擎」
 *
 * ══════════════════════════════════════════════════════════
 * 【天坦的原始算法位置】
 * ══════════════════════════════════════════════════════════
 *
 *   a.n(boundsMap, rawBoundsMap, a, b)   完整比较（→ compareNodes）
 *   a.z(a, b, rectA, rectB)              特殊规则（→ compareSpecial）
 *   a.A(rectA, rectB)                    包含关系（→ compareContain）
 *   a.v() / a.w() / a.t()                几何工具（Bounds 里已有）
 */
object NodeBoundsComparator {

    /** 天坦 f23904f = 20 */
    private const val THRESHOLD_20 = 20

    /**
     * 屏幕参数 —— 天坦用静态字段（`f23907i`/`f23908j`/`f23909k`/`f23910l`）
     *
     * 由 `NodeOrderComparator.initFromContext()` 注入。
     */
    @Volatile
    var screenWidth: Int = 1080

    @Volatile
    var screenHeight: Int = 1920

    /** 屏高 * 0.2 —— 天坦 f23909k */
    private val threshold02H: Double get() = screenHeight * 0.2

    /** 屏高 * 0.3 —— 天坦 f23910l */
    private val threshold03H: Double get() = screenHeight * 0.3

    // ==================== 完整比较（天坦 n()）====================

    /**
     * 比较两个节点 —— 1:1 抄天坦 `a.n()`
     *
     * @param boundsA 节点 A 的（修正后）边界
     * @param boundsB 节点 B 的（修正后）边界
     * @param rawA 节点 A 的原始边界（grid 类节点才有，否则同 boundsA）
     * @param rawB 节点 B 的原始边界
     * @param roleA 节点 A 的角色码（用于特殊规则）
     * @param roleB 节点 B 的角色码
     * @param validA A 的边界是否有效
     * @param validB B 的边界是否有效
     * @return 负数 = A 在前，正数 = B 在前，0 = 相等
     */
    fun compare(
        boundsA: Bounds,
        boundsB: Bounds,
        rawA: Bounds,
        rawB: Bounds,
        roleA: Int,
        roleB: Int,
        validA: Boolean,
        validB: Boolean
    ): Int {
        // ① 有效性检查 —— 天坦：
        //    if (!validA && !validB) return 0
        //    if (!validA) return 1
        //    if (!validB) return -1
        if (!validA && !validB) return 0
        if (!validA) return 1
        if (!validB) return -1

        // ② 特殊规则 z()
        val special = compareSpecial(boundsA, boundsB, rawA, rawB, roleA, roleB)
        if (special != 0) return special

        // ③ 包含关系 A()
        val contain = compareContain(boundsA, boundsB)
        if (contain != null) return contain

        // ④ top → bottom
        val topDiff = boundsA.top - boundsB.top
        if (topDiff != 0) return topDiff
        return boundsA.bottom - boundsB.bottom
    }

    /**
     * 特殊排序规则 —— 1:1 抄天坦 `a.z(a, b, rectA, rectB)`
     *
     * 场景：父子容器嵌套时，子元素应该排在父容器前面/后面。
     *
     * 【天坦的两个情况】
     *   情况 1：b 包含 a，且 a 是「窄条」
     *     - a 在下方（top >= 屏高*0.2 或 centerY >= b.centerY）→ 视条件返回 0 或 -1
     *     - 否则返回 -1（a 排在 b 前）
     *
     *   情况 2：a 包含 b，且 b 是「窄条」
     *     - b 在上方 → 返回 1（b 排在 a 前）
     *     - 右对齐且窄 → 返回 1
     */
    fun compareSpecial(
        boundsA: Bounds,
        boundsB: Bounds,
        rawA: Bounds,
        rawB: Bounds,
        roleA: Int,
        roleB: Int
    ): Int {
        // ---- 情况 1：b 包含 a，a 是窄条 ----
        if (rawB.contains(rawA) &&
            rawB.width > screenWidth * 0.7 &&
            rawA.height < threshold03H &&
            rawA.height < rawB.height / 2
        ) {
            if (rawA.top >= threshold02H || rawA.centerY >= rawB.centerY) {
                return if (rawA.right != rawB.right ||
                    rawA.width >= rawB.width * 0.3 ||
                    rawA.top >= screenHeight / 2 ||
                    roleB == NodeRoleHelper.ROLE_EDIT_TEXT
                ) 0 else -1
            }
            return -1
        }

        // ---- 情况 2：a 包含 b，b 是窄条 ----
        if (rawA.contains(rawB) &&
            rawA.width > screenWidth * 0.7 &&
            rawB.height < threshold03H &&
            rawB.height < rawA.height / 2
        ) {
            if (rawB.top < threshold02H && rawB.centerY < rawA.centerY) {
                return 1
            }
            if (rawA.right == rawB.right &&
                rawB.width < rawA.width * 0.3 &&
                rawB.top < screenHeight / 2 &&
                roleA != NodeRoleHelper.ROLE_EDIT_TEXT
            ) {
                return 1
            }
        }

        return 0
    }

    /**
     * 包含关系处理 —— 1:1 抄天坦 `a.A(rectA, rectB)`
     *
     * @return null = 无法判断（继续后续比较）；否则为比较结果
     */
    fun compareContain(ra: Bounds, rb: Bounds): Int? {
        // 对应天坦 `d7/C1417r.java:227~271` 的 `A(rect, rect2)`
        //
        // ★★ 本轮修正（P2）：原来只实现了**分支①**，漏了整个分支②
        //
        // 【天坦原文结构】
        // ```java
        // public final Integer A(Rect rect, Rect rect2) {
        //     if (!rect.contains(rect2) && !rect2.contains(rect)) {   // 无包含关系才进
        //         int overlapV = min(rect.bottom, rect2.bottom) - max(rect.top, rect2.top);
        //         // ── 分支① 垂直重叠且水平不重叠 → 同行按左右排
        //         if (overlapV > f23910f && w(rect, rect2) && !v(rect, rect2)) {
        //             int l1 = rect.left, l2 = rect2.left;
        //             return l1 != l2 ? l1 - l2 : 0;
        //         }
        //         // ── 分支② 水平相交且垂直重叠够多 → 按「并排卡片」规则排
        //         if (overlapV > f23910f && v(rect, rect2)
        //             && (minH = min(rect.height(), rect2.height())) > 0
        //             && overlapV * 2 > minH) {
        //             int overlapH = min(rect.right, rect2.right) - max(rect.left, rect2.left);
        //             int minW = min(rect.width(), rect2.width());
        //             // 再按「水平重叠比例」三档细分（0.3 / 0.9 阈值）
        //             if (minW <= 0 || overlapH >= minW * 0.3f) {
        //                 if (minW <= 0 || overlapH < minW * 0.9f) return pick(G(rect2,rect), B(rect,rect2));
        //                 else                                    return pick(E(rect,rect2), F(rect,rect2));
        //             } else                                      return pick(C(rect,rect2), D(rect,rect2));
        //         }
        //     }
        //     return null;    // 有包含关系 / 条件不满足 → 交给后续比较器
        // }
        // ```
        //
        // 【漏掉分支②的后果】并排卡片（同一行的多个卡片、宽度相近、
        //   水平方向有重叠）会走不到这条规则，退化成按 top 排序 ——
        //   而它们的 top 往往相同 → 顺序不稳定/错乱。
        //
        // 【w() 与 v() 的对应】天坦 `w(r1, r2)` = 垂直相交、
        //   `v(r1, r2)` = 水平相交（见本文件 Geometry 区）。

        // 有包含关系 → 交给后续规则
        if (ra.contains(rb) || rb.contains(ra)) return null

        val overlapV = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top)

        // ── 分支①：垂直重叠、水平不重叠 → 同行按左右排
        if (overlapV > THRESHOLD_20 &&
            intersectsVertically(ra, rb) &&
            !intersectsHorizontally(ra, rb)
        ) {
            val d = ra.left - rb.left
            return if (d != 0) d else 0
        }

        // ── 分支②：水平相交且垂直重叠超过较小高度一半 → 并排卡片
        if (overlapV > THRESHOLD_20 &&
            intersectsHorizontally(ra, rb)
        ) {
            val minHeight = Math.min(ra.height, rb.height)
            if (minHeight > 0 && overlapV * 2 > minHeight) {
                val overlapH = Math.min(ra.right, rb.right) - Math.max(ra.left, rb.left)
                val minWidth = Math.min(ra.width, rb.width)
                return if (minWidth <= 0 || overlapH >= minWidth * 0.3f) {
                    if (minWidth <= 0 || overlapH < minWidth * 0.9f) {
                        // 水平重叠 30%~90% → 先按「右边界对齐度」再按「左边界」
                        pick(orderByRight(rb, ra), orderByBottom(ra, rb))
                    } else {
                        // 水平重叠 ≥90% → 按水平居中 / 垂直居中
                        pick(orderByCenterX(ra, rb), orderByCenterY(ra, rb))
                    }
                } else {
                    // 水平重叠 <30% → 按左边界 / 顶边界
                    pick(orderByLeft(ra, rb), orderByTop(ra, rb))
                }
            }
        }
        return null
    }

    /** 天坦 `a.k(a, b)` —— 取第一个非 0 的比较结果 */
    private inline fun pick(first: Int, second: Int): Int = if (first != 0) first else second

    // 天坦 `G(rect2, rect)` / `B(rect, rect2)`：按右边界对齐度
    private fun orderByRight(a: Bounds, b: Bounds): Int = a.right - b.right

    // 天坦 `E/F`：水平居中距离
    private fun orderByCenterX(a: Bounds, b: Bounds): Int =
        (a.left + a.right) - (b.left + b.right)

    // 天坦 `C/D`：左边界
    private fun orderByLeft(a: Bounds, b: Bounds): Int = a.left - b.left

    // 天坦 `B(rect, rect2)`：底边界
    private fun orderByBottom(a: Bounds, b: Bounds): Int = a.bottom - b.bottom

    // 天坦 `F`：垂直居中距离
    private fun orderByCenterY(a: Bounds, b: Bounds): Int =
        (a.top + a.bottom) - (b.top + b.bottom)

    // 天坦 `D`：顶边界
    private fun orderByTop(a: Bounds, b: Bounds): Int = a.top - b.top

    // ==================== 几何工具（天坦 v()/w()/t()）====================

    /** 垂直相交 —— 天坦 `a.w()` */
    fun intersectsVertically(r1: Bounds, r2: Bounds): Boolean =
        r1.top < r2.bottom && r2.top < r1.bottom

    /** 水平相交 —— 天坦 `a.v()` */
    fun intersectsHorizontally(r1: Bounds, r2: Bounds): Boolean =
        r1.left < r2.right && r2.left < r1.right

    /** 垂直重叠 >= 60% —— 天坦 `a.t()` */
    fun overlapsVertically(r1: Bounds, r2: Bounds): Boolean {
        if (!intersectsVertically(r1, r2)) return false
        val overlap = Math.min(r1.bottom, r2.bottom) - Math.max(r1.top, r2.top)
        val minH = Math.min(r1.height, r2.height)
        return minH > 0 && overlap * 100 >= minH * 60
    }

    // ==================== 有效性判定 ====================

    /**
     * 边界是否有效 —— 对应天坦
     * `ra != null && ((ra.width() > 0 && ra.height() > 0) || nodeVisible)`
     *
     * ★ 参数命名修正（本轮）：原来叫 `importantForAccessibility`，
     *   会导致调用方误传 `node.isImportantForAccessibility`。
     *   经可读版全项目 grep 确认：**天坦从未调用过
     *   `isImportantForAccessibility`**，它的有效性判定用的是
     *   `AccessibilityNodeInfoUtils.isVisible()`
     *   （`w7/AccessibilityNodeInfoUtils.java:570~577`）。
     *   e 且在真实设备上几乎恒真，误用会让「无效节点」分支全部短路。
     *
     * @param nodeVisible 节点是否可见（传 `NodeOrderComparator.isVisible` 的结果）
     */
    fun isValid(rect: Bounds?, nodeVisible: Boolean): Boolean {
        if (rect == null) return false
        return (rect.width > 0 && rect.height > 0) || nodeVisible
    }
}
