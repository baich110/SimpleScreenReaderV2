package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势链 —— **双击必须终止**（不得落回通用链）
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】天坦 `Q6/TouchInteractionMonitor.java:860~872`（原样摘录）
 * ══════════════════════════════════════════════════════════
 *
 * ```java
 *   if (gestureId == 17) {
 *       if (this.f7610o) {
 *           F(accessibilityGestureEvent);       // 触摸探索模式：转交手势链
 *           return;                             // ← 无论如何都 return
 *       } else {
 *           this.f7600e.performClick();         // 否则：直接点击当前焦点
 *           return;                             // ← 无论如何都 return
 *       }
 *   }
 *   if (gestureId == 18) {
 *       this.f7600e.performLongClickAndStartDrag();
 *       return;                                 // ← 无条件 return
 *   }
 * ```
 *
 * **两个分支都 `return`** —— 双击（17）/ 双击长按（18）
 * 在任何情况下都**不会**落到后面的通用手势链上。
 *
 * ══════════════════════════════════════════════════════════
 * 【本轮修复的缺陷】
 * ══════════════════════════════════════════════════════════
 *
 * 白茶原来写成「处理器没消费 → 继续走链」，并在注释里声称
 * 「天坦也是这个行为」—— 那是**我臆断的**，源码里没有。
 *
 * 【真机后果】双击一个不可点节点（`clickTarget()` 返回 false）时，
 * 同一个双击会被链上后续 Actor 依次尝试，可能触发
 * 「停止播放」「退出全屏」「打开侧边菜单」等**无关动作**。
 */
class GestureChainDoubleTapTest {

    /** 记录是否被调用过的探针 Actor */
    private class ProbeActor(
        override val name: String,
        private val consume: Boolean = true
    ) : GestureActor {
        override var isActive: Boolean = true
        var callCount = 0
            private set
        var lastGestureId = -1
            private set

        override fun onGesture(gestureId: Int, displayId: Int): Boolean {
            callCount++
            lastGestureId = gestureId
            return consume
        }
    }

    // ==================== 双击：处理器成功 → 终止 ====================

    @Test
    fun doubleTap_consumed_doesNotReachChain() {
        val chain = GestureChain()
        val probe = ProbeActor("Probe")
        chain.register(probe)

        chain.onDoubleTap = { true }
        val handled = chain.handle(GestureChain.GESTURE_DOUBLE_TAP)

        assertTrue("双击应被消费", handled)
        assertEquals("链上 Actor 不应被调用", 0, probe.callCount)
        assertEquals("最后处理者应为 DoubleTap", "DoubleTap", chain.lastHandledBy)
    }

    // ==================== 双击：处理器**未**消费 → 仍须终止 ====================

    /**
     * ★★ 本轮修复的核心断言
     *
     * 依据 `TouchInteractionMonitor.java:861~868`：两个分支都是 `return`。
     */
    @Test
    fun doubleTap_notConsumed_stillDoesNotReachChain() {
        val chain = GestureChain()
        val probe = ProbeActor("Probe")
        chain.register(probe)

        chain.onDoubleTap = { false }        // 模拟 clickTarget() 失败
        val handled = chain.handle(GestureChain.GESTURE_DOUBLE_TAP)

        assertFalse("未消费时返回值应为 false（调用方据此震错误反馈）", handled)
        assertEquals(
            "★ 未消费也**不得**落到通用链上（天坦两分支都 return）",
            0, probe.callCount
        )
        assertEquals("最后处理者仍应记为 DoubleTap", "DoubleTap", chain.lastHandledBy)
    }

    /** handler 未接线时同样不落链 */
    @Test
    fun doubleTap_noHandler_doesNotReachChain() {
        val chain = GestureChain()
        val probe = ProbeActor("Probe")
        chain.register(probe)

        chain.onDoubleTap = null
        val handled = chain.handle(GestureChain.GESTURE_DOUBLE_TAP)

        assertFalse(handled)
        assertEquals("无处理器时也不得落链", 0, probe.callCount)
    }

    // ==================== 双击长按：同理 ====================

    @Test
    fun doubleTapHold_notConsumed_stillDoesNotReachChain() {
        val chain = GestureChain()
        val probe = ProbeActor("Probe")
        chain.register(probe)

        chain.onDoubleTapHold = { false }
        val handled = chain.handle(GestureChain.GESTURE_DOUBLE_TAP_AND_HOLD)

        assertFalse(handled)
        assertEquals("双击长按未消费也不得落链", 0, probe.callCount)
        assertEquals("DoubleTapHold", chain.lastHandledBy)
    }

    @Test
    fun doubleTapHold_consumed_doesNotReachChain() {
        val chain = GestureChain()
        val probe = ProbeActor("Probe")
        chain.register(probe)

        chain.onDoubleTapHold = { true }
        assertTrue(chain.handle(GestureChain.GESTURE_DOUBLE_TAP_AND_HOLD))
        assertEquals(0, probe.callCount)
    }

    // ==================== 记账（真机排障用）====================

    /**
     * 未消费也要计入 `unhandledCount` ——
     * 否则真机上「双击没反应」无法区分「没处理」与「处理失败」
     */
    @Test
    fun doubleTap_failure_is_counted() {
        val chain = GestureChain()
        chain.onDoubleTap = { false }
        chain.handle(GestureChain.GESTURE_DOUBLE_TAP)

        assertEquals("失败应计入 unhandled", 1L, chain.unhandledCount)
        assertEquals("不应计入 handled", 0L, chain.handledCount)
    }

    @Test
    fun doubleTap_success_is_counted() {
        val chain = GestureChain()
        chain.onDoubleTap = { true }
        chain.handle(GestureChain.GESTURE_DOUBLE_TAP)

        assertEquals(1L, chain.handledCount)
        assertEquals(0L, chain.unhandledCount)
    }

    // ==================== 其他手势必须**仍然**走链（不得回归）====================

    /**
     * 普通手势（如上滑）必须照常走到 Actor ——
     * 修复双击终止逻辑**不得**把链本身堵死。
     */
    @Test
    fun ordinaryGesture_stillReachesChain() {
        val chain = GestureChain()
        val probe = ProbeActor("Probe")
        chain.register(probe)

        val handled = chain.handle(GestureChain.GESTURE_SWIPE_UP)

        assertTrue("普通手势应由 Actor 消费", handled)
        assertEquals("普通手势必须走到链上", 1, probe.callCount)
        assertEquals("最后处理者应为探针", "Probe", chain.lastHandledBy)
    }

    /** 未激活的 Actor 应被跳过 */
    @Test
    fun inactiveActor_isSkipped() {
        val chain = GestureChain()
        val inactive = ProbeActor("Inactive").apply { isActive = false }
        val active = ProbeActor("Active")
        chain.register(inactive)
        chain.register(active)

        chain.handle(GestureChain.GESTURE_SWIPE_UP)
        assertEquals("未激活 Actor 不应被调用", 0, inactive.callCount)
        assertEquals("应落到下一个 Actor", 1, active.callCount)
    }

    /** 单个 Actor 抛异常不得阻断整条链 */
    @Test
    fun throwingActor_doesNotBreakChain() {
        val chain = GestureChain()
        val throwing = object : GestureActor {
            override val name = "Throwing"
            override var isActive = true
            override fun onGesture(gestureId: Int, displayId: Int): Boolean =
                throw IllegalStateException("boom")
        }
        val probe = ProbeActor("Probe")
        chain.register(throwing)
        chain.register(probe)

        assertTrue("抛异常后链应继续", chain.handle(GestureChain.GESTURE_SWIPE_UP))
        assertEquals(1, probe.callCount)
    }

    // ==================== 导航切换手势 ====================

    @Test
    fun navigationSwitch_isHandledBeforeChain() {
        val chain = GestureChain()
        val probe = ProbeActor("Probe")
        chain.register(probe)

        var seen = -1
        chain.onNavigationSwitch = { id -> seen = id; true }
        assertTrue(chain.handle(GestureChain.GESTURE_SWIPE_DOWN_AND_UP))

        assertEquals("应收到手势 id", GestureChain.GESTURE_SWIPE_DOWN_AND_UP, seen)
        assertEquals("导航切换不应落到链上", 0, probe.callCount)
    }
}
