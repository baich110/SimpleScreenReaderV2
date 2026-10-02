package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统状态播报测试 —— 对标 TalkBack `RingerModeAndScreenMonitor` + 天坦 `W6/h`
 *
 * ══════════════════════════════════════════════════════════
 * 【能测什么 / 测不到什么】
 * ══════════════════════════════════════════════════════════
 *
 * ✅ 能测：播报文案规则（哪种模式播、哪种不播）、
 *          常量与两方一致、action 常量正确
 *
 * ❌ 测不到：**真实广播是否送达**、注册/注销生命周期、
 *            ROM 差异（HyperOS 的 USER_PRESENT/USER_UNLOCKED 时序）
 *    —— 需要真机
 */
class SystemStateSpeechTest {

    // ==================== 播报规则 ====================

    /**
     * ★ 静音必须播报 —— 这是最需要用户知道的状态
     */
    @Test
    fun silent_mode_announces() {
        assertEquals(
            SystemStateSpeech.TEXT_SILENT,
            SystemStateSpeech.ringerAnnouncement(SystemStateAnnouncer.RINGER_MODE_SILENT)
        )
        assertTrue(
            SystemStateSpeech.shouldAnnounceRinger(SystemStateAnnouncer.RINGER_MODE_SILENT)
        )
    }

    @Test
    fun vibrate_mode_announces() {
        assertEquals(
            SystemStateSpeech.TEXT_VIBRATE,
            SystemStateSpeech.ringerAnnouncement(SystemStateAnnouncer.RINGER_MODE_VIBRATE)
        )
        assertTrue(
            SystemStateSpeech.shouldAnnounceRinger(SystemStateAnnouncer.RINGER_MODE_VIBRATE)
        )
    }

    /**
     * ★★ 正常模式**不播报** —— 这是关键设计，两方一致。
     *
     * 【为什么】
     *   从静音切回正常时，铃声本身就是最好的反馈；
     *   再念「正常模式」是噪音，而且会和铃声重叠。
     */
    @Test
    fun normal_mode_does_not_announce() {
        assertNull(
            "正常模式不该播报 —— 铃声本身就是反馈",
            SystemStateSpeech.ringerAnnouncement(SystemStateAnnouncer.RINGER_MODE_NORMAL)
        )
        assertFalse(
            SystemStateSpeech.shouldAnnounceRinger(SystemStateAnnouncer.RINGER_MODE_NORMAL)
        )
    }

    /**
     * 未知模式不播 —— 避免误报（如未来 Android 新增模式）
     */
    @Test
    fun unknown_mode_does_not_announce() {
        assertNull(SystemStateSpeech.ringerAnnouncement(99))
        assertNull(SystemStateSpeech.ringerAnnouncement(-1))
        assertFalse(SystemStateSpeech.shouldAnnounceRinger(99))
    }

    /** shouldAnnounce 与 announcement 必须一致 —— 防止两处判断漂移 */
    @Test
    fun should_announce_matches_announcement() {
        listOf(-1, 0, 1, 2, 3, 99).forEach { mode ->
            assertEquals(
                "模式 $mode 的两处判断不一致",
                SystemStateSpeech.ringerAnnouncement(mode) != null,
                SystemStateSpeech.shouldAnnounceRinger(mode)
            )
        }
    }

    @Test
    fun announcement_texts_are_non_empty() {
        assertTrue(SystemStateSpeech.TEXT_SILENT.isNotEmpty())
        assertTrue(SystemStateSpeech.TEXT_VIBRATE.isNotEmpty())
    }

    // ==================== 常量对齐 ====================

    /**
     * ★ 铃声模式常量必须与 Android `AudioManager` 一致。
     * 写错会导致「静音时念成震动」—— 且不会有任何报错。
     */
    @Test
    fun ringer_mode_constants_match_android() {
        assertEquals(0, SystemStateAnnouncer.RINGER_MODE_SILENT)
        assertEquals(1, SystemStateAnnouncer.RINGER_MODE_VIBRATE)
        assertEquals(2, SystemStateAnnouncer.RINGER_MODE_NORMAL)
    }

    @Test
    fun ringer_action_matches_android() {
        assertEquals(
            "android.media.RINGER_MODE_CHANGED",
            SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED
        )
    }

    /**
     * ★ 天坦多注册的 USER_UNLOCKED —— 本实现也注册
     */
    @Test
    fun user_unlocked_action_is_registered() {
        assertEquals(
            "android.intent.action.USER_UNLOCKED",
            SystemStateAnnouncer.ACTION_USER_UNLOCKED
        )
    }

    /** 天坦设 999 优先级避免被其他应用拦截 */
    @Test
    fun priority_matches_tantang() {
        assertEquals(999, SystemStateAnnouncer.RECEIVER_PRIORITY)
    }
}

/**
 * 广播处理逻辑测试 —— 通过 [SystemStateAnnouncer.handleAction]
 * 直接驱动，不需要真实 Intent/Context
 *
 * 【为什么能这样测】
 *   本轮特意把处理逻辑从 `onReceive` 抽成 `handleAction(action, mode)` ——
 *   原写法需要真实 Intent，JVM 里构造困难，导致这块零覆盖。
 */
class SystemStateAnnouncerTest {

    private val spoken = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    /**
     * 【注意】本测试**不注册真实广播**，只调用 handleAction ——
     * 因为 register() 需要真实 Context。
     * 这里传 null 会崩，故用一个最小替身无法构造（Context 是抽象类）。
     *
     * 所以本类只测「纯处理逻辑」部分，构造器参数用 lazy 委托规避。
     */
    private fun announcer() = SystemStateAnnouncer(
        context = NoopContext(),
        speak = { t, _ -> spoken.add(t) },
        log = { logs.add(it) }
    )

    @Test
    fun silent_broadcast_speaks() {
        val a = announcer()
        a.handleAction(
            SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED,
            SystemStateAnnouncer.RINGER_MODE_SILENT
        )
        assertEquals(1, spoken.size)
        assertEquals(SystemStateSpeech.TEXT_SILENT, spoken[0])
    }

    @Test
    fun vibrate_broadcast_speaks() {
        val a = announcer()
        a.handleAction(
            SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED,
            SystemStateAnnouncer.RINGER_MODE_VIBRATE
        )
        assertEquals(SystemStateSpeech.TEXT_VIBRATE, spoken[0])
    }

    @Test
    fun normal_broadcast_is_silent() {
        val a = announcer()
        a.handleAction(
            SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED,
            SystemStateAnnouncer.RINGER_MODE_NORMAL
        )
        assertTrue("正常模式不该出声：$spoken", spoken.isEmpty())
    }

    @Test
    fun screen_off_updates_state() {
        val a = announcer()
        a.handleAction(android.content.Intent.ACTION_SCREEN_OFF)
        assertFalse("熄屏后 isInteractive 应为 false", a.isInteractive)
        assertFalse(a.isScreenOn())
    }

    @Test
    fun screen_on_updates_state() {
        val a = announcer()
        a.handleAction(android.content.Intent.ACTION_SCREEN_OFF)
        a.handleAction(android.content.Intent.ACTION_SCREEN_ON)
        assertTrue("亮屏后 isInteractive 应为 true", a.isInteractive)
    }

    /** 屏幕开关不该出声 —— 用户自己按的电源键，知道发生了什么 */
    @Test
    fun screen_events_do_not_speak() {
        val a = announcer()
        a.handleAction(android.content.Intent.ACTION_SCREEN_ON)
        a.handleAction(android.content.Intent.ACTION_SCREEN_OFF)
        assertTrue("屏幕开关不该播报：$spoken", spoken.isEmpty())
    }

    @Test
    fun ringer_mode_is_tracked() {
        val a = announcer()
        a.handleAction(
            SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED,
            SystemStateAnnouncer.RINGER_MODE_VIBRATE
        )
        assertEquals(SystemStateAnnouncer.RINGER_MODE_VIBRATE, a.ringerMode)
    }

    @Test
    fun all_actions_are_logged() {
        val a = announcer()
        a.handleAction(android.content.Intent.ACTION_SCREEN_ON)
        a.handleAction(android.content.Intent.ACTION_SCREEN_OFF)
        a.handleAction(android.content.Intent.ACTION_USER_PRESENT)
        a.handleAction(SystemStateAnnouncer.ACTION_USER_UNLOCKED)
        assertTrue("每个事件都应有日志便于真机排障：$logs", logs.size >= 4)
    }

    @Test
    fun unknown_action_is_ignored_safely() {
        val a = announcer()
        a.handleAction("com.example.UNKNOWN_ACTION")
        assertTrue(spoken.isEmpty())
    }
}

/** 最小 Context 替身 —— 只用于不触碰系统服务的测试路径 */
private class NoopContext : android.content.ContextWrapper(null)
