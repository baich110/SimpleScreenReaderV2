package com.baicha.reader.core

/**
 * 遍历方向 —— 对应 TalkBack `TraversalStrategy.SearchDirection`
 *
 * 【为什么需要完整的 6 个方向（本项目原来只有 2 个）】
 *
 * 原来的 `FocusManager` 只区分「前/后」两个方向（forward 布尔值），
 * 这只能做**逻辑遍历**（按树的读写顺序）。
 *
 * TalkBack 的框架区分两类方向：
 *
 *   ① **逻辑方向**（Logical）：FORWARD / BACKWARD
 *      —— 按无障碍树的遍历顺序（读取顺序）移动
 *      —— 对应读屏的「单指右滑 / 左滑」
 *
 *   ② **空间方向**（Spatial）：UP / DOWN / LEFT / RIGHT
 *      —— 按**屏幕几何位置**移动（找最近的那个方向上的节点）
 *      —— 这是 TalkBack 的 `DirectionalTraversalStrategy` 的基础
 *      —— 对应读屏的「按位置找最近的控件」
 *
 * 空间方向是**框架级能力**：手势映射、网格遍历、焦点恢复、
 * 滚动时的邻接查找都依赖它。缺了它就只能做顺序遍历，
 * 遇到「布局顺序 ≠ 视觉顺序」的界面就会跳错。
 *
 * 参考：
 *   TalkBack  TraversalStrategy.java（方向常量定义）
 *   TalkBack  TraversalStrategyUtils.java:111-168（空间/逻辑判定与转换）
 *   天坦      d7/C1423r.java（几何比较 z()/A()，用于排序与包含判定）
 */
object TraversalDirection {

    // ==================== 方向常量（对齐 TalkBack 数值）====================

    /** 未知方向 —— 对应 TalkBack SEARCH_FOCUS_UNKNOWN */
    const val UNKNOWN = 0

    /** 逻辑前进 —— 对应 TalkBack SEARCH_FOCUS_FORWARD */
    const val FORWARD = 1

    /** 逻辑后退 —— 对应 TalkBack SEARCH_FOCUS_BACKWARD */
    const val BACKWARD = 2

    /** 空间左 —— 对应 TalkBack SEARCH_FOCUS_LEFT */
    const val LEFT = 3

    /** 空间右 —— 对应 TalkBack SEARCH_FOCUS_RIGHT */
    const val RIGHT = 4

    /** 空间上 —— 对应 TalkBack SEARCH_FOCUS_UP */
    const val UP = 5

    /** 空间下 —— 对应 TalkBack SEARCH_FOCUS_DOWN */
    const val DOWN = 6

    /**
     * 是否空间方向 —— 对应 TalkBack `isSpatialDirection()`
     */
    fun isSpatial(direction: Int): Boolean = when (direction) {
        UP, DOWN, LEFT, RIGHT -> true
        FORWARD, BACKWARD -> false
        else -> throw IllegalArgumentException("非法方向: $direction")
    }

    /**
     * 是否逻辑方向 —— 对应 TalkBack `isLogicalDirection()`
     */
    fun isLogical(direction: Int): Boolean = direction == FORWARD || direction == BACKWARD

    /**
     * 空间方向 → 逻辑方向 —— 对应 TalkBack `getLogicalDirection()`
     *
     * TalkBack 会按 LTR/RTL 决定左右映射；中文场景是 LTR：
     *   左 → BACKWARD，右 → FORWARD
     *   上 → BACKWARD，下 → FORWARD
     *
     * @param isRtl 是否从右到左布局（阿拉伯语等）
     */
    fun toLogical(direction: Int, isRtl: Boolean = false): Int {
        val left = if (isRtl) FORWARD else BACKWARD
        val right = if (isRtl) BACKWARD else FORWARD
        return when (direction) {
            LEFT -> left
            RIGHT -> right
            UP, BACKWARD -> BACKWARD
            DOWN, FORWARD -> FORWARD
            else -> throw IllegalArgumentException("非法方向: $direction")
        }
    }

    /**
     * 方向 → 滚动动作 —— 对应 TalkBack `convertSearchDirectionToScrollAction()`
     *
     * 前 / 下 → 向下滚动；后 / 上 → 向上滚动；左 / 右 → 横向滚动
     */
    fun toScrollAction(direction: Int): Int = when (direction) {
        FORWARD, DOWN -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        BACKWARD, UP -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        LEFT -> 16908344  // ACTION_SCROLL_LEFT (API 23+)
        RIGHT -> 16908345 // ACTION_SCROLL_RIGHT (API 23+)
        else -> 0
    }

    /**
     * 滚动动作 → 方向 —— 对应 TalkBack `convertScrollActionToSearchDirection()`
     */
    fun fromScrollAction(scrollAction: Int): Int = when (scrollAction) {
        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> FORWARD
        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> BACKWARD
        16908344 -> LEFT
        16908345 -> RIGHT
        else -> UNKNOWN
    }

    /**
     * 方向名（诊断与朗读用）
     */
    fun name(direction: Int): String = when (direction) {
        UNKNOWN -> "未知"
        FORWARD -> "前进"
        BACKWARD -> "后退"
        LEFT -> "左"
        RIGHT -> "右"
        UP -> "上"
        DOWN -> "下"
        else -> "非法($direction)"
    }

    /** 全部合法方向 */
    val all: IntArray = intArrayOf(FORWARD, BACKWARD, LEFT, RIGHT, UP, DOWN)
}
