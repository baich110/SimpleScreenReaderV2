package com.baicha.reader.core

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 焦点控制器 —— 走天坦 d7/C1423r 遍历 + w7/C3678d 可聚焦判定
 *
 * 【统一架构（照抄天坦，不混 TalkBack）】
 *
 *   FocusController.moveFocus(direction)
 *        ↓
 *   TatansTraversal.next()/previous()     ← d7/C1423r.v()/y()
 *        ├─ 遍历内部判可见性 X(node)
 *        └─ filter = ShouldFocusNode.shouldFocus   ← w7/C3678d.r(node, false)
 *
 * 【关键修正 2026-10-02】
 *   ① filter 统一：遍历内部和 searchFocus 循环**必须用同一个判定**。
 *      之前 TatansTraversal 内置 isNodeFocusable（只判可见性）当 filter，
 *      而 searchFocus 循环用 shouldFocusNode → 两套集合不一致 → 焦点乱跳。
 *      现统一注入 ShouldFocusNode::shouldFocus。
 *
 *   ② 索引顺序一致：排序列表和 getChild(i) 必须在同一顺序空间定位。
 *      已下沉到 TatansTraversal 内部用 I() 双分支统一处理。
 *
 *   ③ 去掉重复的 searchFocus 循环：天坦的 v()/y() 本身就是完整的
 *      "找下一个可聚焦"，不需要外层再包一层 seen 循环去重
 *      （外层 seen 用内容 hash，会把合法节点误判为重复）。
 */
class FocusController(private val service: ReaderAccessibilityService) {

    companion object {
        private const val TAG = "Focus"

        /** 滑动浏览防抖（天坦 110ms） */
        private const val NAV_DEBOUNCE_MS = 110L

        /** 触摸目标有效期（触摸后 3 秒内双击，优先操作触摸到的节点） */
        private const val TOUCH_TARGET_TIMEOUT_MS = 3000L
    }

    /** 焦点历史（抄 TalkBack AccessibilityFocusActionHistory 的语义，非架构） */
    val history = FocusHistory()

    private var lastNavTime = 0L
    private var lastNavDirection: Direction? = null

    /** 同一方向连续跨越的步数（用于防抖放宽） */
    private var navStreak = 0

    // ==================== 获取焦点 ====================

    /**
     * 获取当前焦点
     *
     * 优先级：
     * ① 服务级无障碍焦点（findFocus FOCUS_ACCESSIBILITY）
     * ② 根节点上的无障碍焦点
     * ③ 焦点历史
     * ④ 输入焦点
     */
    fun getFocused(): AccessibilityNodeInfo? {
        try {
            service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let {
                if (isValid(it)) {
                    history.record(it, FocusHistory.Source.PROGRAMMATIC)
                    return it
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        val root = service.rootInActiveWindow

        root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let {
            if (isValid(it)) {
                history.record(it, FocusHistory.Source.PROGRAMMATIC)
                return it
            }
        }

        history.getLastFocused()?.let {
            if (isValid(it)) return it
        }

        root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (isValid(it)) return it
        }

        return null
    }

    // ==================== 滑动浏览 ====================

    /**
     * 滑动移动焦点
     *
     * @return 实际设上焦点的节点（null = 没找到/设失败）
     */
    fun moveFocus(direction: Direction): AccessibilityNodeInfo? {
        val now = SystemClock.uptimeMillis()

        // 防抖：同方向 110ms 内重复调用丢弃；换方向立即放行
        if (direction == lastNavDirection && now - lastNavTime < NAV_DEBOUNCE_MS) {
            navStreak++
            ReaderLog.d(TAG, "moveFocus debounced: $direction (streak=$navStreak)")
            return null
        }
        lastNavTime = now
        if (direction != lastNavDirection) navStreak = 0
        lastNavDirection = direction

        val root = service.rootInActiveWindow ?: run {
            ReaderLog.w(TAG, "moveFocus: no root")
            return null
        }

        val current = getFocused()

        // 没焦点 → 找树里第一个可聚焦节点
        if (current == null) {
            val first = findFirstFocusable(root)
            if (first == null) {
                ReaderLog.w(TAG, "moveFocus: no focusable node found")
                return null
            }
            ReaderLog.i(TAG, "moveFocus $direction: no current, first=${desc(first)}")
            return if (focusNode(first, FocusHistory.Source.LOGICAL_NAVIGATION)) first else null
        }

        val forward = direction == Direction.NEXT ||
                direction == Direction.RIGHT ||
                direction == Direction.DOWN

        // 天坦 C1423r：一次调用就是完整的一步
        val target = searchFocus(current, forward)

        if (target != null && target != current) {
            ReaderLog.i(TAG, "moveFocus $direction: ${desc(current)} -> ${desc(target)}")
            return if (focusNode(target, FocusHistory.Source.LOGICAL_NAVIGATION)) target else null
        }

        // 走到头了 → 焦点循环（回到另一端）
        val wrap = if (forward) findFirstFocusable(root) else findLastFocusable(root)
        if (wrap != null && wrap != current) {
            ReaderLog.i(TAG, "moveFocus $direction: wrap -> ${desc(wrap)}")
            return if (focusNode(wrap, FocusHistory.Source.LOGICAL_NAVIGATION)) wrap else null
        }

        ReaderLog.i(TAG, "moveFocus $direction: at edge")
        return null
    }

    /**
     * 焦点搜索 —— 对应天坦 C1423r.v(node) / y(node)
     *
     * 【关键】filter 与遍历内部判定统一为 ShouldFocusNode::shouldFocus。
     */
    private fun searchFocus(start: AccessibilityNodeInfo, forward: Boolean): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null

        val traversal = TatansTraversal(root)
        // ★ 统一 filter：与 ShouldFocusNode.shouldFocus 完全一致
        traversal.setFilter { ShouldFocusNode.shouldFocus(it) }

        val result = if (forward) traversal.next(start) else traversal.previous(start)

        if (result == null) {
            ReaderLog.d(TAG, "searchFocus: null (forward=$forward) from ${desc(start)}")
        }
        return result
    }

    /**
     * 找第一个可聚焦节点
     */
    private fun findFirstFocusable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val traversal = TatansTraversal(root)
        traversal.setFilter { ShouldFocusNode.shouldFocus(it) }

        // 先把 root 当候选（天坦 v() 从 root 向下）
        if (ShouldFocusNode.shouldFocus(root)) return root
        return traversal.next(root)
    }

    /**
     * 找最后一个可聚焦节点
     */
    private fun findLastFocusable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val traversal = TatansTraversal(root)
        traversal.setFilter { ShouldFocusNode.shouldFocus(it) }

        var current: AccessibilityNodeInfo? = root
        var last: AccessibilityNodeInfo? = null
        var guard = 0
        while (current != null && guard++ < 2000) {
            val prev = traversal.previous(current) ?: break
            if (prev == current) break
            current = prev
            last = prev
        }
        return last
    }

    // ==================== 焦点设置 ====================

    /**
     * 设焦点
     *
     * 天坦只做「清旧焦点 + 设新焦点」，失败就失败，不做向上/向下兜底
     * （兜底会导致「焦点飘」：摸到 A 却聚焦到 A 的父容器 B）
     */
    fun focusNode(
        node: AccessibilityNodeInfo,
        source: Int = FocusHistory.Source.PROGRAMMATIC,
        forceRefocus: Boolean = false
    ): Boolean {
        // ① 清旧焦点 —— forceRefocus 时无条件清（触摸换了节点必须重设）
        try {
            val root = service.rootInActiveWindow
            val cur = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (cur != null && (cur != node || forceRefocus)) {
                cur.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            }
        } catch (e: Exception) {
            // ignore
        }

        // ② 设新焦点
        if (node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
            history.record(node, source, forceRefocus)
            ReaderLog.d(TAG, "focus ok -> ${desc(node)} force=$forceRefocus")
            return true
        }

        ReaderLog.d(TAG, "focus failed -> ${desc(node)}")
        return false
    }

    /**
     * 触摸探索设焦点
     *
     * 天坦：如果触摸的节点不是「上次聚焦的节点」，强制重新聚焦
     */
    fun focusForTouch(node: AccessibilityNodeInfo): Boolean {
        // 记录触摸位置（双击时优先用这个）
        touchNode = node
        touchBounds = Rect().also { node.getBoundsInScreen(it) }
        touchTime = SystemClock.uptimeMillis()

        // ★ 修正：forceRefocus 真正传给 focusNode
        val forceRefocus = !history.lastFocusedEquals(node)
        return focusNode(node, FocusHistory.Source.TOUCH_EXPLORATION, forceRefocus)
    }

    /** 最近触摸到的节点（双击优先用） */
    @Volatile
    private var touchNode: AccessibilityNodeInfo? = null

    @Volatile
    private var touchBounds: Rect = Rect()

    @Volatile
    private var touchTime = 0L

    /**
     * 获取「当前该操作的目标节点」
     *
     * 优先级：
     * ① 最近触摸的节点（3 秒内）—— 用户刚摸过，意图明确
     * ② 系统无障碍焦点
     * ③ 焦点历史
     */
    fun getTargetNode(): AccessibilityNodeInfo? {
        val now = SystemClock.uptimeMillis()

        val tn = touchNode
        if (tn != null && now - touchTime < TOUCH_TARGET_TIMEOUT_MS) {
            if (isValid(tn)) return tn
            val byPos = findNodeByBounds(service.rootInActiveWindow, touchBounds)
            if (byPos != null) return byPos
        }

        return getFocused()
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo?, target: Rect): AccessibilityNodeInfo? {
        if (root == null || target.isEmpty) return null
        return findNodeByBoundsInternal(root, target, 0)
    }

    private fun findNodeByBoundsInternal(
        node: AccessibilityNodeInfo,
        target: Rect,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 25) return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r == target) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByBoundsInternal(child, target, depth + 1)
            if (found != null) return found
        }
        return null
    }

    // ==================== 可聚焦判定 ====================

    /**
     * 可聚焦判定 —— 1:1 抄天坦 w7/C3678d.r(node, false)
     */
    fun shouldFocusNode(node: AccessibilityNodeInfo): Boolean =
        ShouldFocusNode.shouldFocus(node)

    // ==================== 点击 / 长按 ====================

    fun clickFocused(): Boolean {
        val focused = getTargetNode() ?: return false
        var node: AccessibilityNodeInfo? = focused
        var depth = 0

        // ① 优先 ACTION_CLICK
        while (node != null && depth < 10) {
            if (node.isClickable && node.isEnabled) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ReaderLog.i(TAG, "click -> ${desc(node)} ok=$ok")
                if (ok) return true
            }
            node = node.parent
            depth++
        }

        // ② 兜底坐标点击
        ReaderLog.i(TAG, "click by coordinate -> ${desc(focused)}")
        return clickByCoordinate(focused)
    }

    fun longClickFocused(): Boolean {
        val focused = getTargetNode() ?: return false
        var node: AccessibilityNodeInfo? = focused
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isLongClickable && node.isEnabled) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                ReaderLog.i(TAG, "longClick -> ${desc(node)} ok=$ok")
                return ok
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun clickByCoordinate(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        return service.dispatchGestureTap(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    /** 滚动 */
    fun scroll(forward: Boolean): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        var node: AccessibilityNodeInfo? = getFocused()
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isScrollable) return node.performAction(action)
            node = node.parent
            depth++
        }
        val root = service.rootInActiveWindow ?: return false
        return root.performAction(action)
    }

    // ==================== 工具 ====================

    private fun isValid(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Exception) {
        false
    }

    fun desc(node: AccessibilityNodeInfo): String {
        val r = Rect().also { node.getBoundsInScreen(it) }
        return "${node.className?.toString()?.substringAfterLast('.')}" +
                "[${node.text ?: node.contentDescription ?: ""}]$r"
    }

    /** 窗口变化 */
    fun onWindowChanged() {
        history.onWindowChanged()
        lastNavDirection = null
        navStreak = 0
        ShouldFocusNode.clear()
    }

    /** 服务销毁 */
    fun shutdown() {
        history.clear()
        ShouldFocusNode.clear()
    }

    enum class Direction {
        NEXT, PREVIOUS, UP, DOWN, LEFT, RIGHT
    }
}
