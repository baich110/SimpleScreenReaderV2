package com.baicha.reader.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍事件处理器 —— 从 `ReaderAccessibilityService` 抽出的「事件分发」职责
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么抽出来】
 * ══════════════════════════════════════════════════════════
 *
 * 原服务类混合了五类职责（生命周期 / 事件分发 / 手势 / 播报 / 工具），
 * 累积到 1333 行 35 个方法，可维护性差。
 *
 * 事件分发（handleEvent + 6 个辅助方法）占 341 行，且依赖清晰，
 * 适合独立成类。
 *
 * ══════════════════════════════════════════════════════════
 * 【事件类型覆盖（21 种）】
 * ══════════════════════════════════════════════════════════
 *
 *   1        点击             → 合成 + 播报 + 音效
 *   2        长按             → 音效 + 播报
 *   4        选中             → 合成 + 播报
 *   8        滚动             → 音效 + 位置播报 + 语音输入判定
 *   16       焦点             → speakNode
 *   32       窗口状态变化      → 状态重置 + 焦点恢复
 *   128      触摸探索          → **先打断** + speakHover
 *   2048     文本变化          → 增量播报
 *   4096     滚动（另一种）    → 同上
 *   8192     选择变化          → 播报
 *   16384    粒度遍历          → 播报
 *   32768    无障碍焦点        → speakNode（去重）
 *   65536    焦点清除          → 日志
 *   131072   拖拽             → 播报
 *   262144   上下文点击        → 音效 + 播报
 *   1048576  触摸探索开始      → **先打断**
 *   2097152  触摸探索结束      → 日志
 *   4194304  窗口集合变化      → 缓存失效
 *   + 通知 / 系统播报 / 内容变化
 */
class AccessibilityEventHandler(
    /** 播报引擎 */
    private val speech: SpeechEngine,
    /** 音效 */
    private val sound: SoundManager,
    /** 振动 */
    private val haptic: HapticFeedback,
    /** 焦点管理 */
    private val focus: FocusManager,
    /** 自动滚动 */
    private val autoScroll: AutoScrollManager,
    /** 语音输入滚动追踪 */
    private val voiceScroll: VoiceScrollTracker,
    /** 节点合成（走缓存） */
    private val compose: (AccessibilityNodeInfo?) -> String?,
    /** 日志 */
    private val log: (String) -> Unit,
    /** 音频焦点管理器（记录前台包名） */
    private val audioFocus: AudioFocusManager,
    /** 屏幕状态恢复 */
    private val screenRecovery: ScreenStateRecovery,
    /**
     * 增量文本监控（文本差分）
     *
     * 【用途】实时更新界面（聊天/股票/弹幕）只念变化部分，不整段重读。
     * 详见 TextDiff.kt 的类注释。
     */
    private val textMonitor: NodeTextMonitor
) {

    companion object {
        private const val TAG = "EventHandler"

        /** 滚动节流窗口 —— 天坦 ProcessorScroll 用 250ms */
        private const val SCROLL_THROTTLE_MS = 250L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 内容变化限流器（A2） */
    private val contentChangeFilter = ContentChangeFilter()

    // ==================== 状态 ====================

    /** 最近一次焦点动作（供屏幕状态恢复的竞态判定）*/
    @Volatile
    var lastFocusActionTime = 0L
        private set

    /**
     * 当前被监听文本变化的节点（增量朗读的基准）
     *
     * 只保留一个：无障碍焦点只有一个，多个会互相干扰且无界增长。
     */
    private var watchedNode: AccessibilityNodeInfo? = null

    @Volatile
    var lastFocusActionSource = 0
        private set

    /** 去重：上次朗读的节点身份 */
    private var lastSpokenIdentity = -1
    private var lastSpeakTime = 0L

    /** 触摸探索：上次悬停节点的身份 */
    private var lastHoverIdentity = -1

    /** 滚动节流状态 */
    private var lastScrollTime = 0L
    private var lastScrollNodeKey = -1
    private var lastScrollWindowId = -1

    /** 屏幕状态 */
    @Volatile
    private var screenOn = true
    private var lastScreenCheckTime = 0L

    /** 屏幕状态提供者（由服务注入） */
    @Volatile
    var isScreenOnProvider: () -> Boolean = { true }

    /** 窗口变化后的回调（由服务注入，做缓存清理等） */
    @Volatile
    var onWindowChangedHook: (() -> Unit)? = null

    /** 记录一次焦点动作 */
    fun recordFocusAction(source: Int) {
        lastFocusActionTime = SystemClock.uptimeMillis()
        lastFocusActionSource = source
    }

    /** 窗口变化时重置内部状态 */
    fun resetState() {
        lastSpokenIdentity = -1
        lastHoverIdentity = -1
        lastScrollNodeKey = -1
        lastScrollWindowId = -1
    }

    // ==================== 主入口 ====================

    /**
     * 事件入口（无障碍线程）—— **必须极快**
     */
    fun onEvent(event: AccessibilityEvent) {
        val et = event.eventType

        // ① 屏幕状态（1 秒缓存）
        updateScreenState()

        // ② 滚动事件立即反馈（音效要跟手）
        if (et == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            autoScroll.onScrollEvent(event)
        }

        // ③ 过滤 + 入队由 EventDispatcher 负责（在服务里）
        //    本方法只处理「已入队事件的分发」
    }

    /**
     * 事件分发（主线程）—— 21 种事件类型
     */
    fun handleEvent(event: AccessibilityEvent) {
        when (event.eventType) {

            // ---------- 1: 点击 ----------
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleNodeEvent(event, "点击", interrupt = true)
            }

            // ---------- 2: 长按 ----------
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val text = compose(event.source)
                log("长按: ${text ?: "无文本"}")
                sound.longClicked()
                haptic.longPress()
                if (!text.isNullOrBlank()) speech.speak(text, interrupt = true)
            }

            // ---------- 4: 选中 ----------
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                handleNodeEvent(event, "选中", interrupt = true)
            }

            // ---------- 8 / 4096: 滚动 ----------
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event)

            // ---------- 16: 焦点 ----------
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                speakNode(event.source, "焦点")
            }

            // ---------- 32: 窗口状态变化 ----------
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChanged(event)

            // ---------- 32768: 无障碍焦点 ----------
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                // ★ 开始监听该节点的文本变化（增量朗读的基准）
                //
                // 只在焦点节点上监听：无障碍焦点就是「用户正在关注的那个」，
                // 只有它的文本变化值得播报。全树监听既浪费又吵。
                // 上一个焦点取消监听，避免 watched 无界增长。
                event.source?.let { src ->
                    if (src !== watchedNode) {
                        watchedNode?.let { textMonitor.unwatch(it) }
                        textMonitor.watch(src)
                        watchedNode = src
                    }
                }
                val text = compose(event.source)
                log("无障碍焦点: ${text ?: "(无内容)"}")
                if (!text.isNullOrBlank()) {
                    val identity = event.source?.let { System.identityHashCode(it) } ?: -1
                    val now = SystemClock.uptimeMillis()
                    if (identity != lastSpokenIdentity || now - lastSpeakTime > 300) {
                        lastSpokenIdentity = identity
                        lastSpeakTime = now
                        speech.speak(text, interrupt = true)
                        playFocusSound(event.source)
                    }
                }
            }

            // ---------- 65536: 焦点清除 ----------
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                log("焦点清除")
            }

            // ---------- 128: 触摸探索 ----------
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                // ★ 天坦行为：触摸一碰先打断当前播报
                speech.interruptChannelA()
                event.source?.let { speakHover(it) }
            }

            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                // 离开不播报
            }

            // ---------- 2048: 文本变化 ----------
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = event.source
                val text = node?.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    // ★ 增量朗读（文本差分）
                    //
                    // 【为什么不能直接整段重读】
                    //   实时更新的界面（聊天、股票、弹幕、进度）文本频繁变化：
                    //     价格 "10.5" → "10.8"
                    //       整段重读 → 每次都念完整内容，噪音淹没用户
                    //       增量朗读 → 只念 "10.8" ★
                    //
                    // 【为什么首次仍是整段读】
                    //   差分需要「上一版文本」做基准。没 watch 过就没有基准，
                    //   此时只能整段读 —— 这也是正确行为（首次出现应完整念）。
                    val incremental = node?.let { textMonitor.onTextChanged(it) }
                    if (incremental != null) {
                        // 已在 onIncrementalText 回调里播报，此处不重复
                        log("文本(增量): $incremental")
                    } else {
                        log("文本: $text")
                        speech.speak(text, interrupt = false)
                    }
                }
            }

            // ---------- 8192: 选择变化 ----------
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val node = event.source
                val text = node?.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    log("选择: $text")
                    speech.speak(text, interrupt = false)
                }
            }

            // ---------- 1048576: 触摸探索开始 ----------
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> {
                log("触摸探索开始")
                // ★ 天坦行为：探索开始也先打断
                speech.interruptChannelA()
            }

            // ---------- 2097152: 触摸探索结束 ----------
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> {
                log("触摸探索结束")
            }

            // ---------- 4194304: 窗口集合变化 ----------
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)
            }

            // ---------- 16384: 粒度遍历 ----------
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> {
                // ★ 用 extractTraversedText 取「当前粒度单元」，而不是整段文本
                //
                // 【修的什么 bug】
                //   原实现直接用 event.text —— 那是**整个节点的文本**，
                //   于是「用户按下一个字，读屏念整段」。
                //
                // 【为什么之前没接线上】
                //   extractTraversedText 早已写好但无调用方。
                //   而它原来的第一优先分支也用 event.text，
                //   直接接线等于把 bug 保留 —— 一并修正为只认索引切片
                //   （对齐 TalkBack TextEventInterpreter 与天坦）。
                val text = GranularityMover.extractTraversedText(event)
                if (!text.isNullOrEmpty()) {
                    log("粒度遍历: $text")
                    speech.speak(text, interrupt = true)
                }
            }

            // ---------- 131072: 拖拽 ----------
            131072 -> {
                val text = compose(event.source)
                if (!text.isNullOrBlank()) {
                    log("拖拽: $text")
                    speech.speak(text, interrupt = true)
                    haptic.focus()
                }
            }

            // ---------- 262144: 上下文点击 ----------
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> {
                val text = compose(event.source)
                log("上下文点击: ${text ?: "(无内容)"}")
                sound.longClicked()
                haptic.longPress()
                if (!text.isNullOrBlank()) speech.speak(text, interrupt = true)
            }

            // ---------- 通知 ----------
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text?.joinToString("")?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    log("通知: $text")
                    speech.speak(text, interrupt = true)
                }
            }

            // ---------- 系统播报 ----------
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                val text = event.text?.joinToString("")?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    log("播报: $text")
                    speech.speak(text, interrupt = true)
                }
            }

            // ---------- 内容变化 ----------
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 缓存必须失效（结构/文本可能变了）
                CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)

                // ★ 条件播报（A2）—— 原来是「完全不播」（太吵）
                //
                // 【改的什么】原实现直接丢弃，导致直播比分、下载进度、
                //   计时器这类**只靠内容变化传递信息**的场景用户完全感知不到。
                //
                // 【怎么做到既不刷屏又不漏】双层过滤，对齐 TalkBack：
                //   ① 语义层：只播文本/状态/内容描述变化，
                //      结构变化（子树增删）交给焦点/窗口事件处理
                //   ② 限流层：同节点 500ms 内只播一次，换节点立即放行
                handleContentChanged(event)
            }

            else -> {
                // 其他事件静默
            }
        }
    }

    /**
     * 窗口内容变化处理 —— 对标 TalkBack
     * `EventTypeWindowContentChangedFeedbackRule`(431 行)
     * 与 `WindowContentChangeAnnouncementFilter`(126 行)
     *
     * 【为什么值得为它单开一个方法】
     *   这是**唯一一类既能提供关键信息、又能刷屏**的事件。
     *   下载进度必须播，动画帧必须丢 —— 两者是同一个 eventType。
     *   处理逻辑值得独立成方法并有名字。
     */
    private fun handleContentChanged(event: AccessibilityEvent) {
        // ① 语义过滤：这类变化值得播吗
        val kind = try {
            ContentChangeKind.classify(event.contentChangeTypes)
        } catch (e: Throwable) {
            ContentChangeKind.NONE
        }
        if (!ContentChangeKind.isWorthAnnouncing(kind)) {
            return
        }

        // ② 限流：同节点太频繁就丢
        val node = event.source ?: return
        val nodeKey = System.identityHashCode(node)
        // live region 的语义就是「内容变了要主动告知」——
        // 对它不跳过首次播报（对齐 TalkBack）
        val isLiveRegion = try {
            node.liveRegion != android.view.View.ACCESSIBILITY_LIVE_REGION_NONE
        } catch (e: Throwable) {
            false
        }
        if (!contentChangeFilter.shouldAnnounce(nodeKey, isLiveRegion = isLiveRegion)) {
            return
        }

        // ③ 合成 + 播报（不打断当前朗读）
        val text = compose(node)
        if (!text.isNullOrBlank()) {
            log("内容变化: $text")
            speech.speak(text, interrupt = false)
        }
    }

    // ==================== 辅助处理 ====================

    private fun handleNodeEvent(event: AccessibilityEvent, tag: String, interrupt: Boolean) {
        val text = compose(event.source)
        if (!text.isNullOrBlank()) {
            log("$tag: $text")
            speech.speak(text, interrupt = interrupt)
            sound.tick()
            haptic.click()
        } else {
            log("$tag（无内容）")
            sound.tick()
        }
    }

    private fun handleWindowChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        log("窗口: $pkg / $cls")

        sound.windowState()

        if (pkg.isNotEmpty()) {
            audioFocus.foregroundPackage = pkg
        }

        // 重置状态
        resetState()
        focus.onWindowChanged()
        autoScroll.resetScrollState()
        voiceScroll.reset()
        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)

        // 通知服务（做它自己的清理）
        onWindowChangedHook?.invoke()

        // 播报页面标题
        val title = event.text?.joinToString("")?.trim().orEmpty()
        if (title.isNotEmpty()) speech.speak(title, interrupt = true)

        // ★ 屏幕状态变化后的焦点恢复（带延迟判定）
        val transitionStart = event.eventTime
        val elapsed = SystemClock.uptimeMillis() - transitionStart

        if (ScreenStateTiming.shouldDelay(elapsed)) {
            mainHandler.postDelayed(
                { performScreenStateRecovery(transitionStart) },
                ScreenStateTiming.WINDOW_CHANGE_DELAY_MS - elapsed
            )
        } else {
            performScreenStateRecovery(transitionStart)
        }
    }

    private fun performScreenStateRecovery(transitionStart: Long) {
        try {
            val ok = screenRecovery.onScreenStateChanged(transitionStart)
            if (ok) log("屏幕状态恢复: ${screenRecovery.stats()}")
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "屏幕状态恢复失败: ${e.message}")
        }
    }

    private fun handleScroll(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()

        // ① 时间节流
        if (now - lastScrollTime < SCROLL_THROTTLE_MS) return
        lastScrollTime = now

        // 滚动后可见性变了 → 失效判定/文本/边界缓存
        CacheRegistry.invalidate(CacheRegistry.Level.SCROLL)

        val node = event.source
        val nodeKey = node?.hashCode() ?: -1
        val windowId = node?.windowId ?: -1

        // ② 节点去重（判定抽成纯函数以便单测）
        if (ScrollDedup.isDuplicate(nodeKey, windowId, lastScrollNodeKey, lastScrollWindowId)) {
            return
        }
        lastScrollNodeKey = nodeKey
        lastScrollWindowId = windowId

        // ③ 滚动音效
        sound.scroll(autoScroll.computeEarconRate(event))

        // ④ 标记滚动状态（供"滚动中导航"拦截）
        val scrollDir = when (autoScroll.lastDetectedDirection) {
            ScrollDirectionDetector.FORWARD -> 1
            ScrollDirectionDetector.BACKWARD -> -1
            else -> 0
        }
        if (scrollDir != 0) {
            focus.onScrollStart(scrollDir)
        }

        // ⑤ 语音输入触发的滚动 → 静默
        val userAction = voiceScroll.identify(event.eventTime, node)
        if (voiceScroll.shouldSuppressAnnouncement(userAction)) {
            log("滚动（语音输入触发，静默）")
            return
        }

        // ⑥ 播报滚动位置
        val msg = ScrollPositionFormatter.format(
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount,
            isPager = node?.let {
                NodeRoleHelper.roleOf(it) == NodeRoleHelper.ROLE_PAGER
            } ?: false
        )
        if (msg != null) {
            log("滚动: $msg")
            speech.speak(msg, interrupt = true)
        } else {
            log("滚动（无位置信息）")
        }
    }

    // ==================== 播报 ====================

    /** 触摸探索播报 */
    fun speakHover(node: AccessibilityNodeInfo) {
        val identity = System.identityHashCode(node)
        val now = SystemClock.uptimeMillis()

        if (identity == lastHoverIdentity && now - lastSpeakTime < 250) return

        val text = compose(node)
        val roleName = if (text.isNullOrBlank()) {
            SpeechCompositor.roleName(NodeRoleHelper.roleOf(node), node)
        } else null

        if (text.isNullOrBlank() && roleName == null) {
            lastHoverIdentity = identity
            lastSpeakTime = now
            log("触摸: (无标签) → 兜底播报")
            speech.speak(UNTAGGED, interrupt = true)
            focus.focusForTouch(node)
            playFocusSound(node)
            return
        }

        lastHoverIdentity = identity
        lastSpeakTime = now

        val spoken = text ?: roleName!!
        log("触摸: $spoken")
        speech.speak(spoken, interrupt = true)
        recordFocusAction(ScreenStateRecovery.FOCUS_SOURCE_TOUCH_EXPLORATION)
        focus.focusForTouch(node)
        playFocusSound(node)
    }

    /** 焦点变化播报 */
    fun speakNode(node: AccessibilityNodeInfo?, tag: String) {
        if (node == null) return

        val identity = System.identityHashCode(node)
        val now = SystemClock.uptimeMillis()
        if (identity == lastSpokenIdentity && now - lastSpeakTime < 300) return

        val text = compose(node) ?: run {
            val role = SpeechCompositor.roleName(NodeRoleHelper.roleOf(node), node)
            if (role != null) {
                lastSpokenIdentity = identity
                lastSpeakTime = now
                log("$tag: $role")
                speech.speak(role, interrupt = true)
                playFocusSound(node)
            } else {
                lastSpokenIdentity = identity
                lastSpeakTime = now
                log("$tag: (无标签) → 兜底播报")
                speech.speak(UNTAGGED, interrupt = true)
                playFocusSound(node)
            }
            return
        }

        lastSpokenIdentity = identity
        lastSpeakTime = now
        log("$tag: $text")
        speech.speak(text, interrupt = true)
        playFocusSound(node)
    }

    /**
     * 播焦点音效 —— 天坦 z6/C4421m.java:898
     *   j(node)==true  → focus_actionable（可操作）
     *   j(node)==false → focus（不可操作）
     */
    private fun playFocusSound(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (isActionable(node)) {
            sound.focusActionable()
            haptic.focusClickable()
        } else {
            sound.focus()
            haptic.focus()
        }
        // ★ 进度音效 —— 对应天坦 `z6/C4421m.java:1134`
        //
        // 【为什么叠在焦点音效之后】
        //   焦点音效告诉用户「焦点到了」，进度音效告诉「到哪个位置」。
        //   两者语义不同，叠加不冲突；且进度音高会覆盖
        //   焦点音效的默认 rate —— 所以只在有进度时额外播一次。
        //
        // 【效果】调音量/亮度时，焦点每落一次就有一声音高，
        //   用户不必等语音念完就能感知当前值的位置。
        playProgressEarcon(node)
    }

    /**
     * 若有进度信息，播一声对应音高的反馈。
     *
     * 音高公式由 `ProgressSpeech.pitchRate()` 提供 ——
     * 与单测覆盖的是同一份实现，避免公式在两处漂移。
     */
    private fun playProgressEarcon(node: AccessibilityNodeInfo) {
        val info = try {
            node.rangeInfo
        } catch (e: Throwable) {
            null
        } ?: return

        // 复用合成器里的同一套百分比计算（含 PERCENT 三路分派）
        val percent = ProgressSpeech.percentOf(
            rangeType = info.type,
            current = info.current,
            min = info.min,
            max = info.max
        ) ?: return

        sound.progressEarcon(percent)
    }

    /** 天坦 AbstractC3688i.j(node) */
    private fun isActionable(node: AccessibilityNodeInfo): Boolean {
        if (node.isFocusable) return true
        if (node.isClickable) return true
        if (node.isLongClickable) return true
        return try {
            node.actionList.any {
                it.id == 1 || it.id == 16 || it.id == 32 || it.id == 131072 || it.id == 2048
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun updateScreenState() {
        val now = SystemClock.uptimeMillis()
        if (now - lastScreenCheckTime < 1000) return
        lastScreenCheckTime = now

        val on = isScreenOnProvider()
        if (on != screenOn) {
            screenOn = on
            log(if (on) "屏幕亮起" else "屏幕关闭")
        }
    }

    fun stats(): String =
        "焦点动作 $lastFocusActionTime / 屏幕${if (screenOn) "亮" else "灭"}"

    private val UNTAGGED: String
        get() = "未加标签"
}
