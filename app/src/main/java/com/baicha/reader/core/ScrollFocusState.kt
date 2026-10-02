package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 滚动焦点恢复状态 —— 对应天坦 `M6/h` 的内部类 `c`（f6010j）
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这个类】
 * ══════════════════════════════════════════════════════════
 *
 * 自动滚动的场景：用户滑到底部，还要继续下一个焦点，
 * 但下一个焦点在屏幕外 → 需要「先滚动，再聚焦」。
 *
 * 这个两步操作有多个失败点：
 *   ① App 不响应滚动指令（滚动没发生）
 *   ② 滚动发生了但目标节点还没出现（需要重试）
 *   ③ 滚动后原节点被回收（焦点丢失）
 *   ④ 重试次数超限（必须放弃，否则死循环）
 *
 * 天坦用这个状态对象 + 一个重试任务 (`f`) 来管理整个过程。
 *
 * ══════════════════════════════════════════════════════════
 * 【天坦的字段（M6/h 内部类 c）】
 * ══════════════════════════════════════════════════════════
 *
 *   c(node, anchor, direction, targetType, flag, rect)
 *   ├─ c()  取节点          → f6010j.c()
 *   ├─ d()  取锚点          → f6010j.d()
 *   ├─ b()  取方向          → f6010j.b()
 *   ├─ e()  取目标类型      → f6010j.e()
 *   └─ f()  取标志          → f6010j.f()
 *
 * 【关键常量】
 *   MAX_ATTEMPT = 100     重试上限（天坦 "i9 > 100"）
 *   RETRY_DELAY_MS = 25   重试间隔（天坦 postDelayed(task, 25L)）
 *   SLOW_RETRY_DELAY = 500  targetType == 2 时的延迟（天坦 500L）
 */
class ScrollFocusState(
    /**
     * 滚动容器（对应天坦 cVar.c()）
     *
     * 允许为 null：便于对「重试计数 / 延迟 / 动作码」这些纯逻辑做单测
     * （JVM 环境拿不到真实 AccessibilityNodeInfo）。
     */
    @JvmField val scrollNode: AccessibilityNodeInfo?,

    /** 锚点节点 —— 滚动前的焦点（对应天坦 cVar.d()） */
    @JvmField val anchorNode: AccessibilityNodeInfo?,

    /** 方向（对应天坦 cVar.b()） */
    @JvmField val direction: Int,

    /**
     * 目标类型（对应天坦 cVar.e()）
     *
     * 决定聚焦动作码与重试延迟：
     *   0 → 普通，立即重试
     *   1 → 快速，立即重试
     *   2 → 慢速，延迟 500ms 重试
     */
    @JvmField val targetType: Int,

    /** 是否单列列表（对应天坦 cVar.f()）—— 影响配置开关 */
    @JvmField val isSingleColumnList: Boolean,

    /** 滚动前的锚点边界（用于 boundsChanged 判定） */
    @JvmField val boundsBeforeScroll: Bounds
) {

    companion object {
        /** 重试上限 —— 天坦 "i9 > 100" */
        const val MAX_ATTEMPT = 100

        /** 常规重试间隔 —— 天坦 25ms */
        const val RETRY_DELAY_MS = 25L

        /** 慢速重试延迟 —— 天坦 targetType==2 时用 500ms */
        const val SLOW_RETRY_DELAY_MS = 500L

        /** 目标类型 */
        const val TYPE_NORMAL = 0
        const val TYPE_FAST = 1
        const val TYPE_SLOW = 2
    }

    /** 当前重试次数 */
    @Volatile
    var attempt: Int = 0
        private set

    /** 是否已取消 */
    @Volatile
    var cancelled: Boolean = false
        private set

    /** 递增重试计数 */
    fun nextAttempt(): Int = ++attempt

    /** 是否超过重试上限 */
    fun isExhausted(): Boolean = attempt > MAX_ATTEMPT

    /** 本次重试应有的延迟 —— 对应天坦 `if (f6023b == 2) postDelayed(500) else run()` */
    fun retryDelay(): Long =
        if (targetType == TYPE_SLOW) SLOW_RETRY_DELAY_MS else RETRY_DELAY_MS

    /** 取消 */
    fun cancel() {
        cancelled = true
    }

    // 【已删除】原 focusActionCode() —— 对应天坦
    //   `targetType != 0 ? (targetType != 1 ? 10 : 8) : 6`
    //
    // 删除理由（本轮审查）：
    //   天坦的 6/8/10 是它**内部** W(node, action) 方法的入参，
    //   三个值在它那套 self-defined 聚焦流程里有不同路径。
    //   白茶没有对应的 W() —— 聚焦统一走
    //   `node.performAction(ACTION_ACCESSIBILITY_FOCUS)`，
    //   不存在「三种聚焦方式」。
    //
    //   所以这个函数**永远不会有语义正确的调用方** ——
    //   不是「还没接线」，是「本来就不适用」。
    //   留着只会让人误以为白茶有差异化聚焦能力。
    //
    // 注意：`targetType` 本身**仍在用**（决定重试延迟
    //   TYPE_SLOW → 500ms，见 retryDelay()），那部分是对的。
    //   被删的只是「用 targetType 算聚焦动作码」这一处。

    override fun toString(): String =
        "ScrollFocusState(dir=$direction type=$targetType attempt=$attempt)"
}

/**
 * 滚动焦点恢复结果
 */
sealed class ScrollRecoveryResult {
    /** 成功聚焦 */
    data class Success(val node: AccessibilityNodeInfo, val attempts: Int) : ScrollRecoveryResult()

    /** 重试次数耗尽 —— 对应天坦 "exceeded max attempt" */
    data class Exhausted(val attempts: Int) : ScrollRecoveryResult()

    /** 已取消 */
    object Cancelled : ScrollRecoveryResult()

    /** 目标仍未出现但未超限 —— 应继续重试 */
    object Retry : ScrollRecoveryResult()
}

/**
 * 滚动焦点恢复回调 —— 对应天坦内部类 `a`（AutoScrollCallback）
 *
 * 天坦的三个回调：
 *   d()  onAutoScrollFailed()      滚动失败
 *   e()  onAutoScrolled()          滚动成功
 *   f()  assumeScrollSuccess()     假定成功（某些场景误判失败时的兜底）
 */
interface ScrollRecoveryCallback {
    /** 滚动成功并聚焦 —— 对应天坦 `e(boundsChanged)` */
    fun onAutoScrolled(boundsChanged: Boolean)

    /** 滚动失败（重试耗尽）—— 对应天坦 `d()` */
    fun onAutoScrollFailed()

    /**
     * 假定滚动成功 —— 对应天坦 `d()` 里 `f6025d` 为 true 的分支
     *
     * 天坦原注释：
     *   "AutoScrollCallback: assumeScrollSuccess, treating as success"
     */
    fun assumeScrollSuccess(): Boolean
}
