package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统状态播报测试 —— 已按两方**源码**核实修正
 *
 * ══════════════════════════════════════════════════════════
 * 【本文件修正了初版的三处「凭印象」错误】
 * ══════════════════════════════════════════════════════════
 *
 * | 事件 | 初版（错） | 真实行为 | 来源 |
 * |---|---|---|---|
 * | 铃声变化 | 主动播报 | **只存字段** | TalkBack `handleRingerModeChanged()` / 天坦 `l(int)` |
 * | 屏幕点亮 | 不播 | 播**时间** | TalkBack `speakForDisplayOn()` |
 * | 屏幕关闭 | 不播 | 播「屏幕关闭」+ 铃声 | TalkBack `handleScreenOffInBackgroundThread()` |
 *
 * 下面每条测试都在注释里标注**源码依据**，
 * 避免再出现「凭印象写测试，把错的锁死」。
 */
class SystemStateSpeechTest {

    // ==================== 铃声：只存字段，不主动播报 ====================

    /**
     * ★ 铃声名称映射本身正确（用于日志与熄屏顺带播报）
     */
    @Test
    fun ringer_name_mapping() {
        assertEquals(
            SystemStateSpeech.TEXT_SILENT,
            SystemStateSpeech.ringerName(SystemStateAnnouncer.RINGER_MODE_SILENT)
        )
        assertEquals(
            SystemStateSpeech.TEXT_VIBRATE,
            SystemStateSpeech.ringerName(SystemStateAnnouncer.RINGER_MODE_VIBRATE)
        )
        assertEquals(
            SystemStateSpeech.TEXT_NORMAL,
            SystemStateSpeech.ringerName(SystemStateAnnouncer.RINGER_MODE_NORMAL)
        )
    }

    @Test
    fun unknown_ringer_mode_is_safe() {
        assertEquals("未知", SystemStateSpeech.ringerName(99))
        assertEquals("未知", SystemStateSpeech.ringerName(-1))
    }

    // ==================== 熄屏播报 ====================

    /**
     * ★ 熄屏播「屏幕已关闭」（静音状态）
     * 依据：TalkBack `handleScreenOffInBackgroundThread()`
     */
    @Test
    fun screen_off_with_silent_announces_both() {
        val text = SystemStateSpeech.screenOffAnnouncement(
            inCall = false,
            ringerMode = SystemStateAnnouncer.RINGER_MODE_SILENT
        )
        assertTrue("应播「屏幕已关闭」：$text", text.contains(SystemStateSpeech.TEXT_SCREEN_OFF))
        assertTrue("静音时还应顺带念铃声状态：$text", text.contains(SystemStateSpeech.TEXT_SILENT))
    }

    @Test
    fun screen_off_with_vibrate_announces_both() {
        val text = SystemStateSpeech.screenOffAnnouncement(
            inCall = false,
            ringerMode = SystemStateAnnouncer.RINGER_MODE_VIBRATE
        )
        assertTrue(text.contains(SystemStateSpeech.TEXT_SCREEN_OFF))
        assertTrue("震动时也应念：$text", text.contains(SystemStateSpeech.TEXT_VIBRATE))
    }

    /**
     * ★ 响铃（正常）时不念铃声状态 —— 对齐 TalkBack
     * `appendRingerStateAnnouncement()` 里 RINGER_MODE_NORMAL 直接 return
     */
    @Test
    fun screen_off_with_normal_omits_ringer() {
        val text = SystemStateSpeech.screenOffAnnouncement(
            inCall = false,
            ringerMode = SystemStateAnnouncer.RINGER_MODE_NORMAL
        )
        assertEquals(
            "响铃是默认状态，念它是噪音",
            SystemStateSpeech.TEXT_SCREEN_OFF,
            text
        )
    }

    /**
     * ★★ 通话中不念铃声状态
     *
     * 依据：TalkBack `handleScreenOffInBackgroundThread()` 的
     *   `if (isIdle()) { appendRingerStateAnnouncement(ttsText); }`
     *   —— 明确判定「不在通话中」才追加。
     *
     * 【这条测试就是初版漏掉的场景】
     */
    @Test
    fun screen_off_during_call_omits_ringer() {
        listOf(
            SystemStateAnnouncer.RINGER_MODE_SILENT,
            SystemStateAnnouncer.RINGER_MODE_VIBRATE,
            SystemStateAnnouncer.RINGER_MODE_NORMAL
        ).forEach { mode ->
            val text = SystemStateSpeech.screenOffAnnouncement(inCall = true, ringerMode = mode)
            assertEquals(
                "通话中熄屏不该念铃声状态（模式=$mode）",
                SystemStateSpeech.TEXT_SCREEN_OFF,
                text
            )
        }
    }

    // ==================== 亮屏播报时间 ====================

    /**
     * ★★ 亮屏播报的是「时间」而不是「屏幕点亮」
     *
     * 依据：TalkBack `speakForDisplayOn()` →
     *   `appendCurrentTimeAnnouncementIfNeeded(ttsText)`
     * 天坦 `n()` 播锁屏内容（时间/日期/电量拼装）。
     * 两方都是「告诉用户现在几点」。
     */
    @Test
    fun screen_on_formats_time_not_plain_text() {
        val t = SystemStateSpeech.formatTime(0L)
        assertNotNull(t)
        assertTrue("应形如 HH:mm：$t", Regex("^\\d{2}:\\d{2}$").matches(t!!))
    }

    @Test
    fun time_format_uses_24_hour() {
        // 用固定 epoch 验证格式（避免依赖本地时区的小时值）
        val t = SystemStateSpeech.formatTime(1700000000000L)
        assertNotNull(t)
        assertTrue("必须是 24 小时制 HH:mm：$t", t!!.split(":")[0].toInt() in 0..23)
    }

    @Test
    fun time_is_null_when_disabled() {
        assertNull(
            "用户关闭报时后不该播时间",
            SystemStateSpeech.formatTime(System.currentTimeMillis(), enabledTellingTime = false)
        )
    }

    /**
     * ★ 亮屏播时间的两个前置条件（对齐 TalkBack speakForDisplayOn）
     */
    @Test
    fun screen_on_time_requires_both_conditions() {
        assertTrue(
            SystemStateSpeech.shouldAnnounceTimeOnScreenOn(
                enabledTellingTime = true, deviceProvisioned = true
            )
        )
        assertFalse(
            "关闭报时 → 不播",
            SystemStateSpeech.shouldAnnounceTimeOnScreenOn(
                enabledTellingTime = false, deviceProvisioned = true
            )
        )
        assertFalse(
            "设备未完成初始设置 → 不播时间",
            SystemStateSpeech.shouldAnnounceTimeOnScreenOn(
                enabledTellingTime = true, deviceProvisioned = false
            )
        )
    }

    // ==================== 常量 ====================

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

    @Test
    fun user_unlocked_action_is_registered() {
        assertEquals(
            "android.intent.action.USER_UNLOCKED",
            SystemStateAnnouncer.ACTION_USER_UNLOCKED
        )
    }

    @Test
    fun priority_matches_tantang() {
        assertEquals(999, SystemStateAnnouncer.RECEIVER_PRIORITY)
    }
}

/**
 * 广播处理逻辑测试
 *
 * 【本轮修正的核心】铃声变化**不该出声** ——
 * 初版实现播报了，与两方源码都不符。
 */
class SystemStateAnnouncerTest {

    private val spoken = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private fun announcer() = SystemStateAnnouncer(
        context = NoopContext(),
        speak = { t, _ -> spoken.add(t) },
        log = { logs.add(it) }
    )

    /**
     * ★★ 铃声变化**不出声** —— 这是本轮修正的头号问题
     *
     * 依据：TalkBack `handleRingerModeChanged()` 只有 `this.ringerMode = ringerMode;`
     *       天坦 `l(int)` 只有 `this.f10351l = i9;`
     *
     * 【这条测试如果存在，初版的错误就不会发生】
     */
    @Test
    fun ringer_change_does_not_speak() {
        val a = announcer()
        listOf(
            SystemStateAnnouncer.RINGER_MODE_SILENT,
            SystemStateAnnouncer.RINGER_MODE_VIBRATE,
            SystemStateAnnouncer.RINGER_MODE_NORMAL
        ).forEach { mode ->
            a.handleAction(SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED, mode)
        }
        assertTrue(
            "两方的铃声变化都只存字段不播报，白茶也不该出声。实际：$spoken",
            spoken.isEmpty()
        )
    }

    /** 但铃声模式必须被记录下来（熄屏时要顺带播） */
    @Test
    fun ringer_change_is_still_tracked() {
        val a = announcer()
        a.handleAction(
            SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED,
            SystemStateAnnouncer.RINGER_MODE_VIBRATE
        )
        assertEquals(SystemStateAnnouncer.RINGER_MODE_VIBRATE, a.ringerMode)
    }

    /**
     * ★★ 熄屏播「屏幕已关闭」+ 铃声状态（非通话中）
     */
    @Test
    fun screen_off_speaks_screen_off_and_ringer() {
        val a = announcer()
        a.handleAction(
            SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED,
            SystemStateAnnouncer.RINGER_MODE_SILENT
        )
        a.handleAction(android.content.Intent.ACTION_SCREEN_OFF)

        assertEquals(1, spoken.size)
        assertTrue(
            "应播「屏幕已关闭，静音」：${spoken[0]}",
            spoken[0].contains(SystemStateSpeech.TEXT_SCREEN_OFF) &&
                spoken[0].contains(SystemStateSpeech.TEXT_SILENT)
        )
    }

    /** 通话中熄屏不出声铃声状态 */
    @Test
    fun screen_off_in_call_omits_ringer() {
        val a = announcer()
        a.inCall = true
        a.handleAction(
            SystemStateAnnouncer.ACTION_RINGER_MODE_CHANGED,
            SystemStateAnnouncer.RINGER_MODE_SILENT
        )
        a.handleAction(android.content.Intent.ACTION_SCREEN_OFF)

        assertEquals(SystemStateSpeech.TEXT_SCREEN_OFF, spoken[0])
    }

    /**
     * ★★ 亮屏播报「时间」而不是「屏幕点亮」
     */
    @Test
    fun screen_on_speaks_time() {
        val a = announcer()
        a.handleAction(android.content.Intent.ACTION_SCREEN_ON)

        assertEquals(1, spoken.size)
        assertTrue(
            "亮屏应播时间（HH:mm）：${spoken[0]}",
            Regex("^\\d{2}:\\d{2}$").matches(spoken[0])
        )
    }

    @Test
    fun screen_state_is_tracked() {
        val a = announcer()
        a.handleAction(android.content.Intent.ACTION_SCREEN_OFF)
        assertFalse(a.isInteractive)
        a.handleAction(android.content.Intent.ACTION_SCREEN_ON)
        assertTrue(a.isInteractive)
    }

    /** USER_PRESENT 不播报（两方都不播） */
    @Test
    fun user_present_does_not_speak() {
        val a = announcer()
        a.handleAction(android.content.Intent.ACTION_USER_PRESENT)
        assertTrue(spoken.isEmpty())
    }

    /** 解锁也不播报（天坦只恢复状态） */
    @Test
    fun user_unlocked_does_not_speak() {
        val a = announcer()
        a.handleAction(SystemStateAnnouncer.ACTION_USER_UNLOCKED)
        assertTrue(spoken.isEmpty())
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

