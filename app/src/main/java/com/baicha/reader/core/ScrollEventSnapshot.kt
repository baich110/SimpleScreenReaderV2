package com.baicha.reader.core

import android.view.accessibility.AccessibilityEvent

/**
 * 滚动事件快照 —— 对应天坦 `M6/r.c`
 *
 * 【为什么需要快照】
 *
 * 判断「本次滚动是向上还是向下」不能只看单次事件 —— 因为：
 *   - 列表滚动事件的 `scrollDeltaY` 在很多 App 上恒为 -1（无效值）
 *   - 光看 `toIndex` 也无法判断方向（不知道上次在哪）
 *
 * 天坦的做法：**保存上一次事件的关键字段**，两次对比得出方向。
 * 这是一个三级 fallback 的判定链（见 [ScrollDirectionDetector]）。
 *
 * 【天坦字段（f6119a ~ f6125g）】
 *   fromIndex / toIndex / scrollX / scrollY / itemCount
 *   + AbstractC3674b.d() / e()（垂直/水平滚动量）
 *
 * 【本实现】
 *   同样的字段，但用 `data class` 语义（带 equals）便于比较，
 *   且提供 `hasValidData()` 用于判定"是否是真实滚动"。
 */
class ScrollEventSnapshot(
    /** 可见起始索引 */
    @JvmField val fromIndex: Int,
    /** 可见结束索引 */
    @JvmField val toIndex: Int,
    /** 水平滚动位置 */
    @JvmField val scrollX: Int,
    /** 垂直滚动位置 */
    @JvmField val scrollY: Int,
    /** 列表项总数 */
    @JvmField val itemCount: Int,
    /** 水平滚动增量 */
    @JvmField val deltaX: Int,
    /** 垂直滚动增量 */
    @JvmField val deltaY: Int,

    /**
     * 水平可滚动最大值 —— `AccessibilityEvent.getMaxScrollX()`
     *
     * 【为什么需要】自动滚动成功判定的**边缘检测**依赖它：
     * 天坦 `AutoScrollSuccessChecker.e()`（`:97~123`）
     * 用 `maxScrollY - scrollY < 72` 判定「已到底」，
     * 从而在「位置不再变化」的情况下**提前判成功** ——
     * 否则会误判失败并无限重试（焦点卡在列表末尾反复播报）。
     */
    @JvmField val maxScrollX: Int = 0,

    /** 垂直可滚动最大值 —— `AccessibilityEvent.getMaxScrollY()` */
    @JvmField val maxScrollY: Int = 0
) {

    /**
     * 从无障碍事件构造 —— 主构造器
     *
     * 注意：字段直接从事件读取，构造后不再依赖事件对象
     * （事件会被系统回收，不能持有引用）。
     */
    constructor(event: AccessibilityEvent) : this(
        fromIndex = event.fromIndex,
        toIndex = event.toIndex,
        scrollX = event.scrollX,
        scrollY = event.scrollY,
        itemCount = event.itemCount,
        deltaX = event.scrollDeltaX,
        deltaY = event.scrollDeltaY,
        maxScrollX = event.maxScrollX,
        maxScrollY = event.maxScrollY
    )

    /**
     * 是否是「真实滚动」—— 对应天坦 `M6/r.l()`
     *
     * 天坦判定：只要有任一有效字段就是真实滚动。
     * 全部为 -1 说明事件没带滚动信息（很多 App 的常见情况）。
     */
    fun hasValidData(): Boolean {
        if (fromIndex == -1 && toIndex == -1 && scrollX == -1 && scrollY == -1) {
            // 再看增量
            return !(deltaX == -1 && deltaY == -1)
        }
        return true
    }

    override fun toString(): String =
        "快照(from=$fromIndex to=$toIndex x=$scrollX y=$scrollY " +
                "count=$itemCount dx=$deltaX dy=$deltaY)"
}

/**
 * 滚动方向判定 —— 对应天坦 `M6/r.i()` + `j()`
 *
 * 【天坦的三级 fallback 判定链】
 *
 *   i(event):
 *       ① 有上次快照吗？没有 → 直接用增量判定（j）；旧版本 SDK → 返回 0
 *       ② 比索引：  fromIndex/toIndex 变大 → 方向 1（向后/向下）
 *                   变小 → 方向 2（向前/向上）
 *       ③ 比滚动量：scrollX/scrollY 变大 → 1；变小 → 2
 *       ④ 都相同 → 用增量判定（j）
 *
 *   j(event):
 *       dx == -1 && dy == -1  → 0（无效）
 *       dx > 0 || dy > 0      → 1
 *       dx < 0 || dy < 0      → 2
 *       否则                   → 0
 *
 * 【为什么需要三级】
 *   不同 App 暴露的信息不同：
 *     - 标准 ListView：有 fromIndex/toIndex
 *     - 自绘列表：只有 scrollY
 *     - 部分场景：只有 delta
 *   单用任何一级都会在部分 App 上失效。
 */
object ScrollDirectionDetector {

    const val UNKNOWN = 0
    const val FORWARD = 1
    const val BACKWARD = 2

    /**
     * 判定滚动方向 —— 对应天坦 `M6/r.i()`
     *
     * @param current 本次事件快照
     * @param previous 上次事件快照；null = 没有历史
     * @param useDeltaFallback 是否允许用增量兜底（对应天坦 `AbstractC3703x.g()` 版本门控）
     */
    fun detect(
        current: ScrollEventSnapshot,
        previous: ScrollEventSnapshot?,
        useDeltaFallback: Boolean = true
    ): Int {
        // ① 没有历史 → 只能靠增量
        if (previous == null) {
            return if (useDeltaFallback) detectByDelta(current) else UNKNOWN
        }

        // ② 优先比索引（最可靠）
        if (current.fromIndex > previous.fromIndex || current.toIndex > previous.toIndex) {
            return FORWARD
        }
        if (current.fromIndex < previous.fromIndex || current.toIndex < previous.toIndex) {
            return BACKWARD
        }

        // ③ 退回比滚动量
        if (current.scrollX > previous.scrollX || current.scrollY > previous.scrollY) {
            return FORWARD
        }
        if (current.scrollX < previous.scrollX || current.scrollY < previous.scrollY) {
            return BACKWARD
        }

        // ④ 都相同 → 用增量兜底
        return if (useDeltaFallback) detectByDelta(current) else UNKNOWN
    }

    /**
     * 按增量判定 —— 对应天坦 `M6/r.j()`
     */
    fun detectByDelta(snapshot: ScrollEventSnapshot): Int {
        val dx = snapshot.deltaX
        val dy = snapshot.deltaY
        if (dx == -1 && dy == -1) return UNKNOWN
        if (dx > 0 || dy > 0) return FORWARD
        return if (dx < 0 || dy < 0) BACKWARD else UNKNOWN
    }

    /** 方向名（诊断） */
    fun name(direction: Int): String = when (direction) {
        FORWARD -> "向后"
        BACKWARD -> "向前"
        else -> "未知"
    }
}

/**
 * 滚动位置播报 —— 对应天坦 `M6/r.f()`
 *
 * 【天坦的三种格式】
 *   ① 单点滚动（fromIndex == toIndex）："第 N 项，共 M 项"
 *   ② 区间滚动："第 N 到 K 项，共 M 项"
 *   ③ Pager（角色 16）："页码文本,第 N 项，共 M 项"
 */
object ScrollPositionFormatter {

    /**
     * 格式化滚动位置
     *
     * @param fromIndex 起始索引（-1 = 无效）
     * @param toIndex 结束索引
     * @param itemCount 总数
     * @param isPager 是否分页控件
     * @param pagerLabel 分页控件的页码文本（如"第 2 页"）
     * @return 播报文本；null = 无有效位置信息
     */
    fun format(
        fromIndex: Int,
        toIndex: Int,
        itemCount: Int,
        isPager: Boolean = false,
        pagerLabel: String? = null
    ): String? {
        if (fromIndex < 0 || itemCount <= 0) return null

        return if (isPager) {
            // 分页控件：页码文本 + 位置
            val pos = "第 ${fromIndex + 1} 项，共 $itemCount 项"
            if (pagerLabel.isNullOrEmpty()) pos else "$pagerLabel，$pos"
        } else if (fromIndex == toIndex || toIndex < 0 || toIndex + 1 > itemCount) {
            // 单点
            "第 ${fromIndex + 1} 项，共 $itemCount 项"
        } else {
            // 区间
            "第 ${fromIndex + 1} 到 ${toIndex + 1} 项，共 $itemCount 项"
        }
    }
}
