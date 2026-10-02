package com.baicha.reader.core

/**
 * 窗口顺序与选择 —— **纯逻辑，不依赖 android.view.accessibility**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这个类（本轮抽取）】
 * ══════════════════════════════════════════════════════════
 *
 * `WindowTraversal.navigateToNextWindow()` 的算法本身是纯逻辑：
 *
 * ```
 *   给定 [当前窗口 id] + [已过滤的窗口 id 列表] + [方向]
 *   → 算出目标窗口在列表中的下标（含环绕）
 * ```
 *
 * 但它此前把这段逻辑**内联在**一个接收 `AccessibilityService` 的方法里，
 * 于是无法单测 —— `AccessibilityWindowInfo` 是 **final 类**
 * （AOSP 源码：`public final class AccessibilityWindowInfo`），
 * 既不能实例化（JVM 里构造器返回 null）也不能子类化，
 * 与 `AccessibilityNodeInfo`（非 final，可子类化）情况不同。
 *
 * 结论：想验证「环绕/越界/当前窗口不在列表里」这些边界，
 * **必须**把算法与 Android 类型解耦。这就是本类存在的理由。
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】天坦 `M6/h.F()`
 * ══════════════════════════════════════════════════════════
 *
 * 天坦的做法同为「在当前窗口下标上 ±1 并按列表长度环绕」，
 * 且「当前窗口不在候选列表里」时退化为从端点开始。
 * 白茶此处与天坦一致，本类只是把这段算法抽出来使可测。
 */
object WindowOrdering {

    /**
     * 算目标窗口下标（含环绕）
     *
     * @param currentWindowId 当前窗口 ID；**不在** [windowIds] 里时传任意值
     * @param windowIds 已过滤的候选窗口 ID 列表（顺序 = 遍历顺序）
     * @param forward true = 前向（下标 +1），false = 后向（下标 -1）
     * @return 目标下标；列表为空时返回 -1
     */
    fun nextIndex(currentWindowId: Int, windowIds: List<Int>, forward: Boolean): Int {
        if (windowIds.isEmpty()) return -1

        val idx = windowIds.indexOf(currentWindowId)

        // 当前窗口不在候选列表里 → 从端点开始
        //   （天坦：idx == -1 时前向取第一个、后向取最后一个）
        var next: Int = if (idx < 0) {
            if (forward) 0 else windowIds.size - 1
        } else {
            if (forward) idx + 1 else idx - 1
        }

        // 环绕
        if (forward && next >= windowIds.size) next = 0
        if (!forward && next < 0) next = windowIds.size - 1

        return next
    }

    /**
     * 目标窗口是否是「唯一窗口且就是当前窗口」
     *
     * 这是 `navigateToNextWindow` 的**提前返回**条件：
     * 只有一个窗口时跨窗口没有意义，返回 null 让调用方不动作。
     *
     * 【易错点】判断必须用「筛选后」的列表长度与当前窗口 id，
     * 而不是全部窗口数 —— 屏幕上有 5 个窗口但只有 1 个可聚焦时，
     * 同样应该提前返回。
     */
    fun isSingleCurrentWindow(currentWindowId: Int, windowIds: List<Int>): Boolean =
        windowIds.size == 1 && windowIds[0] == currentWindowId

    /**
     * 按层级升序排列窗口索引 —— 对应 `sortedBy { it.layer }`
     *
     * 【为什么需要排序】`AccessibilityService.getWindows()` 的返回顺序
     * **不保证稳定**，直接用它做「前/后一个窗口」会导致方向时好时坏。
     * 按 `layer`（Z 序，越大越靠上）升序后与视觉层叠一致。
     *
     * 本函数把「id + layer」成对传入，返回按 layer 升序的 **id 列表**，
     * 从而可脱离 Android 类型单测。
     *
     * 【排序稳定性】`sortedBy` 是稳定排序，layer 相同时保持原相对顺序。
     */
    fun sortByLayer(windows: List<Pair<Int, Int>>): List<Int> =
        windows.sortedBy { it.second }.map { it.first }
}
