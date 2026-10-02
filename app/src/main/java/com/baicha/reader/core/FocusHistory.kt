package com.baicha.reader.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 焦点历史 —— 抄 TalkBack AccessibilityFocusActionHistory
 *
 * 作用：记录「谁设了焦点、什么时候、从哪来」，解决：
 * ① 触摸焦点和滑动焦点互通（都记在同一份历史里）
 * ② 焦点事件去重（判断事件是不是自己触发的）
 * ③ 窗口切换后恢复焦点
 *
 * 参考源码：
 *   talkback/.../focusmanagement/record/AccessibilityFocusActionHistory.java
 *   talkback/.../focusmanagement/record/FocusActionRecord.java
 *   talkback/.../focusmanagement/record/FocusActionInfo.java
 */
class FocusHistory {

    companion object {
        private const val TAG = "FocusHistory"

        /** 历史记录最大条数 */
        private const val MAX_HISTORY = 30

        /** 焦点事件匹配的时间容差（TalkBack 用 1000ms） */
        private const val MATCH_TIMEOUT_MS = 1000L
    }

    /** 焦点来源（抄 TalkBack FocusActionInfo.sourceAction） */
    object Source {
        const val UNKNOWN = 0
        const val LOGICAL_NAVIGATION = 1   // 滑动浏览
        const val TOUCH_EXPLORATION = 2    // 触摸探索
        const val SCREEN_STATE_CHANGE = 3  // 屏幕/窗口切换
        const val PROGRAMMATIC = 4         // 程序设置
    }

    /** 一条焦点记录 */
    data class Record(
        val node: AccessibilityNodeInfo,
        val source: Int,
        val time: Long,
        val bounds: Rect,
        val forceRefocus: Boolean = false
    )

    /** 历史队列（最新在前） */
    private val history = ArrayDeque<Record>(MAX_HISTORY)

    /** 上次聚焦的节点（用于快速比较） */
    @Volatile
    private var lastFocusedNode: AccessibilityNodeInfo? = null

    @Volatile
    private var lastFocusedBounds: Rect = Rect()

    /** 窗口切换时缓存待恢复的焦点 */
    private var cachedNodeToRestore: AccessibilityNodeInfo? = null

    // ==================== 写入 ====================

    /**
     * 记录一次焦点动作（抄 TalkBack onAccessibilityFocusAction）
     */
    fun record(node: AccessibilityNodeInfo, source: Int, forceRefocus: Boolean = false) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val rec = Record(node, source, android.os.SystemClock.uptimeMillis(), bounds, forceRefocus)

        synchronized(history) {
            history.addFirst(rec)
            while (history.size > MAX_HISTORY) history.removeLast()
        }

        lastFocusedNode = node
        lastFocusedBounds = bounds

        ReaderLog.d(TAG, "record: source=$source text=${node.text} bounds=$bounds")
    }

    // ==================== 读取 ====================

    /** 上次聚焦的节点 */
    fun getLastFocused(): AccessibilityNodeInfo? = lastFocusedNode

    /** 上次聚焦的边界 */
    fun getLastFocusedBounds(): Rect = Rect(lastFocusedBounds)

    /** 最近一条记录 */
    fun getLastRecord(): Record? {
        synchronized(history) { return history.firstOrNull() }
    }

    /** 最近一条指定来源的记录 */
    fun getLastRecordFrom(source: Int): Record? {
        synchronized(history) {
            return history.firstOrNull { it.source == source }
        }
    }

    /**
     * 上次聚焦的节点是否就是这个节点（抄 lastAccessibilityFocusedNodeEquals）
     * 用于触摸探索时判断要不要强制重设焦点
     */
    fun lastFocusedEquals(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val last = lastFocusedNode ?: return false
        if (last == node) return true
        // 位置匹配兜底
        val a = Rect().also { last.getBoundsInScreen(it) }
        val b = Rect().also { node.getBoundsInScreen(it) }
        return a == b
    }

    /**
     * 判断某个焦点事件是不是自己触发的（抄 matchFocusActionRecordFromEvent）
     * 用于去重：自己设焦点触发的事件不该再播报
     *
     * 【修正】原实现只要「1 秒内有任意记录位置相同」就判定为自己触发，
     * 不区分来源 → 用户手指摸到同一位置（或页面重绘后边界相同）时，
     * 焦点事件被误吞 → 触摸探索不播报。
     *
     * 现在：只有 **来源是程序/遍历设置** 的记录才算"自己设的"；
     * 触摸探索来源的记录不算（那是用户操作，必须播报）。
     */
    fun isEventFromSelf(event: android.view.accessibility.AccessibilityEvent): Boolean {
        val node = event.source ?: return false
        val now = android.os.SystemClock.uptimeMillis()

        synchronized(history) {
            for (rec in history) {
                if (now - rec.time > MATCH_TIMEOUT_MS) break

                // 触摸探索的来源不算"自己设的"——用户操作的必须播报
                if (rec.source == Source.TOUCH_EXPLORATION) continue

                if (rec.node == node) return true

                // 位置匹配（同来源限定）
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r == rec.bounds) return true
            }
        }
        return false
    }

    // ==================== 窗口切换恢复 ====================

    /** 缓存当前焦点（窗口切换前调） */
    fun cacheCurrentFocus() {
        cachedNodeToRestore = lastFocusedNode
        ReaderLog.d(TAG, "cacheCurrentFocus: ${cachedNodeToRestore?.text}")
    }

    /** 取出缓存的焦点（窗口切换后调） */
    fun popCachedFocus(): AccessibilityNodeInfo? {
        val n = cachedNodeToRestore
        cachedNodeToRestore = null
        return n?.takeIf { isValid(it) }
    }

    // ==================== 清理 ====================

    /** 清空（窗口切换/服务销毁） */
    fun clear() {
        synchronized(history) { history.clear() }
        lastFocusedNode = null
        lastFocusedBounds = Rect()
        ReaderLog.d(TAG, "cleared")
    }

    /** 只清节点引用，保留历史（窗口变化时用） */
    fun onWindowChanged() {
        lastFocusedNode = null
        lastFocusedBounds = Rect()
    }

    private fun isValid(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.isVisibleToUser
        } catch (e: Exception) {
            false
        }
    }
}
