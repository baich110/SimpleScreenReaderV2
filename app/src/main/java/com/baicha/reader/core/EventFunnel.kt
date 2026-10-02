package com.baicha.reader.core

import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 事件漏斗 —— 1:1 抄天坦 AccessibilityEventDispatcher（G6/d.java，328 行）
 *
 * 天坦的两道过滤：
 *   ① isDuplicateFocus()  —— 焦点去重（同节点重复聚焦直接丢）
 *   ② shouldDropEvent()   —— 12 条规则
 *
 * 12 条规则（原文顺序）：
 *   1.  服务未启用 → 全丢
 *   2.  微信语音播报中 → 丢 64/2048/4194304
 *   3.  128（HOVER_ENTER）+ 跳过标志 → 丢
 *   4.  64（无障碍焦点）+ 非 23 角色 + 屏幕关 → 丢
 *   5.  屏幕关闭 → 丢（微信/QQ 的 32 除外）
 *   6.  128 + 全屏朗读中 → 丢
 *   7.  12300 掩码 + 距窗口切换 < 200ms + 非触摸滚动 → 丢
 *   8.  1/2（点击/长按）+ 距上次点击 ≤ 100ms → 丢
 *   9.  4194304（窗口集合变化）+ 无有效 windowChanges → 丢
 *   10. 2048（文本变化）+ 讯飞输入法 contentChangeTypes > 1 → 丢；其他 == 0 才留
 *   11. 64 + 自己包名 → 丢（防自己播报自己）
 *   12. 4（选中）距上次焦点 < 200ms / TextView → 丢
 *       8192（选择变化）距上次焦点 < 200ms → 丢
 */
class EventFunnel {

    companion object {
        /** 天坦的窗口噪声掩码（12300 = 4 | 8 | 16 | 32 | 64 | 128 | 2048 | 8192 | ...） */
        private const val MASK_WINDOW_NOISE = 12300

        /** 天坦用的窗口变化类型掩码 */
        private const val WINDOWS_CHANGE_TYPES_USED = 0x1F
    }

    var droppedCount = 0
        private set
    var passedCount = 0
        private set

    private var lastWindowStateChangedTime = 0L
    private var lastClickedTime = 0L
    private var lastAccessibilityFocusedTime = 0L
    private var lastFocusHash = -1

    /** 微信语音播报中 */
    private var isWechatSpeaking = false

    /** 是否屏幕开启（由服务设置） */
    var isScreenOn = true

    /** 是否全屏朗读中 */
    var isFullScreenReading = false

    /** 触摸探索是否开启 */
    var isTouchExploring = false

    /** 自己的包名（防自播报） */
    var selfPackageName: String = "com.baicha.reader"

    /**
     * 入口：返回 true 表示应该丢弃
     * 对应天坦 onEvent()
     */
    fun shouldDrop(event: AccessibilityEvent): Boolean {
        // ① 焦点去重
        if (isDuplicateFocus(event)) {
            droppedCount++
            return true
        }

        // ② 12 条规则
        if (shouldDropByRules(event)) {
            droppedCount++
            return true
        }

        // ③ 窗口切换 → 重置焦点哈希
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangedTime = event.eventTime
            lastFocusHash = -1
        }

        passedCount++
        return false
    }

    fun resetFocusHash() {
        lastFocusHash = -1
    }

    // ==================== ① 焦点去重（天坦 isDuplicateFocus）====================

    private fun isDuplicateFocus(event: AccessibilityEvent): Boolean {
        // 32768 = TYPE_VIEW_ACCESSIBILITY_FOCUSED
        if (event.eventType != 32768) return false

        lastAccessibilityFocusedTime = event.eventTime

        val source = event.source
        val hash = source?.hashCode() ?: -1

        if (lastFocusHash == hash) {
            // 小米安全输入法例外（每次都要读）
            if (TextUtils.equals(event.packageName, "com.miui.securityinputmethod")) {
                return false
            }
            return true
        }

        lastFocusHash = hash
        return false
    }

    // ==================== ② 12 条规则（天坦 shouldDropEvent）====================

    private fun shouldDropByRules(event: AccessibilityEvent): Boolean {
        val type = event.eventType
        val time = event.eventTime
        val pkg = event.packageName?.toString().orEmpty()

        // ---- 规则 2：微信语音播报中 ----
        // 天坦：32768 事件若 contentDescription 含"转文字 发送" → 置标志并**丢弃该事件**
        if (isWechatSpeaking) {
            if (type == 32768) {
                val cd = event.contentDescription?.toString().orEmpty()
                if (cd.contains("转文字") && cd.contains("发送")) {
                    return true
                }
                isWechatSpeaking = false
            }
        }
        if (type == 32768) {
            val cd = event.contentDescription?.toString().orEmpty()
            if (cd.contains("转文字") && cd.contains("发送")) {
                isWechatSpeaking = true
                return true
            }
            isWechatSpeaking = false
        }
        if (isWechatSpeaking && (type == 64 || type == 2048 || type == 4194304)) {
            return true
        }

        // ---- 规则 4：无障碍焦点 + 屏幕关 ----
        if (type == 64 && !isScreenOn) {
            return true
        }

        // ---- 规则 5：屏幕关闭 → 丢（微信/QQ 窗口变化除外）----
        if (!isScreenOn) {
            if (type == 32 && (pkg == "com.tencent.mm" || pkg == "com.tencent.mobileqq")) {
                return false
            }
            return true
        }

        // ---- 规则 6：HOVER_ENTER + 全屏朗读中 ----
        if (type == 128 && isFullScreenReading) {
            return true
        }

        // ---- 规则 7：窗口切换后 200ms 内的噪声 ----
        if ((type and MASK_WINDOW_NOISE) != 0 &&
            time - lastWindowStateChangedTime < 200 &&
            !isScrollFromTouch(event)
        ) {
            return true
        }

        // ---- 规则 8：点击/长按 100ms 去重 ----
        if (type == 1 || type == 2) {
            val diff = time - lastClickedTime
            if (diff <= 100) {
                return true
            }
            lastClickedTime = time
        }

        // ---- 规则 9：窗口集合变化 + 无有效 windowChanges ----
        if (type == 4194304) {
            val wc = event.windowChanges
            if ((wc and WINDOWS_CHANGE_TYPES_USED) == 0) {
                return true
            }
        }

        // ---- 规则 10：文本变化 ----
        if (type == 2048) {
            return if (pkg == "com.iflytek.inputmethod") {
                event.contentChangeTypes <= 1
            } else {
                event.contentChangeTypes == 0
            }
        }

        // ---- 规则 11：无障碍焦点 + 自己包名 ----
        if (type == 64 && pkg == selfPackageName) {
            return true
        }

        // ---- 规则 12：选中/选择变化 200ms 去重 ----
        if (type == 4) {
            if (time - lastAccessibilityFocusedTime < 200) {
                return true
            }
            val cls = event.className?.toString().orEmpty()
            if (isScreenOn && cls == "android.widget.TextView") {
                return true
            }
        }
        if (type == 8192 && time - lastAccessibilityFocusedTime < 200) {
            return true
        }

        return false
    }

    /**
     * 是否来自触摸的滚动 —— 1:1 抄天坦 AccessibilityEventDispatcher.p()
     *
     * 天坦原版：
     *   isScrollFromTouch(event):
     *       return event.eventType == 8
     *           && 4 == AbstractC3685g0.a(AbstractC2848b.a(event).a())
     *
     * 即：事件类型是滚动(8)，且**事件来源节点的角色 == 4**（ROLE_EDIT_TEXT）。
     *
     * 【修正】原来写成 `fromIndex == toIndex`，语义完全不对：
     * 那个条件只表示"滚动但可见索引没变"，跟"来自触摸"毫无关系。
     * 正确做法是从事件反查 source 节点，判其角色。
     */
    private fun isScrollFromTouch(event: AccessibilityEvent): Boolean {
        if (event.eventType != 8) return false
        return try {
            val source = event.source ?: return false
            NodeRoleHelper.roleOf(source) == NodeRoleHelper.ROLE_EDIT_TEXT
        } catch (e: Throwable) {
            false
        }
    }
}