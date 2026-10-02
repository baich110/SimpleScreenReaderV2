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

        /** 无标签元素的兜底播报文本 */
        private const val UNTAGGED = "未加标签"

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

    /** 手势动作处理器（从本类抽出的动作执行职责） */
    lateinit var gestureActions: GestureActionHandler
        private set

    /** 语音输入滚动追踪器（识别输入法引发的滚动） */
    lateinit var voiceScroll: VoiceScrollTracker
        private set

    /** 屏幕状态变化后的焦点恢复（对应 TalkBack FocusProcessorForScreenStateChange） */
    lateinit var screenRecovery: ScreenStateRecovery
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

    /**
     * 最近一次焦点动作的时间与来源
     *
     * 供 ScreenStateRecovery 做「用户在窗口切换期间是否已操作」的竞态判定。
     * 对应 TalkBack 的 AccessibilityFocusActionHistory。
     */
    @Volatile
    private var lastFocusActionTime = 0L

    @Volatile
    private var lastFocusActionSource = 0

    /** 记录一次焦点动作 */
    private fun recordFocusAction(source: Int) {
        lastFocusActionTime = SystemClock.uptimeMillis()
        lastFocusActionSource = source
    }

    /**
     * 去重：上次朗读的节点**身份**
     *
     * 【修正】原来用 `node.hashCode()`（内容 hash）。
     * AccessibilityNodeInfo.hashCode() 基于内容（text/desc/bounds/className），
     * 导致内容相同的不同节点（两个"确定"按钮、格式雷同的列表项）被误判为重复，
     * 第二个直接不朗读 —— 这是「焦点在动但不朗读」的原因之一。
     *
     * 改用 System.identityHashCode（对象身份），与天坦 HashSet<o0.w> 的语义一致。
     */
    private var lastSpokenIdentity = -1
    private var lastSpeakTime = 0L

    /** 触摸探索：上次悬停节点的身份 */
    private var lastHoverIdentity = -1

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
        // ★ 初始化屏幕参数（排序算法依赖，天坦 C1423r.M(Context)）
        NodeOrderComparator.initFromContext(this)

        // ★ 登记所有缓存的失效回调 —— 避免"忘了挂钩子"（见 CacheRegistry 注释）
        registerCacheHandlers()

        // ★ 把缓存注入到热点路径（排序 + 可聚焦判定）
        NodeOrderComparator.cache = nodeCache
        ShouldFocusNode.cache = nodeCache

        focus = FocusManager(this)
        screenRecovery = ScreenStateRecovery(
            getCurrentFocus = { focus.getFocused() },
            getActiveWindowId = {
                try {
                    windows?.firstOrNull { it.isActive }?.id ?: -1
                } catch (e: Throwable) {
                    -1
                }
            },
            isWindowAccessibilityFocused = {
                try {
                    windows?.firstOrNull { it.isActive }?.isAccessibilityFocused ?: false
                } catch (e: Throwable) {
                    false
                }
            },
            onRestoreFocus = { force -> restoreInitialFocus(force) },
            getLastFocusActionTime = { lastFocusActionTime },
            getLastFocusActionSource = { lastFocusActionSource }
        )
        speech = SpeechEngine(this).apply {
            enginePackage = TtsEngineManager.getSavedEngine(this@ReaderAccessibilityService)
            speechRate = TtsEngineManager.getRate(this@ReaderAccessibilityService)
            pitch = TtsEngineManager.getPitch(this@ReaderAccessibilityService)
            volume = TtsEngineManager.getVolume(this@ReaderAccessibilityService)
        }
        gestureChain = GestureChain()
        // ★ 加载朗读替换规则 —— 修复「引擎建了但从未加载规则」的静默失效
        replacement = ReadReplacementEngine().also { engine ->
            try {
                val n = ReadReplacementStore.loadWithBuiltin(this, engine)
                if (n > 0) log("朗读替换: 加载 $n 条规则，启用=${engine.enabled}")
            } catch (e: Throwable) {
                ReaderLog.w("Service", "朗读替换规则加载失败: ${e.message}")
            }
        }
        voiceScroll = VoiceScrollTracker().apply {
            // 麦克风占用检测复用音频焦点管理器
            isMicrophoneInUse = {
                try {
                    audioFocus.shouldMute()
                } catch (e: Throwable) {
                    false
                }
            }
        }
        navigation = NavigationManager().apply {
            onModeChanged = { mode ->
                // 切换导航后清缓存（过滤器变了，判定结果不再有效）
                CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)
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

        // ---- ★ 语音输入静音闸门（对应天坦 SpeechController 的录音检测）----
        //
        // 原来 AudioFocusManager.shouldMute() 写好了却从未被调用 ——
        // 用户在语音输入时读屏仍会朗读，把用户的话盖掉。
        // 接进 SpeechEngine 的播报入口后，所有播报路径统一受控。
        speech.speechGate = { audioFocus.shouldMute() }

        // ---- 焦点管理委托 ----
        focus.onCancelScroll = { autoScroll.stop() }
        // ★ 滚动结束时显式结束「滚动中」状态（避免隐式依赖 TTL）
        autoScroll.onScrollSettled = { focus.onScrollEnd() }

        // ---- ★ 滚动焦点恢复回调 —— 对应天坦 M6/h 内部类 a ----
        //
        // 滚动失败（重试 100 次仍无果）时，兜底尝试跨窗口导航。
        // 这是「滚动到底部后焦点丢失」的补救路径。
        autoScroll.recoveryCallback = object : ScrollRecoveryCallback {
            override fun onAutoScrolled(boundsChanged: Boolean) {
                log("滚动完成（边界变化=$boundsChanged）")
            }

            override fun onAutoScrollFailed() {
                log("滚动失败 → 尝试跨窗口兜底")
                // 兜底：用跨窗口导航找下一个焦点
                val node = WindowTraversal.navigateToNextWindow(
                    service = this@ReaderAccessibilityService,
                    forward = true
                )
                if (node != null) {
                    if (focus.applyFocus(node)) {
                        log("跨窗口兜底成功: ${focus.desc(node)}")
                        speakNode(node, "跨窗口")
                    }
                } else {
                    log("跨窗口兜底：无可用窗口")
                }
            }

            override fun assumeScrollSuccess(): Boolean {
                // 默认不假定成功（保持天坦的保守行为）
                return false
            }
        }

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
     * 登记缓存失效回调 —— 集中管理，避免遗漏
     *
     * 【为什么集中登记】
     *   上一轮 TatansTraversal 的索引缓存就是因为「有人在别处手动 clear，
     *   漏了它」而出的 bug。集中登记后，新增缓存只需加一行 register。
     */
    private fun registerCacheHandlers() {
        // ---- 窗口/内容变化：全部清 ----
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "nodeCache") {
            nodeCache.invalidateAll()
        }
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "boundsCache") {
            NodeBoundsCalculator.clear()
        }
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "traversalIndex") {
            TatansTraversal.invalidateIndexCache()
        }

        // ---- 滚动：只清可见性相关（结构没变）----
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "focusAndText") {
            nodeCache.invalidateFocus()
        }
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "boundsCache") {
            NodeBoundsCalculator.clear()
        }

        // ---- 配置变化：屏幕参数重算 + 全部失效 ----
        CacheRegistry.register(CacheRegistry.Level.CONFIG, "screenMetrics") {
            NodeOrderComparator.initFromContext(this)
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
            isMediaPlaying = { gestureActions.isMediaActive() },
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
            onMappedAction = { id, _ -> gestureActions.handleMappedGesture(id) }
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

            // ---------- 32768: 无障碍焦点 ----------
            // 【修正】原注释写「64 / 32768，值同为 32768」是错的：
            //   32768 = TYPE_VIEW_ACCESSIBILITY_FOCUSED
            //   64    = TYPE_VIEW_HOVER_EXIT（另一个分支处理）
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                val text = composeNode(event.source)
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
                CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)
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
                CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)
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
        lastSpokenIdentity = -1
        lastHoverIdentity = -1
        dispatcher.resetFocusHash()
        focus.onWindowChanged()
        autoScroll.resetScrollState()
        voiceScroll.reset()
        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)

        // 播报页面标题
        val title = event.text?.joinToString("")?.trim().orEmpty()
        if (title.isNotEmpty()) speech.speak(title, interrupt = true)

        // ★ 屏幕状态变化后的焦点恢复 —— 对应 TalkBack onScreenStateChanged
        //
        // TalkBack 的延迟逻辑：窗口切换刚开始（耗时 < 500ms）时先等一等，
        // 因为「会先播报窗口标题，用户感觉不到延迟」，同时给窗口切换留出时间。
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

    /**
     * 执行屏幕状态恢复
     */
    private fun performScreenStateRecovery(transitionStart: Long) {
        try {
            val ok = screenRecovery.onScreenStateChanged(transitionStart)
            if (ok) {
                log("屏幕状态恢复: ${screenRecovery.stats()}")
            }
        } catch (e: Throwable) {
            ReaderLog.w("Service", "屏幕状态恢复失败: ${e.message}")
        }
    }

    /**
     * 恢复初始焦点 —— 对应 TalkBack 的 pipeline.input(WindowChange)
     *
     * 策略：
     *   ① 优先用焦点历史里的锚点位置找最近的节点
     *   ② 退化到「窗口内第一个可聚焦节点」
     */
    private fun restoreInitialFocus(force: Boolean) {
        val root = rootInActiveWindow ?: return

        // ① 找第一个可聚焦（本窗口）
        val strategy = TraversalStrategyFactory.createDefault(
            root = root,
            filter = { ShouldFocusNode.shouldFocus(it) }
        )
        val first = try {
            FocusSearch.findFirstFocus(
                traversal = strategy,
                root = root,
                direction = TraversalDirection.FORWARD,
                filter = { ShouldFocusNode.shouldFocus(it) }
            )
        } finally {
            strategy.release()
        }

        if (first != null) {
            if (focus.applyFocus(first)) {
                recordFocusAction(ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION)
                speakNode(first, "恢复焦点")
            }
        } else {
            log("恢复焦点：本窗口无可聚焦节点")
        }
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

        // ★ 滚动后节点可见性变了 → 只失效判定/文本/边界（结构未变）
        CacheRegistry.invalidate(CacheRegistry.Level.SCROLL)

        val node = event.source
        val nodeKey = node?.hashCode() ?: -1
        val windowId = node?.windowId ?: -1

        // ② 节点去重
        if (nodeKey == lastScrollNodeKey && windowId == lastScrollWindowId) return
        lastScrollNodeKey = nodeKey
        lastScrollWindowId = windowId

        // ③ 滚动音效（速率随速度动态变化）—— 对应天坦 M6/r.e()
        sound.scroll(autoScroll.computeEarconRate(event))

        // ④ ★ 标记滚动状态（供 FocusManager 的"滚动中导航"拦截使用）
        //
        // 方向来自 AutoScrollManager 判定好的三级结果
        // （比直接用 scrollDeltaY 可靠 —— 该字段在很多 App 上恒为 -1）
        val scrollDir = when (autoScroll.lastDetectedDirection) {
            ScrollDirectionDetector.FORWARD -> 1
            ScrollDirectionDetector.BACKWARD -> -1
            else -> 0
        }
        if (scrollDir != 0) {
            focus.onScrollStart(scrollDir)
        }

        // ⑤ 识别语音输入触发的滚动 —— 对应天坦 M6/r.h()
        //
        // 用户在语音输入时，输入法会自动滚列表（候选词翻页等）。
        // 这类滚动不该播报位置，否则会盖住用户正在说的话。
        val userAction = voiceScroll.identify(event.eventTime, node)
        if (voiceScroll.shouldSuppressAnnouncement(userAction)) {
            log("滚动（语音输入触发，静默）")
            return
        }

        // ⑥ 播报滚动位置 —— 走统一格式化（对应天坦 M6/r.f()）
        val msg = ScrollPositionFormatter.format(
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount,
            isPager = node?.let { NodeRoleHelper.roleOf(it) == NodeRoleHelper.ROLE_PAGER } ?: false
        )
        if (msg != null) {
            log("滚动: $msg")
            speech.speak(msg, interrupt = true)
        } else {
            log("滚动（无位置信息）")
        }
    }

    // ==================== 播报 ====================

    /**
     * 触摸探索播报 —— 天坦 M6/A.C()
     */
    private fun speakHover(node: AccessibilityNodeInfo) {
        // 【修正】同 speakNode：改用身份比较
        val identity = System.identityHashCode(node)
        val now = SystemClock.uptimeMillis()

        if (identity == lastHoverIdentity && now - lastSpeakTime < 250) return

        val text = composeNode(node, includeRole = true)
        val roleName = if (text.isNullOrBlank()) {
            SpeechCompositor.roleName(NodeRoleHelper.roleOf(node), node)
        } else null

        if (text.isNullOrBlank() && roleName == null) {
            lastHoverIdentity = identity
            lastSpeakTime = now
            // 【修正】原来静默 —— 触摸到无标签元素时完全没有反馈。
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
        // 记录触摸探索动作（供屏幕状态恢复的竞态判定）
        recordFocusAction(ScreenStateRecovery.FOCUS_SOURCE_TOUCH_EXPLORATION)
        focus.focusForTouch(node)
        playFocusSound(node)
    }

    /**
     * 焦点变化播报
     */
    private fun speakNode(node: AccessibilityNodeInfo?, tag: String) {
        if (node == null) return

        // 【修正】身份比较，不用内容 hash
        val identity = System.identityHashCode(node)
        val now = SystemClock.uptimeMillis()
        if (identity == lastSpokenIdentity && now - lastSpeakTime < 300) return

        val text = composeNode(node, includeRole = true) ?: run {
            val role = SpeechCompositor.roleName(NodeRoleHelper.roleOf(node), node)
            if (role != null) {
                lastSpokenIdentity = identity
                lastSpeakTime = now
                log("$tag: $role")
                speech.speak(role, interrupt = true)
                playFocusSound(node)
            } else {
                // 【修正】原来这里是静默 return —— 焦点在动却完全没有声音，用户以为读屏坏了。
                // 天坦行为：至少有兜底内容。这里播「未加标签」提示，让用户知道焦点确实移动了。
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
        // 记录逻辑导航动作（供屏幕状态恢复的竞态判定）
        recordFocusAction(ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION)

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

    /** 媒体手势（委托给 GestureActionHandler） */
    private fun handleMediaGesture(gestureId: Int): Boolean =
        gestureActions.handleMappedGesture(gestureId)


    /**
     * 映射手势（按 GestureMap 查表执行）
     */
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

    /**
     * 配置变化（横竖屏切换等）—— 对应天坦 SoundBackService.onConfigurationChanged
     *
     * 【为什么必须处理】
     *   屏幕尺寸/方向变了 → 排序算法的阈值全部失效 → 必须重算。
     *   同时缓存的边界与判定也要失效（布局变了）。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        ReaderLog.i("Service", "配置变化: orientation=${newConfig.orientation}")

        // ① 重算屏幕参数 + 全部缓存失效（布局变了）
        CacheRegistry.invalidate(CacheRegistry.Level.CONFIG)

        // ③ 焦点状态重置
        focus.onWindowChanged()
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

        // 逐个关闭资源：任一失败不影响其余，但记录日志便于排查
        // （原来是静默 catch，关闭失败无从发现）
        shutdownSafely("speech") { speech.shutdown() }
        shutdownSafely("sound") { sound.shutdown() }
        shutdownSafely("autoScroll") { autoScroll.stop() }
        shutdownSafely("focus") { focus.shutdown() }
        shutdownSafely("eventQueue") { eventQueue.clear() }
        shutdownSafely("nodeCache") { nodeCache.hardClear() }
        shutdownSafely("boundsCache") { NodeBoundsCalculator.clear() }
        shutdownSafely("gestureChain") { gestureChain.clear() }

        onLogUpdate = null
        synchronized(eventLog) { eventLog.clear() }

        log("服务已销毁")
    }

    // ==================== 工具 ====================

    /**
     * 安全关闭辅助 —— 捕获异常但记录日志
     *
     * 关闭路径必须「尽力而为」：任一组件关闭失败不能影响其他组件，
     * 但也不能静默吞掉（否则资源泄漏无从发现）。
     */
    private inline fun shutdownSafely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            ReaderLog.w("Shutdown", "$name 关闭失败: ${e.message}")
        }
    }

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

    /**
     * 重新加载朗读替换规则 —— 供设置界面调用
     *
     * 【为什么需要】
     *   `ReadReplacementEngine` 是内存态，用户改完规则要立即生效；
     *   同时替换结果被 `NodeCache` 缓存着，必须清掉。
     */
    fun reloadReplacementRules() {
        try {
            val n = ReadReplacementStore.loadWithBuiltin(this, replacement)
            CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)
            log("朗读替换: 重新加载 $n 条规则，启用=${replacement.enabled}")
        } catch (e: Throwable) {
            ReaderLog.w("Service", "重载替换规则失败: ${e.message}")
        }
    }

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
