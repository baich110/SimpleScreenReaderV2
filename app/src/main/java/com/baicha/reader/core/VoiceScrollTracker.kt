package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 语音输入滚动追踪 —— 对应天坦 `a7/C1083e`（语音输入触发的滚动识别）
 *
 * 【要解决的问题】
 *
 * 用户用**语音输入**时，输入法会自动触发列表滚动（例如语音识别候选词
 * 翻页、联想词滚动）。这类滚动有两个特征：
 *
 *   ① 用户没有主动操作界面 —— 只是说话
 *   ② 短时间内会连续触发多次（750ms 内）
 *
 * 如果按普通滚动处理，读屏会跟着念"第 5 项，共 20 项、第 6 项…"
 * —— 完全盖住用户正在说的话。
 *
 * 【天坦的做法（`M6/r.h()`）】
 *
 *   h(eventTime, node):
 *       a = C1083e.f12325a.a()                       // 取上次语音滚动记录
 *       if (a != null && eventTime - a.a() < 750 && a.e(node)) {
 *           return a.c()                             // 返回记录的 userAction 类型
 *       }
 *       return 0
 *
 *   判定条件：**750ms 内 + 同一个节点** → 认为是语音输入引发的滚动，
 *   返回一个特殊的 `userAction` 值，后续处理走不同分支。
 *
 * 【本实现】
 *   同样的语义，但去掉天坦对具体输入法的耦合：
 *   通过「短时间内同节点的重复滚动」+「麦克风占用」两个信号综合判定。
 */
class VoiceScrollTracker {

    companion object {
        /** 语音滚动的判定窗口 —— 天坦 750ms */
        const val VOICE_SCROLL_WINDOW_MS = 750L

        /** userAction 常量 —— 对应天坦的返回值 */
        const val ACTION_NORMAL = 0
        const val ACTION_VOICE_INPUT = 3
        const val ACTION_IME = 1
    }

    /** 上次滚动的时间戳 */
    @Volatile
    private var lastScrollTime = 0L

    /** 上次滚动涉及的节点（身份） */
    @Volatile
    private var lastNode: AccessibilityNodeInfo? = null

    /** 上次判定的 userAction */
    @Volatile
    private var lastAction = ACTION_NORMAL

    /** 麦克风状态提供者（由服务注入） */
    @Volatile
    var isMicrophoneInUse: (() -> Boolean)? = null

    /**
     * 识别语音输入触发的滚动 —— 对应天坦 `M6/r.h()`
     *
     * @param eventTime 事件时间
     * @param node 滚动涉及的节点
     * @return userAction（0 = 普通滚动，3 = 语音输入触发）
     */
    fun identify(eventTime: Long, node: AccessibilityNodeInfo?): Int {
        val prevNode = lastNode
        val withinWindow = eventTime - lastScrollTime < VOICE_SCROLL_WINDOW_MS
        val sameNode = prevNode != null && node != null && prevNode === node

        // ① 麦克风在录音 → 更可能是语音输入
        val micActive = isMicrophoneInUse?.invoke() ?: false

        val action = if (micActive && withinWindow && sameNode) {
            // 高度可疑：录音中 + 短时间内同节点重复滚动
            ACTION_VOICE_INPUT
        } else if (withinWindow && sameNode) {
            // 可能：连续滚动（用户快速滑动）
            ACTION_IME
        } else {
            ACTION_NORMAL
        }

        lastScrollTime = eventTime
        lastNode = node
        lastAction = action
        return action
    }

    /**
     * 该滚动是否应「静默处理」—— 语音输入触发时不播报位置
     *
     * 天坦的处理：语音滚动的播报走不同分支（通常不播位置）。
     */
    fun shouldSuppressAnnouncement(action: Int): Boolean =
        action == ACTION_VOICE_INPUT

    /** 上次判定的动作 */
    fun lastAction(): Int = lastAction

    /** 重置（窗口变化） */
    fun reset() {
        lastScrollTime = 0L
        lastNode = null
        lastAction = ACTION_NORMAL
    }

    fun stats(): String = when (lastAction) {
        ACTION_VOICE_INPUT -> "上次: 语音输入触发"
        ACTION_IME -> "上次: 连续滚动"
        else -> "上次: 普通滚动"
    }
}
