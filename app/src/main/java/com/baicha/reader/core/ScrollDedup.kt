package com.baicha.reader.core

/**
 * 滚动事件去重判定 —— 从天坦 `M6/r.b`（`NodeIdentifier`）对齐而来
 *
 * ══════════════════════════════════════════════════════════
 * 【天坦怎么做的】
 * ══════════════════════════════════════════════════════════
 *
 * 天坦 `M6/r.java:88~121` 定义内部类 `b`（`NodeIdentifier`）：
 *
 * ```java
 * public b(o0.w wVar) {
 *     this.f6117a = wVar.hashCode();     // 节点哈希
 *     this.f6118b = wVar.U();            // 窗口 ID
 * }
 * public boolean equals(Object obj) {
 *     return this.f6117a == other.f6117a && this.f6118b == other.f6118b;
 * }
 * ```
 *
 * 用 **(节点哈希, 窗口ID)** 二元组判定「是不是同一次滚动」。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么要带窗口 ID（关键设计）】
 * ══════════════════════════════════════════════════════════
 *
 * 只用节点哈希会出错：不同窗口里可能出现 **hashCode 相同** 的节点
 * （`AccessibilityNodeInfo.hashCode()` 在部分实现里基于内容，
 * 两个内容相同的节点会撞哈希）。
 *
 * 撞哈希的后果：用户在 A 窗口滚动，切到 B 窗口滚动时，
 * 事件被判为「重复」而**被吞掉不播报** —— 用户以为读屏卡了。
 *
 * 加上 windowId 后，跨窗口的同哈希节点不再互相干扰。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么要抽成纯函数】
 * ══════════════════════════════════════════════════════════
 *
 * 判定原本内联在 `handleScroll(event)` 里 —— 而
 * `AccessibilityEvent` 在 JVM 单测中构造不出来
 * （`AccessibilityEvent.obtain()` 返回 null），
 * 导致这条判定**完全没有测试覆盖**。
 *
 * 变异测试证实了这一点：把判定改成恒 false，所有测试仍通过。
 * 抽出纯函数后即可覆盖。
 */
object ScrollDedup {

    /** 无法取到节点时的占位值 */
    const val UNKNOWN = -1

    /**
     * 是否为重复滚动事件。
     *
     * @param nodeKey     本次滚动的节点哈希（取不到用 [UNKNOWN]）
     * @param windowId    本次滚动的窗口 ID（取不到用 [UNKNOWN]）
     * @param lastNodeKey 上次的节点哈希
     * @param lastWindowId 上次的窗口 ID
     * @return true = 重复，应吞掉
     */
    fun isDuplicate(
        nodeKey: Int,
        windowId: Int,
        lastNodeKey: Int,
        lastWindowId: Int
    ): Boolean {
        // 【重要】两个都取不到（UNKNOWN）时不判定为重复。
        //
        // 否则：事件里没有 source 的滚动（系统合成事件、
        // 焦点在非节点区域）会被全部吞掉，用户滑了半天没声音。
        // 宁可重复播报，也不静默丢失。
        if (nodeKey == UNKNOWN && windowId == UNKNOWN) return false

        return nodeKey == lastNodeKey && windowId == lastWindowId
    }
}
