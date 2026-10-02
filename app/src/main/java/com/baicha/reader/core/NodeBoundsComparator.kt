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
        // 有包含关系 → 交给特殊规则处理，这里不判
        if (ra.contains(rb) || rb.contains(ra)) return null

        val overlapH = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top)
        if (overlapH > THRESHOLD_20 &&
            intersectsVertically(ra, rb) &&
            !intersectsHorizontally(ra, rb)
        ) {
            // 垂直重叠但水平不重叠 → 同行按左右排
            return ra.left - rb.left
        }
        return null
    }

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
     * `ra != null && ((ra.width() > 0 && ra.height() > 0) || node.isImportantForAccessibility)`
     */
    fun isValid(rect: Bounds?, importantForAccessibility: Boolean): Boolean {
        if (rect == null) return false
        return (rect.width > 0 && rect.height > 0) || importantForAccessibility
    }
}
