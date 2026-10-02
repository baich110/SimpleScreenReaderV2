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

        /**
         * 重试间隔 —— 天坦 25ms
         *
         * 注意：新框架统一用 [ScrollFocusState.RETRY_DELAY_MS]，
         * 此常量仅供旧代码路径使用，保留是为兼容。
         */
        @Deprecated("改用 ScrollFocusState.RETRY_DELAY_MS")
        private const val RETRY_DELAY_MS = 25L

        /** 滚动完成检查延迟 —— 天坦 110ms */
        private const val SCROLL_SETTLE_MS = 110L

        /**
         * 最大重试次数
         *
         * 【修正】原为 40，天坦是 **100**（"i9 > 100"）。
         * 新框架统一用 [ScrollFocusState.MAX_ATTEMPT]。
         */
        @Deprecated("改用 ScrollFocusState.MAX_ATTEMPT")
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
    // ==================== 恢复回调 ====================

    /**
     * 滚动恢复回调 —— 对应天坦 `M6/h` 内部类 `a`
     *
     * 服务可注入以处理「滚动成功/失败」的后续动作
     * （例如失败后尝试跨窗口导航）。
     */
    @Volatile
    var recoveryCallback: ScrollRecoveryCallback? = null

    /** 最近一次恢复结果（诊断） */
    @Volatile
    var lastResult: ScrollRecoveryResult? = null
        private set

    /**
     * 滚动重试任务 —— 对应天坦 `M6/h` 内部类 `f`
     *
     * 天坦原逻辑：
     *   run() {
     *       if (b0(scrollNode, pivot, dir, type)) {
     *           a()                              // 成功 → 取消
     *       } else {
     *           handler.postDelayed(this, 25L)   // 失败 → 25ms 重试
     *       }
     *   }
     */
    private inner class ScrollTask(val state: ScrollFocusState) : Runnable {

        override fun run() {
            if (state.cancelled) return

            // ① 重试上限检查 —— 对应天坦 "i9 > 100"
            val attempt = state.nextAttempt()
            if (state.isExhausted()) {
                ReaderLog.w(TAG, "滚动重试超限（$attempt 次），判定失败")
                lastResult = ScrollRecoveryResult.Exhausted(attempt)
                onFailed()
                return
            }

            // ② 尝试聚焦（节点可空 → 缺失时视为未成功，继续重试）
            val scrollNode = state.scrollNode
            val anchorNode = state.anchorNode
            if (scrollNode == null || anchorNode == null) {
                lastResult = ScrollRecoveryResult.Retry
                handler.postDelayed(this, state.retryDelay())
                return
            }

            val boundsChanged = hasBoundsChanged(state)
            val focused = tryFocusAfterScroll(
                scrollNode, anchorNode, state.direction, state.targetType
            )

            if (focused != null) {
                ReaderLog.d(TAG, "滚动聚焦成功（第 $attempt 次，boundsChanged=$boundsChanged）")
                lastResult = ScrollRecoveryResult.Success(focused, attempt)
                onSucceeded(boundsChanged)
                return
            }

            // ③ 失败 → 按 targetType 决定的延迟重试
            lastResult = ScrollRecoveryResult.Retry
            handler.postDelayed(this, state.retryDelay())
        }

        fun cancel() {
            state.cancel()
            handler.removeCallbacks(this)
            if (currentTask === this) currentTask = null
            isScrolling = false
        }
    }

    /**
     * 成功回调 —— 对应天坦 `M6/h.a.e(boundsChanged)`
     *
     * 天坦逻辑：
     *   if (f6023b == 2) postDelayed(runnable, 500L) else runnable.run()
     *   → targetType == 2（慢速）时延迟 500ms 再收尾
     */
    private fun onSucceeded(boundsChanged: Boolean) {
        val task = currentTask ?: return
        val state = task.state
        task.cancel()

        val notify = Runnable {
            recoveryCallback?.onAutoScrolled(boundsChanged)
        }
        if (state.targetType == ScrollFocusState.TYPE_SLOW) {
            handler.postDelayed(notify, ScrollFocusState.SLOW_RETRY_DELAY_MS)
        } else {
            notify.run()
        }
    }

    /**
     * 失败回调 —— 对应天坦 `M6/h.a.d()` / `M6/h.H()`
     *
     * 天坦逻辑：
     *   if (assumeScrollSuccess) { 视为成功 }
     *   else { onAutoScrollFailed() }
     */
    private fun onFailed() {
        val task = currentTask ?: return
        val state = task.state
        task.cancel()

        val cb = recoveryCallback
        if (cb != null && cb.assumeScrollSuccess()) {
            ReaderLog.i(TAG, "假定滚动成功（assumeScrollSuccess）")
            cb.onAutoScrolled(false)
        } else {
            cb?.onAutoScrollFailed()
        }
    }

    /**
     * 边界是否变化 —— 对应天坦的 `boundsChanged` 判定
     *
     * 【为什么需要】
     *   判断「滚动是否真的生效」：如果锚点节点的边界没变，
     *   说明 App 没有响应滚动指令 → 应视为失败或继续重试。
     */
    private fun hasBoundsChanged(state: ScrollFocusState): Boolean {
        val node = state.anchorNode ?: return false
        return try {
            val now = android.graphics.Rect()
            node.getBoundsInScreen(now)
            Bounds(now) != state.boundsBeforeScroll
        } catch (e: Throwable) {
            // 节点已失效 → 视为"变了"（发生了某种变化）
            true
        }
    }

    /** 当前是否有滚动任务在跑 */
    val hasActiveTask: Boolean
        get() = currentTask != null

    /** 当前重试次数（诊断） */
    fun currentAttempt(): Int = currentTask?.state?.attempt ?: 0

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

        // 记录锚点滚动前的边界 —— 用于 boundsChanged 判定
        val boundsBefore = try {
            val r = android.graphics.Rect()
            pivotNode.getBoundsInScreen(r)
            Bounds(r)
        } catch (e: Throwable) {
            Bounds.EMPTY
        }

        val state = ScrollFocusState(
            scrollNode = AccessibilityNodeInfo.obtain(scrollNode),
            anchorNode = AccessibilityNodeInfo.obtain(pivotNode),
            direction = direction,
            targetType = targetType,
            isSingleColumnList = false,
            boundsBeforeScroll = boundsBefore
        )

        val task = ScrollTask(state)
        currentTask = task
        isScrolling = true
        lastScrollDirection = direction

        ReaderLog.d(TAG, "autoScroll: 启动 direction=$direction type=$targetType")
        handler.postDelayed(task, ScrollFocusState.RETRY_DELAY_MS)
    }

    /**
     * 滚动后尝试聚焦 —— 1:1 抄天坦 M6/h.java b0()
     *
     * @return 聚焦成功的节点；null = 未成功（应继续重试）
     */
    private fun tryFocusAfterScroll(
        scrollNode: AccessibilityNodeInfo,
        pivotNode: AccessibilityNodeInfo,
        direction: Int,
        targetType: Int
    ): AccessibilityNodeInfo? {
        return try {
            // ① 从滚动容器开始遍历
            val traversal = TatansTraversal(scrollNode)
            traversal.setFilter { ShouldFocusNode.shouldFocus(it) }

            // ② 找下一个
            val forward = direction == 1
            val next = if (forward) traversal.next(pivotNode) else traversal.previous(pivotNode)
            if (next == null) {
                ReaderLog.d(TAG, "滚动后未找到下一个节点")
                return null
            }

            // ③ 确认还在同一个滚动容器里（天坦：必须同一个容器）
            val scrolled = findScrollableAncestor(next)
            if (scrolled !== scrollNode) {
                ReaderLog.d(TAG, "已离开滚动容器")
                return null
            }

            // ④ 尝试聚焦
            val ok = focusManager.applyFocus(next)
            if (ok) {
                ReaderLog.d(TAG, "滚动后聚焦: ${focusManager.desc(next)}")
                next
            } else {
                null
            }
        } catch (e: Exception) {
            ReaderLog.w(TAG, "滚动聚焦异常: ${e.message}")
            null
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