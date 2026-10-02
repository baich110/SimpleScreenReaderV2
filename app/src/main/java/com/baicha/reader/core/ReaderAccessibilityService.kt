package com.baicha.reader.core

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 白茶读屏 —— 无障碍服务核心（框架层重构版）
 *
 * 【完整链路（照抄天坦，不遗漏）】
 *
 *   onAccessibilityEvent(event)                    无障碍线程，必须极快
 *        ↓
 *   EventDispatcher.onEvent(event)                 ① 焦点去重 ② 14 条过滤
 *        ↓
 *   EventQueue.offer(event)                        环形缓冲入队（拷贝事件）
 *        ↓
 *   主线程 drain                                   批量消费，不阻塞系统派发
 *        ↓
 *   handleEvent(event)                             19 种事件分发
 *        ├─ 点击/长按/选中   → SpeechCompositor + SpeechEngine
 *        ├─ 滚动            → AutoScrollManager + 音效
 *        ├─ 焦点            → FocusManager
 *        ├─ 触摸探索        → SpeechEngine.interruptChannelA() ★ 先打断
 *        ├─ 窗口变化        → FocusManager.onWindowChanged + 缓存失效
 *        └─ 文本变化        → 增量播报
 *        ↓
 *   SpeechCompositor.compose()                     六片段合成（角色/文本/状态/位置）
 *        ↓
 *   SpeechEngine.speak()                           双通道（A 普通 / B 打断）
 *        ↓
 *   TtsManager → TextToSpeech
 *
 * 【高性能要点】
 *   ① 事件异步化：无障碍线程只做「过滤 + 入队」，立即返回
 *   ② 零 GC 队列：EventQueue 用环形缓冲，稳态无分配
 *   ③ 三代缓存：NodeCache 缓存子节点列表 / 可聚焦判定 / 合成文本
 *   ④ 锁最小化：只 EventQueue/EventDispatcher 内部有锁，且临界区极短
 *   ⑤ 懒创建：SpeechEngine 的 B 通道按需创建
 *   ⑥ 批量消费：一次 drain 最多 16 条，避免逐条 Context switch
 */
class ReaderAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BaiChaReader"
        private const val MAX_LOG = 300

        /** 滚动节流窗口 —— 天坦 ProcessorScroll 用 250ms */
        private const val SCROLL_THROTTLE_MS = 250L

        /** 启动播报 */
        private const val MSG_READY = "白茶读屏已启动"

        val eventLog = ArrayDeque<String>(MAX_LOG)

        @Volatile
        var instance: ReaderAccessibilityService? = null
            private set

        @Volatile
        var isRunning = false
            private set

        var onLogUpdate: ((String) -> Unit)? = null
    }

    // ==================== 框架组件 ====================

    /** 事件队列（环形缓冲，零 GC） */
    lateinit var eventQueue: EventQueue
        private set

    /** 事件分发器（焦点去重 + 14 条过滤） */
    lateinit var dispatcher: EventDispatcher
        private set

    /** 播报引擎（双通道） */
    lateinit var speech: SpeechEngine
        private set

    /** 焦点管理器（滚动拦截 + 重试 + 可达性） */
    lateinit var focus: FocusManager
        private set

    /** 节点缓存（三代缓存） */
    lateinit var nodeCache: NodeCache
        private set

    /** 手势责任链（Actor 模式） */
    lateinit var gestureChain: GestureChain
        private set

    /** 导航管理器（27 种导航模式的分发） */
    lateinit var navigation: NavigationManager
        private set

    /** 朗读替换引擎（两级作用域 + 正则） */
    lateinit var replacement: ReadReplacementEngine
        private set

    // ==================== 辅助组件 ====================

    lateinit var haptic: HapticFeedback
        private set

    lateinit var audioFocus: AudioFocusManager
        private set

    lateinit var sound: SoundManager
        private set

    lateinit var autoScroll: AutoScrollManager
        private set

    // ==================== 朗读设置 ====================

    /** 语序（对应天坦 GlobalConfig.z()） */
    @Volatile
    var speechOrder: SpeechOrder = SpeechOrder.TEXT_FIRST

    // ==================== 内部状态 ====================

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 去重：上次朗读的节点哈希 */
    private var lastSpokenHash = -1
    private var lastSpeakTime = 0L

    /** 触摸探索：上次悬停节点 */
    private var lastHoverHash = -1

    /** 屏幕状态（带缓存，避免每次都查 PowerManager） */
    @Volatile
    private var screenOn = true

    private var lastScreenCheckTime = 0L

    /** 滚动节流 */
    private var lastScrollTime = 0L
    private var lastScrollNodeKey = -1
    private var lastScrollWindowId = -1

    // ==================== 生命周期 ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true

        ReaderLog.init(this)
        ReaderLog.i("Service", "=== onServiceConnected ===")

        // ---- 框架组件（按依赖顺序初始化）----
        nodeCache = NodeCache()
        eventQueue = EventQueue(capacity = 256) { dropped ->
            if (dropped > 0) ReaderLog.w("EventQueue", "队列满，丢弃 $dropped")
        }
        dispatcher = EventDispatcher(eventQueue).apply {
            selfPackageName = packageName
            isScreenOn = true
        }
        // ★ 把缓存注入到热点路径（排序 + 可聚焦判定）
        NodeOrderComparator.cache = nodeCache
        ShouldFocusNode.cache = nodeCache

        focus = FocusManager(this)
        speech = SpeechEngine(this).apply {
            enginePackage = TtsEngineManager.getSavedEngine(this@ReaderAccessibilityService)
            speechRate = TtsEngineManager.getRate(this@ReaderAccessibilityService)
            pitch = TtsEngineManager.getPitch(this@ReaderAccessibilityService)
            volume = TtsEngineManager.getVolume(this@ReaderAccessibilityService)
        }
        gestureChain = GestureChain()
        replacement = ReadReplacementEngine()
        navigation = NavigationManager().apply {
            onModeChanged = { mode ->
                // 切换导航后清缓存（过滤器变了，判定结果不再有效）
                nodeCache.invalidateAll()
                speech.speak(mode.label, interrupt = true)
                haptic.focus()
                log("导航 → ${mode.label}")
            }
        }

        // ---- 辅助组件 ----
        haptic = HapticFeedback(this)
        audioFocus = AudioFocusManager(this)
        sound = SoundManager(this).apply { preloadAll() }
        autoScroll = AutoScrollManager(this, focus)

        // ---- 事件消费：队列 → handleEvent ----
        eventQueue.consumer = { event -> handleEvent(event) }

        // ---- 音频焦点委托 ----
        speech.onRequestAudioFocus = { audioFocus.requestFocus() }
        speech.onAbandonAudioFocus = { audioFocus.abandonFocus() }

        // ---- 焦点管理委托 ----
        focus.onCancelScroll = { autoScroll.stop() }

        // ---- 手势责任链装配（顺序 = 优先级）----
        setupGestureChain()

        log("=== 服务已连接 ===")
        Log.i(TAG, "onServiceConnected")

        enableTouchExploration()

        // TTS 就绪后打招呼
        speech.init {
            log("TTS 就绪")
            speech.speak(MSG_READY, interrupt = true)
            haptic.focus()
        }
    }

    /**
     * 装配手势责任链 —— 对应天坦 SoundBackService.X1 的顺序
     *
     *   ① 双击 / 双击长按（硬编码优先，见 GestureChain.handle）
     *   ② MediaControlActor    媒体控制
     *   ③ FullScreenModeActor  全屏朗读/复制
     *   ④ VideoBrowseActor     视频浏览
     *   ⑤ SlidingMenuActor     边缘菜单
     *   ⑥ DefaultNavigationActor 兜底
     */
    private fun setupGestureChain() {
        // 双击 → 点击当前焦点
        gestureChain.onDoubleTap = { _ ->
            val ok = focus.clickTarget()
            if (ok) {
                haptic.click()
                log("→ 双击：点击")
            } else {
                haptic.error()
                log("→ 双击：点击失败")
            }
            ok
        }

        // 双击长按 → 长按当前焦点
        gestureChain.onDoubleTapHold = { _ ->
            val ok = focus.longClickTarget()
            if (ok) haptic.longPress() else haptic.error()
            ok
        }

        // ★ 导航切换手势（天坦训练文案确认）：
        //    先向下再向上滑 → 下一个导航
        //    先向上再向下滑 → 上一个导航
        // 这两个手势不参与通用映射，直接硬处理（与天坦一致）
        gestureChain.onNavigationSwitch = { gestureId ->
            when (gestureId) {
                Gestures.GESTURE_SWIPE_DOWN_AND_UP -> { navigation.next(); true }
                Gestures.GESTURE_SWIPE_UP_AND_DOWN -> { navigation.previous(); true }
                else -> false
            }
        }

        // ② 媒体控制（仅播放时激活）
        gestureChain.register(MediaControlActor(
            isMediaPlaying = { isMediaActive() },
            onMediaControl = { id, _ -> handleMediaGesture(id) }
        ))

        // ⑤ 边缘菜单
        gestureChain.register(SlidingMenuActor(
            isMenuEnabled = { false },   // 功能层再开
            onEdgeGesture = { _, _ -> false }
        ))

        // ⑥ 兜底导航
        gestureChain.register(DefaultNavigationActor(
            onFocusMove = { forward, _ -> moveFocusInternal(forward) },
            onScroll = { forward, _ -> scrollInternal(forward) },
            onMappedAction = { id, _ -> handleMappedGesture(id) }
        ))
    }

    // ==================== 事件入口 ====================

    /**
     * 无障碍事件回调 —— **必须极快**
     *
     * 只做：屏幕状态跟踪 + 滚动预处理 + 过滤 + 入队。
     * 真正的处理在主线程 handleEvent()。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val et = event.eventType

        // ① 屏幕状态（1 秒缓存，避免高频查 PowerManager）
        updateScreenState()

        // ② 滚动事件需要立即反馈（音效要跟手），不能等队列
        if (et == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            autoScroll.onScrollEvent(event)
        }

        // ③ 过滤 + 入队
        dispatcher.onEvent(event)
    }

    /**
     * 屏幕状态更新 —— 1 秒节流
     */
    private fun updateScreenState() {
        val now = SystemClock.uptimeMillis()
        if (now - lastScreenCheckTime < 1000) return
        lastScreenCheckTime = now

        val on = isScreenOn()
        if (on != screenOn) {
            screenOn = on
            dispatcher.isScreenOn = on
            log(if (on) "屏幕亮起" else "屏幕关闭")
        }
    }

    // ==================== 事件分发（主线程）====================

    /**
     * 事件分发 —— 对应天坦 SoundBackService.handleEvent()
     *
     * 19 种事件类型完整覆盖。
     */
    private fun handleEvent(event: AccessibilityEvent) {
        when (event.eventType) {

            // ---------- 1: 点击 ----------
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleNodeEvent(event, "点击", interrupt = true)
            }

            // ---------- 2: 长按 ----------
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val text = composeNode(event.source)
                log("长按: ${text ?: "无文本"}")
                sound.longClicked()
                haptic.longPress()
                if (!text.isNullOrBlank()) speech.speak(text, interrupt = true)
            }

            // ---------- 4: 选中 ----------
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                handleNodeEvent(event, "选中", interrupt = true)
            }

            // ---------- 8: 滚动 ----------
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event)

            // ---------- 16: 焦点 ----------
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                speakNode(event.source, "焦点")
            }

            // ---------- 32: 窗口状态变化 ----------
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChanged(event)

            // ---------- 64 / 32768: 无障碍焦点（值同为 32768）----------
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                val text = composeNode(event.source)
                log("无障碍焦点: ${text ?: "(无内容)"}")
                if (!text.isNullOrBlank()) {
                    val hash = event.source?.hashCode() ?: -1
                    val now = SystemClock.uptimeMillis()
                    if (hash != lastSpokenHash || now - lastSpeakTime > 300) {
                        lastSpokenHash = hash
                        lastSpeakTime = now
                        speech.speak(text, interrupt = true)
                        playFocusSound(event.source)
                    }
                }
            }

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
                    log("文本: $text")
                    speech.speak(text, interrupt = false)
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
                dispatcher.isFullScreenReading = false
                // ★ 天坦行为：探索开始也先打断
                speech.interruptChannelA()
            }

            // ---------- 2097152: 触摸探索结束 ----------
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> {
                log("触摸探索结束")
            }

            // ---------- 4194304: 窗口集合变化 ----------
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                nodeCache.invalidateAll()
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
                // 不播报（太吵），但缓存要失效
                nodeCache.invalidateAll()
            }

            // ---------- 16384: 文本按粒度遍历（读屏软件的"按字/词/行"回调）----------
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> {
                val text = event.text?.joinToString("")?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    log("粒度遍历: $text")
                    speech.speak(text, interrupt = true)
                }
            }

            // ---------- 131072: 拖拽（TYPE_VIEW_DRAGGED 在公开 SDK 中不可见，用数值）----------
            131072 -> {
                val node = event.source
                val text = composeNode(node)
                if (!text.isNullOrBlank()) {
                    log("拖拽: $text")
                    speech.speak(text, interrupt = true)
                    haptic.focus()
                }
            }

            // ---------- 262144: 上下文点击（长按菜单）----------
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> {
                val node = event.source
                val text = composeNode(node)
                log("上下文点击: ${text ?: "(无内容)"}")
                sound.longClicked()
                haptic.longPress()
                if (!text.isNullOrBlank()) speech.speak(text, interrupt = true)
            }

            else -> {
                // 其他事件静默
            }
        }
    }

    /**
     * 通用节点事件处理（点击/选中）
     */
    private fun handleNodeEvent(event: AccessibilityEvent, tag: String, interrupt: Boolean) {
        val node = event.source
        val text = composeNode(node)
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

    /**
     * 窗口变化处理
     */
    private fun handleWindowChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        log("窗口: $pkg / $cls")

        sound.windowState()

        if (pkg.isNotEmpty()) {
            audioFocus.foregroundPackage = pkg
        }

        // 重置全部状态
        lastSpokenHash = -1
        lastHoverHash = -1
        dispatcher.resetFocusHash()
        focus.onWindowChanged()
        nodeCache.invalidateAll()

        // 播报页面标题
        val title = event.text?.joinToString("")?.trim().orEmpty()
        if (title.isNotEmpty()) speech.speak(title, interrupt = true)
    }

    /**
     * 滚动处理 —— 对应天坦 M6/r ProcessorScroll.e()
     *
     * 两层节流：① 250ms 时间窗 ② 同节点+同窗口去重
     */
    private fun handleScroll(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()

        // ① 时间节流
        if (now - lastScrollTime < SCROLL_THROTTLE_MS) return
        lastScrollTime = now

        // ★ 滚动后节点可见性变了 → 只失效判定/文本缓存（结构未变，子节点列表可留）
        nodeCache.invalidateFocus()

        val node = event.source
        val nodeKey = node?.hashCode() ?: -1
        val windowId = node?.windowId ?: -1

        // ② 节点去重
        if (nodeKey == lastScrollNodeKey && windowId == lastScrollWindowId) return
        lastScrollNodeKey = nodeKey
        lastScrollWindowId = windowId

        // ③ 滚动音效（速率随速度变化）
        val deltaY = event.scrollDeltaY
        val rate = if (deltaY != 0) {
            Math.pow(2.0, (deltaY / 50.0) - 1).toFloat().coerceIn(0.5f, 2.0f)
        } else 1.0f
        sound.scroll(rate)

        // ④ 播报滚动位置
        val total = event.itemCount
        if (total > 0 && event.toIndex >= 0) {
            val from = event.fromIndex
            val to = event.toIndex
            val msg = if (from == to || to < 0 || to + 1 > total) {
                "第 ${from + 1} 项，共 $total 项"
            } else {
                "第 ${from + 1} 到 ${to + 1} 项，共 $total 项"
            }
            log("滚动: $msg")
            speech.speak(msg, interrupt = true)
        } else {
            log("滚动")
        }
    }

    // ==================== 播报 ====================

    /**
     * 触摸探索播报 —— 天坦 M6/A.C()
     */
    private fun speakHover(node: AccessibilityNodeInfo) {
        val hash = node.hashCode()
        val now = SystemClock.uptimeMillis()

        if (hash == lastHoverHash && now - lastSpeakTime < 250) return

        val text = composeNode(node, includeRole = true)
        val roleName = if (text.isNullOrBlank()) {
            SpeechCompositor.roleName(NodeRoleHelper.roleOf(node), node)
        } else null

        if (text.isNullOrBlank() && roleName == null) {
            lastHoverHash = hash
            lastSpeakTime = now
            log("触摸: (无内容)")
            focus.focusForTouch(node)
            return
        }

        lastHoverHash = hash
        lastSpeakTime = now

        val spoken = text ?: roleName!!
        log("触摸: $spoken")
        speech.speak(spoken, interrupt = true)
        focus.focusForTouch(node)
        playFocusSound(node)
    }

    /**
     * 焦点变化播报
     */
    private fun speakNode(node: AccessibilityNodeInfo?, tag: String) {
        if (node == null) return

        val hash = node.hashCode()
        val now = SystemClock.uptimeMillis()
        if (hash == lastSpokenHash && now - lastSpeakTime < 300) return

        val text = composeNode(node, includeRole = true) ?: run {
            val role = SpeechCompositor.roleName(NodeRoleHelper.roleOf(node), node)
            if (role != null) {
                lastSpokenHash = hash
                lastSpeakTime = now
                log("$tag: $role")
                speech.speak(role, interrupt = true)
                playFocusSound(node)
            } else {
                log("$tag: (无内容)")
            }
            return
        }

        lastSpokenHash = hash
        lastSpeakTime = now
        log("$tag: $text")
        speech.speak(text, interrupt = true)
        playFocusSound(node)
    }

    /**
     * 播焦点音效 —— 天坦 z6/C4421m.java:898
     *
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
    }

    /**
     * 合成节点朗读文本 —— 走 NodeCache + SpeechCompositor
     */
    private fun composeNode(
        node: AccessibilityNodeInfo?,
        includeRole: Boolean = true
    ): String? {
        if (node == null) return null
        return nodeCache.composeText(node) {
            val raw = SpeechCompositor.compose(
                node = node,
                order = speechOrder,
                includeRole = includeRole,
                granularity = navigation.granularityLevel()
            )
            // ★ 应用朗读替换（两级作用域）
            replacement.apply(raw, node.packageName?.toString())
        }
    }

    /**
     * 天坦 AbstractC3688i.j(node) —— 节点是否可操作
     */
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

    // ==================== 手势 ====================

    override fun onGesture(gestureId: Int): Boolean {
        ReaderLog.i("Service", "onGesture(Int): $gestureId")
        return gestureChain.handle(gestureId, 0)
    }

    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        val id = gestureEvent.gestureId
        ReaderLog.i("Service", "onGesture(Event): $id")
        val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                gestureEvent.displayId
            } catch (e: Throwable) {
                0
            }
        } else 0
        return gestureChain.handle(id, displayId)
    }

    /**
     * 焦点移动 —— 供手势链调用
     */
    private fun moveFocusInternal(forward: Boolean): Boolean {
        val dir = if (forward) FocusManager.DIR_NEXT else FocusManager.DIR_PREVIOUS
        // ★ 走当前导航的模式与过滤器
        val node = focus.moveFocus(
            direction = dir,
            strategyType = navigation.strategyTypeCode(),
            filter = navigation.filter()
        ) ?: run {
            haptic.boundary()
            return false
        }
        speakNode(node, if (forward) "下一焦点" else "上一焦点")
        autoScroll.ensureVisible(node, if (forward) 1 else 2)
        return true
    }

    /**
     * 滚动 —— 供手势链调用
     */
    private fun scrollInternal(forward: Boolean): Boolean {
        val ok = focus.scroll(forward)
        if (ok) sound.scroll(if (forward) 1.1f else 0.9f)
        return ok
    }

    /**
     * 媒体手势
     */
    private fun handleMediaGesture(gestureId: Int): Boolean {
        val keyCode = when (gestureId) {
            Gestures.GESTURE_2_FINGER_SWIPE_LEFT -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            Gestures.GESTURE_2_FINGER_SWIPE_RIGHT -> KeyEvent.KEYCODE_MEDIA_NEXT
            Gestures.GESTURE_2_FINGER_SINGLE_TAP -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return false
        }
        sendMediaKey(keyCode)
        return true
    }

    /**
     * 映射手势（按 GestureMap 查表执行）
     */
    private fun handleMappedGesture(gestureId: Int): Boolean {
        return when (GestureMap.actionOf(gestureId)) {
            GestureAction.FOCUS_PREVIOUS -> moveFocusInternal(false)
            GestureAction.FOCUS_NEXT -> moveFocusInternal(true)
            GestureAction.SCROLL_BACKWARD -> scrollInternal(false)
            GestureAction.SCROLL_FORWARD -> scrollInternal(true)
            GestureAction.CLICK -> focus.clickTarget().also { if (it) haptic.click() }
            GestureAction.LONG_CLICK -> focus.longClickTarget()
            GestureAction.BACK -> { performGlobalAction(GLOBAL_ACTION_BACK); true }
            GestureAction.HOME -> { performGlobalAction(GLOBAL_ACTION_HOME); true }
            GestureAction.RECENTS -> { performGlobalAction(GLOBAL_ACTION_RECENTS); true }
            GestureAction.NOTIFICATIONS -> { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); true }
            GestureAction.STOP_SPEAK -> { speech.stop(); true }
            GestureAction.TOGGLE_SPEAK -> toggleSpeech()
            GestureAction.SCREENSHOT -> { performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); true }
            GestureAction.VOLUME_UP -> { adjustVolume(true); true }
            GestureAction.VOLUME_DOWN -> { adjustVolume(false); true }
            GestureAction.MEDIA_PREVIOUS, GestureAction.MEDIA_NEXT, GestureAction.MEDIA_PLAY_PAUSE ->
                handleMediaGesture(gestureId)
            GestureAction.GRANULARITY_CHARACTER -> setGranularity(GranularityNavigator.Level.CHARACTER)
            GestureAction.GRANULARITY_WORD -> setGranularity(GranularityNavigator.Level.WORD)
            GestureAction.GRANULARITY_LINE -> setGranularity(GranularityNavigator.Level.LINE)
            GestureAction.GRANULARITY_DEFAULT -> cycleGranularity()
            GestureAction.COPY -> { val ok = TextEditActions.copy(focus.getTargetNode()); speech.speak(if (ok) "已复制" else "复制失败", true); ok }
            GestureAction.PASTE -> { val ok = TextEditActions.paste(focus.getTargetNode()); speech.speak(if (ok) "已粘贴" else "粘贴失败", true); ok }
            GestureAction.CUT -> { val ok = TextEditActions.cut(focus.getTargetNode()); speech.speak(if (ok) "已剪切" else "剪切失败", true); ok }
            GestureAction.SELECT_ALL -> { val ok = TextEditActions.selectAll(focus.getTargetNode()); speech.speak(if (ok) "已全选" else "全选失败", true); ok }
            GestureAction.GLOBAL_MENU -> { showGlobalMenu(); true }
            GestureAction.SCREEN_RECOGNIZE -> { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN); true }
            GestureAction.NONE -> false
        }
    }

    private fun toggleSpeech(): Boolean {
        val enabled = !speech.enabled
        speech.enabled = enabled
        if (!enabled) speech.stop()
        speech.speak(if (enabled) "读屏已开启" else "读屏已关闭", interrupt = true)
        log("→ 读屏${if (enabled) "开" else "关"}")
        return true
    }

    private fun setGranularity(level: GranularityNavigator.Level): Boolean {
        GranularityNavigator.setLevel(level)
        nodeCache.invalidateAll()
        speech.speak(GranularityNavigator.levelName(level), interrupt = true)
        haptic.focus()
        return true
    }

    private fun cycleGranularity(): Boolean {
        val level = GranularityNavigator.cycle()
        nodeCache.invalidateAll()
        speech.speak(GranularityNavigator.levelName(level), interrupt = true)
        haptic.focus()
        return true
    }

    private fun adjustVolume(up: Boolean) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (up) android.media.AudioManager.ADJUST_RAISE
                else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val pct = if (max > 0) vol * 100 / max else 0
            speech.speakInterrupt("音量 $pct%")
            haptic.focus()
        } catch (e: Exception) {
            log("调音量失败: ${e.message}")
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            log("媒体键失败: ${e.message}")
        }
    }

    private fun isMediaActive(): Boolean = try {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        am.isMusicActive
    } catch (e: Exception) {
        false
    }

    private fun showGlobalMenu() {
        val items = arrayOf("语音设置", "焦点粒度", "停止朗读")
        android.app.AlertDialog.Builder(this)
            .setTitle("全局菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> log("→ 语音设置")
                    1 -> cycleGranularity()
                    2 -> speech.stop()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 坐标点击（焦点管理器兜底用） */
    fun dispatchGestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ==================== 触摸探索配置 ====================

    private fun enableTouchExploration() {
        try {
            val info = serviceInfo ?: run {
                log("serviceInfo 为 null，跳过")
                return
            }

            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = info.feedbackType or
                    (AccessibilityServiceInfo.FEEDBACK_SPOKEN or
                            AccessibilityServiceInfo.FEEDBACK_HAPTIC or
                            AccessibilityServiceInfo.FEEDBACK_AUDIBLE)
            info.notificationTimeout = 0L

            var flags = info.flags
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH.inv()
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            flags = flags or 4096
            flags = flags or AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME
            flags = flags or 128
            info.flags = flags

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                info.setInteractiveUiTimeoutMillis(10000)
            }
            serviceInfo = info

            ReaderLog.i("Service", "flags=0x${flags.toString(16)}")
        } catch (e: Exception) {
            log("开启触摸探索失败: ${e.message}")
        }
    }

    // ==================== 按键 ====================

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    speech.stop(clearQueue = false)
                    return false   // 不拦截，音量照常调
                }
            }
        }
        return false
    }

    override fun onInterrupt() {
        log("服务中断")
    }

    private fun isScreenOn(): Boolean = try {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.isInteractive
    } catch (e: Exception) {
        true
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false

        try { speech.shutdown() } catch (e: Exception) { }
        try { sound.shutdown() } catch (e: Exception) { }
        try { autoScroll.stop() } catch (e: Exception) { }
        try { focus.shutdown() } catch (e: Exception) { }
        try { eventQueue.clear() } catch (e: Exception) { }
        try { nodeCache.hardClear() } catch (e: Exception) { }
        try { gestureChain.clear() } catch (e: Exception) { }

        onLogUpdate = null
        synchronized(eventLog) { eventLog.clear() }

        log("服务已销毁")
    }

    // ==================== 工具 ====================

    private fun log(msg: String) {
        ReaderLog.i("Service", msg)
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.CHINA)
            .format(java.util.Date())
        val line = "[$time] $msg"
        synchronized(eventLog) {
            eventLog.addFirst(line)
            while (eventLog.size > MAX_LOG) eventLog.removeLast()
        }
        onLogUpdate?.invoke(line)
    }

    fun speakTest(text: String) {
        speech.speak(text, interrupt = true)
        haptic.focus()
        log("测试朗读: $text")
    }

    fun stats(): String = buildString {
        append("事件: ${dispatcher.stats()}\n")
        append("队列: ${eventQueue.stats()}\n")
        append("播报: ${speech.stats()}\n")
        append("焦点: ${focus.stats()}\n")
        append("缓存: ${nodeCache.stats()}\n")
        append("手势: ${gestureChain.stats()}")
    }

    /**
     * 重载 TTS 设置
     */
    fun reloadTtsSettings() {
        val newEngine = TtsEngineManager.getSavedEngine(this)
        if (newEngine != speech.enginePackage) {
            log("切换语音库: ${newEngine ?: "系统默认"}")
            speech.setEngine(newEngine)
        } else {
            speech.updateSettings(
                TtsEngineManager.getRate(this),
                TtsEngineManager.getPitch(this),
                TtsEngineManager.getVolume(this),
                TtsEngineManager.getLanguage(this)
            )
        }
    }

    fun currentEngine(): String? = speech.enginePackage

    // ==================== 输入法适配 ====================

    fun onImeTouchExplorationChanged(disable: Boolean) {
        try {
            val info = serviceInfo ?: return
            var flags = info.flags
            flags = if (disable) {
                flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            } else {
                flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
            info.flags = flags
            serviceInfo = info
            log("输入法请求触摸探索: ${if (disable) "关闭" else "开启"}")
        } catch (e: Exception) {
            Log.e(TAG, "onImeTouchExplorationChanged failed", e)
        }
    }

    fun onImePassthroughRegionChanged(region: android.graphics.Region?) {
        try {
            val r = if (region == null || region.isEmpty) android.graphics.Region() else region
            setTouchExplorationPassthroughRegion(android.view.Display.DEFAULT_DISPLAY, r)
        } catch (e: Exception) {
            Log.e(TAG, "onImePassthroughRegionChanged failed", e)
        }
    }

    fun speakFromIme(text: String, queueMode: Int) {
        speech.speak(text, interrupt = queueMode == 0)
    }

    fun interruptSpeech() {
        speech.stop()
        log("输入法中断播报")
    }

    /** 按文本查找并激活节点（输入法候选词上屏） */
    fun activateNodeByTexts(texts: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val textSet = texts.toHashSet()
        val target = findNodeByTexts(root, textSet, 0) ?: return false

        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isClickable && node.isEnabled) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                log("输入法激活: ${texts.firstOrNull()}, ok=$ok")
                return ok
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun findNodeByTexts(
        node: AccessibilityNodeInfo,
        texts: Set<String>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 20) return null
        val t = node.text?.toString()?.trim()
        val c = node.contentDescription?.toString()?.trim()
        if ((!t.isNullOrEmpty() && texts.contains(t)) ||
            (!c.isNullOrEmpty() && texts.contains(c))
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByTexts(child, texts, depth + 1)?.let { return it }
        }
        return null
    }

    // ==================== 对外兼容接口 ====================

    fun moveFocus(direction: Int): Boolean =
        focus.moveFocus(direction) != null

    fun clickFocused(): Boolean = focus.clickTarget()
}
