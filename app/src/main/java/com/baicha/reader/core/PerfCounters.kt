package com.baicha.reader.core

import android.os.SystemClock

/**
 * 性能计数 —— 建立性能基线所需的最小埋点
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要（审查报告「目标 D」）】
 * ══════════════════════════════════════════════════════════
 *
 * 项目做了 9 处缓存，但**没有任何基线数据** ——
 * 无法回答「缓存有没有用」「滑动一次要多久」。
 * 没有基线就无法判断优化是否有效，也无法发现性能回归。
 *
 * ══════════════════════════════════════════════════════════
 * 【设计取舍：为什么不用现成的性能框架】
 * ══════════════════════════════════════════════════════════
 *
 * 读屏运行在无障碍服务里，**每个触摸/滑动/焦点事件都在这条路径上**，
 * 测量本身的开销必须趋近于零：
 *
 *   - ❌ 不分配对象（无 boxing、无 String 拼接在热路径上）
 *   - ❌ 不用 System.nanoTime 的复杂换算 —— 只在需要时读
 *   - ✅ 用 Long 累加 + 原生 int 计数
 *   - ✅ 全部字段 @Volatile，读路径无锁
 *
 * 天坦与 TalkBack 都没有这类埋点（都靠 Log 手工排查），
 * 这是本项目独有的一层。
 *
 * ══════════════════════════════════════════════════════════
 * 【怎么用】
 * ══════════════════════════════════════════════════════════
 *
 * ```kotlin
 * val t0 = PerfCounters.begin()
 * ... 被测代码 ...
 * PerfCounters.endNav(t0)      // 记录一次导航耗时
 *
 * PerfCounters.cacheHit()      // 缓存命中
 * PerfCounters.cacheMiss()     // 缓存未命中
 *
 * PerfCounters.report()        // 取完整报告
 * ```
 */
object PerfCounters {

    // ==================== 导航耗时 ====================

    @Volatile
    var navCount = 0L
        private set

    @Volatile
    var navTotalMs = 0L
        private set

    @Volatile
    var navMaxMs = 0L
        private set

    /** 记录一次导航耗时（毫秒） */
    fun endNav(startUptime: Long) {
        val cost = SystemClock.uptimeMillis() - startUptime
        navCount++
        navTotalMs += cost
        if (cost > navMaxMs) navMaxMs = cost
    }

    /** 开始计时 —— 返回起点 */
    fun begin(): Long = SystemClock.uptimeMillis()

    /** 平均导航耗时 */
    fun avgNavMs(): Long = if (navCount == 0L) 0L else navTotalMs / navCount

    // ==================== 缓存命中 ====================

    @Volatile
    var cacheHits = 0L
        private set

    @Volatile
    var cacheMisses = 0L
        private set

    fun cacheHit() {
        cacheHits++
    }

    fun cacheMiss() {
        cacheMisses++
    }

    /** 命中率（0~1）；无访问时返回 0 */
    fun hitRate(): Double {
        val total = cacheHits + cacheMisses
        return if (total == 0L) 0.0 else cacheHits.toDouble() / total.toDouble()
    }

    // ==================== 事件吞吐 ====================

    @Volatile
    var eventCount = 0L
        private set

    @Volatile
    var droppedEvents = 0L
        private set

    fun eventHandled() {
        eventCount++
    }

    /**
     * 事件被丢弃（队列满 / 去重 / 节流）。
     *
     * 【为什么要单独统计】
     *   丢弃是**静默**的 —— 用户只会感觉「漏读了」。
     *   有计数才能判断「是读屏太快还是丢太多」。
     */
    fun eventDropped() {
        droppedEvents++
    }

    /** 丢弃率 */
    fun dropRate(): Double {
        val total = eventCount + droppedEvents
        return if (total == 0L) 0.0 else droppedEvents.toDouble() / total.toDouble()
    }

    // ==================== 报告 ====================

    /**
     * 生成可读报告。
     *
     * 【为什么用 String 而不是返回对象】
     *   只在需要时（日志/调试）调用，不在热路径上；
     *   返回字符串便于直接落日志、贴进文档当基线。
     */
    fun report(): String = buildString(256) {
        append("【性能基线】\n")
        append("导航：$navCount 次，平均 ${avgNavMs()}ms，最大 ${navMaxMs}ms\n")
        append(
            "缓存：命中 $cacheHits / 未命中 $cacheMisses，"
        )
        append("命中率 ${"%.1f".format(hitRate() * 100)}%\n")
        append("事件：处理 $eventCount / 丢弃 $droppedEvents，")
        append("丢弃率 ${"%.1f".format(dropRate() * 100)}%")
    }

    /**
     * 重置 —— 供测试与「开始新一轮测量」使用。
     *
     * 【为什么需要显式重置】
     *   服务是常驻的，计数器会一直累积；
     *   想测「刚才那一段」的性能必须有清零点。
     */
    fun reset() {
        navCount = 0
        navTotalMs = 0
        navMaxMs = 0
        cacheHits = 0
        cacheMisses = 0
        eventCount = 0
        droppedEvents = 0
    }
}
