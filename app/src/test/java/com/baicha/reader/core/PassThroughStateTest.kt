package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 穿透模式状态机测试 —— 对标 TalkBack `PassThroughModeActor` + 天坦 `m6/C2126d0`
 *
 * ══════════════════════════════════════════════════════════
 * 【能测什么 / 测不到什么】
 * ══════════════════════════════════════════════════════════
 *
 * ✅ 能测：状态机迁移、两种模式、空闲超时、锁定不超时、
 *          退出原因、边界（重复进入/未进入就退出）
 *
 * ❌ 测不到：`setTouchExplorationPassthroughRegion` 是否真的生效、
 *            真实触摸是否被穿透、定时器在真机上的精度
 */
class PassThroughStateTest {

    // ==================== 进入 / 退出 ====================

    @Test
    fun initially_inactive() {
        val s = PassThroughState()
        assertFalse(s.isActive)
        assertFalse(s.isLocked)
    }

    @Test
    fun enter_activates_transient_mode() {
        val s = PassThroughState()
        assertTrue(s.enter(now = 1000))
        assertTrue(s.isActive)
        assertFalse("瞬时模式不该被标记为锁定", s.isLocked)
    }

    @Test
    fun enter_locked_activates_locked_mode() {
        val s = PassThroughState()
        assertTrue(s.enterLocked(now = 1000))
        assertTrue(s.isActive)
        assertTrue(s.isLocked)
    }

    /**
     * ★ 重复进入返回 false —— 避免重复播报
     *
     * 使用场景：用户连按两次穿透手势，
     * 第二次不该再念一遍「穿透模式」。
     */
    @Test
    fun duplicate_enter_returns_false() {
        val s = PassThroughState()
        assertTrue(s.enter(1000))
        assertFalse("已在瞬时模式，不应再次生效", s.enter(1100))
    }

    @Test
    fun duplicate_locked_enter_returns_false() {
        val s = PassThroughState()
        assertTrue(s.enterLocked(1000))
        assertFalse(s.enterLocked(1100))
    }

    /**
     * 瞬时 → 锁定：应生效（模式确实变了）
     */
    @Test
    fun transient_to_locked_is_a_real_change() {
        val s = PassThroughState()
        s.enter(1000)
        assertTrue("从瞬时切到锁定应生效", s.enterLocked(1100))
        assertTrue(s.isLocked)
    }

    @Test
    fun locked_to_transient_is_a_real_change() {
        val s = PassThroughState()
        s.enterLocked(1000)
        assertTrue("从锁定切到瞬时应生效", s.enter(1100))
        assertFalse(s.isLocked)
    }

    @Test
    fun exit_when_inactive_returns_null() {
        val s = PassThroughState()
        assertNull("本来就没在穿透，退出应返回 null", s.exit())
    }

    @Test
    fun exit_returns_reason() {
        val s = PassThroughState()
        s.enter(1000)
        assertEquals(
            PassThroughState.ExitReason.USER,
            s.exit(PassThroughState.ExitReason.USER, 1100)
        )
        assertFalse(s.isActive)
        assertFalse(s.isLocked)
    }

    @Test
    fun exit_clears_locked_flag() {
        val s = PassThroughState()
        s.enterLocked(1000)
        s.exit(PassThroughState.ExitReason.USER, 1100)
        assertFalse("退出后锁定标志必须清掉", s.isLocked)
    }

    // ==================== 空闲超时 ====================

    @Test
    fun timeout_matches_both_implementations() {
        assertEquals(
            "TalkBack PASS_THROUGH_IDLE_MS=4000；天坦 s() 里也是 4000L",
            4000L,
            PassThroughState.PASS_THROUGH_IDLE_MS
        )
    }

    /**
     * ★ 瞬时模式：4 秒无触摸 → 自动退出
     */
    @Test
    fun transient_mode_exits_after_idle_timeout() {
        val s = PassThroughState()
        s.enter(now = 1000)
        assertFalse("3.9 秒不该退出", s.shouldExitForIdle(4999))
        assertTrue("满 4 秒应退出", s.shouldExitForIdle(5000))
    }

    @Test
    fun check_idle_timeout_returns_reason_and_exits() {
        val s = PassThroughState()
        s.enter(now = 1000)
        val reason = s.checkIdleTimeout(5001)
        assertEquals(PassThroughState.ExitReason.IDLE_TIMEOUT, reason)
        assertFalse("超时后应已退出", s.isActive)
    }

    @Test
    fun check_idle_timeout_returns_null_before_timeout() {
        val s = PassThroughState()
        s.enter(now = 1000)
        assertNull(s.checkIdleTimeout(2000))
        assertTrue("未超时不该退出", s.isActive)
    }

    /**
     * ★★ 锁定模式**不超时** —— 对齐天坦
     *
     * 天坦 `e()`：`if (!this.f29268e || this.f29267d || timer == null) return;
     *              timer.cancel();`
     * 输入全屏区域时走 `h()` 路径，`f29267d=true` → 取消定时器。
     *
     * 【为什么重要】游戏里用户可能十几秒不动（思考策略），
     * 若按空闲超时退出，一动就又变回读屏接管 —— 体验断裂。
     */
    @Test
    fun locked_mode_never_times_out() {
        val s = PassThroughState()
        s.enterLocked(now = 1000)
        assertFalse("锁定模式 1 分钟后也不该超时", s.shouldExitForIdle(61000))
        assertFalse("锁定模式 1 小时后也不该超时", s.shouldExitForIdle(3601000))
        assertNull(s.checkIdleTimeout(999999999))
        assertTrue("锁定模式必须保持激活", s.isActive)
    }

    /**
     * ★ 触摸会刷新空闲计时
     *
     * 对应 TalkBack `PassThroughModeActor`：
     *   "If no user touch activity before the timer expires, ... exit"
     */
    @Test
    fun touch_refreshes_idle_timer() {
        val s = PassThroughState()
        s.enter(now = 1000)

        // 3.5 秒时用户触摸了一次
        s.onTouch(4500)

        assertFalse("触摸后重新计时，3.5 秒时不该退出", s.shouldExitForIdle(5000))
        assertFalse("触摸后 3999ms 仍不该退出", s.shouldExitForIdle(8499))
        assertTrue("触摸后满 4000ms 才退出", s.shouldExitForIdle(8500))
    }

    @Test
    fun touch_while_inactive_is_ignored() {
        val s = PassThroughState()
        s.onTouch(5000)
        assertFalse("未激活时触摸不该产生状态", s.isActive)
        assertFalse(s.shouldExitForIdle(9999999))
    }

    @Test
    fun idle_remaining_is_reported() {
        val s = PassThroughState()
        s.enter(now = 1000)
        assertEquals(4000L, s.idleRemainingMs(1000))
        assertEquals(2000L, s.idleRemainingMs(3000))
        assertTrue("已超时应为负", s.idleRemainingMs(6000) < 0)
    }

    @Test
    fun idle_remaining_is_zero_when_inactive() {
        val s = PassThroughState()
        assertEquals(0L, s.idleRemainingMs(1000))
    }

    // ==================== 边界与重置 ====================

    @Test
    fun reset_clears_everything() {
        val s = PassThroughState()
        s.enterLocked(1000)
        s.reset()
        assertFalse(s.isActive)
        assertFalse(s.isLocked)
    }

    @Test
    fun stats_reports_mode() {
        val s = PassThroughState()
        assertTrue(s.stats().contains("关"))
        s.enter(1000)
        assertTrue("瞬时模式应体现在统计里：${s.stats()}", s.stats().contains("瞬时"))
        s.enterLocked(2000)
        assertTrue("锁定模式应体现在统计里：${s.stats()}", s.stats().contains("锁定"))
    }

    /**
     * 自定义超时可用于测试与将来调整
     */
    @Test
    fun custom_timeout_is_respected() {
        val s = PassThroughState(idleTimeoutMs = 1000)
        s.enter(now = 0)
        assertFalse(s.shouldExitForIdle(999))
        assertTrue(s.shouldExitForIdle(1000))
    }

    // ==================== 退出原因枚举 ====================

    @Test
    fun exit_reasons_are_distinct() {
        val reasons = PassThroughState.ExitReason.values()
        assertEquals(
            "三种退出原因（用户/超时/上下文变化）应互不相同",
            reasons.size,
            reasons.toSet().size
        )
    }

    /**
     * ★ 上下文变化退出 —— 对应天坦
     * `resetTouchExplorePassThroughWhenContextMenuShow`
     */
    @Test
    fun context_change_exit_reason_exists() {
        val s = PassThroughState()
        s.enter(1000)
        assertEquals(
            PassThroughState.ExitReason.CONTEXT_CHANGED,
            s.exit(PassThroughState.ExitReason.CONTEXT_CHANGED, 1100)
        )
    }
}
