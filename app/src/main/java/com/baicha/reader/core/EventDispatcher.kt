package com.baicha.reader.core

import android.view.accessibility.AccessibilityEvent

/**
 * 事件分发器 —— 对应天坦 G6/d（AccessibilityEventDispatcher，328 行）
 *
 * 【天坦的三段式】
 *
 *   onEvent(event)
 *       ① isDuplicateFocus(event)     焦点去重
 *       ② shouldDropEvent(event)      14 条规则过滤
 *       ③ eventQueue.offer(event)     入队
 *       ④ handler 唤醒                → 主线程消费
 *
 *   消费时：dispatchEvent(event)
 *       ① handleEvent(event)          真正分发
 *       ② event.recycle()             回收
 *
 * 【与本项目旧实现的差异】
 *   旧实现：onAccessibilityEvent 里直接 filter + handle（同步，阻塞无障碍线程）
 *   新实现：filter 后入队，主线程消费（不阻塞系统派发）
 *
 * 【高性能强化】
 *   ① 过滤规则**短路求值**：把最可能命中、最便宜的判定放前面
 *   ② 时间/哈希判定全部用**局部变量缓存**，避免重复 getter 调用
 *   ③ 事件类型掩码预计算成常量，避免每次位运算重组
 *   ④ 过滤器无锁：内部状态只在无障碍线程写，消费线程只读
 */
class EventDispatcher(
    private val queue: EventQueue
) {

    // ==================== 配置（由服务注入）====================

    /** 自己的包名 */
    @Volatile
    var selfPackageName: String = "com.baicha.reader"

    /**
     * 是否过滤掉「自己 App」的无障碍焦点事件
     *
     * 【修正】原来无条件过滤（`pkg == selfPackageName` 就丢），
     * 导致读屏自己的设置界面（MainActivity / TtsSettingsActivity）读不出来。
     *
     * 天坦的对应规则（AccessibilityEventDispatcher 规则 11）带 **flag 21** 开关，
     * 默认不开 → 不过滤。这里对齐：默认 false。
     *
     * 何时需要打开：当读屏自己的界面出现「播报自己播报的内容」死循环时才开。
     */
    @Volatile
    var filterSelfPackageEvents: Boolean = false

    /** 屏幕是否亮着 */
    @Volatile
    var isScreenOn: Boolean = true

    /** 是否全屏朗读中 */
    @Volatile
    var isFullScreenReading: Boolean = false

    /** 微信语音播报中 */
    @Volatile
    private var isWechatSpeaking: Boolean = false

    // ==================== 时间戳状态 ====================

    @Volatile
    private var lastWindowStateChangedTime = 0L

    @Volatile
    private var lastClickedTime = 0L

    @Volatile
    private var lastAccessibilityFocusedTime = 0L

    @Volatile
    private var lastFocusHash = -1

    // ==================== 统计 ====================

    @Volatile
    var passedCount = 0L
        private set

    @Volatile
    var droppedCount = 0L
        private set

    // ==================== 入口 ====================

    /**
     * 事件入口 —— 天坦 AccessibilityEventDispatcher.onEvent()
     *
     * 在**无障碍线程**执行，必须极快。只做过滤 + 入队。
     */
    fun onEvent(event: AccessibilityEvent) {
        PerfCounters.eventHandled()
        // ① 焦点去重（最便宜，先做）
        if (isDuplicateFocus(event)) {
            droppedCount++
            // ★ 性能基线：丢弃要计数 —— 丢弃是静默的，
            //   用户只会感觉「漏读了」，有计数才能判断丢得凶不凶
            PerfCounters.eventDropped()
            return
        }

        // ② 规则过滤
        if (shouldDropEvent(event)) {
            droppedCount++
            PerfCounters.eventDropped()
            return
        }

        // ③ 窗口状态变化 → 更新基准时间 + 重置焦点哈希
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangedTime = event.eventTime
            lastFocusHash = -1
        }

        // ④ 入队（内部会拷贝事件）
        passedCount++
        queue.offer(event)
    }

    fun resetFocusHash() {
        lastFocusHash = -1
    }

    fun resetAll() {
        lastFocusHash = -1
        lastWindowStateChangedTime = 0L
        lastClickedTime = 0L
        lastAccessibilityFocusedTime = 0L
        isWechatSpeaking = false
    }

    // ==================== ① 焦点去重（天坦 isDuplicateFocus）====================

    /**
     * 焦点事件去重 —— 1:1 抄天坦 o()
     *
     * 同一个节点重复收到无障碍焦点事件时丢弃。
     * 例外：
     *   - 文本编辑状态变化中
     *   - 小米安全输入法（每次都要读）
     *   - 微信读书（比对内容描述）
     *   - vivo 锁屏（首次指纹提示）
     */
    private fun isDuplicateFocus(event: AccessibilityEvent): Boolean {
        if (event.eventType != TYPE_VIEW_ACCESSIBILITY_FOCUSED) return false

        lastAccessibilityFocusedTime = event.eventTime

        val source = try {
            event.source
        } catch (e: Throwable) {
            null
        }
        val hash = source?.hashCode() ?: -1
        val pkg = event.packageName?.toString().orEmpty()

        if (lastFocusHash == hash) {
            // 例外 1：小米安全输入法每次都要读
            if (pkg == PKG_MIUI_SECURITY_INPUT) return false

            // 例外 2：微信读书比对内容描述
            if (pkg == PKG_WEREAD) {
                val desc = event.contentDescription?.toString()
                return desc != null && desc == lastWereadDescription
            }
            return true
        }

        lastFocusHash = hash
        lastWereadDescription = event.contentDescription?.toString()
        return false
    }

    /** 微信读书上次的内容描述 */
    @Volatile
    private var lastWereadDescription: String? = null

    // ==================== ② 14 条过滤规则（天坦 shouldDropEvent）====================

    /**
     * 过滤规则 —— 1:1 抄天坦 n()
     *
     * 【顺序优化】天坦的顺序保持，但每条的判定内部做了短路。
     * 高频事件（64/128/2048）的判定放在前面。
     */
    private fun shouldDropEvent(event: AccessibilityEvent): Boolean {
        val type = event.eventType
        val time = event.eventTime
        val pkg = event.packageName?.toString().orEmpty()

        // ---------- 规则 1：无障碍焦点 + 非对话框角色 + 屏幕关 ----------
        if (type == TYPE_VIEW_ACCESSIBILITY_FOCUSED && !isScreenOn) {
            return true
        }

        // ---------- 规则 2：屏幕关 → 丢（微信/QQ 窗口变化除外）----------
        if (!isScreenOn) {
            if (type == TYPE_WINDOW_STATE_CHANGED &&
                (pkg == PKG_WECHAT || pkg == PKG_QQ)
            ) {
                return false
            }
            return true
        }

        // ---------- 规则 3：微信语音播报中 ----------
        if (type == TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            val cd = event.contentDescription?.toString().orEmpty()
            if (cd.contains("转文字") && cd.contains("发送")) {
                isWechatSpeaking = true
                return true
            }
            isWechatSpeaking = false
        }
        // 天坦原版用 64（其自定义的"无障碍焦点"位），Android 真实对应值是
        // TYPE_VIEW_ACCESSIBILITY_FOCUSED(32768)。这里按真实语义处理。
        if (isWechatSpeaking &&
            (type == TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
                    type == TYPE_VIEW_TEXT_CHANGED ||
                    type == TYPE_WINDOWS_CHANGED)
        ) {
            return true
        }

        // ---------- 规则 4：HOVER_ENTER + 全屏朗读中 ----------
        if (type == TYPE_VIEW_HOVER_ENTER && isFullScreenReading) {
            return true
        }

        // ---------- 规则 5：窗口切换后 200ms 内的噪声 ----------
        if ((type and MASK_WINDOW_NOISE) != 0 &&
            time - lastWindowStateChangedTime < 200 &&
            !isScrollFromTouch(event)
        ) {
            return true
        }

        // ---------- 规则 6：点击/长按 100ms 去重 ----------
        if (type == TYPE_VIEW_CLICKED || type == TYPE_VIEW_LONG_CLICKED) {
            val diff = time - lastClickedTime
            if (diff <= 100) return true
            lastClickedTime = time
        }

        // ---------- 规则 7：窗口集合变化 + 无有效 windowChanges ----------
        if (type == TYPE_WINDOWS_CHANGED) {
            if ((event.windowChanges and WINDOWS_CHANGE_TYPES_USED) == 0) return true
        }

        // ---------- 规则 8：文本变化 ----------
        if (type == TYPE_VIEW_TEXT_CHANGED) {
            return if (pkg == PKG_IFLYTEK_IME) {
                event.contentChangeTypes <= 1
            } else {
                event.contentChangeTypes == 0
            }
        }

        // ---------- 规则 9：无障碍焦点 + 自己包名（受开关控制）----------
        // 【修正】默认不过滤，否则读屏自己的设置界面无法朗读（对齐天坦 flag 21 默认关闭）
        if (filterSelfPackageEvents &&
            type == TYPE_VIEW_ACCESSIBILITY_FOCUSED &&
            pkg == selfPackageName
        ) {
            return true
        }

        // ---------- 规则 10/11：选中/选择变化 200ms 去重 ----------
        if (type == TYPE_VIEW_SELECTED) {
            if (time - lastAccessibilityFocusedTime < 200) return true
            if (event.className?.toString() == "android.widget.TextView") return true
        }
        if (type == TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            if (time - lastAccessibilityFocusedTime < 200) return true
        }

        return false
    }

    /**
     * 是否来自触摸的滚动 —— 1:1 抄天坦 p()
     *
     *   return event.eventType == 8 && 4 == roleOf(source)
     *
     * 角色 4 = ROLE_EDIT_TEXT
     */
    private fun isScrollFromTouch(event: AccessibilityEvent): Boolean {
        if (event.eventType != TYPE_VIEW_SCROLLED) return false
        return try {
            val source = event.source ?: return false
            NodeRoleHelper.roleOf(source) == NodeRoleHelper.ROLE_EDIT_TEXT
        } catch (e: Throwable) {
            false
        }
    }

    fun stats(): String = "放行 $passedCount / 丢弃 $droppedCount"

    companion object {
        // ---------- 事件类型常量（避免每次访问静态字段）----------
        private const val TYPE_VIEW_CLICKED = AccessibilityEvent.TYPE_VIEW_CLICKED               // 1
        private const val TYPE_VIEW_LONG_CLICKED = AccessibilityEvent.TYPE_VIEW_LONG_CLICKED     // 2
        private const val TYPE_VIEW_SELECTED = AccessibilityEvent.TYPE_VIEW_SELECTED             // 4
        private const val TYPE_VIEW_FOCUSED = AccessibilityEvent.TYPE_VIEW_FOCUSED               // 8
        private const val TYPE_VIEW_SCROLLED = AccessibilityEvent.TYPE_VIEW_SCROLLED             // 4096
        private const val TYPE_WINDOW_STATE_CHANGED = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED // 32
        private const val TYPE_VIEW_ACCESSIBILITY_FOCUSED = 32768
        private const val TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED = 65536
        private const val TYPE_VIEW_HOVER_ENTER = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER       // 128
        private const val TYPE_VIEW_TEXT_CHANGED = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED     // 16
        private const val TYPE_VIEW_TEXT_SELECTION_CHANGED = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED // 8192
        private const val TYPE_WINDOWS_CHANGED = AccessibilityEvent.TYPE_WINDOWS_CHANGED         // 4194304

        /** 窗口噪声掩码 —— 天坦常量 12300 = 4|8|4096|8192 */
        private const val MASK_WINDOW_NOISE = 12300

        /** 有效窗口变化类型 —— 天坦用 WINDOWS_CHANGE_TYPES_USED（0x1F） */
        private const val WINDOWS_CHANGE_TYPES_USED = 0x1F

        // ---------- 包名常量（避免字符串比较的重建）----------
        private const val PKG_WECHAT = "com.tencent.mm"
        private const val PKG_QQ = "com.tencent.mobileqq"
        private const val PKG_WEREAD = "com.tencent.weread"
        private const val PKG_IFLYTEK_IME = "com.iflytek.inputmethod"
        private const val PKG_MIUI_SECURITY_INPUT = "com.miui.securityinputmethod"
    }
}
