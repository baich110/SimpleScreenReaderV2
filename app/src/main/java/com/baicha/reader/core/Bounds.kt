package com.baicha.reader.core

import android.graphics.Rect

/**
 * 纯几何矩形 —— **不依赖 android.graphics**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这个类】
 * ══════════════════════════════════════════════════════════
 *
 * `android.graphics.Rect` 在 JVM 单元测试中是**空壳**：
 *   Rect(10, 20, 30, 40).left  →  0        （构造器 no-op）
 *   rect.set(1, 2, 3, 4)       →  无效果
 *
 * 导致 `SpatialTraversal` / `NodeBoundsCalculator` / `NodeOrderComparator`
 * 三个模块的几何算法**完全无法单测** —— 测试会在全 0 数据上"通过"，
 * 给出虚假的安全感。
 *
 * 解法：把几何运算抽到不依赖 Android 的类型上，
 * 边界处只做一次 `Rect → Bounds` 转换。
 *
 * ══════════════════════════════════════════════════════════
 * 【设计】
 * ══════════════════════════════════════════════════════════
 *
 *   - `data class`：自动 equals/hashCode/toString，便于断言与调试
 *   - 不可变：算法里不会意外修改
 *   - 零 Android 依赖：纯 JVM 可测
 *   - 提供与 `Rect` 的互转方法
 *
 * 【性能】
 *   不可变数据类在 JIT 下会被标量替换（逃逸分析），
 *   实测开销可忽略。相比"无法测试"的代价，这点成本完全值得。
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {

    // ==================== 与 Android Rect 的互转 ====================

    /** 从 Android Rect 构造 —— 边界处唯一需要调用的转换 */
    constructor(rect: Rect) : this(rect.left, rect.top, rect.right, rect.bottom)

    /**
     * 写到 Android Rect
     *
     * 仅在需要回传给 Android API 时使用（如 performAction 的参数）。
     */
    fun toRect(out: Rect) {
        out.set(left, top, right, bottom)
    }

    /** 转为 Android Rect（新建对象，慎用） */
    fun toRect(): Rect = Rect(left, top, right, bottom)

    // ==================== 基础属性 ====================

    val width: Int get() = right - left

    val height: Int get() = bottom - top

    /** 是否为空（宽或高 <= 0） */
    val isEmpty: Boolean get() = left >= right || top >= bottom

    val centerX: Int get() = (left + right) / 2

    val centerY: Int get() = (top + bottom) / 2

    /**
     * 中心 X —— **AOSP 公式**，供 `FocusFinder` 类算法使用
     *
     * ══════════════════════════════════════════════════════════
     * 【为什么必须单独提供（★ 本轮修复）】
     * ══════════════════════════════════════════════════════════
     *
     * AOSP `DirectionalTraversalStrategy.minorAxisDistance()` 原文：
     * ```java
     *   case SEARCH_FOCUS_LEFT, SEARCH_FOCUS_RIGHT -> {
     *     // the distance between the center verticals
     *     return Math.abs(((source.top + source.height() / 2)
     *                      - ((dest.top + dest.height() / 2))));
     *   }
     * ```
     * 即中心 = **`top + height / 2`**，而 [centerY] 用的是
     * **`(top + bottom) / 2`**。
     *
     * 【两者何时不同】在**非负坐标**下恒等；在**负坐标**下差 1 ——
     * 因为 Kotlin/Java 的整数除法**向零截断**：
     * ```
     *   top=-20, bottom=-19  (height=1)
     *     AOSP:   -20 + 1/2 = -20 + 0 = -20
     *     centerY: (-20 + -19)/2 = -39/2 = -19      ← 差 1
     * ```
     *
     * 【为什么这是真问题，不是抠细节】
     * Android 屏幕坐标**可以为负**：视图滚动出屏幕上方/左侧时，
     * `getBoundsInScreen()` 返回的 top/left 就是负数。
     * 长列表滚动、横向滚动、被状态栏遮挡的内容 —— 全都产生负坐标。
     * 于是「按方向找最近焦点」在**滚动过的内容上会选错候选**：
     * 两个候选的 `minorAxisDistance` 都被算小/算大 1 像素，
     * 加权距离比较（13×major² + minor²）可能因此翻转，
     * 表现为「上下滑动时焦点跳到了斜对面的元素上」。
     *
     * 【为什么不用 13 倍权重抵消】major 项权重 13，minor 项权重 1。
     * 当两个候选的 major 距离相同时（同一行/同一列的常见情形），
     * **minor 就是唯一决定因素** —— 差 1 足以翻转结果。
     *
     * 【修复策略】不去改 [centerX]/[centerY]（它们被别的算法依赖，
     * 改了会引入新偏差），而是**新增** AOSP 公式的独立访问器，
     * 让 `SpatialTraversal` 用它 —— 与「同名不同函数不能混用」同一原则。
     */
    val aospCenterX: Int get() = left + width / 2

    /** 中心 Y —— AOSP 公式，见 [aospCenterX] 的完整说明 */
    val aospCenterY: Int get() = top + height / 2

    // ==================== 几何运算 ====================

    /**
     * 是否包含另一个矩形
     */
    fun contains(other: Bounds): Boolean =
        left <= other.left && top <= other.top &&
                right >= other.right && bottom >= other.bottom

    /**
     * 是否包含点
     */
    fun contains(x: Int, y: Int): Boolean =
        x >= left && x < right && y >= top && y < bottom

    /**
     * 是否与另一个矩形相交（含边界接触）
     */
    fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right &&
                top < other.bottom && other.top < bottom

    /**
     * 水平方向是否相交 —— 对应天坦 `a.v()`
     */
    fun intersectsHorizontally(other: Bounds): Boolean =
        left < other.right && other.left < right

    /**
     * 垂直方向是否相交 —— 对应天坦 `a.w()`
     */
    fun intersectsVertically(other: Bounds): Boolean =
        top < other.bottom && other.top < bottom

    /**
     * 垂直重叠度是否 >= 60% —— 对应天坦 `a.t()`
     */
    fun overlapsVerticallyBy60(other: Bounds): Boolean {
        if (!intersectsVertically(other)) return false
        val overlap = Math.min(bottom, other.bottom) - Math.max(top, other.top)
        val minH = Math.min(height, other.height)
        return minH > 0 && overlap * 100 >= minH * 60
    }

    /**
     * 内缩 —— 对应 `Rect.inset()`
     *
     * 正数为内缩，负数为外扩。
     */
    fun inset(dx: Int, dy: Int): Bounds =
        Bounds(left + dx, top + dy, right - dx, bottom - dy)

    /**
     * 平移 —— 对应 `Rect.offset()`
     */
    fun offset(dx: Int, dy: Int): Bounds =
        Bounds(left + dx, top + dy, right + dx, bottom + dy)

    /**
     * 与另一个矩形的交集
     */
    fun intersectWith(other: Bounds): Bounds {
        val l = Math.max(left, other.left)
        val t = Math.max(top, other.top)
        val r = Math.min(right, other.right)
        val b = Math.min(bottom, other.bottom)
        return if (l < r && t < b) Bounds(l, t, r, b) else EMPTY
    }

    /**
     * 与另一个矩形的并集（包围盒）
     */
    fun unionWith(other: Bounds): Bounds =
        Bounds(
            Math.min(left, other.left),
            Math.min(top, other.top),
            Math.max(right, other.right),
            Math.max(bottom, other.bottom)
        )

    /**
     * 与另一个矩形之间的最小距离（中心点欧氏距离）
     */
    fun centerDistanceTo(other: Bounds): Int {
        val dx = centerX - other.centerX
        val dy = centerY - other.centerY
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
    }

    override fun toString(): String = "Bounds($left,$top-$right,$bottom)"

    companion object {
        /** 空矩形 */
        val EMPTY = Bounds(0, 0, 0, 0)

        /**
         * 从 Android Rect 数组构造 —— 便于批量转换
         */
        fun from(rect: Rect?): Bounds? =
            rect?.let { Bounds(it.left, it.top, it.right, it.bottom) }
    }
}

/**
 * 可变边界累加器 —— 用于"子节点并集"计算
 *
 * 对应天坦 `NodeBoundsCalculator` 里的 minTop/minLeft/maxRight/maxBottom 累加。
 * 单独做成类是为了：① 可测 ② 避免每次 new Bounds
 */
class BoundsAccumulator {

    var minLeft: Int = Int.MAX_VALUE
        private set
    var minTop: Int = Int.MAX_VALUE
        private set
    var maxRight: Int = Int.MIN_VALUE
        private set
    var maxBottom: Int = Int.MIN_VALUE
        private set

    /** 是否累加过至少一个有效矩形 */
    var hasValue: Boolean = false
        private set

    /**
     * 累加一个矩形
     *
     * @return true = 已累加（矩形有效）
     */
    fun add(b: Bounds): Boolean {
        if (b.isEmpty) return false
        if (b.left < minLeft) minLeft = b.left
        if (b.top < minTop) minTop = b.top
        if (b.right > maxRight) maxRight = b.right
        if (b.bottom > maxBottom) maxBottom = b.bottom
        hasValue = true
        return true
    }

    /** 取出并集结果 */
    fun toBounds(): Bounds =
        if (!hasValue) Bounds.EMPTY
        else Bounds(minLeft, minTop, maxRight, maxBottom)

    /**
     * 把并集裁剪到给定边界内 —— 对应天坦的
     * `bounds.top = max(minTop, bounds.top)` 等四行
     */
    fun clampTo(outer: Bounds): Bounds {
        if (!hasValue) return outer
        return Bounds(
            Math.max(minLeft, outer.left),
            Math.max(minTop, outer.top),
            Math.min(maxRight, outer.right),
            Math.min(maxBottom, outer.bottom)
        )
    }

    fun reset() {
        minLeft = Int.MAX_VALUE
        minTop = Int.MAX_VALUE
        maxRight = Int.MIN_VALUE
        maxBottom = Int.MIN_VALUE
        hasValue = false
    }
}
