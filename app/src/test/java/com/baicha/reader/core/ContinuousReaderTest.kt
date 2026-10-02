package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续朗读状态机测试 —— 对标 TalkBack `FullScreenReadActor` + `FullScreenReadInterpreter`
 *
 * ══════════════════════════════════════════════════════════
 * 【测试依据全部来自源码，行号已标注】
 * ══════════════════════════════════════════════════════════
 *   · `FullScreenReadActor.java:65~67`  三个状态常量
 *   · `FullScreenReadActor.java:315~325` moveForward（推进方式）
 *   · `FullScreenReadInterpreter.java:44`     INTERRUPT_DELAY_MS = 200
 *   · `FullScreenReadInterpreter.java:48~52`  打断事件掩码
 *   · `FullScreenReadInterpreter.java:86~110` 打断规则
 *
 * ══════════════════════════════════════════════════════════
 * 【能测什么 / 测不到什么】
 * ══════════════════════════════════════════════════════════
 *
 * ✅ 能测：状态迁移、打断判定、200ms 窗口、进度条豁免、估算时长
 * ❌ 测不到：朗读是否真的连续（需真机）、TTS 完成回调时序、
 *            WakeLock 是否生效
 */
class ContinuousReadStateTest {

    // ==================== 状态常量（对齐 TalkBack）====================

    @Test
    fun state_codes_match_talkback() {
        assertEquals("STATE_STOPPED = 0", 0, ReadState.STOPPED.code)
        assertEquals("STATE_READING_FROM_BEGINNING = 1", 1, ReadState.READING_FROM_BEGINNING.code)
        assertEquals("STATE_READING_FROM_NEXT = 2", 2, ReadState.READING_FROM_NEXT.code)
    }

    @Test
    fun stopped_is_not_active() {
        assertFalse(ReadState.STOPPED.isActive)
        assertTrue(ReadState.READING_FROM_BEGINNING.isActive)
        assertTrue(ReadState.READING_FROM_NEXT.isActive)
    }

    @Test
    fun state_from_code_round_trips() {
        ReadState.values().forEach { st ->
            assertEquals(st, ReadState.fromCode(st.code))
        }
        assertEquals("未知码回退 STOPPED", ReadState.STOPPED, ReadState.fromCode(99))
    }

    // ==================== 状态迁移 ====================

    @Test
    fun initially_stopped() {
        val s = ContinuousReadState()
        assertEquals(ReadState.STOPPED, s.state)
        assertFalse(s.isActive)
    }

    @Test
    fun start_from_next() {
        val s = ContinuousReadState()
        assertTrue(s.start(fromBeginning = false, now = 1000))
        assertEquals(ReadState.READING_FROM_NEXT, s.state)
        assertTrue(s.isActive)
    }

    @Test
    fun start_from_beginning() {
        val s = ContinuousReadState()
        assertTrue(s.start(fromBeginning = true, now = 1000))
        assertEquals(ReadState.READING_FROM_BEGINNING, s.state)
    }

    /** 重复 start 同一种模式 → false，避免重复播报 */
    @Test
    fun duplicate_start_returns_false() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertFalse(s.start(fromBeginning = false, now = 1100))
    }

    /** 换模式 → 算真实变化 */
    @Test
    fun switching_read_mode_is_a_change() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertTrue(s.start(fromBeginning = true, now = 1100))
        assertEquals(ReadState.READING_FROM_BEGINNING, s.state)
    }

    @Test
    fun stop_when_active_returns_true() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertTrue(s.stop())
        assertEquals(ReadState.STOPPED, s.state)
    }

    @Test
    fun stop_when_inactive_returns_false() {
        val s = ContinuousReadState()
        assertFalse("本来就没在朗读，停止应返回 false", s.stop())
    }

    /**
     * ★ previousState 记录「切换前是什么」——
     * TalkBack `FullScreenReadActor:337` 用它判断「是否从朗读中恢复」
     */
    @Test
    fun previous_state_is_tracked() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        s.stop()
        assertEquals(ReadState.READING_FROM_NEXT, s.previousState)
    }

    // ==================== 打断判定 ====================

    @Test
    fun interrupt_delay_matches_talkback() {
        assertEquals(
            "TalkBack FullScreenReadInterpreter.java:44 INTERRUPT_DELAY_MS = 200",
            200L,
            ContinuousReadState.INTERRUPT_DELAY_MS
        )
    }

    /**
     * ★ 掩码必须由这 4 个事件组成
     * 依据：`FullScreenReadInterpreter.java:48~52`
     */
    @Test
    fun interrupt_mask_contains_the_four_events() {
        val m = ContinuousReadState.MASK_INTERRUPT
        assertTrue("应含 TYPE_VIEW_CLICKED", (m and ContinuousReadState.TYPE_VIEW_CLICKED) != 0)
        assertTrue("应含 TYPE_VIEW_LONG_CLICKED", (m and ContinuousReadState.TYPE_VIEW_LONG_CLICKED) != 0)
        assertTrue(
            "应含 TYPE_TOUCH_EXPLORATION_GESTURE_START",
            (m and ContinuousReadState.TYPE_TOUCH_EXPLORATION_GESTURE_START) != 0
        )
        assertTrue("应含 TYPE_ANNOUNCEMENT", (m and ContinuousReadState.TYPE_ANNOUNCEMENT) != 0)
    }

    /**
     * ★ 事件常量已用 `javap android.jar` 核实
     */
    @Test
    fun event_constants_match_android() {
        assertEquals(1, ContinuousReadState.TYPE_VIEW_CLICKED)
        assertEquals(2, ContinuousReadState.TYPE_VIEW_LONG_CLICKED)
        assertEquals(4, ContinuousReadState.TYPE_VIEW_SELECTED)
        assertEquals(16384, ContinuousReadState.TYPE_ANNOUNCEMENT)
        assertEquals(
            "TOUCH_EXPLORATION_GESTURE_START = 512（不是 1048576）",
            512,
            ContinuousReadState.TYPE_TOUCH_EXPLORATION_GESTURE_START
        )
    }

    @Test
    fun inactive_never_interrupts() {
        val s = ContinuousReadState()
        assertFalse(
            "未在朗读时不该「打断」",
            s.shouldInterrupt(ContinuousReadState.TYPE_VIEW_CLICKED, 5000)
        )
    }

    @Test
    fun click_interrupts_after_delay_window() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertTrue(
            "启动 200ms 后的点击应打断",
            s.shouldInterrupt(ContinuousReadState.TYPE_VIEW_CLICKED, 1200)
        )
    }

    /**
     * ★★ 启动后 200ms 内的点击**忽略**
     *
     * 依据：`FullScreenReadInterpreter.java:86~93`
     *   "the clicking the OK button event comes so late after the ACM
     *    is active, so that TalkBack will stop the continuity."
     */
    @Test
    fun click_within_delay_window_is_ignored() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertFalse(
            "启动 100ms 内的点击应被忽略（对话框确认键的延迟事件）",
            s.shouldInterrupt(ContinuousReadState.TYPE_VIEW_CLICKED, 1100)
        )
        assertFalse(
            "199ms 仍应忽略",
            s.shouldInterrupt(ContinuousReadState.TYPE_VIEW_CLICKED, 1199)
        )
        assertTrue(
            "满 200ms 应打断",
            s.shouldInterrupt(ContinuousReadState.TYPE_VIEW_CLICKED, 1200)
        )
    }

    @Test
    fun long_click_interrupts() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertTrue(s.shouldInterrupt(ContinuousReadState.TYPE_VIEW_LONG_CLICKED, 5000))
    }

    @Test
    fun announcement_interrupts() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertTrue(s.shouldInterrupt(ContinuousReadState.TYPE_ANNOUNCEMENT, 5000))
    }

    @Test
    fun touch_exploration_start_interrupts() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertTrue(
            "触摸探索开始应打断（用户上手了）",
            s.shouldInterrupt(ContinuousReadState.TYPE_TOUCH_EXPLORATION_GESTURE_START, 5000)
        )
    }

    // ==================== TYPE_VIEW_SELECTED 的特殊处理 ====================

    /**
     * ★★ 选中事件打断朗读，**但进度条/滑块除外**
     *
     * 依据：`FullScreenReadInterpreter.java:97~104`
     *   "Interrupts full screen reading on TYPE_VIEW_SELECTED event that
     *    comes from user actions. Except for progress bar and seek bar
     *    that may send the event automatically."
     *
     * 【这条测试是 A4 最容易写错的地方】
     *   若不做豁免，进度条每走一格就会打断连续朗读。
     */
    @Test
    fun selected_interrupts_for_normal_nodes() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertTrue(
            "普通节点的选中事件应打断",
            s.shouldInterrupt(ContinuousReadState.TYPE_VIEW_SELECTED, 5000, isProgressBar = false)
        )
    }

    @Test
    fun selected_does_not_interrupt_for_progress_bar() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertFalse(
            "进度条的自动选中事件不该打断朗读",
            s.shouldInterrupt(ContinuousReadState.TYPE_VIEW_SELECTED, 5000, isProgressBar = true)
        )
    }

    /** 非选中事件，进度条标志不影响 */
    @Test
    fun progress_bar_flag_only_affects_selected() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertTrue(
            "点击事件即使来自进度条也应打断（是用户真的点了）",
            s.shouldInterrupt(
                ContinuousReadState.TYPE_VIEW_CLICKED, 5000, isProgressBar = true
            )
        )
    }

    /** 无关事件不打断 */
    @Test
    fun unrelated_event_does_not_interrupt() {
        val s = ContinuousReadState()
        s.start(fromBeginning = false, now = 1000)
        assertFalse(
            "窗口变化等无关事件不该打断朗读",
            s.shouldInterrupt(32, 5000)   // TYPE_WINDOW_STATE_CHANGED
        )
    }

    @Test
    fun reset_clears_state() {
        val s = ContinuousReadState()
        s.start(fromBeginning = true, now = 1000)
        s.reset()
        assertEquals(ReadState.STOPPED, s.state)
        assertEquals(ReadState.STOPPED, s.previousState)
    }

    @Test
    fun stats_reports_state() {
        val s = ContinuousReadState()
        assertTrue(s.stats().contains("已停止"))
        s.start(fromBeginning = false, now = 0)
        assertTrue(s.stats().contains("连续朗读"))
    }
}

/**
 * 朗读时长估算测试
 *
 * 【为什么需要】TalkBack 用 TTS 完成回调推进；
 * 白茶没有该回调注入点，改用时长估算。
 * 估算错了会「长文本没读完就跳走」或「短文本读完干等」。
 */
class ContinuousReaderEstimateTest {

    private fun reader(text: String?) = ContinuousReader(
        state = ContinuousReadState(),
        moveNext = { true },
        playComplete = {},
        speak = {},
        currentText = { text }
    )

    @Test
    fun estimate_constants_are_reasonable() {
        assertTrue(
            "每字 200ms ≈ 300 字/分钟，符合中文 TTS 语速",
            ContinuousReader.PER_CHAR_MS in 100L..300L
        )
        assertTrue(ContinuousReader.MIN_DELAY_MS > 0)
        assertTrue(
            "最长间隔必须封顶，否则超长文本会让用户以为卡死",
            ContinuousReader.MAX_DELAY_MS <= 60000L
        )
    }

    @Test
    fun no_text_returns_min_delay() {
        assertEquals(ContinuousReader.MIN_DELAY_MS, reader(null).estimateDelayMs())
    }

    @Test
    fun longer_text_gets_longer_delay() {
        val short = reader("确定").estimateDelayMs()
        val long = reader("这是一段比较长的文本用于验证估算随长度增长").estimateDelayMs()
        assertTrue("长文本延迟应更长：$short vs $long", long > short)
    }

    @Test
    fun delay_is_clamped_to_min() {
        // 空串或极短文本
        assertTrue(reader("").estimateDelayMs() >= ContinuousReader.MIN_DELAY_MS)
    }

    @Test
    fun delay_is_clamped_to_max() {
        val huge = "字".repeat(10000)
        assertTrue(
            "超长文本必须被 clamp，否则用户以为卡死",
            reader(huge).estimateDelayMs() <= ContinuousReader.MAX_DELAY_MS
        )
    }

    @Test
    fun normal_text_falls_in_range() {
        val d = reader("这是二十个字的普通文本内容示例").estimateDelayMs()
        assertTrue(d in ContinuousReader.MIN_DELAY_MS..ContinuousReader.MAX_DELAY_MS)
    }
}
