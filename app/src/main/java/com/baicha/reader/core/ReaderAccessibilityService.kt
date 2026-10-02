package com.baicha.reader.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 白茶读屏 —— 无障碍服务核心
 *
 * 架构 1:1 抄天坦 SoundBackService：
 *
 *   onAccessibilityEvent(event)
 *        ↓
 *   EventFunnel.shouldDrop(event)      ← 天坦 AccessibilityEventDispatcher
 *        ↓（不丢）
 *   handleEvent(event)                 ← 天坦 SoundBackService.handleEvent() 的 switch
 *        ├─ 1    点击        → speakNode
 *        ├─ 2    长按        → 提示音
 *        ├─ 4    选中        → speakNode
 *        ├─ 8    滚动        → 播报滚动位置
 *        ├─ 16   焦点        → speakNode
 *        ├─ 32   窗口变化    → 播报页面
 *        ├─ 64   无障碍焦点  → speakNode   ← 关键
 *        ├─ 128  触摸探索    → speakHover  ← 关键
 *        ├─ 2048 文本变化    → 播报文本
 *        ├─ 8192 选择变化    → 播报选中文本
 *        ├─ 32768 无障碍焦点 → speakNode   ← 关键
 *        ├─ 1048576 触摸探索开始
 *        ├─ 2097152 触摸探索结束
 *        └─ 4194304 窗口集合变化
 *        ↓
 *   NodeSpeaker.describe()  →  TtsManager.speak()
 */
class ReaderAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BaiChaReader"
        private const val MAX_LOG = 300

        /** 滚动节流窗口（天坦 ProcessorScroll 是 250ms） */
        private const val SCROLL_THROTTLE_MS = 250L

        val eventLog = ArrayDeque<String>(MAX_LOG)

        @Volatile
        var instance: ReaderAccessibilityService? = null
            private set

        @Volatile
        var isRunning = false
            private set

        var onLogUpdate: ((String) -> Unit)? = null
    }

    private lateinit var funnel: EventFunnel
    private lateinit var tts: TtsManager
    private lateinit var haptic: HapticFeedback
    private lateinit var focusController: FocusController
    private lateinit var autoScroll: AutoScrollManager
    private lateinit var audioFocus: AudioFocusManager
    private lateinit var sound: SoundManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 去重：上次朗读的节点哈希 */
    private var lastSpokenHash = -1
    private var lastSpeakTime = 0L

    /** 触摸探索：上次悬停节点 */
    private var lastHoverHash = -1

    /** 屏幕状态 */
    private var screenOn = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true

        // 初始化独立日志系统（写文件，不依赖 logcat）
        ReaderLog.init(this)
        ReaderLog.i("Service", "=== onServiceConnected ===")

        funnel = EventFunnel().apply {
            selfPackageName = packageName
            isScreenOn = true
        }
        tts = TtsManager(this)
        haptic = HapticFeedback(this)
        focusController = FocusController(this)
        autoScroll = AutoScrollManager(this, focusController)
        audioFocus = AudioFocusManager(this)
        sound = SoundManager(this)
        sound.preloadAll()   // 预加载苹果音效

        log("=== 服务已连接 ===")
        Log.i(TAG, "onServiceConnected")

        // 关键：开启触摸探索
        enableTouchExploration()

        tts.setOnReady {
            log("TTS 就绪")
            tts.speak("白茶读屏已启动")
            haptic.focus()
        }
    }

    /**
     * 开启触摸探索
     *
     * 【修正】原注释与代码矛盾：注释说"天坦不在代码里设 FLAG_REQUEST_TOUCH_EXPLORATION_MODE"，
     * 代码却明确设了。事实是 —— 天坦**两边都做**：
     *   ① XML 的 accessibilityFlags 声明（服务注册时生效）
     *   ② 运行时代码里再设一次（确保 MIUI/HyperOS 上不被系统重置）
     * 所以保留代码设置，并把注释改正。
     */
    private fun enableTouchExploration() {
        try {
            val info = serviceInfo ?: run {
                log("serviceInfo 为 null，跳过触摸探索设置")
                return
            }

            // 照天坦 AbstractC1945c.a()：
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = info.feedbackType or
                    (AccessibilityServiceInfo.FEEDBACK_SPOKEN or
                            AccessibilityServiceInfo.FEEDBACK_HAPTIC or
                            AccessibilityServiceInfo.FEEDBACK_AUDIBLE)
            info.notificationTimeout = 0L

            // flags：明确设置
            // 关键：必须设 FLAG_REQUEST_TOUCH_EXPLORATION_MODE，否则手势识别不工作
            var flags = info.flags

            // ① 明确打开触摸探索模式（手势识别的前提）
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE

            // ② 明确关闭双指穿透（会干扰手势识别）
            flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH.inv()

            // ③ 其他必要 flag
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            flags = flags or 4096   // FLAG_REQUEST_MULTI_FINGER_GESTURES
            flags = flags or AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME
            flags = flags or 128    // FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK

            info.flags = flags

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                info.setInteractiveUiTimeoutMillis(10000)
            }

            serviceInfo = info

            ReaderLog.i("Service", "flags set: 0x${flags.toString(16)} " +
                    "(touchExplorationFlag=${flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0})")
            Log.i(TAG, "serviceInfo set, flags=0x${flags.toString(16)}")
        } catch (e: Exception) {
            log("开启触摸探索失败: ${e.message}")
            Log.e(TAG, "enableTouchExploration failed", e)
        }
    }

    // ==================== 事件入口（天坦 onAccessibilityEvent）====================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 调试：记录所有事件类型（排查手势事件是否到达）
        val et = event.eventType
        if (et == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            et == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            et == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START ||
            et == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END ||
            et == AccessibilityEvent.TYPE_GESTURE_DETECTION_START ||
            et == AccessibilityEvent.TYPE_GESTURE_DETECTION_END
        ) {
            ReaderLog.i("Event", "type=$et")
        }

        // 屏幕状态跟踪（用 PowerManager，AccessibilityEvent 没有 SCREEN_ON/OFF 常量）
        val screenOnNow = isScreenOn()
        if (screenOnNow != screenOn) {
            screenOn = screenOnNow
            funnel.isScreenOn = screenOnNow
            log(if (screenOnNow) "屏幕亮起" else "屏幕关闭")
        }

        // 滚动事件 → 交给 AutoScrollManager（天坦 C2121b.i()）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            autoScroll.onScrollEvent(event)
        }

        // ① 过滤
        if (funnel.shouldDrop(event)) return

        // ② 分发
        try {
            handleEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "handleEvent error", e)
        }
    }

    /**
     * 事件分发 —— 1:1 抄天坦 SoundBackService.handleEvent() 的 switch
     */
    private fun handleEvent(event: AccessibilityEvent) {
        when (event.eventType) {

            // ---------- 1: 点击 ----------
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source

                val text = NodeSpeaker.describe(node)
                if (!text.isNullOrBlank()) {
                    log("点击: $text")
                    speak(text, interrupt = true)
                    sound.tick()
                    haptic.click()
                } else {
                    log("点击（无文本）")
                    sound.tick()
                }
            }

            // ---------- 2: 长按 ----------
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val text = NodeSpeaker.describe(event.source)
                log("长按: ${text ?: "无文本"}")
                sound.longClicked()
                haptic.longPress()
                if (!text.isNullOrBlank()) speak(text, interrupt = true)
            }

            // ---------- 4: 选中 ----------
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                val text = NodeSpeaker.describe(event.source)
                if (!text.isNullOrBlank()) {
                    log("选中: $text")
                    speak(text, interrupt = true)
                }
            }

            // ---------- 8: 滚动 ----------
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScroll(event)
            }

            // ---------- 16: 焦点 ----------
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                speakNode(event.source, "焦点")
            }

            // ---------- 32: 窗口变化 ----------
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                val cls = event.className?.toString().orEmpty()
                log("窗口: $pkg / $cls")

                // 音效：窗口切换
                sound.windowState()

                // 更新前台包名（给语音输入检测用）
                if (pkg.isNotEmpty()) {
                    audioFocus.foregroundPackage = pkg
                }

                lastSpokenHash = -1
                lastHoverHash = -1
                funnel.resetFocusHash()
                // 清焦点缓存（新页面重新定位）
                focusController.onWindowChanged()

                // 播报页面标题（天坦行为）
                val title = event.text?.joinToString("")?.trim().orEmpty()
                if (title.isNotEmpty()) speak(title, interrupt = true)
            }

            // ---------- 64: 无障碍焦点 ----------
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                // 去重：自己设的焦点不重复播报（抄 TalkBack isEventFromFocusManagement）
                if (focusController.history.isEventFromSelf(event)) {
                    log("无障碍焦点（自己设的，跳过播报）")
                } else {
                    speakNode(event.source, "无障碍焦点")
                }
            }

            // ---------- 64 的清除 ----------
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                log("焦点清除")
            }

            // ---------- 128: 触摸探索（手指摸到节点）----------
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                val node = event.source ?: return
                speakHover(node)
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
                    speak(text, interrupt = false)
                }
            }

            // ---------- 8192: 选择变化 ----------
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val node = event.source
                val text = node?.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    log("选择: $text")
                    speak(text, interrupt = false)
                }
            }

            // ---------- 1048576: 触摸探索开始 ----------
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> {
                log("触摸探索开始")
                funnel.isTouchExploring = true
            }

            // ---------- 2097152: 触摸探索结束 ----------
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> {
                log("触摸探索结束")
                funnel.isTouchExploring = false
            }

            // ---------- 4194304: 窗口集合变化 ----------
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                log("窗口集合变化")
            }

            // ---------- 通知 ----------
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text?.joinToString("")?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    log("通知: $text")
                    speak(text, interrupt = true)
                }
            }

            // ---------- 系统播报 ----------
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                val text = event.text?.joinToString("")?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    log("播报: $text")
                    speak(text, interrupt = true)
                }
            }

            // ---------- 内容变化 ----------
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 不播报，太吵（天坦也不播）
            }

            else -> {
                // 其他事件静默
            }
        }
    }

    // ==================== 滚动处理（抄天坦 ProcessorScroll）====================

    /** 上次滚动处理时间（250ms 节流） */
    private var lastScrollTime = 0L

    /** 上次滚动的节点标识（去重） */
    private var lastScrollNodeKey = -1

    /** 上次滚动的窗口 ID */
    private var lastScrollWindowId = -1

    /**
     * 滚动事件处理 —— 1:1 抄天坦 M6/r.java ProcessorScroll.e()
     *
     * 天坦的两层节流：
     * ① 时间节流：250ms 内的滚动事件全部丢弃（m(now) = now - last < 250）
     * ② 节点去重：同一节点 + 同一窗口的连续滚动，只播一次
     */
    private fun handleScroll(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()

        // ① 时间节流（天坦 250ms）
        if (now - lastScrollTime < SCROLL_THROTTLE_MS) return
        lastScrollTime = now

        val node = event.source
        val nodeKey = node?.hashCode() ?: -1
        val windowId = node?.windowId ?: -1

        // ② 节点去重：同一节点 + 同一窗口 → 跳过
        if (nodeKey == lastScrollNodeKey && windowId == lastScrollWindowId) {
            return
        }
        lastScrollNodeKey = nodeKey
        lastScrollWindowId = windowId

        // ③ 播滚动音效（天坦：速率随滚动速度变化）
        val deltaY = event.scrollDeltaY
        val rate = if (deltaY != 0) {
            Math.pow(2.0, (deltaY / 50.0) - 1).toFloat().coerceIn(0.5f, 2.0f)
        } else 1.0f
        sound.scroll(rate)

        // ④ 播报滚动位置（列表才有）
        val total = event.itemCount
        if (total > 0 && event.toIndex >= 0) {
            val msg = "第 ${event.toIndex + 1} 项，共 $total 项"
            log("滚动: $msg")
            speak(msg, interrupt = true)
        } else {
            log("滚动")
        }
    }

    // ==================== 播报 ====================

    /** 触摸探索：手指摸到节点 → 播报 + 震动 */
    private fun speakHover(node: AccessibilityNodeInfo) {
        val hash = node.hashCode()
        val now = SystemClock.uptimeMillis()

        // 同一节点 250ms 内不重复播报
        if (hash == lastHoverHash && now - lastSpeakTime < 250) return

        val text = NodeSpeaker.describe(node, includeRole = true)
        val role = if (text.isNullOrBlank()) NodeSpeaker.roleOf(node) else null

        if (text.isNullOrBlank() && role == null) {
            // 既无文本也无角色 → 不播报，但仍设焦点（保证滑动能从这继续）
            lastHoverHash = hash
            lastSpeakTime = now
            log("触摸: (无文本节点)")
            focusController.focusForTouch(node)
            return
        }

        // ★ 修正：原实现同时写 lastSpokenHash 和 lastHoverHash 两个独立去重窗口，
        // 导致后续 speakNode 的去重判定混乱。触摸只维护 lastHoverHash。
        lastHoverHash = hash
        lastSpeakTime = now

        val spoken = text ?: role!!
        log("触摸: $spoken")
        speak(spoken, interrupt = true)

        // 触摸探索也设无障碍焦点，让触摸焦点和滑动焦点统一
        focusController.focusForTouch(node)

        // 音效 + 震动 —— 统一走 playFocusSound
        playFocusSound(node)
    }

    /**
     * 天坦 AbstractC3688i.j(node) —— 判断节点"是否可操作"
     *
     *   j(node) = isFocusable || isClickable || isLongClickable
     *             || z(node, 1, 16, 32)              ← ACTION_FOCUS/CLICK/LONG_CLICK
     *             || y(node, 131072, 2048)           ← 单指上滑 / PREVIOUS_HTML_ELEMENT
     *
     * 【修正】原来第二个动作常量写的是 1024，天坦实际是 131072。
     */
    private fun isActionable(node: AccessibilityNodeInfo): Boolean {
        if (node.isFocusable) return true
        if (node.isClickable) return true
        if (node.isLongClickable) return true

        return try {
            node.actionList.any {
                it.id == 1 ||          // ACTION_FOCUS
                        it.id == 16 ||         // ACTION_CLICK
                        it.id == 32 ||         // ACTION_LONG_CLICK
                        it.id == 131072 ||     // ACTION_SCROLL_UP 之外的语义动作
                        it.id == 2048          // ACTION_PREVIOUS_HTML_ELEMENT
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 焦点变化播报 */
    private fun speakNode(node: AccessibilityNodeInfo?, tag: String) {
        if (node == null) return

        val hash = node.hashCode()
        val now = SystemClock.uptimeMillis()

        if (hash == lastSpokenHash && now - lastSpeakTime < 300) return

        val text = NodeSpeaker.describe(node, includeRole = true) ?: run {
            // 无文本 → 播报角色
            val role = NodeSpeaker.roleOf(node)
            if (role != null) {
                lastSpokenHash = hash
                lastSpeakTime = now
                log("$tag: $role")
                speak(role, interrupt = true)
                playFocusSound(node)
            } else {
                log("$tag: (无文本节点)")
            }
            return
        }

        lastSpokenHash = hash
        lastSpeakTime = now

        log("$tag: $text")
        speak(text, interrupt = true)
        playFocusSound(node)
    }

    /**
     * 播焦点音效 —— 按节点可操作性判定（和触摸探索一致）
     *
     * 天坦 z6/C4421m.java:898 真实逻辑：
     *   j(node)==true  → raw/focus_actionable
     *   j(node)==false → raw/focus
     */
    private fun playFocusSound(node: AccessibilityNodeInfo) {
        if (isActionable(node)) {
            sound.focusActionable()
            haptic.focusClickable()
        } else {
            sound.focus()
            haptic.focus()
        }
    }

    /**
     * 滑动浏览后主动播报当前焦点
     *
     * 为什么需要：滑动时我们调 performAction 设焦点，系统会回发
     * TYPE_VIEW_ACCESSIBILITY_FOCUSED 事件，但我们的去重逻辑
     * （isEventFromSelf）会把它当成「自己设的」跳过播报。
     * 结果焦点在动但没声音。
     *
     * 解决：滑动后主动播报，不依赖事件回调。
     */
    private fun speakCurrentFocus(tag: String) {
        val node = focusController.getFocused() ?: return
        val text = NodeSpeaker.describe(node, includeRole = true) ?: run {
            // 无文本 → 播报角色
            val role = NodeSpeaker.roleOf(node)
            if (role != null) {
                log("$tag: $role")
                speak(role, interrupt = true)
            } else {
                log("$tag: (无文本节点)")
            }
            // ★ 修正：统一走 playFocusSound，避免音效判定与别处不一致
            playFocusSound(node)
            return
        }

        lastSpokenHash = node.hashCode()
        lastSpeakTime = SystemClock.uptimeMillis()

        log("$tag: $text")
        speak(text, interrupt = true)
        playFocusSound(node)
    }

    private fun speak(text: String, interrupt: Boolean) {
        // 语音输入中 → 静音（不打断用户说话）
        if (audioFocus.shouldMute()) {
            Log.d(TAG, "muted (voice input active): $text")
            return
        }
        // 请求音频焦点（DUCK 模式，压低其他音频而不是抢断）
        audioFocus.requestFocus()
        tts.speak(text, interrupt)
    }

    // ==================== 手势（抄天坦 GestureController）====================

    override fun onGesture(gestureId: Int): Boolean {
        ReaderLog.i("Service", "onGesture(Int): $gestureId")
        return handleGesture(gestureId)
    }

    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        val id = gestureEvent.gestureId
        ReaderLog.i("Service", "onGesture(Event): $id")
        return handleGesture(id)
    }

    private fun handleGesture(gestureId: Int): Boolean {
        // 手势结束音效已去掉（用户反馈不需要）
        return routeGesture(gestureId)
    }

    private fun routeGesture(gestureId: Int): Boolean {
        // 46 手势完整映射 —— 抄天坦 GestureMap
        return when (GestureMap.actionOf(gestureId)) {

            GestureAction.FOCUS_PREVIOUS -> {
                val node = focusController.moveFocus(FocusController.Direction.PREVIOUS)
                if (node != null) {
                    speakNode(node, "上一焦点")
                    autoScroll.ensureVisible(node, 2)
                    true
                } else {
                    haptic.boundary()
                    false
                }
            }

            GestureAction.FOCUS_NEXT -> {
                val node = focusController.moveFocus(FocusController.Direction.NEXT)
                if (node != null) {
                    speakNode(node, "下一焦点")
                    autoScroll.ensureVisible(node, 1)
                    true
                } else {
                    haptic.boundary()
                    false
                }
            }

            GestureAction.SCROLL_BACKWARD -> {
                val ok = focusController.scroll(false)
                if (ok) sound.scroll(0.9f)
                ok
            }

            GestureAction.SCROLL_FORWARD -> {
                val ok = focusController.scroll(true)
                if (ok) sound.scroll(1.1f)
                ok
            }

            GestureAction.CLICK -> {
                val ok = focusController.clickFocused()
                if (ok) {
                    haptic.click()
                    log("→ 点击")
                } else {
                    haptic.error()
                    log("→ 点击失败")
                }
                ok
            }

            GestureAction.LONG_CLICK -> {
                val ok = focusController.longClickFocused()
                if (ok) haptic.longPress() else haptic.error()
                ok
            }

            GestureAction.BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                log("→ 返回")
                true
            }

            GestureAction.HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                log("→ 主屏")
                true
            }

            GestureAction.RECENTS -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                log("→ 最近任务")
                true
            }

            GestureAction.NOTIFICATIONS -> {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                log("→ 通知栏")
                true
            }

            GestureAction.GLOBAL_MENU -> {
                showGlobalMenu()
                true
            }

            GestureAction.GRANULARITY_CHARACTER -> {
                GranularityNavigator.setLevel(GranularityNavigator.Level.CHARACTER)
                speak("按字", interrupt = true)
                log("→ 粒度：按字")
                haptic.focus()
                true
            }

            GestureAction.GRANULARITY_WORD -> {
                GranularityNavigator.setLevel(GranularityNavigator.Level.WORD)
                speak("按词", interrupt = true)
                log("→ 粒度：按词")
                haptic.focus()
                true
            }

            GestureAction.GRANULARITY_LINE -> {
                GranularityNavigator.setLevel(GranularityNavigator.Level.LINE)
                speak("按行", interrupt = true)
                log("→ 粒度：按行")
                haptic.focus()
                true
            }

            GestureAction.GRANULARITY_DEFAULT -> {
                val level = GranularityNavigator.cycle()
                speak(GranularityNavigator.levelName(level), interrupt = true)
                log("→ 粒度：${GranularityNavigator.levelName(level)}")
                haptic.focus()
                true
            }

            GestureAction.COPY -> {
                val node = focusController.getTargetNode()
                val ok = TextEditActions.copy(node)
                speak(if (ok) "已复制" else "复制失败", interrupt = true)
                ok
            }
            GestureAction.PASTE -> {
                val node = focusController.getTargetNode()
                val ok = TextEditActions.paste(node)
                speak(if (ok) "已粘贴" else "粘贴失败", interrupt = true)
                ok
            }

            GestureAction.CUT -> {
                val node = focusController.getTargetNode()
                val ok = TextEditActions.cut(node)
                speak(if (ok) "已剪切" else "剪切失败", interrupt = true)
                ok
            }

            GestureAction.SELECT_ALL -> {
                val node = focusController.getTargetNode()
                val ok = TextEditActions.selectAll(node)
                speak(if (ok) "已全选" else "全选失败", interrupt = true)
                ok
            }

            GestureAction.STOP_SPEAK -> {
                tts.stop()
                log("→ 停止朗读")
                haptic.focus()
                true
            }

            GestureAction.TOGGLE_SPEAK -> {
                val enabled = !tts.isEnabled()
                tts.setEnabled(enabled)
                speak(if (enabled) "读屏已开启" else "读屏已关闭", interrupt = true)
                log("→ 读屏${if (enabled) "开" else "关"}")
                true
            }

            GestureAction.SCREENSHOT -> {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                log("→ 截图")
                true
            }

            GestureAction.SCREEN_RECOGNIZE -> {
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                log("→ 分屏")
                true
            }

            GestureAction.VOLUME_UP -> {
                // 【修正】原实现错误地执行了无障碍快捷键，与手势名不符。
                // 四指上下滑 → 真正调音量。
                adjustVolume(up = true)
                true
            }

            GestureAction.VOLUME_DOWN -> {
                adjustVolume(up = false)
                true
            }

            GestureAction.MEDIA_PREVIOUS,
            GestureAction.MEDIA_NEXT,
            GestureAction.MEDIA_PLAY_PAUSE -> {
                val keyCode = when (GestureMap.actionOf(gestureId)) {
                    GestureAction.MEDIA_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    GestureAction.MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                sendMediaKey(keyCode)
                true
            }

            GestureAction.NONE -> false
        }
    }

    /**
     * 调音量 —— 四指上下滑
     */
    private fun adjustVolume(up: Boolean) {
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val dir = if (up) android.media.AudioManager.ADJUST_RAISE
            else android.media.AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                dir,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val percent = if (max > 0) (vol * 100 / max) else 0
            speak("音量 $percent%", interrupt = true)
            log("→ 音量 ${if (up) "+" else "-"} ($percent%)")
            haptic.focus()
        } catch (e: Exception) {
            log("调音量失败: ${e.message}")
        }
    }

    /**
     * 发送媒体按键
     */
    private fun sendMediaKey(keyCode: Int) {
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            log("→ 媒体键 $keyCode")
        } catch (e: Exception) {
            log("媒体键失败: ${e.message}")
        }
    }

    /**
     * 全局菜单 —— 抄天坦的全局菜单（C2153r0）
     *
     * 弹出常用操作列表
     */
    private fun showGlobalMenu() {
        val items = arrayOf(
            "读屏设置",
            "语音设置",
            "手势设置",
            "焦点粒度",
            "屏幕识别",
            "停止朗读"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("全局菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> log("→ 读屏设置")
                    1 -> log("→ 语音设置")
                    2 -> log("→ 手势设置")
                    3 -> {
                        val level = GranularityNavigator.cycle()
                        speak(GranularityNavigator.levelName(level), interrupt = true)
                    }
                    4 -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    5 -> tts.stop()
                }
            }
            .setNegativeButton("取消", null)
            .show()
        log("→ 全局菜单")
    }

    /** 坐标点击（FocusController 兜底用） */
    fun dispatchGestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ==================== 按键 ====================

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    tts.stop()
                    log("音量键 → 停止朗读")
                    return false   // 不拦截，音量照常调
                }
            }
        }
        return false
    }

    override fun onInterrupt() {
        log("服务中断")
    }

    /** 屏幕是否亮着 */
    private fun isScreenOn(): Boolean {
        return try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isInteractive
        } catch (e: Exception) {
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        try {
            tts.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        try {
            sound.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        try {
            autoScroll.stop()
        } catch (e: Exception) {
            // ignore
        }
        focusController.shutdown()

        // ★ 修正：清静态引用，避免 Activity 回调悬挂导致泄漏
        onLogUpdate = null
        synchronized(eventLog) { eventLog.clear() }

        log("服务已销毁")
        Log.i(TAG, "onDestroy")
    }

    // ==================== 工具 ====================

    private fun log(msg: String) {
        // 用独立日志系统（写文件 + 内存 + logcat）
        ReaderLog.i("Service", msg)

        // 保留内存环形缓冲（App 内面板兼容）
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
        tts.speak(text, true)
        haptic.focus()
        log("测试朗读: $text")
    }

    fun stats(): String = "放行 ${funnel.passedCount} / 丢弃 ${funnel.droppedCount}"

    /**
     * 重新加载 TTS 设置（引擎/语速/音调/音量）
     * 供设置界面调用 —— 改完立刻生效，不用重启服务
     */
    fun reloadTtsSettings() {
        val newEngine = TtsEngineManager.getSavedEngine(this)
        if (newEngine != tts.enginePackage) {
            // 引擎变了 → 重建
            log("切换语音库: ${newEngine ?: "系统默认"}")
            tts.initEngine(newEngine)
        } else {
            // 引擎没变 → 只更新参数
            tts.setSpeechRate(TtsEngineManager.getRate(this))
            tts.setPitch(TtsEngineManager.getPitch(this))
            tts.setVolume(TtsEngineManager.getVolume(this))
            val voice = TtsEngineManager.getVoiceName(this)
            if (!voice.isNullOrEmpty()) tts.setVoice(voice)
            val lang = TtsEngineManager.getLanguage(this)
            if (!lang.isNullOrEmpty()) tts.setLanguage(lang)
        }
    }

    /** 当前使用的语音库包名 */
    fun currentEngine(): String? = tts.enginePackage

    // ==================== 天坦输入法适配回调 ====================

    /** 输入法请求开关触摸探索 */
    fun onImeTouchExplorationChanged(disable: Boolean) {
        try {
            val info = serviceInfo ?: return
            var flags = info.flags
            if (disable) {
                flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            } else {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
            info.flags = flags
            serviceInfo = info
            log("输入法请求触摸探索: ${if (disable) "关闭" else "开启"}")
        } catch (e: Exception) {
            Log.e(TAG, "onImeTouchExplorationChanged failed", e)
        }
    }

    /** 输入法设置触摸穿透区域（键盘区域） */
    fun onImePassthroughRegionChanged(region: android.graphics.Region?) {
        try {
            if (region == null || region.isEmpty) {
                // 清除穿透区域
                setTouchExplorationPassthroughRegion(
                    android.view.Display.DEFAULT_DISPLAY,
                    android.graphics.Region()
                )
                log("清除触摸穿透区域")
            } else {
                setTouchExplorationPassthroughRegion(
                    android.view.Display.DEFAULT_DISPLAY,
                    region
                )
                log("设置触摸穿透区域: $region")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onImePassthroughRegionChanged failed", e)
        }
    }

    /** 输入法直接调读屏播报 */
    fun speakFromIme(text: String, queueMode: Int) {
        // queueMode: 0 = QUEUE_FLUSH, 1 = QUEUE_ADD
        speak(text, interrupt = queueMode == 0)
    }

    /** 输入法请求中断播报 */
    fun interruptSpeech() {
        tts.stop()
        log("输入法中断播报")
    }

    /** 按文本列表查找并激活节点（输入法候选词上屏） */
    fun activateNodeByTexts(texts: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val textSet = texts.toHashSet()

        val target = findNodeByTexts(root, textSet, 0) ?: return false

        // 向上找可点击祖先
        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isClickable && node.isEnabled) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                log("输入法激活节点: ${texts.firstOrNull()}, ok=$ok")
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
            val found = findNodeByTexts(child, texts, depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun moveFocus(direction: FocusController.Direction): Boolean =
        focusController.moveFocus(direction) != null

    fun clickFocused(): Boolean = focusController.clickFocused()
}

// ==================== 手势常量（对齐天坦 C0905s$b）====================
const val GESTURE_SWIPE_UP = 1
const val GESTURE_SWIPE_DOWN = 2
const val GESTURE_SWIPE_LEFT = 3
const val GESTURE_SWIPE_RIGHT = 4
const val GESTURE_SWIPE_UP_AND_DOWN = 7
const val GESTURE_SWIPE_DOWN_AND_UP = 8
const val GESTURE_SWIPE_DOWN_AND_LEFT = 15
const val GESTURE_DOUBLE_TAP = 17
const val GESTURE_DOUBLE_TAP_AND_HOLD = 18
const val GESTURE_TWO_FINGER_SINGLE_TAP = 19
const val GESTURE_TWO_FINGER_DOUBLE_TAP = 20