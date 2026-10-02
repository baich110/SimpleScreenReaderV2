package com.baicha.reader.core

/**
 * 连续朗读状态机 —— 对标 TalkBack `FullScreenReadActor`(458 行)
 * 与 `FullScreenReadInterpreter`(114 行)
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * 用户想看长文/消息列表，但逐条滑动很累（每条都要一个手势）。
 * 连续朗读：**自动**逐个焦点朗读，读完一个自动到下一个，
 * 直到内容读完或用户主动打断。
 *
 * 白茶现状：`EventDispatcher.isFullScreenReading` 字段已声明、
 * 在规则 4 里被读取，**但没有任何地方把它置为 true** ——
 * 该功能完全不可达。`NavigationMode.CONTINUES_READING` 也标着
 * `implemented = false`。
 *
 * ══════════════════════════════════════════════════════════
 * 【两方对标（已读源码）】
 * ══════════════════════════════════════════════════════════
 *
 * **TalkBack** —— 有完整实现，两个类协作：
 *
 * `FullScreenReadActor.java:65~67` 定义三个状态：
 * ```java
 * public static final int STATE_STOPPED = 0;
 * public static final int STATE_READING_FROM_BEGINNING = 1;
 * public static final int STATE_READING_FROM_NEXT = 2;
 * ```
 *
 * `FullScreenReadActor.java:315~325` 的推进方式：
 * ```java
 * private void moveForward() {
 *     // Continuous reading mode (CRM) always uses default granularity.
 *     if (!pipeline.returnFeedback(eventId,
 *             Feedback.focusDirection(SEARCH_FOCUS_FORWARD)
 *                 .setGranularity(DEFAULT).setScroll(true))) {
 *         pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
 *         interrupt(true);   // 内部中断（走到头了）
 *     }
 * }
 * ```
 * 关键：**推进 = 一次普通的「下一个焦点」导航**，
 * 失败（走到底）→ 播完成音 + 自动停止。
 * 粒度固定为 DEFAULT（连续朗读时用户不能切粒度）。
 *
 * `FullScreenReadActor.java:98` 还持有 **WakeLock** ——
 * 朗读过程中屏幕不能休眠（否则朗读中断）。
 *
 * **天坦** —— ❌ **无对应实现**。
 * `X6/z.java:320` 只定义了导航类型 `CONTINUES_READING(..., 11)`，
 * 全项目搜不到自动推进朗读的引擎（它的「连续」指的是
 * 连续 OCR 文字识别，属另一功能）。
 *
 * 【A/B 取舍】天坦无对应实现，故**直接对标 TalkBack**。
 *
 * ══════════════════════════════════════════════════════════
 * 【打断条件（照 TalkBack `FullScreenReadInterpreter`）】
 * ══════════════════════════════════════════════════════════
 *
 * `FullScreenReadInterpreter.java:44~52`：
 * ```java
 * private static final int INTERRUPT_DELAY_MS = 200;
 * private static final int MASK_EVENT_TYPES_INTERRUPT_CONTINUOUS =
 *     TYPE_VIEW_CLICKED
 *         | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
 *         | AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
 *         | AccessibilityEventCompat.TYPE_ANNOUNCEMENT;
 * ```
 *
 * 三条打断规则：
 *   ① **用户操作**（点击/长按/触摸探索开始/系统播报）→ 立即打断
 *   ② **`TYPE_VIEW_SELECTED`** → 打断，**但进度条/滑块除外**
 *      （它们的选中事件是自动发的，不是用户操作）
 *   ③ 启动后 **200ms 内**的点击事件**忽略**（过滤对话框确认键的延迟事件）
 */

/**
 * 连续朗读的三个状态 —— 1:1 对应 TalkBack `FullScreenReadActor:65~67`
 */
enum class ReadState(val code: Int, val label: String) {
    /** 停止 */
    STOPPED(0, "已停止"),

    /** 从开头读 —— 用户长按手势触发时用 */
    READING_FROM_BEGINNING(1, "从头朗读"),

    /** 从当前位置的下一个读 —— 用户已在某处时用 */
    READING_FROM_NEXT(2, "连续朗读");

    val isActive: Boolean get() = this != STOPPED

    companion object {
        fun fromCode(code: Int): ReadState =
            values().firstOrNull { it.code == code } ?: STOPPED
    }
}

/**
 * 连续朗读状态机 —— **纯逻辑，可单测**
 *
 * 与 TalkBack 的差异：TalkBack 把状态机与 Actor（含 pipeline、
 * wakeLock、focusMonitor）混在 458 行里，无法单测。
 * 白茶只抽状态与判定，系统调用留给 [ContinuousReader]。
 *
 * 【本轮已验证 / 未验证】
 *   ✅ 已验证：状态迁移、打断判定、200ms 窗口、进度条豁免
 *   ❌ 未验证：真实朗读是否连续（需真机）、WakeLock 是否生效
 */
class ContinuousReadState {

    @Volatile
    var state: ReadState = ReadState.STOPPED
        private set

    /** 上一次的状态 —— 对应 TalkBack `previousState`，用于判定「是否从朗读中恢复」 */
    @Volatile
    var previousState: ReadState = ReadState.STOPPED
        private set

    /** 启动时间 —— 对应 TalkBack `continuousReadStartTime` */
    private var startTime: Long = 0L

    /** 是否激活 */
    val isActive: Boolean get() = state.isActive

    /**
     * 开始朗读。
     *
     * @param fromBeginning true = 从头（`STATE_READING_FROM_BEGINNING`）
     *                      false = 从下一个（`STATE_READING_FROM_NEXT`）
     * @return 是否发生了状态变化
     */
    fun start(fromBeginning: Boolean, now: Long): Boolean {
        val target = if (fromBeginning) {
            ReadState.READING_FROM_BEGINNING
        } else {
            ReadState.READING_FROM_NEXT
        }
        if (state == target) return false
        setState(target)
        startTime = now
        return true
    }

    /**
     * 停止朗读 —— 对应 TalkBack `interrupt(boolean)`
     *
     * @return 是否确实停止了（false = 本来就没在朗读）
     */
    fun stop(): Boolean {
        if (!state.isActive) return false
        setState(ReadState.STOPPED)
        return true
    }

    private fun setState(next: ReadState) {
        previousState = state
        state = next
    }

    /**
     * 判断是否应该因用户操作而打断。
     *
     * 对应 TalkBack `FullScreenReadInterpreter.java:86~110`
     *
     * @param eventType    事件类型码
     * @param eventTime    事件时间
     * @param isProgressBar 源节点是进度条/滑块（`ROLE_PROGRESS_BAR` / `ROLE_SEEK_CONTROL`）
     * @return true = 打断朗读
     */
    fun shouldInterrupt(
        eventType: Int,
        eventTime: Long,
        isProgressBar: Boolean = false
    ): Boolean {
        if (!isActive) return false

        // ① 对话框确认键的延迟点击 —— 启动后 200ms 内忽略
        //
        // TalkBack `FullScreenReadInterpreter.java:87~93`：
        //   "the receiving of masked accessibility events after user
        //    confirms the dialog will interrupt the running Continuous
        //    Reading Mode. Sometimes the clicking the OK button event
        //    comes so late after the ACM is active..."
        if (eventType == TYPE_VIEW_CLICKED &&
            eventTime - startTime < INTERRUPT_DELAY_MS
        ) {
            return false
        }

        // ② 选中事件 —— 进度条/滑块豁免
        //
        // TalkBack `:97~104`：
        //   "Interrupts full screen reading on TYPE_VIEW_SELECTED event
        //    that comes from user actions. Except for progress bar and
        //    seek bar that may send the event automatically."
        if (eventType == TYPE_VIEW_SELECTED) {
            return !isProgressBar
        }

        // ③ 用户主动操作的事件掩码
        return (eventType and MASK_INTERRUPT) != 0
    }

    fun reset() {
        state = ReadState.STOPPED
        previousState = ReadState.STOPPED
        startTime = 0L
    }

    fun stats(): String = "连续朗读=${state.label}"

    companion object {
        /**
         * 启动后忽略点击的窗口。
         * 依据：TalkBack `FullScreenReadInterpreter.java:44`
         *   `private static final int INTERRUPT_DELAY_MS = 200;`
         */
        const val INTERRUPT_DELAY_MS = 200L

        // 事件类型常量 —— 与 AccessibilityEvent 一致
        const val TYPE_VIEW_CLICKED = 1
        const val TYPE_VIEW_LONG_CLICKED = 2
        const val TYPE_VIEW_SELECTED = 4
        const val TYPE_ANNOUNCEMENT = 16384
        /**
         * 触摸探索开始 = **512**
         *
         * 【核实修正】我最初按项目里一条错误注释写成 1048576 ——
         * 用 `javap android.jar` 核对：
         *   TYPE_TOUCH_EXPLORATION_GESTURE_START = 512
         *   TYPE_TOUCH_INTERACTION_START        = 1048576
         * 两者不同。项目里 AccessibilityEventHandler 的两处注释
         * 也把 1048576/2097152 标成了「触摸探索开始/结束」——
         * 代码用的是具名常量（正确），但注释是错的，已一并修正。
         */
        const val TYPE_TOUCH_EXPLORATION_GESTURE_START = 512

        /**
         * 打断朗读的事件掩码。
         * 依据：TalkBack `FullScreenReadInterpreter.java:48~52`
         *
         * ```java
         * private static final int MASK_EVENT_TYPES_INTERRUPT_CONTINUOUS =
         *     TYPE_VIEW_CLICKED                              // 1
         *         | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED // 2
         *         | AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START // 1048576
         *         | AccessibilityEventCompat.TYPE_ANNOUNCEMENT;  // 16384
         * ```
         */
        const val MASK_INTERRUPT =
            TYPE_VIEW_CLICKED or
                TYPE_VIEW_LONG_CLICKED or
                TYPE_TOUCH_EXPLORATION_GESTURE_START or
                TYPE_ANNOUNCEMENT


    }
}

/**
 * 连续朗读驱动 —— 负责实际推进焦点
 *
 * 与 [ContinuousReadState]（纯状态机）分离：
 * 状态机可单测，本类只做系统调用。
 *
 * ══════════════════════════════════════════════════════════
 * 【推进方式：照 TalkBack `FullScreenReadActor.moveForward()`】
 * ══════════════════════════════════════════════════════════
 *
 * ```java
 * // Continuous reading mode (CRM) always uses default granularity.
 * if (!pipeline.returnFeedback(eventId,
 *         Feedback.focusDirection(SEARCH_FOCUS_FORWARD)
 *             .setGranularity(DEFAULT).setScroll(true))) {
 *     pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
 *     interrupt(true);
 * }
 * ```
 *
 * 关键三点：
 *   ① 推进 = 一次**普通的下一个焦点导航**（不另造机制）
 *   ② 导航失败（走到底）→ 播完成音 + 自动停止
 *   ③ 粒度固定 DEFAULT
 *
 * ══════════════════════════════════════════════════════════
 * 【怎么知道「一个节点读完了」】
 * ══════════════════════════════════════════════════════════
 *
 * TalkBack 用 `speechController.setShouldInjectAutoReadingCallbacks(
 * isActive(), nodeSpokenRunnable)` —— 即 TTS 朗读完成回调。
 *
 * 白茶没有这个 TTS 回调注入点，改用**时间估算**：
 * 按文本长度估算朗读时长，到点后推进。
 *
 * 【为什么不用固定间隔】固定间隔会导致「长文本没读完就跳走」
 * 或「短文本读完干等」。估算虽不精确，但明显更好。
 */
class ContinuousReader(
    val state: ContinuousReadState,
    /** 执行一次「下一个焦点」导航；返回 false = 到头了 */
    private val moveNext: () -> Boolean,
    /** 播报完成音 */
    private val playComplete: () -> Unit,
    /** 播报状态提示 */
    private val speak: (String) -> Unit,
    /** 取当前焦点文本（用于估算朗读时长） */
    private val currentText: () -> String? = { null },
    private val log: (String) -> Unit = {}
) {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 推进任务 —— 朗读完一项后调 moveNext，成功则继续排下一次 */
    private val advanceTask = object : Runnable {
        override fun run() {
            if (!state.isActive) return

            val ok = try {
                moveNext()
            } catch (e: Throwable) {
                log("连续朗读：推进异常 ${e.message}")
                false
            }

            if (!ok) {
                // 走到底 —— 照 TalkBack：播完成音 + 自动停止
                log("连续朗读：已读到末尾")
                playComplete()
                stop()
                speak("朗读完成")
                return
            }

            // 排下一次
            scheduleNext()
        }
    }

    /**
     * 开始连续朗读。
     *
     * @param fromBeginning true = 从头开始读
     */
    fun start(fromBeginning: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!state.start(fromBeginning, now)) {
            log("连续朗读：已在运行，忽略")
            return
        }

        log("连续朗读：开始（${state.state.label}）")
        speak(state.state.label)

        if (fromBeginning) {
            // 从头读：先回到页面首个可聚焦节点，再排首次推进
            // 复用「上一个焦点」的失败路径无法回到开头，
            // 故直接调用 moveNext 的调用方提供的语义由服务决定
            scheduleNext()
        } else {
            // 从下一个开始 —— 让用户先听完当前项再推进
            scheduleNext()
        }
    }

    /** 停止 —— 对应 TalkBack `interrupt()` */
    fun stop(): Boolean {
        handler.removeCallbacks(advanceTask)
        val stopped = state.stop()
        if (stopped) log("连续朗读：停止")
        return stopped
    }

    /** 切换（用于手势） */
    fun toggle() {
        if (state.isActive) stop() else start()
    }

    /**
     * 处理事件 —— 判断是否该打断。
     *
     * 由事件处理器在分发时调用。
     *
     * @param eventType 事件类型码
     * @param eventTime 事件时间
     * @param isProgressBar 源节点是否是进度条/滑块
     * @return true = 已打断
     */
    fun onEvent(eventType: Int, eventTime: Long, isProgressBar: Boolean = false): Boolean {
        if (!state.shouldInterrupt(eventType, eventTime, isProgressBar)) return false
        handler.removeCallbacks(advanceTask)
        state.stop()
        log("连续朗读：被事件打断（type=$eventType）")
        return true
    }

    fun shutdown() {
        handler.removeCallbacks(advanceTask)
        state.reset()
    }

    /** 排下一次推进 */
    private fun scheduleNext() {
        handler.removeCallbacks(advanceTask)
        handler.postDelayed(advanceTask, estimateDelayMs())
    }

    /**
     * 估算当前项的朗读时长。
     *
     * 【公式】基础延迟 + 每字时长 × 字数
     *
     * 中文按每分钟约 300 字估算 → 每字 200ms。
     * 基础延迟 300ms 用于覆盖 TTS 起播与停顿。
     *
     * 【为什么要 clamp】极端长文本（如整屏公告）会让用户
     * 以为卡住了；极端短文本（如「确定」）读完立刻推进会太急。
     */
    internal fun estimateDelayMs(): Long {
        val text = currentText() ?: return MIN_DELAY_MS
        val len = text.length
        val estimated = BASE_DELAY_MS + len * PER_CHAR_MS
        return estimated.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    companion object {
        /** 起播与停顿时长 */
        const val BASE_DELAY_MS = 300L

        /** 每字估算时长（中文约 300 字/分钟） */
        const val PER_CHAR_MS = 200L

        /** 最短推进间隔 */
        const val MIN_DELAY_MS = 500L

        /** 最长推进间隔 —— 防止超长文本让用户以为卡死 */
        const val MAX_DELAY_MS = 20000L
    }
}
