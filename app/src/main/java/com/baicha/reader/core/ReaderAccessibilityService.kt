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

    /** 无障碍事件处理器（从本类抽出的事件分发职责） */
    lateinit var eventHandler: AccessibilityEventHandler
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

    /** 网页元素导航协调器（P1-3） */
    lateinit var webNavigator: WebElementNavigator
        private set

    /** 增量文本监控（文本差分）—— 实时更新界面只念变化部分 */
    lateinit var textMonitor: NodeTextMonitor
        private set

    /** 输入法适配器（候选词上屏 / 播报 / 打断） */
    lateinit var imeAdapter: ImeAdapter
        private set

    /**
     * TTS 系统设置监听（A5）—— 对标 TalkBack `FailoverTextToSpeech`
     * 与天坦 `Z6/J`
     */
    lateinit var ttsObserver: TtsSettingsObserver
        private set

    /**
     * 朗读期间防休眠（A5）—— 对标 TalkBack `ProcessorVolumeStream`
     * 与天坦 `Z6/J`
     */
    lateinit var speechWakeLock: SpeechWakeLock
        private set

    /**
     * 连续朗读（A4）—— 对标 TalkBack `FullScreenReadActor`
     */
    lateinit var continuousReader: ContinuousReader
        private set

    /**
     * 系统状态播报（静音/屏幕/锁屏/解锁）。
     *
     * 对标 TalkBack `RingerModeAndScreenMonitor` 与天坦 `W6/h`。
     */
    lateinit var systemState: SystemStateAnnouncer
        private set

    /**
     * 最近一次聚焦的节点路径 —— 用于跨屏幕状态/窗口变化的焦点恢复。
     *
     * 【为什么存路径而不是存节点引用】
     *   节点会被 RecyclerView 回收，引用失效后指向别的 item。
     *   路径是「特征描述」，可在新树里重新定位。
     */
    @Volatile
    private var lastFocusPath: NodePath? = null

    // ==================== 朗读设置 ====================

    /** 语序（对应天坦 GlobalConfig.z()） */
    @Volatile
    var speechOrder: SpeechOrder = SpeechOrder.TEXT_FIRST

    // ==================== 内部状态 ====================

    private val mainHandler = Handler(Looper.getMainLooper())

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
        // ★ 焦点监控器（多级回退：无障碍焦点 → 输入焦点 → 历史编辑框）
        focus.monitor = FocusMonitor(
            findAccessibilityFocus = {
                try {
                    findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                } catch (e: Throwable) {
                    null
                }
            },
            findInputFocus = {
                try {
                    rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (e: Throwable) {
                    null
                }
            },
            isImeWindowOnScreen = {
                try {
                    ImeWindowDetector.isInputWindowOnScreen(windows)
                } catch (e: Throwable) {
                    false
                }
            }
        )
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
            getLastFocusActionTime = { eventHandler.lastFocusActionTime },
            getLastFocusActionSource = { eventHandler.lastFocusActionSource }
        )
        speech = SpeechEngine(this).apply {
            enginePackage = TtsEngineManager.getSavedEngine(this@ReaderAccessibilityService)
            speechRate = TtsEngineManager.getRate(this@ReaderAccessibilityService)
            pitch = TtsEngineManager.getPitch(this@ReaderAccessibilityService)
            volume = TtsEngineManager.getVolume(this@ReaderAccessibilityService)
        }
        // ★ 朗读防休眠（A5）
        speechWakeLock = SpeechWakeLock(this) { msg -> log(msg) }
        // 播报开始/结束时持有/释放唤醒锁
        speech.onSpeakingStateChanged = { speaking ->
            if (speaking) speechWakeLock.acquire() else speechWakeLock.release()
        }

        // ★ TTS 系统设置监听（A5）
        //
        // 修复的问题：设置只在服务启动时读一次 ——
        // 用户在系统设置里改语速/引擎后白茶不生效，必须重启服务。
        ttsObserver = TtsSettingsObserver(
            context = this,
            onEngineChanged = { pkg ->
                // 【核实】TalkBack `FailoverTextToSpeech.updateDefaultEngine()`：
                //   "TalkBack will use the default TTS engine" ——
                //   空 pkg 表示回系统默认，同样需要重建引擎。
                // 天坦 `Z6/J` 亦然（重读字段后重建）。
                if (speech.enginePackage != pkg) {
                    speech.setEngine(pkg)
                    log("TTS 设置变更：引擎切换为 ${pkg ?: "系统默认"}")
                }
            },
            onPitchChanged = { pitch -> speech.pitch = pitch },
            onRateChanged = { rate -> speech.speechRate = rate },
            log = { msg -> log(msg) }
        )
        ttsObserver.register()

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
            // ★ 未实现导航的反馈（原先静默拒绝，用户以为读屏坏了）
            onUnimplemented = { mode ->
                speech.speak("${'$'}{mode.label}尚未实现", interrupt = true)
                haptic.error()
            }
            onModeChanged = { mode ->
                // 切换导航后清缓存（过滤器变了，判定结果不再有效）
                CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)
                // ★ 网页场景念「网页链接」而非「链接」—— 让用户听出模式
                //   对齐天坦 X6/z.b.f(context, useWeb)
                speech.speak(
                    NavigationLabel.of(mode, inWebContext()),
                    interrupt = true
                )
                haptic.focus()
                // ★ 持久化（P1-5）：下次服务重启仍是这个导航
                NavigationStore.save(this@ReaderAccessibilityService, mode)
                // ★ 光标重定位（P1-6）
                //
                // 切到「按字」/「按词」时，旧的光标位置会让系统
                // 从一个错误起点开始算 —— 第一个词可能从半个字开始念。
                // 对齐 TalkBack resetNavigableNodesSelection()。
                CursorRepositioner.onGranularityChanged(
                    node = focus.getFocused(),
                    microGranularity = CursorRepositioner.isMicroGranularity(granularityLevel())
                )
                log("导航 → ${mode.label}")
            }
            // ★ 恢复上次导航（P1-5）
            //
            // 用 restore() 而非 setMode()：恢复配置不该有播报/震动，
            // 否则每次开机都会莫名念一遍导航名。
            restore(NavigationStore.load(this@ReaderAccessibilityService))
        }

        // ---- 辅助组件 ----
        haptic = HapticFeedback(this)
        audioFocus = AudioFocusManager(this)
        sound = SoundManager(this).apply { preloadAll() }
        autoScroll = AutoScrollManager(this, focus)
        webNavigator = WebElementNavigator()
        imeAdapter = ImeAdapter(this)
        // ★ 系统状态播报（A1）—— 注册广播
        //
        // 播报走 speech，与朗读替换/静音闸门共享同一条路径，
        // 保证静音时系统播报也不会出声。
        systemState = SystemStateAnnouncer(
            context = this,
            speak = { text, interrupt -> speech.speak(text, interrupt = interrupt) },
            log = { msg -> log(msg) }
        )
        systemState.register()

        // ★ 连续朗读（A4）
        //
        // 修复的问题：`EventDispatcher.isFullScreenReading` 字段声明了、
        // 在规则 4 里被读取，但**没有任何地方置为 true** —— 功能完全不可达。
        continuousReader = ContinuousReader(
            state = ContinuousReadState(),
            moveNext = {
                // 推进 = 一次普通的下一个焦点导航
                //（照 TalkBack moveForward()：CRM 复用普通导航，不另造机制）
                val node = focus.moveFocus(
                    direction = FocusManager.DIR_NEXT,
                    strategyType = TraversalStrategyFactory.TYPE_DEFAULT,
                    filter = navigation.filter()
                )
                if (node != null) {
                    speakNode(node, "连续朗读")
                    autoScroll.ensureVisible(node, 1)
                    true
                } else {
                    false   // 走到底
                }
            },
            playComplete = { sound.complete() },
            speak = { t -> speech.speak(t, interrupt = true) },
            currentText = { focus.getFocused()?.let { composeNode(it) } },
            log = { msg -> log(msg) }
        )
        // ★ 实例化手势动作处理器
        //
        // 【本发现的一个严重缺陷】`gestureActions` 声明为 lateinit
        // 却**从未初始化** —— 而它在 3 处被使用：
        //   · isMediaPlaying = { gestureActions.isMediaActive() }
        //   · onMappedAction = { gestureActions.handleMappedGesture(id) }
        //   · handleMediaGesture → gestureActions.handleMappedGesture(id)
        // 首次手势就会抛 UninitializedPropertyAccessException，
        // 整个无障碍服务崩溃 —— 用户彻底失去辅助。
        gestureActions = GestureActionHandler(
            context = this,
            onMoveFocus = { forward -> moveFocusInternal(forward) },
            onScroll = { forward -> scrollInternal(forward) },
            getTargetNode = { focus.getFocused() },
            speak = { text, interrupt -> speech.speak(text, interrupt = interrupt) },
            stopSpeech = { speech.stop() },
            // 读屏开关状态直接读写 SpeechEngine 的 enabled ——
            // 它就是「读屏是否出声」的唯一真相
            isSpeechEnabled = { speech.enabled },
            setSpeechEnabled = { enabled -> speech.enabled = enabled },
            navigation = navigation,
            invalidateCache = { CacheRegistry.invalidate(CacheRegistry.Level.CONTENT) },
            onClickTarget = { focus.clickTarget() },
            onLongClickTarget = { focus.longClickTarget() }
        )
        // 连续朗读切换（A4）
        gestureActions.continuousReadToggle = {
            continuousReader.toggle()
            true
        }

        // ★ 把连续朗读状态接进事件分发器（A4）
        //   规则 4 依赖它：全屏朗读中忽略 HOVER_ENTER，
        //   否则触摸探索的事件会打断朗读流。
        //   原来该字段无人置 true，规则 4 永不成立。
        dispatcher.fullScreenReadState = { continuousReader.state.isActive }
        dispatcher.fullScreenReadInterrupt = { type, time, isBar ->
            continuousReader.onEvent(type, time, isBar)
        }
        // ★ 增量文本监控（文本差分）—— 实时更新界面只念变化部分
        textMonitor = NodeTextMonitor().apply {
            enabled = true
            onIncrementalText = { delta -> speech.speak(delta, interrupt = false) }
        }

        // ---- 事件处理器（从本类抽出）----
        eventHandler = AccessibilityEventHandler(
            speech = speech,
            sound = sound,
            haptic = haptic,
            focus = focus,
            autoScroll = autoScroll,
            voiceScroll = voiceScroll,
            compose = { node -> composeNode(node) },
            log = { msg -> log(msg) },
            audioFocus = audioFocus,
            screenRecovery = screenRecovery,
            textMonitor = textMonitor
        ).apply {
            isScreenOnProvider = { isScreenOn() }
            onWindowChangedHook = {
                // 服务侧的窗口变化清理
                lastSpokenIdentity = -1
                lastHoverIdentity = -1
                dispatcher.resetFocusHash()
            }
        }

        // ---- 事件消费：队列 → 事件处理器 ----
        eventQueue.consumer = { event -> eventHandler.handleEvent(event) }

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

        // ---- ★ 粒度移动委托（用系统 API 移动光标，而非找下一个节点）----
        focus.granularityMover = { node, forward ->
            val level = navigation.granularityLevel()
            val supported = GranularityMover.isSupported(node, level)
            if (supported) {
                GranularityMover.move(node, level, forward)
            } else {
                false   // 系统不支持 → 回退到普通遍历
            }
        }
        // ★ 采集焦点路径 —— 供恢复时按特征找回（抗节点回收）
        //
        // 修复的真机问题：原来 restoreInitialFocus 无条件取第一个节点，
        // 导致息屏亮屏/切换 App 后焦点跳到页面顶部。
        focus.onFocusApplied = { node ->
            val path = NodePath.of(node)
            if (path != null) lastFocusPath = path
        }

        // ★ 滚动定位委托（跳到顶部/底部）—— P1-1
        //
        // 对齐 TalkBack ACTION_SCROLL_TO_POSITION：
        //   向上找可滚动容器 → 发 (row, column) → App 自己一步到位。
        // 不用 ACTION_SCROLL_FORWARD 循环：依赖滚动量与惯性，跳不准。
        focus.scrollJump = { top ->
            val current = focus.getFocused() ?: rootInActiveWindow
            val ok = if (top) ScrollToPosition.scrollToTop(current)
            else ScrollToPosition.scrollToBottom(current)
            if (ok) {
                // 容器已接受 → 焦点位置由滚动事件回带。
                // 标记「滚动进行中」，避免用户在容器还在滚时又猛点导航。
                focus.onScrollStart(if (top) FocusManager.DIR_PREVIOUS else FocusManager.DIR_NEXT)
                autoScroll.onScrollSettled?.invoke()
            }
            ok
        }
        // ★ 滚动结束时显式结束「滚动中」状态（避免隐式依赖 TTL）
        autoScroll.onScrollSettled = { focus.onScrollEnd() }
        // ★ 提供窗口根给 BFS 兜底（嵌套滚动场景）
        autoScroll.rootProvider = { rootInActiveWindow }

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
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "webElementNavigator") {
            webNavigator.clear()
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
    // 事件分发已抽到 AccessibilityEventHandler（见该类注释）

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
        eventHandler.recordFocusAction(
            ScreenStateRecovery.FOCUS_SOURCE_TOUCH_EXPLORATION, focus.getFocused()
        )
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
                includeRole = includeRole
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
        //
        // 【注意】此处只记时间戳 —— 目标节点要等遍历完成后才知道。
        // 带节点的完整记录在下面焦点设上之后补记（见 applyFocus 之后）。
        eventHandler.recordFocusAction(
            ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION
        )

        // ★ 窗口导航模式：直接跨窗口，不做窗口内遍历
        //
        // 对应天坦导航码 9（WINDOWS_NAVIGATION）。
        // 这个导航的本质就是「切到下一个窗口的第一个焦点」。
        if (navigation.current == NavigationMode.WINDOWS_NAVIGATION) {
            val cross = WindowTraversal.navigateToNextWindow(
                service = this,
                forward = forward,
                // ★ 用「应用窗口」过滤器：排除状态栏/导航栏/输入法
                //   否则用户会滑到状态栏的时间电量上，打断浏览
                filter = WindowTraversal.applicationWindows(this)
            )
            if (cross != null) {
                if (focus.applyFocus(cross)) {
                    eventHandler.speakNode(cross, "窗口导航")
                    return true
                }
            }
            haptic.boundary()
            log("窗口导航：无更多窗口")
            return false
        }

        // ★ 网页元素导航（P1-3）—— 必须在普通遍历之前
        //
        // 对应 TalkBack `CursorGranularityManager.navigateWeb()`：
        // 在 WebView 里，「链接」「标题」「控件」三种导航走
        // ACTION_NEXT_HTML_ELEMENT，由 WebView 按 HTML 语义查找。
        //
        // 【为什么优先于树遍历】
        //   WebView 的无障碍树是惰性展开的，远处的链接/标题根本不在树里，
        //   自己遍历会「找不到就报边界」——用户以为到底了，其实没有。
        if (navigation.current.let { it == NavigationMode.LINKS ||
                it == NavigationMode.HEADINGS || it == NavigationMode.CONTROLS }) {
            val cur = focus.getFocused()
            if (cur != null && webNavigator.tryNavigate(cur, navigation.current, forward)) {
                log("网页元素导航：${navigation.current.label}")
                // 位置已由 WebView 处理，新焦点由事件回带
                return true
            }
        }

        // ★ 滚动定位（跳到顶部/底部）—— P1-1
        //
        // 对应 TalkBack `performActionScrollToPosition()`。
        // 方向来自当前导航枚举，而不是手势方向 ——
        // 「跳到顶部」是一个动作，向上/向下滑动对它没有意义。
        when (navigation.current) {
            NavigationMode.SCROLL_TOP -> focus.setScrollJumpDirection(top = true)
            NavigationMode.SCROLL_BOTTOM -> focus.setScrollJumpDirection(top = false)
            else -> {}
        }

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
        // 焦点已设上 → 补记带节点信息的完整历史（A6）
        eventHandler.recordFocusAction(
            ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION, node
        )
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
     * 当前是否处于网页上下文。
     *
     * 【判定依据】当前焦点节点是否暴露 HTML 元素动作 ——
     * 这是 WebView 自己声明的能力，比猜包名可靠
     * （对齐 TalkBack `WebInterfaceUtils.supportsWebActions()`）。
     */
    private fun inWebContext(): Boolean {
        val node = focus.getFocused() ?: return false
        return webNavigator.supportsWebActions(node)
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
        shutdownSafely("systemState") { systemState.unregister() }
        shutdownSafely("continuousReader") { continuousReader.shutdown() }
        shutdownSafely("ttsObserver") { ttsObserver.unregister() }
        shutdownSafely("speechWakeLock") { speechWakeLock.releaseAll() }

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

    /**
     * 恢复初始焦点 —— 对应 TalkBack pipeline.input(WindowChange)
     *
     * 策略：找本窗口内第一个可聚焦节点并设焦点。
     */
    /**
     * 恢复焦点 —— 修复「永远跳到第一个节点」
     *
     * ══════════════════════════════════════════════════════════
     * 【原来错在哪】
     * ══════════════════════════════════════════════════════════
     *
     * 原实现无条件调 `FocusSearch.findFirstFocus()` ——
     * 名字叫「恢复焦点」，行为是「聚焦第一个节点」。
     *
     * 真机后果：息屏亮屏 / 切换 App 后，焦点**无条件跳到页面顶部**。
     * 用户读了半屏内容，锁屏再解锁就回到开头。
     *
     * ══════════════════════════════════════════════════════════
     * 【现在的策略（三路降级）】
     * ══════════════════════════════════════════════════════════
     *
     *   ① 有路径记录 → NodePathResolver 按路径特征找回（抗节点回收）
     *   ② 路径找不到 → 退回「第一个可聚焦节点」（原来的行为）
     *
     * 路径在每次成功聚焦时采集（见 `focus.onFocusApplied`）。
     */
    private fun restoreInitialFocus(force: Boolean) {
        val root = rootInActiveWindow ?: return

        // ⓪ 优先用**本窗口的历史记录**的路径（A6）
        //
        // 【为什么优先于全局 lastFocusPath】
        //   lastFocusPath 是「最后一次聚焦的节点」，可能来自**别的窗口**。
        //   例如：在 A 应用聚焦某处 → 切到 B 应用 → B 触发恢复，
        //   这时用全局路径会在 B 的树里找一个属于 A 的节点（找不到，
        //   或错误命中同文本的节点）。
        //   按 windowId 查历史能拿到「本窗口上次焦点在哪」。
        //
        // 对应 TalkBack `getLastFocusActionRecordInWindow()`（:253）——
        // 白茶此前只有全局单值，正是 A6 要补的能力。
        val windowId = try {
            root.windowId
        } catch (e: Throwable) {
            -1
        }
        val windowPath = if (windowId >= 0) {
            eventHandler.focusHistory.lastInWindow(windowId)?.nodePath
        } else {
            null
        }

        // ① 路径恢复：先试本窗口历史，再退回全局
        val restored = NodePathResolver.resolve(root, windowPath)
            ?: NodePathResolver.resolve(root, lastFocusPath)
        if (restored != null && ShouldFocusNode.shouldFocus(restored)) {
            if (focus.applyFocus(restored, force = true)) {
                eventHandler.recordFocusAction(
                    ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION, restored
                )
                eventHandler.speakNode(restored, "恢复焦点(路径)")
                log(
                    "恢复焦点：按路径找回成功" +
                        if (windowPath != null) "（本窗口历史）" else "（全局路径）"
                )
                return
            }
        }

        // ② 路径不可用 → 退回第一个（保持原行为，不静默失败）
        log(
            if (lastFocusPath == null) "恢复焦点：无路径记录 → 取第一个"
            else "恢复焦点：路径未命中 → 取第一个"
        )

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
                eventHandler.recordFocusAction(
                    ScreenStateRecovery.FOCUS_SOURCE_LOGICAL_NAVIGATION, first
                )
                eventHandler.speakNode(first, "恢复焦点")
            }
        } else {
            log("恢复焦点：本窗口无可聚焦节点")
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
        // ⚠️ **当前无调用方** —— 因为还没有朗读替换规则的编辑界面
        //    （`ui/` 只有 MainActivity 与 TtsSettingsActivity）。
        //
        // 规则目前在服务启动时由 `ReadReplacementStore.loadWithBuiltin()`
        // 加载一次；用户改规则后没有重载路径。
        //
        // 【待做】做规则编辑界面时，保存后调用本方法即可生效。
        // 本方法实现是完整的，不是占位。
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

    // ---- 输入法适配（委托给 ImeAdapter）----

    /**
     * 候选词上屏（输入法请求）。
     *
     * 【本轮变更】原 `activateNodeByTexts` 只能走节点点击，
     * 且没有调用方。现改走 [ImeAdapter] 的三级降级链：
     * 输入连接 → 节点点击 → 明确失败。
     *
     * ⚠️ **当前无调用方 —— 不是遗漏，是缺外部入口**
     *
     * 无障碍服务由系统绑定（`BIND_ACCESSIBILITY_SERVICE`），
     * 第三方 App **无法**通过 `bindService` 拿到它的 Binder，
     * 所以本方法在应用内没有任何可达路径。
     *
     * 天坦的做法（`net/tatans/soundback/TatansImeService.java`，457 行）：
     *   另起一个普通 `Service`，通过 AIDL
     *   （`ITatansImeConnection`，十几个方法）暴露给自家输入法，
     *   输入法 bindService 后调用它完成上屏/播报。
     *
     * 【待做】新增 `ImeConnectionService` + AIDL 接口 + Manifest 声明：
     *   <service android:name=".core.ImeConnectionService"
     *            android:exported="true"
     *            android:permission="com.baicha.reader.permission.IME_CONNECTION">
     *     <intent-filter>
     *       <action android:name="com.baicha.reader.IME_CONNECTION" />
     *     </intent-filter>
     *   </service>
     *
     * 见 `docs/框架层审查报告-2026-10-02.md` 的 BUG-2。
     */
    fun commitImeCandidate(text: String): Boolean {
        val method = imeAdapter.commitCandidate(text)
        if (method != null) {
            log("候选词上屏(${method.label}): $text")
            return true
        }
        log("候选词上屏失败: $text")
        return false
    }

    fun speakFromIme(text: String, queueMode: Int) {
        imeAdapter.speakCandidate(text, queueMode, speech)
    }

    fun interruptSpeech() {
        imeAdapter.interruptSpeech(speech)
        log("输入法中断播报")
    }

    /** 删除光标前字符（输入法退格） */
    fun deleteBackward(count: Int = 1): Boolean = imeAdapter.deleteBackward(count)

    /**
     * 当前是否可接收输入法文字
     *
     * ⚠️ **当前无调用方** —— 与 [commitImeCandidate] 同一原因
     * （缺 AIDL 入口），见上方说明。
     */
    fun canCommitImeText(): Boolean = imeAdapter.canCommit()

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
