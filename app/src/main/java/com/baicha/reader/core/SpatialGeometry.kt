package com.baicha.reader.core

/**
 * 空间方向查找的**纯几何算法** —— AOSP `FocusFinder`
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么把它从 SpatialTraversal 里抽出来】
 * ══════════════════════════════════════════════════════════
 *
 * 这些函数**本来就只依赖矩形**，与「节点」无关：
 *   isCandidate / beamsOverlap / isToDirectionOf / beamBeats /
 *   majorAxisDistance / majorAxisDistanceToFarEdge / minorAxisDistance /
 *   weightedDistance
 *
 * 但它们此前是 `SpatialTraversal` 的 private 实例方法，
 * 于是只能通过「构造真节点树 + 跑真遍历」才能被测 ——
 * 而 `AccessibilityNodeInfo` 在 JVM 里需要专门替身（见 `FakeNode`），
 * 导致**算法本身从未被逐条断言过**。
 *
 * 抽成顶层 object 后，全部函数可用 [Bounds]（纯 JVM 类型）直接测。
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】AOSP `DirectionalTraversalStrategy.java`
 *   （TalkBack 自带副本：`utils/.../traversal/DirectionalTraversalStrategy.java`）
 * ══════════════════════════════════════════════════════════
 *
 * 逐函数对照的行号见各函数注释。
 *
 * 【本轮已在此发现并修复的偏差】
 * `minorAxisDistance` 原来用 `Bounds.centerY` = `(top+bottom)/2`，
 * 而 AOSP 用 `top + height/2`。两者在非负坐标恒等、**负坐标差 1** ——
 * Android 屏幕坐标可为负（内容滚出屏幕），故真机上会算错。
 * 详见 [minorAxisDistance]。
 */
object SpatialGeometry {

    // ==================== 候选资格 ====================

    /**
     * dest 是否是 direction 方向上 src 的候选 —— AOSP `isCandidate()`
     *
     * 依据 `DirectionalTraversalStrategy.java:436~456`（原样摘录）：
     * ```java
     *   case SEARCH_FOCUS_LEFT -> {
     *     return (srcRect.right > destRect.right || srcRect.left >= destRect.right)
     *         && srcRect.left > destRect.left;
     *   }
     *   case SEARCH_FOCUS_RIGHT -> {
     *     return (srcRect.left < destRect.left || srcRect.right <= destRect.left)
     *         && srcRect.right < destRect.right;
     *   }
     *   case SEARCH_FOCUS_UP -> {
     *     return (srcRect.bottom > destRect.bottom || srcRect.top >= destRect.bottom)
     *         && srcRect.top > destRect.top;
     *   }
     *   case SEARCH_FOCUS_DOWN -> {
     *     return (srcRect.top < destRect.top || srcRect.bottom <= destRect.top)
     *         && srcRect.bottom < destRect.bottom;
     *   }
     * ```
     *
     * 【注意】AOSP 对非法方向**抛异常**（`throw new IllegalArgumentException`），
     * 白茶返回 `false` —— 这是**有意的差异**：读屏是常驻服务，
     * 不能因为一个意外方向值就崩掉，返回 false 让它安全地找不到候选。
     */
    fun isCandidate(src: Bounds, dest: Bounds, direction: Int): Boolean = when (direction) {
        TraversalDirection.LEFT ->
            (src.right > dest.right || src.left >= dest.right) && src.left > dest.left

        TraversalDirection.RIGHT ->
            (src.left < dest.left || src.right <= dest.left) && src.right < dest.right

        TraversalDirection.UP ->
            (src.bottom > dest.bottom || src.top >= dest.bottom) && src.top > dest.top

        TraversalDirection.DOWN ->
            (src.top < dest.top || src.bottom <= dest.top) && src.bottom < dest.bottom

        else -> false
    }

    /**
     * 「src 是否在 dest 的给定方向」—— AOSP `isToDirectionOf()`
     *
     * 依据 `:488~503`：
     * ```java
     *   case SEARCH_FOCUS_LEFT  -> { return src.left >= dest.right; }
     *   case SEARCH_FOCUS_RIGHT -> { return src.right <= dest.left; }
     *   case SEARCH_FOCUS_UP    -> { return src.top >= dest.bottom; }
     *   case SEARCH_FOCUS_DOWN  -> { return src.bottom <= dest.top; }
     * ```
     * 非法方向同样返回 false（AOSP 抛异常）。
     */
    fun isToDirectionOf(direction: Int, src: Bounds, dest: Bounds): Boolean = when (direction) {
        TraversalDirection.LEFT -> src.left >= dest.right
        TraversalDirection.RIGHT -> src.right <= dest.left
        TraversalDirection.UP -> src.top >= dest.bottom
        TraversalDirection.DOWN -> src.bottom <= dest.top
        else -> false
    }

    // ==================== beam ====================

    /**
     * 两个矩形在给定方向的轴上的 beam 是否重叠 —— AOSP `beamsOverlap()`
     *
     * 依据 `:466~475`：
     * ```java
     *   case SEARCH_FOCUS_LEFT, SEARCH_FOCUS_RIGHT -> {
     *     return (rect2.bottom >= rect1.top) && (rect2.top <= rect1.bottom);
     *   }
     *   case SEARCH_FOCUS_UP, SEARCH_FOCUS_DOWN -> {
     *     return (rect2.right >= rect1.left) && (rect2.left <= rect1.right);
     *   }
     * ```
     */
    fun beamsOverlap(direction: Int, rect1: Bounds, rect2: Bounds): Boolean = when (direction) {
        TraversalDirection.LEFT, TraversalDirection.RIGHT ->
            rect2.bottom >= rect1.top && rect2.top <= rect1.bottom

        TraversalDirection.UP, TraversalDirection.DOWN ->
            rect2.right >= rect1.left && rect2.left <= rect1.right

        else -> false
    }

    /**
     * rect1 是否靠「独占 beam」胜出 —— AOSP `beamBeats()`
     *
     * 依据 `:382~413`（原样摘录）：
     * ```java
     *   private boolean beamBeats(int direction, Rect source, Rect rect1, Rect rect2) {
     *     final boolean rect1InSrcBeam = beamsOverlap(direction, source, rect1);
     *     final boolean rect2InSrcBeam = beamsOverlap(direction, source, rect2);
     *
     *     // if rect1 isn't exclusively in the src beam, it doesn't win
     *     if (rect2InSrcBeam || !rect1InSrcBeam) { return false; }
     *
     *     if (!isToDirectionOf(direction, source, rect2)) { return true; }
     *
     *     // for horizontal directions, being exclusively in beam always wins
     *     if ((direction == SEARCH_FOCUS_LEFT) || (direction == SEARCH_FOCUS_RIGHT)) {
     *       return true;
     *     }
     *
     *     // for vertical directions, beams only beat up to a point
     *     return (majorAxisDistance(direction, source, rect1)
     *         < majorAxisDistanceToFarEdge(direction, source, rect2));
     *   }
     * ```
     */
    fun beamBeats(direction: Int, source: Bounds, rect1: Bounds, rect2: Bounds): Boolean {
        val rect1InBeam = beamsOverlap(direction, source, rect1)
        val rect2InBeam = beamsOverlap(direction, source, rect2)

        // rect1 不是「独占 beam」→ 不赢
        if (rect2InBeam || !rect1InBeam) return false

        // 已知：rect1 在 beam 内、rect2 不在
        // 若 rect1 在目标方向而 rect2 不在 → rect1 胜
        if (!isToDirectionOf(direction, source, rect2)) return true

        // 水平方向：独占 beam 永远胜
        if (direction == TraversalDirection.LEFT || direction == TraversalDirection.RIGHT) {
            return true
        }

        // 垂直方向：beam 只能赢到一定程度（rect2 不能"整体更近"）
        return majorAxisDistance(direction, source, rect1) <
                majorAxisDistanceToFarEdge(direction, source, rect2)
    }

    // ==================== 距离 ====================

    /**
     * 主轴距离（到近边）—— AOSP `majorAxisDistance()`
     *
     * 依据 `:509~512`：`return Math.max(0, majorAxisDistanceRaw(...));`
     * 「dest 不在该方向时返回 **0**（不是 1）」。
     */
    fun majorAxisDistance(direction: Int, source: Bounds, dest: Bounds): Int =
        Math.max(0, majorAxisDistanceRaw(direction, source, dest))

    /** AOSP `majorAxisDistanceRaw()` —— 依据 `:514~528` */
    fun majorAxisDistanceRaw(direction: Int, source: Bounds, dest: Bounds): Int = when (direction) {
        TraversalDirection.LEFT -> source.left - dest.right
        TraversalDirection.RIGHT -> dest.left - source.right
        TraversalDirection.UP -> source.top - dest.bottom
        TraversalDirection.DOWN -> dest.top - source.bottom
        else -> 0
    }

    /**
     * 主轴距离（到远边）—— AOSP `majorAxisDistanceToFarEdge()`
     *
     * 依据 `:536~538`：`return Math.max(1, majorAxisDistanceToFarEdgeRaw(...));`
     * 「dest 不在该方向时返回 **1**（用于与 majorAxisDistance 打破平局）」——
     * 注意下界是 **1 而不是 0**，与 [majorAxisDistance] 不同。
     */
    fun majorAxisDistanceToFarEdge(direction: Int, source: Bounds, dest: Bounds): Int =
        Math.max(1, majorAxisDistanceToFarEdgeRaw(direction, source, dest))

    /** AOSP `majorAxisDistanceToFarEdgeRaw()` —— 依据 `:540~554` */
    fun majorAxisDistanceToFarEdgeRaw(direction: Int, source: Bounds, dest: Bounds): Int =
        when (direction) {
            TraversalDirection.LEFT -> source.left - dest.left
            TraversalDirection.RIGHT -> dest.right - source.right
            TraversalDirection.UP -> source.top - dest.top
            TraversalDirection.DOWN -> dest.bottom - source.bottom
            else -> 0
        }

    /**
     * 垂直轴距离（中心线距离）—— AOSP `minorAxisDistance()`
     *
     * ══════════════════════════════════════════════════════════
     * 【★ 本轮修复：原来用的是 `Bounds.centerY`，与 AOSP 不是同一个公式】
     * ══════════════════════════════════════════════════════════
     *
     * 依据 `:565~580`（原样摘录）：
     * ```java
     *   static int minorAxisDistance(int direction, Rect source, Rect dest) {
     *     switch (direction) {
     *       case SEARCH_FOCUS_LEFT, SEARCH_FOCUS_RIGHT -> {
     *         // the distance between the center verticals
     *         return Math.abs(((source.top + source.height() / 2)
     *                          - ((dest.top + dest.height() / 2))));
     *       }
     *       case SEARCH_FOCUS_UP, SEARCH_FOCUS_DOWN -> {
     *         // the distance between the center horizontals
     *         return Math.abs(((source.left + source.width() / 2)
     *                          - ((dest.left + dest.width() / 2))));
     *       }
     * ```
     * 中心 = **`top + height / 2`**（先除后加）。
     *
     * 而 `Bounds.centerY` = **`(top + bottom) / 2`**（先加后除）。
     *
     * 【两者何时不同】非负坐标下恒等；**负坐标下差 1** ——
     * 因为 Kotlin/Java 整数除法**向零截断**：
     * ```
     *   top=-20, bottom=-19 (height=1)
     *     AOSP:    -20 + 1/2    = -20 + 0 = -20
     *     centerY: (-20 + -19)/2 = -39/2  = -19      ← 差 1
     * ```
     *
     * 【为什么对读屏是真问题】Android 屏幕坐标**可以为负** ——
     * 内容滚出屏幕上方/左侧时 `getBoundsInScreen()` 的 top/left 就是负数
     * （长列表滚动、横向滚动、被状态栏遮挡的内容都会产生）。
     *
     * 加权距离 = `13×major² + minor²`。当两个候选的 major 相同
     * （同一行/同一列的常见情形），**minor 是唯一决定因素**，
     * 差 1 像素足以翻转比较结果 ——
     * 表现为「上下滑动时焦点跳到斜对面的元素上」。
     *
     * 【修复】改用 AOSP 公式（`Bounds.aospCenterX/aospCenterY`），
     * 而非去改 `Bounds.centerX/centerY` —— 后者被别的算法依赖，
     * 改了会引入新偏差。这与「同名不同函数不可混用」是同一原则。
     */
    fun minorAxisDistance(direction: Int, source: Bounds, dest: Bounds): Int = when (direction) {
        TraversalDirection.LEFT, TraversalDirection.RIGHT ->
            Math.abs(source.aospCenterY - dest.aospCenterY)

        TraversalDirection.UP, TraversalDirection.DOWN ->
            Math.abs(source.aospCenterX - dest.aospCenterX)

        else -> 0
    }

    /**
     * 加权距离 —— AOSP `getWeightedDistanceFor()`
     *
     * 依据 `:420~427`（原样摘录）：
     * ```java
     *   private int getWeightedDistanceFor(int majorAxisDistance, int minorAxisDistance) {
     *     if (majorAxisDistance > 10000 || minorAxisDistance > 10000) {
     *       return Integer.MAX_VALUE;
     *     } else {
     *       // Won't overflow; max possible value = 1400000000 < Integer.MAX_VALUE.
     *       return 13 * majorAxisDistance * majorAxisDistance
     *              + minorAxisDistance * minorAxisDistance;
     *     }
     *   }
     * ```
     *
     * 【溢出保护必须保留】没有它，`13 * major * major` 在 major 较大时
     * 会溢出成**负数** —— 于是「很远的元素」被算成「最近」，
     * 焦点直接跳到屏幕外的元素上。
     */
    fun weightedDistance(direction: Int, source: Bounds, dest: Bounds): Int {
        val major = majorAxisDistance(direction, source, dest)
        val minor = minorAxisDistance(direction, source, dest)
        if (major > 10000 || minor > 10000) return Int.MAX_VALUE
        return 13 * major * major + minor * minor
    }

    // ==================== 组合判定 ====================

    /**
     * rect1 是否比 rect2 更优 —— AOSP `isBetterCandidate()`
     *
     * 依据 `:347~374`（原样摘录）：
     * ```java
     *   private boolean isBetterCandidate(int direction, Rect source, Rect rect1, Rect rect2) {
     *     if (!isCandidate(source, rect1, direction)) { return false; }
     *     if (!isCandidate(source, rect2, direction)) { return true; }
     *     if (beamBeats(direction, source, rect1, rect2)) { return true; }
     *     if (beamBeats(direction, source, rect2, rect1)) { return false; }
     *     return (getWeightedDistanceFor(majorAxisDistance(direction, source, rect1),
     *                                    minorAxisDistance(direction, source, rect1))
     *         < getWeightedDistanceFor(majorAxisDistance(direction, source, rect2),
     *                                  minorAxisDistance(direction, source, rect2)));
     *   }
     * ```
     *
     * 五个判定步骤的顺序**不可调整** —— 每一步的结论都优先于后面的。
     */
    fun isBetterCandidate(
        direction: Int,
        source: Bounds,
        rect1: Bounds,
        rect2: Bounds
    ): Boolean {
        // ① rect1 不是候选 → 直接否
        if (!isCandidate(source, rect1, direction)) return false

        // ② rect2 不是候选 → rect1 胜
        if (!isCandidate(source, rect2, direction)) return true

        // ③ rect1 靠 beam 胜
        if (beamBeats(direction, source, rect1, rect2)) return true

        // ④ rect2 靠 beam 胜 → rect1 负
        if (beamBeats(direction, source, rect2, rect1)) return false

        // ⑤ 加权距离比较
        return weightedDistance(direction, source, rect1) <
                weightedDistance(direction, source, rect2)
    }
}
