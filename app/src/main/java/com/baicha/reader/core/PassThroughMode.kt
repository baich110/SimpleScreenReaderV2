package com.baicha.reader.core

/**
 * 穿透模式的**状态机与判定** —— 纯逻辑，可单测
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * 有些界面读屏**根本无法操作**：
 *   - 游戏（画布上全是自绘元素，无障碍树是空的）
 *   - 绘图/修图 App（需要精确的手势轨迹）
 *   - 需要多点触控的界面
 *
 * 现状：用户只能**整个关掉读屏** —— 关掉之后又失去了所有辅助，
 * 操作完还得回设置里重新打开，非常难用。
 *
 * 穿透模式让用户**临时**让触摸直达 App，同时读屏服务仍在运行。
 *
 * ══════════════════════════════════════════════════════════
 * 【两方对标】
 * ══════════════════════════════════════════════════════════
 *
 * | | TalkBack | 天坦 |
 * |---|---|---|
 * | 实现 | `PassThroughModeInterpreter`(74) + `PassThroughModeActor` | `m6/C2126d0` |
 * | API | `setTouchExplorationPassthroughRegion` | 同 |
 * | 超时 | `PASS_THROUGH_IDLE_MS = 4000` | **也是 4000ms**（`s()` 里 `timer.schedule(new b(), 4000L)`） |
 * | 模式 | 单一「穿透中」 | **两种**：瞬时 `n()` + 锁定 `h(Region)` |
 *
 * 【核实修正】初版我写「天坦无固定超时，靠手势/上下文退出」——
 * **这是错的**。天坦 `m6/C2126d0.java:239~243` 的 `s()`：
 *   ```java
 *   public final void s() {
 *       Timer timer = new Timer();
 *       this.f29270g = timer;
 *       timer.schedule(new b(), 4000L);
 *   }
 *   ```
 * 而 `s()` 恰好在**瞬时模式入口** `n(true, ...)` 里被调用
 * （`n()` 第 1 行）。所以两边都是 4000ms。
 *
 * 我原来那句「以天坦双模式为骨架 + 加 TalkBack 空闲超时做安全网」
 * 的**前提不成立** —— 两边都有超时，不存在「补天坦缺的超时」。
 *
 * 【A/B 取舍：以天坦的双模式为骨架】
 *
 * 真正的差异在**模式数量**，不在超时：
 *   - 天坦瞬时模式 `n(boolean, boolean)`：全屏 Region 穿透（`g()` 取真实屏幕尺寸）
 *   - 天坦锁定模式 `h(Region, ...)`：指定区域持续穿透，`e()` 取消定时器
 *   - TalkBack 只有单模式
 *
 * 锁定模式的超时被显式取消（`e()` 里的 `timer.cancel()`），
 * 这对游戏场景是必要的：用户可能十几秒不动（思考），
 * 若按空闲超时退出，一动就又变回读屏接管 —— 体验断裂。
 *
 * 故**照天坦的两条路径实现**：瞬时带 4000ms 空闲超时、锁定不超时。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么抽成纯类】
 * ══════════════════════════════════════════════════════════
 *
 * `android.graphics.Region` 与 `Rect` 一样，在 JVM 单测里是**空壳**，
 * 且 `setTouchExplorationPassthroughRegion` 需要真实服务。
 * 状态机本身与 Android API 无关，抽出来才能测。
 */
class PassThroughState(
    /** 空闲自动退出时长（毫秒）—— TalkBack 用 4000 */
    private val idleTimeoutMs: Long = PASS_THROUGH_IDLE_MS
) {

    /** 是否处于穿透中 */
    @Volatile
    var isActive: Boolean = false
        private set

    /**
     * 是否为「锁定模式」
     *
     * 锁定模式：指定区域持续穿透，不会因空闲超时退出
     * （因为用户可能在游戏里思考很久不动）
     */
    @Volatile
    var isLocked: Boolean = false
        private set

    /** 上次触摸时间 */
    private var lastTouchTime: Long = 0L

    /**
     * 退出原因 —— 供播报与日志
     *
     * 对应天坦的日志区分（它分「leave lock mode」与「leave mode」）
     */
    enum class ExitReason {
        /** 用户主动退出 */
        USER,

        /** 空闲超时自动退出 */
        IDLE_TIMEOUT,

        /** 窗口/上下文变化导致退出（天坦 `resetTouchExplorePassThroughWhenContextMenuShow`） */
        CONTEXT_CHANGED
    }

    /**
     * 进入穿透（瞬时模式）—— 对应天坦 `n(true, ...)`
     *
     * @return 是否发生了状态变化（false = 已在穿透中，无需重复播报）
     */
    fun enter(now: Long = 0L): Boolean {
        if (isActive && !isLocked) return false
        isActive = true
        isLocked = false
        lastTouchTime = now
        return true
    }

    /**
     * 进入穿透（锁定模式）—— 对应天坦 `h(region, ...)`
     *
     * 【为什么要锁定模式】
     *   游戏里用户可能十几秒不动（思考策略），
     *   若按空闲超时退出，用户一动就又变成读屏接管 —— 体验断裂。
     *
     * 天坦的实现依据：`h()` 走独立路径，进入后 `e()` 会
     * `timer.cancel()` 取消空闲定时器 —— 即锁定模式**不超时**。
     */
    fun enterLocked(now: Long = 0L): Boolean {
        if (isActive && isLocked) return false
        isActive = true
        isLocked = true
        lastTouchTime = now
        return true
    }

    /**
     * 退出穿透 —— 对应天坦 `n(false, ...)`
     *
     * @return 退出原因；null = 本来就没在穿透
     */
    fun exit(reason: ExitReason = ExitReason.USER, now: Long = 0L): ExitReason? {
        if (!isActive) return null
        isActive = false
        isLocked = false
        lastTouchTime = now
        return reason
    }

    /**
     * 记录一次触摸 —— 每次触摸都刷新空闲计时。
     *
     * 对应 TalkBack `PassThroughModeActor.startPassThroughGuardTimer()`：
     *   "If no user touch activity before the timer expires,
     *    system ... exit"
     */
    fun onTouch(now: Long) {
        if (!isActive) return
        lastTouchTime = now
    }

    /**
     * 是否应该因空闲超时自动退出。
     *
     * 【为什么锁定模式不超时】
     *   上面的游戏场景；锁定模式必须由用户显式退出。
     *
     * @return true = 应退出
     */
    fun shouldExitForIdle(now: Long): Boolean {
        // 未激活或锁定模式 → 永不超时
        if (!isActive || isLocked) return false
        // 【修正】原来还有一条 `lastTouchTime <= 0L → false` 的守卫，
        // 本意是「从未记录过触摸就不超时」。
        // 但它把**合法的 0 时间戳**也挡掉了 ——
        // 单测里 `enter(now = 0)` 是常见写法（与 uptimeMillis 起点无关），
        // 真机上 `SystemClock.uptimeMillis()` 在开机瞬间也可能接近 0。
        //
        // 而且 `isActive` 已经保证了「进入时一定赋过值」，
        // 那条守卫是冗余的 —— 冗余的守卫反而制造了边界 bug。
        return now - lastTouchTime >= idleTimeoutMs
    }

    /**
     * 检查并执行空闲超时退出。
     *
     * @return 退出原因；null = 未超时
     */
    fun checkIdleTimeout(now: Long): ExitReason? =
        if (shouldExitForIdle(now)) exit(ExitReason.IDLE_TIMEOUT, now) else null

    /** 剩余空闲时间（毫秒）；负值表示已超时 */
    fun idleRemainingMs(now: Long): Long =
        if (!isActive) 0L else idleTimeoutMs - (now - lastTouchTime)

    fun reset() {
        isActive = false
        isLocked = false
        lastTouchTime = 0L
    }

    fun stats(): String =
        "穿透=${if (isActive) (if (isLocked) "锁定" else "瞬时") else "关"}"

    companion object {
        /**
         * 空闲自动退出时长。
         *
         * **两方一致**：TalkBack `PASS_THROUGH_IDLE_MS = 4000`；
         * 天坦 `m6/C2126d0.s()` 里 `timer.schedule(new b(), 4000L)`。
         *
         * 【为什么用这个值】两方都选它，说明是实测可用值：
         *   短于它用户来不及操作，长于它「忘了退」的风险变大。
         */
        const val PASS_THROUGH_IDLE_MS = 4000L
    }
}

/**
 * 穿透模式的**系统侧控制** —— 负责调 Android API
 *
 * 与 [PassThroughState]（纯状态机）分离：
 *   - 状态机可单测
 *   - 本类只做系统调用，无法单测
 *
 * 这也是项目既有约定（WindowClassifierCore / GranularityMover 等同样分层）。
 */
class PassThroughController(
    private val service: android.accessibilityservice.AccessibilityService,
    private val log: (String) -> Unit = {},
    private val speak: (String) -> Unit = {}
) {

    val state = PassThroughState()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 空闲检查任务 */
    private val idleChecker = object : Runnable {
        override fun run() {
            if (!state.isActive) return
            val exited = state.checkIdleTimeout(android.os.SystemClock.uptimeMillis())
            if (exited != null) {
                applyRegion(empty = true)
                log("穿透模式：空闲超时自动退出")
                speak("穿透已退出")
                return
            }
            // 还在穿透中 → 200ms 后再查
            mainHandler.postDelayed(this, IDLE_CHECK_INTERVAL_MS)
        }
    }

    /**
     * 进入穿透（全屏瞬时模式）。
     *
     * @return true = 进入成功（状态确实变化了）
     */
    fun enter(): Boolean {
        if (!state.enter(android.os.SystemClock.uptimeMillis())) return false
        applyRegion(empty = false)
        scheduleIdleCheck()
        log("穿透模式：进入（瞬时）")
        speak("穿透模式")
        return true
    }

    /**
     * 进入穿透（锁定模式，全屏）。
     *
     * 当前实现用全屏 Region；后续可按需改成局部区域
     * （天坦的 `h(Region)` 支持局部）。
     */
    fun enterLocked(): Boolean {
        if (!state.enterLocked(android.os.SystemClock.uptimeMillis())) return false
        applyRegion(empty = false)
        log("穿透模式：进入（锁定）")
        speak("穿透模式已锁定")
        return true
    }

    /** 退出穿透 */
    fun exit(): Boolean {
        val reason = state.exit(PassThroughState.ExitReason.USER) ?: return false
        mainHandler.removeCallbacks(idleChecker)
        applyRegion(empty = true)
        log("穿透模式：退出（$reason）")
        speak("穿透已退出")
        return true
    }

    /** 切换（已开则关，未开则开） */
    fun toggle(): Boolean {
        return if (state.isActive) exit() else enter()
    }

    /**
     * 记录触摸 —— 刷新空闲计时。
     *
     * 每次触摸都调用，故不做日志（避免刷屏）。
     */
    fun onTouch() {
        state.onTouch(android.os.SystemClock.uptimeMillis())
    }

    /** 上下文变化时退出（天坦 `resetTouchExplorePassThroughWhenContextMenuShow`） */
    fun onContextChanged() {
        if (!state.isActive) return
        state.exit(PassThroughState.ExitReason.CONTEXT_CHANGED)
        mainHandler.removeCallbacks(idleChecker)
        applyRegion(empty = true)
        log("穿透模式：上下文变化 → 退出")
    }

    /** 关闭 —— 服务销毁时调用 */
    fun shutdown() {
        mainHandler.removeCallbacks(idleChecker)
        if (state.isActive) applyRegion(empty = true)
        state.reset()
    }

    private fun scheduleIdleCheck() {
        mainHandler.removeCallbacks(idleChecker)
        mainHandler.postDelayed(idleChecker, IDLE_CHECK_INTERVAL_MS)
    }

    /**
     * 应用穿透区域。
     *
     * 【空 Region 的语义】= 不穿透（恢复读屏接管）。
     * 这是 Android API 的约定 ——
     * TalkBack 退出时同样传 `new Region()`（见其 99/131 行）。
     */
    private fun applyRegion(empty: Boolean) {
        try {
            val displayId = android.view.Display.DEFAULT_DISPLAY
            if (empty) {
                service.setTouchExplorationPassthroughRegion(
                    displayId,
                    android.graphics.Region()
                )
            } else {
                // 全屏穿透 —— Region 用「整块屏幕」表示
                val metrics = service.resources.displayMetrics
                val full = android.graphics.Region(
                    0, 0, metrics.widthPixels, metrics.heightPixels
                )
                service.setTouchExplorationPassthroughRegion(displayId, full)
            }
        } catch (e: Throwable) {
            // API 30 以下不支持 → 明确记录并回滚状态（不静默）
            log("穿透模式：设置区域失败 ${e.message}")
            state.reset()
            speak("此设备不支持穿透模式")
        }
    }

    companion object {
        private const val IDLE_CHECK_INTERVAL_MS = 200L
    }
}
