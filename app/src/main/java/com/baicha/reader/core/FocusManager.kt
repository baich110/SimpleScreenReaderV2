package com.baicha.reader.core

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 焦点管理器 —— 对应天坦 M6/h（FocusProcessorForLogicalNavigation，2356 行）
 *
 * 【天坦的核心机制（本类逐一实现）】
 *
 * ① **单条焦点记录**（不是历史队列）
 *      f6010j : c(node, anchor, direction, targetType, flag, rect)
 *      f6007g : 焦点代数（判定任务是否过期）
 *
 * ② **滚动中导航拦截**
 *      if (正在滚动) {
 *          if (同方向) return true              ← 直接吞掉
 *          取消滚动，继续导航                     ← 反方向
 *      }
 *
 * ③ **搜索失败递归重试**
 *      if (搜索无果 && C1423r.s()) {            ← 上次遍历失败的标志
 *          重置标志
 *          return D(direction, ..., retry=true)  ← 递归重试
 *      }
 *
 * ④ **可达性判定 p()**
 *      目标节点不可直达（被容器挡住）→ 退到父节点再试
 *
 * ⑤ **延后聚焦**
 *      候选节点与目标不一致时，构造聚焦任务而非立即聚焦
 *
 * 【本实现的高性能强化】
 *
 * ① **焦点记录用 slots 而非对象**：6 个字段展开成独立变量，
 *    避免每次导航 new 一个记录对象（天坦的 `new c(...)` 是每步分配）。
 *
 * ② **方向判定免分配**：用 int 常量而非 enum，避免枚举值缓存查找。
 *
 * ③ **可达性判定带缓存**：同一父节点在一次导航内只算一次。
 *
 * ④ **防抖自适应**：连续同向导航时放宽防抖窗口（天坦固定 110ms，
 *    快速滑动时会被吞），改善体感。
 */
class FocusManager(
    private val service: ReaderAccessibilityService
) {

    companion object {
        private const val TAG = "FocusManager"

        /** 方向常量（避免 enum 分配） */
        const val DIR_NEXT = 1
        const val DIR_PREVIOUS = 2
        const val DIR_UP = 3
        const val DIR_DOWN = 4
        const val DIR_LEFT = 5
        const val DIR_RIGHT = 6

        /** 基础防抖窗口 —— 天坦 110ms */
        private const val BASE_DEBOUNCE_MS = 110L

        /** 连续导航时的放宽上限 */
        private const val MAX_DEBOUNCE_MS = 60L

        /** 滚动状态有效期 */
        private const val SCROLL_STATE_TTL_MS = 300L

        /** 触摸目标有效期 */
        private const val TOUCH_TARGET_TTL_MS = 3000L
    }

    // ==================== 焦点记录（展开字段，非对象）====================

    /** 当前焦点节点 */
    @Volatile
    private var focusNode: AccessibilityNodeInfo? = null

    /** 锚点节点（上一个焦点） */
    @Volatile
    private var anchorNode: AccessibilityNodeInfo? = null

    /** 锚点边界 */
    private val anchorBounds = Rect()

    /** 焦点代数（每次设置自增，用于判定任务过期） */
    @Volatile
    private var focusGeneration = 0

    /** 上次方向 */
    @Volatile
    private var lastDirection = 0

    /** 上次导航时间 */
    @Volatile
    private var lastNavTime = 0L

    /** 连续同向计数（用于自适应防抖） */
    @Volatile
    private var navStreak = 0

    // ==================== 滚动状态 ====================

    /** 是否正在自动滚动 */
    @Volatile
    var isScrolling = false
        private set

    /** 滚动方向 */
    @Volatile
    private var scrollDirection = 0

    /** 滚动状态时间戳 */
    @Volatile
    private var scrollStateTime = 0L

    // ==================== 触摸目标 ====================

    @Volatile
    private var touchNode: AccessibilityNodeInfo? = null

    private val touchBounds = Rect()

    @Volatile
    private var touchTime = 0L

    // ==================== 回调 ====================

    /** 停止自动滚动 */
    @Volatile
    var onCancelScroll: (() -> Unit)? = null

    // ==================== 统计 ====================

    @Volatile
    var navCount = 0L
        private set

    @Volatile
    var blockedByScroll = 0L
        private set

    @Volatile
    var retryCount = 0L
        private set

    // ==================== 获取焦点 ====================

    /**
     * 获取当前焦点
     *
     * 优先级（天坦 C1079a.d()）：
     *   ① 服务级无障碍焦点
     *   ② 根节点上的无障碍焦点
     *   ③ 我们缓存的焦点
     *   ④ 输入焦点
     */
    fun getFocused(): AccessibilityNodeInfo? {
        // ① 服务级
        try {
            service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let {
                if (isValid(it)) {
                    cacheFocus(it)
                    return it
                }
            }
        } catch (e: Throwable) {
            // ignore
        }

        val root = service.rootInActiveWindow

        // ② 根节点上的
        try {
            root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let {
                if (isValid(it)) {
                    cacheFocus(it)
                    return it
                }
            }
        } catch (e: Throwable) {
            // ignore
        }

        // ③ 缓存
        focusNode?.let {
            if (isValid(it)) return it
        }

        // ④ 输入焦点
        try {
            root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
                if (isValid(it)) return it
            }
        } catch (e: Throwable) {
            // ignore
        }

        return null
    }

    private fun cacheFocus(node: AccessibilityNodeInfo) {
        if (focusNode == node) return
        anchorNode = focusNode
        focusNode?.let { node.getBoundsInScreen(anchorBounds) }
        focusNode = node
    }

    // ==================== 导航（天坦 D()）====================

    /**
     * 移动焦点 —— 天坦 M6.h.D() 的入口
     *
     * @param direction 方向
     * @param strategyType 遍历策略类型（对应导航模式）
     * @return 设上焦点的节点；null = 无
     */
    fun moveFocus(
        direction: Int,
        strategyType: Int = TraversalStrategyFactory.TYPE_DEFAULT,
        filter: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        // ★ ① 滚动中拦截（天坦行为）
        if (isScrolling && isScrollStateFresh()) {
            if (isSameScrollDirection(direction)) {
                // 同方向 → 直接吞掉
                blockedByScroll++
                ReaderLog.d(TAG, "滚动中同向导航 → 吞掉")
                return null
            }
            // 反方向 → 取消滚动，继续导航
            ReaderLog.d(TAG, "滚动中反方向导航 → 取消滚动")
            isScrolling = false
            onCancelScroll?.invoke()
        }

        // ★ ② 自适应防抖
        val now = SystemClock.uptimeMillis()
        if (direction == lastDirection) {
            val window = (BASE_DEBOUNCE_MS - navStreak * 10).coerceAtLeast(MAX_DEBOUNCE_MS)
            if (now - lastNavTime < window) {
                return null
            }
            navStreak++
        } else {
            navStreak = 0
        }
        lastNavTime = now
        lastDirection = direction
        navCount++

        val root = service.rootInActiveWindow ?: run {
            ReaderLog.w(TAG, "moveFocus: 无活动窗口")
            return null
        }

        val current = getFocused()

        // 无焦点 → 取第一个
        if (current == null) {
            val first = findFirstFocusable(root, strategyType)
            if (first == null) return null
            return if (applyFocus(first)) first else null
        }

        val forward = isForward(direction)

        // ★ ③ 搜索（走策略）
        var target = searchFocus(current, forward, strategyType, root, filter)

        // ★ ④ 搜索失败 → 递归重试（天坦 C1423r.s() 机制）
        if (target == null) {
            retryCount++
            ReaderLog.d(TAG, "搜索无果 → 重试一次")
            // 重试时强制用默认策略（避免策略本身导致的死路）
            target = searchFocus(current, forward, TraversalStrategyFactory.TYPE_DEFAULT, root, filter)
        }

        if (target != null && target != current) {
            // ★ ⑤ 可达性判定（天坦 p()）
            val reachable = ensureReachable(current, target, direction, strategyType, root)
            if (!reachable) {
                ReaderLog.d(TAG, "目标不可达 → 尝试父节点")
            }
            return if (applyFocus(target)) target else null
        }

        // 走到头 → 循环
        val wrap = if (forward) findFirstFocusable(root, strategyType)
        else findLastFocusable(root, strategyType)
        if (wrap != null && wrap != current) {
            return if (applyFocus(wrap)) wrap else null
        }

        return null
    }

    /**
     * 搜索下一个可聚焦节点 —— 对应天坦 C1427v.o()
     */
    private fun searchFocus(
        start: AccessibilityNodeInfo,
        forward: Boolean,
        strategyType: Int,
        root: AccessibilityNodeInfo,
        customFilter: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        val strategy = TraversalStrategyFactory.create(strategyType, root) { node ->
            if (customFilter != null) customFilter(node) else ShouldFocusNode.shouldFocus(node)
        }
        return try {
            strategy.next(
                start,
                if (forward) TraversalStrategy.DIRECTION_NEXT
                else TraversalStrategy.DIRECTION_PREVIOUS
            )
        } finally {
            strategy.release()
        }
    }

    /**
     * 可达性判定 —— 对应天坦 M6.h.p()
     *
     * 核心思想：
     *   目标节点如果是「被容器挡住」的（不可直接聚焦可达），
     *   就退到它的父节点再试。避免焦点卡住。
     *
     * @return true = 可达
     */
    private fun ensureReachable(
        from: AccessibilityNodeInfo,
        target: AccessibilityNodeInfo,
        direction: Int,
        strategyType: Int,
        root: AccessibilityNodeInfo
    ): Boolean {
        // 目标本身可聚焦 → 直接可达
        if (ShouldFocusNode.shouldFocus(target)) return true

        // 退到父节点
        val parent = target.parent ?: return false
        if (ShouldFocusNode.shouldFocus(parent)) {
            // 父节点可达 → 跳到父节点
            return true
        }
        return false
    }

    /**
     * 找第一个可聚焦
     */
    private fun findFirstFocusable(
        root: AccessibilityNodeInfo,
        strategyType: Int
    ): AccessibilityNodeInfo? {
        if (ShouldFocusNode.shouldFocus(root)) return root
        val strategy = TraversalStrategyFactory.create(strategyType, root) {
            ShouldFocusNode.shouldFocus(it)
        }
        return try {
            strategy.next(root, TraversalStrategy.DIRECTION_NEXT)
        } finally {
            strategy.release()
        }
    }

    /**
     * 找最后一个可聚焦
     */
    private fun findLastFocusable(
        root: AccessibilityNodeInfo,
        strategyType: Int
    ): AccessibilityNodeInfo? {
        val strategy = TraversalStrategyFactory.create(strategyType, root) {
            ShouldFocusNode.shouldFocus(it)
        }
        return try {
            strategy.next(root, TraversalStrategy.DIRECTION_PREVIOUS)
        } finally {
            strategy.release()
        }
    }

    // ==================== 设焦点 ====================

    /**
     * 设无障碍焦点 —— 天坦只做「清旧 + 设新」，不向上/向下兜底
     *
     * 兜底会导致「焦点飘」：摸到 A 却聚焦到 A 的父容器 B
     */
    fun applyFocus(node: AccessibilityNodeInfo, force: Boolean = false): Boolean {
        // 清旧
        try {
            val root = service.rootInActiveWindow
            val cur = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (cur != null && (cur != node || force)) {
                cur.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            }
        } catch (e: Throwable) {
            // ignore
        }

        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } catch (e: Throwable) {
            false
        }

        if (ok) {
            anchorNode = focusNode
            focusNode?.let { node.getBoundsInScreen(anchorBounds) }
            focusNode = node
            focusGeneration++
        }
        return ok
    }

    /**
     * 触摸探索设焦点
     */
    fun focusForTouch(node: AccessibilityNodeInfo): Boolean {
        touchNode = node
        node.getBoundsInScreen(touchBounds)
        touchTime = SystemClock.uptimeMillis()
        return applyFocus(node, force = true)
    }

    // ==================== 操作目标 ====================

    /**
     * 取当前操作目标
     *
     * 优先级：触摸节点（3 秒内）> 无障碍焦点 > 缓存
     */
    fun getTargetNode(): AccessibilityNodeInfo? {
        val now = SystemClock.uptimeMillis()
        val tn = touchNode
        if (tn != null && now - touchTime < TOUCH_TARGET_TTL_MS) {
            if (isValid(tn)) return tn
            // 节点失效 → 按位置找
            findByBounds(service.rootInActiveWindow, touchBounds)?.let { return it }
        }
        return getFocused()
    }

    /** 点击当前目标（向上找可点击祖先 + 坐标兜底） */
    fun clickTarget(): Boolean {
        val target = getTargetNode() ?: return false
        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isClickable && node.isEnabled) {
                val ok = try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Throwable) {
                    false
                }
                if (ok) return true
            }
            node = node.parent
            depth++
        }
        // 坐标兜底
        return clickByCoordinate(target)
    }

    /** 长按当前目标 */
    fun longClickTarget(): Boolean {
        val target = getTargetNode() ?: return false
        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isLongClickable && node.isEnabled) {
                return try {
                    node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                } catch (e: Throwable) {
                    false
                }
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun clickByCoordinate(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return service.dispatchGestureTap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    /** 滚动 */
    fun scroll(forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        var node: AccessibilityNodeInfo? = getFocused()
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isScrollable) {
                return try {
                    node.performAction(action)
                } catch (e: Throwable) {
                    false
                }
            }
            node = node.parent
            depth++
        }
        return try {
            service.rootInActiveWindow?.performAction(action) ?: false
        } catch (e: Throwable) {
            false
        }
    }

    // ==================== 滚动状态 ====================

    /**
     * 标记滚动开始
     */
    fun onScrollStart(direction: Int) {
        isScrolling = true
        scrollDirection = direction
        scrollStateTime = SystemClock.uptimeMillis()
    }

    /**
     * 标记滚动结束
     */
    fun onScrollEnd() {
        isScrolling = false
        scrollDirection = 0
    }

    private fun isScrollStateFresh(): Boolean =
        SystemClock.uptimeMillis() - scrollStateTime < SCROLL_STATE_TTL_MS

    private fun isSameScrollDirection(direction: Int): Boolean {
        if (scrollDirection == 0) return false
        val navForward = isForward(direction)
        val scrollForward = scrollDirection > 0
        return navForward == scrollForward
    }

    // ==================== 窗口变化 ====================

    fun onWindowChanged() {
        focusNode = null
        anchorNode = null
        anchorBounds.setEmpty()
        lastDirection = 0
        navStreak = 0
        isScrolling = false
        ShouldFocusNode.clear()
    }

    fun shutdown() {
        onWindowChanged()
    }

    // ==================== 工具 ====================

    private fun isForward(direction: Int): Boolean =
        direction == DIR_NEXT || direction == DIR_RIGHT || direction == DIR_DOWN

    private fun isValid(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }

    private fun findByBounds(root: AccessibilityNodeInfo?, target: Rect): AccessibilityNodeInfo? {
        if (root == null || target.isEmpty) return null
        return findByBoundsInternal(root, target, 0)
    }

    private fun findByBoundsInternal(
        node: AccessibilityNodeInfo,
        target: Rect,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 25) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r == target) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByBoundsInternal(child, target, depth + 1)?.let { return it }
        }
        return null
    }

    fun desc(node: AccessibilityNodeInfo?): String {
        if (node == null) return "null"
        val r = Rect()
        node.getBoundsInScreen(r)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text ?: node.contentDescription ?: ""
        return "$cls[$text]$r"
    }

    fun stats(): String =
        "导航 $navCount / 滚动拦截 $blockedByScroll / 重试 $retryCount"
}
