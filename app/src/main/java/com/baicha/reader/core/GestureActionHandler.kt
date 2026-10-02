package com.baicha.reader.core

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 手势动作处理器 —— 从 `ReaderAccessibilityService` 抽出
 *
 * 【为什么抽出来】
 *
 * 原服务类 1196 行、38 个方法，混合了「服务生命周期 + 事件分发 + 手势动作 +
 * 播报 + 工具方法」五类职责，可维护性差。
 *
 * 本类只负责**「手势 ID → 具体动作」的映射执行**，
 * 依赖通过构造注入，可独立测试与替换。
 *
 * 【设计】
 *   服务提供三个能力回调（移动焦点 / 滚动 / 播报），
 *   本类负责其余全部手势动作（系统动作、媒体、音量、粒度、编辑、菜单）。
 */
class GestureActionHandler(
    private val context: Context,
    /** 移动焦点 —— 由服务提供 */
    private val onMoveFocus: (forward: Boolean) -> Boolean,
    /** 滚动 —— 由服务提供 */
    private val onScroll: (forward: Boolean) -> Boolean,
    /** 取当前操作目标节点 */
    private val getTargetNode: () -> AccessibilityNodeInfo?,
    /** 播报 */
    private val speak: (text: String, interrupt: Boolean) -> Unit,
    /** 停止播报 */
    private val stopSpeech: () -> Unit,
    /** 读屏开关状态读写 */
    private val isSpeechEnabled: () -> Boolean,
    private val setSpeechEnabled: (Boolean) -> Unit,
    /** 导航管理器 */
    private val navigation: NavigationManager,
    /** 清缓存（粒度变化后调用） */
    private val invalidateCache: () -> Unit,
    /** 点击当前目标（兜底路径用） */
    private val onClickTarget: () -> Boolean = { false },
    /** 长按当前目标（兜底路径用） */
    private val onLongClickTarget: () -> Boolean = { false }
) {
    /**
     * 连续朗读切换钩子（A4）—— 由服务注入。
     *
     * 【为什么用钩子而不是直接持有 ContinuousReader】
     *   与 granularityMover / scrollJump 同构：本类不持有朗读引擎，
     *   保持可测；具体实现由服务层注入。
     *
     * @return 是否处理成功
     */
    var continuousReadToggle: (() -> Boolean)? = null


    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 全局动作执行器（系统级操作，由服务注入） */
    @Volatile
    var performGlobalAction: ((Int) -> Boolean)? = null

    // ==================== 主入口 ====================

    /**
     * 执行按映射表派发的手势
     *
     * @return true = 已处理
     */
    fun handleMappedGesture(gestureId: Int): Boolean {
        return when (GestureMap.actionOf(gestureId)) {
            // ---- 点击/长按：由 GestureChain 硬编码优先处理，这里兜底 ----
            GestureAction.CLICK -> onClickTarget()
            GestureAction.LONG_CLICK -> onLongClickTarget()

            // ---- 导航 ----
            GestureAction.FOCUS_PREVIOUS -> onMoveFocus(false)
            GestureAction.FOCUS_NEXT -> onMoveFocus(true)
            GestureAction.SCROLL_BACKWARD -> onScroll(false)
            GestureAction.SCROLL_FORWARD -> onScroll(true)

            // ---- 系统动作 ----
            GestureAction.BACK -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            GestureAction.HOME -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            GestureAction.NOTIFICATIONS -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.SCREENSHOT -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            GestureAction.SCREEN_RECOGNIZE -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

            // ---- 播报控制 ----
            GestureAction.STOP_SPEAK -> { stopSpeech(); true }
            GestureAction.TOGGLE_SPEAK -> toggleSpeech()

            // ---- 音量 ----
            GestureAction.VOLUME_UP -> { adjustVolume(true); true }
            GestureAction.VOLUME_DOWN -> { adjustVolume(false); true }

            // ---- 媒体 ----
            // ★ 传**动作**而非 gestureId —— 见 handleMediaGesture 的说明：
            //   按 gestureId 分派会与映射表产生第二份真相，且分支不可达。
            GestureAction.MEDIA_PREVIOUS, GestureAction.MEDIA_NEXT, GestureAction.MEDIA_PLAY_PAUSE ->
                handleMediaGesture(GestureMap.actionOf(gestureId))

            // ---- 粒度（保留旧的 4 粒度切换）----
            GestureAction.GRANULARITY_CHARACTER -> setGranularity(GranularityNavigator.Level.CHARACTER)
            GestureAction.GRANULARITY_WORD -> setGranularity(GranularityNavigator.Level.WORD)
            GestureAction.GRANULARITY_LINE -> setGranularity(GranularityNavigator.Level.LINE)
            GestureAction.GRANULARITY_DEFAULT -> cycleGranularity()

            // ---- 连续朗读（A4）----
            // 已开启则停止，未开启则从当前位置开始
            GestureAction.CONTINUOUS_READ -> continuousReadToggle?.invoke() ?: false

            // ---- 文本编辑 ----
            GestureAction.COPY -> runEditAction(EditAction.COPY)
            GestureAction.PASTE -> runEditAction(EditAction.PASTE)
            GestureAction.CUT -> runEditAction(EditAction.CUT)
            GestureAction.SELECT_ALL -> runEditAction(EditAction.SELECT_ALL)

            // ---- 菜单 ----
            GestureAction.GLOBAL_MENU -> { showGlobalMenu(); true }

            GestureAction.NONE -> false
        }
    }

    private fun global(action: Int): Boolean = performGlobalAction?.invoke(action) ?: false

    // ==================== 播报开关 ====================

    private fun toggleSpeech(): Boolean {
        val enabled = !isSpeechEnabled()
        setSpeechEnabled(enabled)
        if (!enabled) stopSpeech()
        speak(if (enabled) "读屏已开启" else "读屏已关闭", true)
        return true
    }

    // ==================== 粒度 ====================

    private fun setGranularity(level: GranularityNavigator.Level): Boolean {
        GranularityNavigator.setLevel(level)
        invalidateCache()
        speak(GranularityNavigator.levelName(level), true)
        return true
    }

    private fun cycleGranularity(): Boolean {
        val level = GranularityNavigator.cycle()
        invalidateCache()
        speak(GranularityNavigator.levelName(level), true)
        return true
    }

    // ==================== 音量 ====================

    private fun adjustVolume(up: Boolean) {
        val am = audioManager ?: return
        try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pct = if (max > 0) vol * 100 / max else 0
            speak("音量 $pct%", true)
        } catch (e: Exception) {
            ReaderLog.w("GestureAction", "调音量失败: ${e.message}")
        }
    }

    // ==================== 媒体 ====================

    /**
     * 媒体手势 —— 按**映射表给出的动作**发对应的媒体键
     *
     * ══════════════════════════════════════════════════════════
     * 【★ 本轮修复：原来的三条分支全部与映射表不符，且全部不可达】
     * ══════════════════════════════════════════════════════════
     *
     * 原实现按**手势 ID** 判断：
     * ```kotlin
     *   val keyCode = when (gestureId) {
     *       Gestures.GESTURE_2_FINGER_SWIPE_LEFT  -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
     *       Gestures.GESTURE_2_FINGER_SWIPE_RIGHT -> KeyEvent.KEYCODE_MEDIA_NEXT
     *       Gestures.GESTURE_2_FINGER_SINGLE_TAP  -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
     *       else -> return false
     *   }
     * ```
     *
     * 【矛盾一：与映射表不符】`GestureMap.actionOf()` 的实际映射是：
     * | 手势 | 映射表给的动作 | 原实现以为的媒体键 |
     * |---|---|---|
     * | 27 双指左滑 | `SCROLL_FORWARD` | MEDIA_PREVIOUS ✗ |
     * | 28 双指右滑 | `SCROLL_BACKWARD` | MEDIA_NEXT ✗ |
     * | 19 双指单击 | `STOP_SPEAK` | MEDIA_PLAY_PAUSE ✗ |
     * | 20 双指双击 | `MEDIA_PLAY_PAUSE` | （未列出）✗ |
     * | 35 四指左滑 | `MEDIA_PREVIOUS` | （未列出）✗ |
     * | 36 四指右滑 | `MEDIA_NEXT` | （未列出）✗ |
     *
     * 【矛盾二：三条分支全都不可达】本方法**只**由
     * `handleMappedGesture()` 的媒体分支调用：
     * ```kotlin
     *   GestureAction.MEDIA_PREVIOUS, GestureAction.MEDIA_NEXT,
     *   GestureAction.MEDIA_PLAY_PAUSE -> handleMediaGesture(gestureId)
     * ```
     * 也就是说，能走到这里的 `gestureId` **必然**满足
     * `actionOf(gestureId) ∈ {MEDIA_*}` ——
     * 而 19/27/28 分别映射到 STOP_SPEAK / SCROLL_FORWARD / SCROLL_BACKWARD，
     * **永远不会**进入这个分支；反之真正会进来的 20/35/36
     * 又不在 `when` 里，落到 `else -> return false`。
     *
     * ⇒ 净效果：**媒体按键按下去永远返回 false（什么都不做）**，
     *    即「上一首/下一首/播放暂停」三个功能**完全不可用**，
     *    且因为返回 false，链会继续往下走，可能被别的 Actor 当成
     *    普通手势处理 —— 用户按四指左滑想切歌，结果焦点动了。
     *
     * 【修复】改为按**动作**分派（与映射表同源），不再重复编码手势 ID：
     * 这样「映射表改了什么，这里就跟着变什么」，不会再出现两份真相。
     *
     * 【为什么不再从 gestureId 反查】那种做法要求本方法自己维护一份
     * 「手势 → 媒体键」的副本 —— 正是产生本次矛盾的根源。
     */
    private fun handleMediaGesture(action: GestureAction): Boolean {
        val keyCode = when (action) {
            GestureAction.MEDIA_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            GestureAction.MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            GestureAction.MEDIA_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return false
        }
        return sendMediaKey(keyCode)
    }

    private fun sendMediaKey(keyCode: Int): Boolean {
        val am = audioManager ?: return false
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            ReaderLog.w("GestureAction", "媒体键失败: ${e.message}")
            false
        }
    }

    /** 是否有媒体在播放（媒体 Actor 的激活条件） */
    fun isMediaActive(): Boolean = try {
        audioManager?.isMusicActive ?: false
    } catch (e: Exception) {
        false
    }

    // ==================== 编辑动作 ====================

    /**
     * 执行编辑动作 —— 走动态动作列表校验
     *
     * 若节点不支持该动作，播报失败原因而不是静默。
     */
    private fun runEditAction(action: EditAction): Boolean {
        val node = getTargetNode()
        if (node == null) {
            speak("${action.label}失败", true)
            return false
        }
        // 先检查该节点是否真的支持这个动作（天坦的动态动作列表机制）
        val available = EditActionExecutor.availableActions(node)
        if (available.isNotEmpty() && !available.contains(action)) {
            speak("当前不支持${action.label}", true)
            return false
        }
        val ok = EditActionExecutor.execute(action, node)
        speak(if (ok) "已${action.label}" else "${action.label}失败", true)
        return ok
    }

    // ==================== 全局菜单 ====================

    private fun showGlobalMenu() {
        val items = arrayOf("语音设置", "导航模式", "焦点粒度", "停止朗读")
        android.app.AlertDialog.Builder(context)
            .setTitle("全局菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ReaderLog.i("Menu", "→ 语音设置")
                    1 -> {
                        val mode = navigation.next()
                        ReaderLog.i("Menu", "→ 导航: ${mode.label}")
                    }
                    2 -> cycleGranularity()
                    3 -> stopSpeech()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
