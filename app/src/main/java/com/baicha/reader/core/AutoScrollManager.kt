package com.baicha.reader.core

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 自动滚动 —— 1:1 抄天坦 m6/C2121b.java（225 行）+ M6/h.java 的 c0()/b0()/f 类
 *
 * 【天坦的自动滚动机制】
 *
 * 触发（M6/h.java c0()）：
 *   c0(scrollNode, pivotNode, direction, targetType):
 *       if (正在滚动 || 不支持) return
 *       f = new f(scrollNode, pivotNode, direction, targetType)
 *       postDelayed(f, 25L)
 *
 * 重试（M6/h.java f 内部类）：
 *   run():
 *       if (b0(scrollNode, pivotNode, direction, targetType)) { a() }   // 成功 → 停
 *       else { postDelayed(this, 25L) }                                  // 失败 → 25ms 重试
 *
 * 执行（M6/h.java b0()）：
 *   b0(scrollNode, pivotNode, direction, targetType):
 *       traversal = C1427v.f(scrollNode)
 *       next = C1427v.p(traversal, pivotNode, direction, filter, false)
 *       if (next == null) return false
 *       scrolled = u(next)                       // 找滚动容器
 *       if (scrolled == scrollNode) {
 *           if (W(next, targetType)) { 聚焦; return true }
 *       }
 *       return false
 *
 * 事件驱动（C2121b.i()）：
 *   onAccessibilityEvent(TYPE_VIEW_SCROLLED):
 *       累加滚动量
 *       if (滚动结束) h()  // 继续处理
 *       else f().d()       // 110ms 后检查
 */
class AutoScrollManager(
    private val service: android.accessibilityservice.AccessibilityService,
    private val focusManager: FocusManager
) {

    companion object {
        private const val TAG = "AutoScroll"

        /** 重试间隔 —— 天坦 25ms */
        private const val RETRY_DELAY_MS = 25L

        /** 滚动完成检查延迟 —— 天坦 110ms */
        private const val SCROLL_SETTLE_MS = 110L

        /** 最大重试次数（防死循环） */
        private const val MAX_RETRY = 40
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 当前滚动任务 —— 天坦 f6013m */
    private var currentTask: ScrollTask? = null

    /** 是否正在滚动 —— 天坦 f6006f */
    @Volatile
    var isScrolling = false
        private set

    /** 滚动方向 —— 天坦 f6008h */
    private var lastScrollDirection = 0

    /** 滚动累计量 —— 天坦 f29251d/f29252e */
    private var scrollDeltaX = 0
    private var scrollDeltaY = 0

    /**
     * 滚动任务 —— 天坦 M6.h.f 内部类
     */
    private inner class ScrollTask(
        val scrollNode: AccessibilityNodeInfo,
        val pivotNode: AccessibilityNodeInfo,
        val direction: Int,
        val targetType: Int
    ) : Runnable {
        var retryCount = 0

        override fun run() {
            if (retryCount++ > MAX_RETRY) {
                ReaderLog.w(TAG, "autoScroll: max retry reached")
                cancel()
                return
            }

            if (tryFocusAfterScroll(scrollNode, pivotNode, direction, targetType)) {
                ReaderLog.d(TAG, "autoScroll: success after ${retryCount} retries")
                cancel()
            } else {
                handler.postDelayed(this, RETRY_DELAY_MS)
            }
        }

        fun cancel() {
            handler.removeCallbacks(this)
            currentTask = null
            isScrolling = false
        }
    }

    /**
     * 触发自动滚动 —— 1:1 抄天坦 M6/h.java c0()
     *
     * @param scrollNode 滚动容器
     * @param pivotNode 当前焦点（支点）
     * @param direction 方向（1 = NEXT，2 = PREVIOUS）
     * @param targetType 目标类型（0 = 普通，1 = 快速）
     */
    fun startAutoScroll(
        scrollNode: AccessibilityNodeInfo,
        pivotNode: AccessibilityNodeInfo,
        direction: Int,
        targetType: Int = 0
    ) {
        // 天坦：正在滚动就不重复触发
        if (isScrolling) {
            ReaderLog.d(TAG, "autoScroll: already scrolling")
            return
        }

        // 天坦：不支持滚动的容器直接返回
        if (!isScrollable(scrollNode)) {
            ReaderLog.d(TAG, "autoScroll: node not scrollable")
            return
        }

        currentTask?.cancel()

        val task = ScrollTask(
            AccessibilityNodeInfo.obtain(scrollNode),
            AccessibilityNodeInfo.obtain(pivotNode),
            direction,
            targetType
        )
        currentTask = task
        isScrolling = true
        lastScrollDirection = direction

        ReaderLog.d(TAG, "autoScroll: start direction=$direction")
        handler.postDelayed(task, RETRY_DELAY_MS)
    }

    /**
     * 滚动后尝试聚焦 —— 1:1 抄天坦 M6/h.java b0()
     *
     * @return true = 聚焦成功（停止重试）
     */
    private fun tryFocusAfterScroll(
        scrollNode: AccessibilityNodeInfo,
        pivotNode: AccessibilityNodeInfo,
        direction: Int,
        targetType: Int
    ): Boolean {
        return try {
            // ① 从滚动容器开始遍历
            val traversal = TatansTraversal(scrollNode)
            traversal.setFilter { ShouldFocusNode.shouldFocus(it) }

            // ② 找下一个
            val forward = direction == 1
            val next = if (forward) traversal.next(pivotNode) else traversal.previous(pivotNode)
            if (next == null) {
                ReaderLog.d(TAG, "autoScroll: no next node")
                return false
            }

            // ③ 确认还在同一个滚动容器里
            val scrolled = findScrollableAncestor(next)
            if (scrolled != scrollNode) {
                ReaderLog.d(TAG, "autoScroll: left scroll container")
                return false
            }

            // ④ 尝试聚焦
            val focused = focusManager.applyFocus(next)
            if (focused) {
                ReaderLog.d(TAG, "autoScroll: focused ${focusManager.desc(next)}")
            }
            focused
        } catch (e: Exception) {
            ReaderLog.w(TAG, "autoScroll: exception ${e.message}")
            false
        }
    }

    /**
     * 上一次滚动事件快照 —— 对应天坦 `M6/r.c f6112h`
     *
     * 【为什么需要】
     *   判断滚动方向不能只看单次事件：很多 App 的 `scrollDeltaY` 恒为 -1，
     *   必须靠「与上次快照对比」才能得出方向。
     */
    @Volatile
    private var lastSnapshot: ScrollEventSnapshot? = null

    /** 最近一次判定的滚动方向 */
    @Volatile
    var lastDetectedDirection: Int = ScrollDirectionDetector.UNKNOWN
        private set

    /**
     * 处理滚动事件 —— 对应天坦 `M6/r.e()` + `C2121b.i()`
     *
     * 【本实现相对原版的修正】
     *   原版只用 `event.scrollDeltaY` 推方向 —— 而该字段在很多 App 上
     *   恒为 -1（无效），导致方向判定永远失败、滚动音效速率固定。
     *
     *   现在改用 `ScrollDirectionDetector` 的**三级 fallback**：
     *     ① 与上次快照比 fromIndex/toIndex（最可靠）
     *     ② 退回比 scrollX/scrollY
     *     ③ 最后才用 delta 兜底
     *
     * @return 本次判定的滚动方向（供调用方播报/调音效）
     */
    fun onScrollEvent(event: android.view.accessibility.AccessibilityEvent): Int {
        val current = try {
            ScrollEventSnapshot(event)
        } catch (e: Throwable) {
            return ScrollDirectionDetector.UNKNOWN
        }

        // ① 判定方向（三级 fallback）
        val direction = ScrollDirectionDetector.detect(current, lastSnapshot)
        lastDetectedDirection = direction

        // ② 累加滚动量（用于"是否真的动了"的判定）
        val dx = current.deltaX
        val dy = current.deltaY
        if (dx != 0 && dx != -1) scrollDeltaX += dx
        if (dy != 0 && dy != -1) scrollDeltaY += dy

        // ③ 保存快照供下次比较（天坦 f6112h = cVar）
        lastSnapshot = current

        // ④ 延迟检查滚动是否结束
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, SCROLL_SETTLE_MS)

        return direction
    }

    private val settleRunnable = Runnable {
        val moved = scrollDeltaX != 0 || scrollDeltaY != 0
        scrollDeltaX = 0
        scrollDeltaY = 0
        if (moved) {
            ReaderLog.d(TAG, "滚动结束")
        }
    }

    /**
     * 重置滚动状态 —— 窗口变化时调用
     *
     * 上次快照跨窗口保留会导致方向判定错误（拿旧窗口的索引比新窗口的）。
     */
    fun resetScrollState() {
        lastSnapshot = null
        lastDetectedDirection = ScrollDirectionDetector.UNKNOWN
        scrollDeltaX = 0
        scrollDeltaY = 0
    }

    /**
     * 计算滚动音效速率 —— 对应天坦 `M6/r.e()` 的
     * `Math.pow(2.0, (速度/50) - 1)`
     *
     * @param event 滚动事件
     * @return 速率（0.5 ~ 2.0）
     */
    fun computeEarconRate(event: android.view.accessibility.AccessibilityEvent): Float {
        val delta = if (event.scrollDeltaY != 0 && event.scrollDeltaY != -1) {
            event.scrollDeltaY
        } else if (event.scrollDeltaX != 0 && event.scrollDeltaX != -1) {
            event.scrollDeltaX
        } else {
            0
        }
        if (delta == 0) return 1.0f
        return Math.pow(2.0, (delta / 50.0) - 1).toFloat().coerceIn(0.5f, 2.0f)
    }

    /**
     * 停止 —— 天坦 C2121b.d()
     */
    fun stop() {
        handler.removeCallbacks(settleRunnable)
        currentTask?.cancel()
        currentTask = null
        isScrolling = false
        // 注意：不清 lastSnapshot —— 停止自动滚动不等于窗口切换
    }

    // ==================== 工具 ====================

    /**
     * 找可滚动祖先 —— 天坦 M6/h.java u()
     */
    private fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 15) {
            if (isScrollable(cur)) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    /**
     * 是否可滚动 —— 天坦 AbstractC3688i / C1423r.y()
     */
    private fun isScrollable(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) return true
        return try {
            node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                        it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 判断节点是否「完全可见」—— 用于决定是否需要滚动
     *
     * 【修正】原实现只判「至少一部分在屏幕内」，边缘露出一像素也算可见，
     * 导致焦点停在半截的节点上不滚动。
     *
     * 天坦 M6/h.java 的实际语义是「节点完整落在可滚动区域可视范围内」。
     * 这里用上下各留 20px 内边距判完整性。
     */
    fun isNodeOnScreen(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false

        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val margin = 20

        // 要求完整可见（上下都不被裁切）
        return rect.top >= margin &&
                rect.bottom <= screenH - margin &&
                rect.left >= 0 &&
                rect.right <= screenW
    }

    /**
     * 焦点移动后检查 —— 如果焦点在屏幕外，触发滚动
     *
     * @return true = 已经可见（无需滚动）；false = 已触发滚动 / 无法滚动
     */
    fun ensureVisible(focusedNode: AccessibilityNodeInfo, direction: Int): Boolean {
        if (isNodeOnScreen(focusedNode)) {
            ReaderLog.d(TAG, "ensureVisible: fully visible")
            return true
        }

        val scrollable = findScrollableAncestor(focusedNode) ?: run {
            ReaderLog.d(TAG, "ensureVisible: no scrollable ancestor")
            return false
        }

        // 触发滚动
        startAutoScroll(scrollable, focusedNode, direction)
        return false
    }
}