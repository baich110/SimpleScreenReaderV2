package com.baicha.reader.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 屏幕状态变化后的焦点恢复 —— 对应 TalkBack
 * `FocusProcessorForScreenStateChange`（186 行）
 *
 * ══════════════════════════════════════════════════════════
 * 【这是焦点系统「恢复层」的第三条路径】
 * ══════════════════════════════════════════════════════════
 *
 *   ① 跨窗口导航        → 用户主动滑动时走下一个窗口
 *   ② 滚动后恢复        → 自动滚动失败后重试/兜底
 *   ③ **屏幕状态变化**   → 窗口切换后重新定位焦点 ← 本类
 *
 * 场景：息屏再亮屏、切应用、弹窗关闭、旋转屏幕 ——
 * 这些都会让原焦点失效，必须重新找一个合理的焦点，
 * 否则用户「找不到焦点在哪」。
 *
 * ══════════════════════════════════════════════════════════
 * 【TalkBack 的算法（本类 1:1 实现）】
 * ══════════════════════════════════════════════════════════
 *
 *   onScreenStateChangedInternal(screenState):
 *       ① 取活动窗口
 *          if (null) → FAIL_NO_ACTIVE_WINDOW
 *
 *       ② ★ 用户是否在窗口切换期间已经操作过？
 *          lastRecord = 历史里「该窗口的最近一次焦点动作」
 *          if (lastRecord != null
 *              && lastRecord.time > 切换开始时间
 *              && (来源 == 触摸探索 || 来源 == 逻辑导航)) {
 *              → FAIL_HAS_INTERACTION     // 用户已经在操作了，别抢焦点
 *          }
 *
 *       ③ 该窗口是否已有有效焦点？
 *          if (hasValidAccessibilityFocusInWindow(window)) {
 *              → FAIL_HAS_VALID_FOCUS     // 已有有效焦点，不动
 *          }
 *
 *       ④ 通过全部检查 → 执行恢复（设初始焦点）
 *
 *   hasValidAccessibilityFocusInWindow(window):
 *       if (!window.isAccessibilityFocused) return false
 *       focus = 当前无障碍焦点
 *       return focus != null
 *              && focus.isVisible
 *              && focus.windowId == window.id
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么第 ② 步很重要】
 * ══════════════════════════════════════════════════════════
 *
 * 窗口切换是异步的：切换刚开始，用户可能已经等不及开始滑动。
 * 如果此时我们"恢复焦点"，会**跳到用户不想要的位置**，
 * 表现为「刚滑到某处，焦点突然被抢走」。
 *
 * TalkBack 用「切换开始时间 vs 用户最近操作时间」的比较来避免这个竞态。
 */
class ScreenStateRecovery(
    /**
     * 取「纯无障碍焦点」—— 对应 TalkBack
     * `accessibilityFocusMonitor.getAccessibilityFocus(useInputFocusIfEmpty = false)`
     *
     * ══════════════════════════════════════════════════════════
     * 【★ 本轮修复：这里原来接的是 `focus.getFocused()`】
     * ══════════════════════════════════════════════════════════
     *
     * TalkBack `FocusProcessorForScreenStateChange.java:172~185`：
     * ```java
     *   private boolean hasValidAccessibilityFocusInWindow(AccessibilityWindowInfo window) {
     *     if (!window.isAccessibilityFocused()) { ... return false; }
     *     AccessibilityNodeInfoCompat currentFocus =
     *         accessibilityFocusMonitor.getAccessibilityFocus(useInputFocusIfEmpty = false);
     *     return (currentFocus != null)
     *         && AccessibilityNodeInfoUtils.isVisible(currentFocus)
     *         && (currentFocus.getWindowId() == window.getId());
     *   }
     * ```
     *
     * 注意那个 **`false`**。而白茶原来接的是 `FocusManager.getFocused()`，
     * 它内部第一步就是
     * `monitor.getAccessibilityFocus(useInputFocusIfEmpty = true)`
     * —— **多了一条「退回输入焦点」的回退链**。
     *
     * 【为什么多这一条是错的】本方法问的是「当前窗口**是否已经有无障碍焦点**」：
     *   · 若把「输入焦点」也算作「已有无障碍焦点」，
     *     那么在**编辑框**场景（唯一有输入焦点的地方）会判定为
     *     `FAIL_HAS_VALID_FOCUS` → **不恢复焦点**。
     *   · 真机表现：从锁屏/切窗口回来时，如果光标停在某个输入框里，
     *     读屏**不把焦点还给用户**，用户滑一下也不知道自己在哪 ——
     *     一个盲人用户此时等于失去了导航能力。
     *   · TalkBack 用一个明确的 `false` 把这条回退**关掉**，正是为了
     *     让「有没有无障碍焦点」这个问题得到**严格的答案**。
     *
     * 【修复方式】新增本钩子，只做「无障碍焦点」查询；
     * 服务侧接 `FocusManager.getAccessibilityFocusStrict()`。
     * 原 `getCurrentFocus` 保留给其他用途（未来若有），
     * 但本类的语义已收紧。
     */
    private val getCurrentFocus: () -> AccessibilityNodeInfo?,
    /** 取当前活动窗口 ID */
    private val getActiveWindowId: () -> Int,
    /** 取当前活动窗口是否已获得无障碍焦点 */
    private val isWindowAccessibilityFocused: () -> Boolean,
    /** 执行恢复（设初始焦点）—— 由服务注入 */
    private val onRestoreFocus: (force: Boolean) -> Unit,
    /** 取上次焦点动作时间（用于竞态判定） */
    private val getLastFocusActionTime: () -> Long,
    /** 取上次焦点动作来源（用于竞态判定） */
    private val getLastFocusActionSource: () -> Int
) {

    companion object {
        private const val TAG = "ScreenStateRecovery"

        /** 焦点来源：触摸探索 —— 对应 TalkBack FocusActionInfo.TOUCH_EXPLORATION(2) */
        const val FOCUS_SOURCE_TOUCH_EXPLORATION = 2

        /** 焦点来源：逻辑导航 —— 对应 TalkBack FocusActionInfo.LOGICAL_NAVIGATION(1) */
        const val FOCUS_SOURCE_LOGICAL_NAVIGATION = 1
    }

    /**
     * 恢复结果 —— 1:1 对应 TalkBack `FocusResult`
     */
    enum class Result(val value: Int, val reason: String) {
        /** 没有活动窗口 —— 对应 FAIL_NO_ACTIVE_WINDOW(-3) */
        FAIL_NO_ACTIVE_WINDOW(-3, "无活动窗口"),

        /** 用户已交互 —— 对应 FAIL_HAS_INTERACTION(-2) */
        FAIL_HAS_INTERACTION(-2, "用户已操作，不抢焦点"),

        /** 已有有效焦点 —— 对应 FAIL_HAS_VALID_FOCUS(-1) */
        FAIL_HAS_VALID_FOCUS(-1, "已有有效焦点"),

        /** 默认失败 —— 对应 FAIL_DEFAULT(0) */
        FAIL_DEFAULT(0, "默认失败"),

        /** 成功 —— 对应 SUCCESS(1) */
        SUCCESS(1, "已恢复焦点")
    }

    /** 窗口切换开始时间（用于竞态判定） */
    @Volatile
    var screenTransitionStartTime: Long = 0L

    /** 上次处理的 override 时间戳 —— 对应 TalkBack `handledOverrideFocusRestoreUptimeMs` */
    @Volatile
    private var handledOverrideTime = 0L

    /** override 恢复请求时间 —— 对应 `actorState.getOverrideFocusRestoreUptimeMs()` */
    @Volatile
    var overrideFocusRestoreTime: Long = 0L

    /** 最近一次结果（诊断） */
    @Volatile
    var lastResult: Result? = null
        private set

    @Volatile
    var successCount = 0L
        private set

    @Volatile
    var skipCount = 0L
        private set

    // ==================== 主入口 ====================

    /**
     * 屏幕状态变化时尝试恢复焦点 —— 1:1 对应
     * TalkBack `onScreenStateChanged()`
     *
     * @param transitionStartTime 窗口切换开始时间（uptimeMillis）
     * @return 是否成功恢复了焦点
     */
    fun onScreenStateChanged(transitionStartTime: Long): Boolean {
        screenTransitionStartTime = transitionStartTime

        val result = try {
            recoverInternal()
        } finally {
            // ★ 对应 TalkBack 的 finally 块：
            //   "Refresh the update-time whenever window transition finished.
            //    Sometimes focus won't restore in special cases, like dismiss dialog
            //    when screen off. If we don't refresh the flag, it won't restore focus
            //    at screen off stage but restore focus at next visible window transition instead."
            handledOverrideTime = overrideFocusRestoreTime
        }

        lastResult = result
        if (result == Result.SUCCESS) successCount++ else skipCount++
        ReaderLog.d(TAG, "屏幕状态恢复: ${result.reason}")
        return result == Result.SUCCESS
    }

    /**
     * 恢复主体 —— 1:1 对应 `onScreenStateChangedInternal()`
     */
    private fun recoverInternal(): Result {
        // ① 有活动窗口吗
        val windowId = getActiveWindowId()
        if (windowId < 0) {
            return Result.FAIL_NO_ACTIVE_WINDOW
        }

        // ② ★ 用户在切换期间已经操作过吗？（防抢焦点竞态）
        if (userInteractedDuringTransition()) {
            return Result.FAIL_HAS_INTERACTION
        }

        // ③ 已有有效焦点吗？
        if (hasValidAccessibilityFocusInWindow(windowId)) {
            return Result.FAIL_HAS_VALID_FOCUS
        }

        // ④ 通过全部检查 → 执行恢复
        val forceRestore = overrideFocusRestoreTime > handledOverrideTime
        ReaderLog.i(TAG, "恢复焦点（force=$forceRestore, window=$windowId）")
        onRestoreFocus(forceRestore)
        return Result.SUCCESS
    }

    /**
     * 用户是否在窗口切换期间已交互 —— 对应 TalkBack 的
     * `FAIL_HAS_INTERACTION` 判定
     *
     * TalkBack 原注释：
     *   "Initial focus will be skipped if user interacts on old window.
     *    So we only skip initial focus if the interaction is happened on
     *    active window to ensure it can grant focus."
     */
    private fun userInteractedDuringTransition(): Boolean {
        val lastActionTime = getLastFocusActionTime()
        if (lastActionTime <= 0L) return false
        if (lastActionTime <= screenTransitionStartTime) return false

        // 来源必须是「用户主动操作」（触摸探索 / 逻辑导航）
        val source = getLastFocusActionSource()
        val userInitiated = source == FOCUS_SOURCE_TOUCH_EXPLORATION ||
                source == FOCUS_SOURCE_LOGICAL_NAVIGATION

        if (userInitiated) {
            ReaderLog.d(
                TAG,
                "用户在切换期间已操作（source=$source, " +
                        "actionTime=$lastActionTime > transitionStart=$screenTransitionStartTime）"
            )
        }
        return userInitiated
    }

    /**
     * 窗口内是否已有有效焦点 —— 对应 TalkBack
     * `hasValidAccessibilityFocusInWindow()`（三重检查）
     */
    private fun hasValidAccessibilityFocusInWindow(windowId: Int): Boolean {
        // ① 窗口本身是否有无障碍焦点
        if (!isWindowAccessibilityFocused()) {
            ReaderLog.d(TAG, "窗口 $windowId 未获得无障碍焦点")
            return false
        }

        // ② 当前焦点节点存在
        val focus = getCurrentFocus()
        if (focus == null) return false

        // ③ 可见 且 属于该窗口
        return try {
            focus.isVisibleToUser && focus.windowId == windowId
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 重置 —— 服务销毁时调用
     */
    fun reset() {
        screenTransitionStartTime = 0L
        handledOverrideTime = 0L
        overrideFocusRestoreTime = 0L
        lastResult = null
    }

    fun stats(): String =
        "成功 $successCount / 跳过 $skipCount / 最近 ${lastResult?.reason ?: "无"}"
}

/**
 * 窗口切换延迟窗口 —— 对应 TalkBack `WINDOW_CHANGE_DELAY_MS`
 *
 * TalkBack 的逻辑：
 *   如果屏幕状态变化的耗时在 (0, WINDOW_CHANGE_DELAY_MS) 区间，
 *   说明窗口还在切换中 → **延迟执行**恢复（让窗口切换完成）
 *
 * TalkBack 原注释：
 *   "Delay the action of assigning the initial focus for screen state changed.
 *    Since TalkBack will announce the window title first, users shouldn't feel
 *    this delay and it allows us to steal some time waiting for the window transition."
 */
object ScreenStateTiming {
    /** 窗口切换延迟窗口 —— TalkBack 同名字段 */
    const val WINDOW_CHANGE_DELAY_MS = 500L

    /**
     * 判断是否需要延迟执行
     *
     * @param elapsed 屏幕状态变化已耗时
     * @return true = 应延迟（窗口还在切换）
     */
    fun shouldDelay(elapsed: Long): Boolean =
        elapsed > 0 && elapsed < WINDOW_CHANGE_DELAY_MS
}
