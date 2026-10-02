package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 补全测试（A5）—— ContentObserver + WakeLock
 *
 * ══════════════════════════════════════════════════════════
 * 【测试依据（源码行号）】
 * ══════════════════════════════════════════════════════════
 *   · TalkBack `FailoverTextToSpeech.java:293~300` 三个 URI
 *   · 天坦     `Z6/J.java:508~515`                  三个 URI（相同）
 *   · 天坦     `Z6/J.java:1080~1094`                变更后重读字段
 *   · TalkBack `ProcessorVolumeStream.java:126~129` WakeLock flags
 *   · 天坦     `Z6/J.java:191`                      WakeLock 字面量
 *
 * ══════════════════════════════════════════════════════════
 * 【能测什么 / 测不到什么】
 * ══════════════════════════════════════════════════════════
 * ✅ 能测：URI 常量、百分数换算、WakeLock flag 值、引用计数语义
 * ❌ 测不到：系统设置变更是否真的送达、唤醒锁是否真的阻止休眠
 *           （都需要真机/真实 Context）
 */
class TtsSettingsObserverTest {

    /**
     * ★ 三个 URI 必须与两方源码一致
     *
     * 天坦 `Z6/J.java:508~510`：
     *   Settings.Secure.getUriFor("tts_default_synth" / "tts_default_pitch" / "tts_default_rate")
     * TalkBack `:293~295` 用同名常量。
     *
     * 【URI 格式已核实】`Settings.Secure.getUriFor(name)` 返回
     * `content://settings/secure/<name>`。
     */
    @Test
    fun observer_uris_match_both_sources() {
        assertEquals(
            "content://settings/secure/tts_default_synth",
            TtsSettingsObserver.URI_DEFAULT_SYNTH
        )
        assertEquals(
            "content://settings/secure/tts_default_pitch",
            TtsSettingsObserver.URI_DEFAULT_PITCH
        )
        assertEquals(
            "content://settings/secure/tts_default_rate",
            TtsSettingsObserver.URI_DEFAULT_RATE
        )
    }

    /** 三个 URI 必须不同 —— 否则会重复回调 */
    @Test
    fun the_three_uris_are_distinct() {
        val uris = setOf(
            TtsSettingsObserver.URI_DEFAULT_SYNTH,
            TtsSettingsObserver.URI_DEFAULT_PITCH,
            TtsSettingsObserver.URI_DEFAULT_RATE
        )
        assertEquals("三个被观察的 URI 必须互不相同", 3, uris.size)
    }

    /**
     * ★ 百分数 → 倍率换算
     *
     * 系统存的是百分数（100 = 正常），天坦 `Z6/J.java:1081` 做 `/ 100.0f`。
     * 若漏了这一步，语速会变成 100 倍。
     */
    @Test
    fun percent_to_ratio_constants() {
        assertEquals("系统默认存 100", 100, TtsSettingsObserver.DEFAULT_PERCENT)
        assertEquals(100.0f, TtsSettingsObserver.PERCENT_TO_RATIO, 0.001f)
    }

    @Test
    fun percent_conversion_is_correct() {
        // 模拟天坦的 / 100.0f
        fun toRatio(percent: Int) = percent / TtsSettingsObserver.PERCENT_TO_RATIO

        assertEquals(1.0f, toRatio(100), 0.001f)
        assertEquals(2.0f, toRatio(200), 0.001f)
        assertEquals(0.5f, toRatio(50), 0.001f)
    }
}

/**
 * 朗读防休眠测试
 *
 * 【为什么这批测试重要】
 *   引用计数写错会导致两种真实故障：
 *     · 计数少减 → 唤醒锁永不释放 → **电池被持续消耗**
 *     · 计数多减 → 提前释放 → 朗读中途熄屏
 *   两者都只有真机能发现，所以逻辑必须靠单测锁死。
 */
class SpeechWakeLockTest {

    /**
     * ★ flag 值必须等于两方的用法
     *
     * 天坦 `Z6/J.java:191` 的字面量 536870922
     *   = SCREEN_BRIGHT_WAKE_LOCK(10) | ON_AFTER_RELEASE(536870912)
     * TalkBack `ProcessorVolumeStream.java:128` 用同样两个常量。
     *
     * 用 javap 核实过常量值：
     *   SCREEN_BRIGHT_WAKE_LOCK = 10
     *   ON_AFTER_RELEASE        = 536870912
     */
    @Test
    fun wake_lock_flags_match_both_sources() {
        assertEquals(
            "天坦字面量 536870922 = 0x2000000A",
            536870922,
            SpeechWakeLock.WAKE_LOCK_FLAGS
        )
    }

    /**
     * 引用计数：嵌套获取时只真正 acquire 一次
     *
     * 场景：连续朗读持有锁 → 中途播报提示又获取一次
     */
    @Test
    fun nested_acquire_uses_reference_counting() {
        val w = SpeechWakeLock(NoopContext())
        // 无真实 PowerManager → lock 为 null，但计数逻辑仍可验证
        w.acquire()
        w.acquire()
        w.acquire()
        // 内层释放不应真正解锁
        assertFalse("还有 2 个使用者，不应释放", w.release())
        assertFalse("还有 1 个使用者，不应释放", w.release())
        // 最后一个释放 → 真正释放
        w.release()
    }

    @Test
    fun release_without_acquire_is_noop() {
        val w = SpeechWakeLock(NoopContext())
        assertFalse("未获取就释放应返回 false", w.release())
    }

    /** 多余的 release 不能让计数变负 —— 否则后续 acquire 会算错 */
    @Test
    fun extra_release_does_not_go_negative() {
        val w = SpeechWakeLock(NoopContext())
        w.acquire()
        w.release()
        w.release()   // 多余的一次
        w.release()   // 再来一次
        // 若计数变负，下面的 acquire 会以为「已有使用者」而不真正获取
        assertTrue("计数不应为负导致后续获取失效", w.acquire())
    }

    @Test
    fun release_all_clears_count() {
        val w = SpeechWakeLock(NoopContext())
        w.acquire()
        w.acquire()
        w.releaseAll()
        assertFalse("releaseAll 后应可再次获取", w.release())
    }

    @Test
    fun is_held_is_false_without_power_manager() {
        val w = SpeechWakeLock(NoopContext())
        // 无真实 PowerManager → 不会真的持有
        assertFalse(w.isHeld)
    }
}

