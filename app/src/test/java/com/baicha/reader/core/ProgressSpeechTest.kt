package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度播报测试
 *
 * 对齐 TalkBack `AccessibilityNodeInfoUtils.getProgressPercent()`
 * 与天坦 `AbstractC3693m.p()`（两者数学相同）+ 天坦的三路分派。
 *
 * 两方都把这套逻辑直接绑在 AccessibilityNodeInfo 上，极难单测；
 * 白茶把它抽成只吃 Float 的纯函数，于是全部边界都能逐条验证。
 */
class ProgressSpeechTest {

    private val delta = 0.01f

    // ==================== 数学部分（与两方一致）====================

    @Test
    fun basic_normalization() {
        // 音量 0~15，当前 7.5 → 50%
        assertEquals(50f, ProgressSpeech.percent(7.5f, 0f, 15f), delta)
    }

    @Test
    fun at_min_is_zero() {
        assertEquals(0f, ProgressSpeech.percent(0f, 0f, 100f), delta)
    }

    @Test
    fun at_max_is_hundred() {
        assertEquals(100f, ProgressSpeech.percent(100f, 0f, 100f), delta)
    }

    @Test
    fun non_zero_min_is_handled() {
        // 区间 10~20，当前 15 → 50%
        assertEquals(50f, ProgressSpeech.percent(15f, 10f, 20f), delta)
    }

    /**
     * ★ 边界①：区间非法（max <= min）→ 0
     * TalkBack: `if (diffProgress <= 0.0f) return 0.0f;`
     * 天坦同。
     */
    @Test
    fun invalid_range_returns_zero() {
        assertEquals(0f, ProgressSpeech.percent(5f, 10f, 10f), delta)
        assertEquals(0f, ProgressSpeech.percent(5f, 10f, 5f), delta)
        assertEquals(0f, ProgressSpeech.percent(5f, 10f, 0f), delta)
    }

    /**
     * ★ 边界②：低于下界 → 夹紧到 0（不是负数）
     * TalkBack: `if (currentProgress < minProgress) return 0.0f;`
     */
    @Test
    fun below_min_clamps_to_zero() {
        assertEquals(0f, ProgressSpeech.percent(-50f, 0f, 100f), delta)
        assertEquals(0f, ProgressSpeech.percent(0f, 10f, 20f), delta)
    }

    /**
     * ★ 边界③：高于上界 → 夹紧到 100（不是超过 100）
     * TalkBack: `if (currentProgress > maxProgress) return 100.0f;`
     */
    @Test
    fun above_max_clamps_to_hundred() {
        assertEquals(100f, ProgressSpeech.percent(200f, 0f, 100f), delta)
        assertEquals(100f, ProgressSpeech.percent(25f, 10f, 20f), delta)
    }

    /**
     * 结果永远在 0~100 内 —— 这是最关键的总体性质，
     * 越界会让播报出现「百分之负二十」这种荒谬文本。
     */
    @Test
    fun result_always_within_0_100() {
        val cases = listOf(
            floatArrayOf(-100f, 0f, 100f),
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1e9f, 0f, 1f),
            floatArrayOf(-1e9f, 0f, 1f),
            floatArrayOf(50f, 100f, 0f)
        )
        cases.forEach { (c, mn, mx) ->
            val p = ProgressSpeech.percent(c, mn, mx)
            assertTrue("percent($c,$mn,$mx) = $p 越界", p in 0f..100f)
        }
    }

    // ==================== 三路分派（天坦特有）====================

    /**
     * ★ PERCENT 型直接采信 current，不走归一化。
     * 天坦：`if (iVarH2.d() == 2) fP = wVar.H().a();`
     */
    @Test
    fun percent_type_trusts_current_directly() {
        assertEquals(
            45f,
            ProgressSpeech.percentOf(ProgressSpeech.TYPE_PERCENT, 45f, 0f, 100f)!!,
            delta
        )
    }

    /**
     * ★ PERCENT 型与通用公式的差别在哪
     *
     * 我最初以为差别是「通用公式会算出 4500%」——**这是错的**。
     * 实测：`percent(45, 0, 1)` 先命中 `current > max` 的夹紧分支，
     * 返回 100 而非 4500（见 above_max_clamps_to_hundred）。
     *
     * 真正的差别在**区间合法但标度不同**的情形：
     *   控件报 type=PERCENT, min=0, max=100, current=45
     *     通用公式：(45-0)/100 = 0.45 → 45%   ✓ 一致
     *   控件报 type=PERCENT, min=50, max=50, current=45（区间退化）
     *     通用公式：diff<=0 → 0%              ✗ 丢弃了真实值
     *     PERCENT 分支：采信 current → 45%     ✓ 正确
     *
     * 即 PERCENT 分支的价值是**在区间信息不可靠时仍能给出正确值**，
     * 因为 PERCENT 语义下 current 本身就是答案，不依赖 min/max。
     */
    @Test
    fun percent_type_survives_degenerate_range() {
        // 区间退化：通用公式丢掉真实值
        assertEquals(0f, ProgressSpeech.percent(45f, 50f, 50f), delta)
        // PERCENT 类型直接采信 current，不受退化区间影响
        assertEquals(
            "PERCENT 语义下 current 就是答案，不该被坏的 min/max 拖累",
            45f,
            ProgressSpeech.percentOf(ProgressSpeech.TYPE_PERCENT, 45f, 50f, 50f)!!,
            delta
        )
    }

    /**
     * 区间与标度都正常时，两种路径应给出一致结果 ——
     * 否则说明 PERCENT 分支引入了偏差。
     */
    @Test
    fun percent_and_generic_agree_when_range_is_sane() {
        val generic = ProgressSpeech.percent(45f, 0f, 100f)
        val percentPath = ProgressSpeech.percentOf(
            ProgressSpeech.TYPE_PERCENT, 45f, 0f, 100f
        )!!
        assertEquals("正常区间下两条路应一致", generic, percentPath, delta)
    }

    @Test
    fun percent_type_still_clamps() {
        assertEquals(
            100f,
            ProgressSpeech.percentOf(ProgressSpeech.TYPE_PERCENT, 500f, 0f, 100f)!!,
            delta
        )
        assertEquals(
            0f,
            ProgressSpeech.percentOf(ProgressSpeech.TYPE_PERCENT, -10f, 0f, 100f)!!,
            delta
        )
    }

    @Test
    fun int_type_uses_normalization() {
        assertEquals(
            50f,
            ProgressSpeech.percentOf(ProgressSpeech.TYPE_INT, 7.5f, 0f, 15f)!!,
            delta
        )
    }

    @Test
    fun float_type_uses_normalization() {
        assertEquals(
            50f,
            ProgressSpeech.percentOf(ProgressSpeech.TYPE_FLOAT, 0.5f, 0f, 1f)!!,
            delta
        )
    }

    /** 未知类型 → null（表示不播报，而不是播「百分之零」） */
    @Test
    fun unknown_type_returns_null() {
        assertNull(ProgressSpeech.percentOf(99, 50f, 0f, 100f))
        assertNull(ProgressSpeech.percentOf(-1, 50f, 0f, 100f))
    }

    // ==================== 音高反馈（天坦特有）====================

    /**
     * ★ 天坦 C4421m:1134：earcon_rate = 2^((percent/50) - 1)
     *   0% → 0.5×，50% → 1.0×，100% → 2.0×
     */
    @Test
    fun pitch_rate_key_points() {
        assertEquals(0.5f, ProgressSpeech.pitchRate(0f), delta)
        assertEquals(1.0f, ProgressSpeech.pitchRate(50f), delta)
        assertEquals(2.0f, ProgressSpeech.pitchRate(100f), delta)
    }

    /** 音高随进度单调递增 —— 否则用户听不出方向 */
    @Test
    fun pitch_rate_is_monotonic() {
        var prev = ProgressSpeech.pitchRate(0f)
        (5..100 step 5).forEach { p ->
            val cur = ProgressSpeech.pitchRate(p.toFloat())
            assertTrue("进度 $p 处音高未递增（$prev → $cur）", cur > prev)
            prev = cur
        }
    }

    @Test
    fun pitch_rate_clamps_out_of_range() {
        assertEquals(ProgressSpeech.pitchRate(0f), ProgressSpeech.pitchRate(-50f), delta)
        assertEquals(ProgressSpeech.pitchRate(100f), ProgressSpeech.pitchRate(150f), delta)
    }

    // ==================== 格式化 ====================

    @Test
    fun format_rounds_to_integer() {
        assertEquals("百分之45", ProgressSpeech.format(45f))
        assertEquals("百分之46", ProgressSpeech.format(45.6f))
        assertEquals("百分之45", ProgressSpeech.format(45.4f))
    }

    @Test
    fun format_handles_bounds() {
        assertEquals("百分之0", ProgressSpeech.format(0f))
        assertEquals("百分之100", ProgressSpeech.format(100f))
        // 越界输入不产生荒谬文本
        assertEquals("百分之0", ProgressSpeech.format(-10f))
        assertEquals("百分之100", ProgressSpeech.format(999f))
    }

    // ==================== 常量与可用性 ====================

    @Test
    fun range_type_constants_match_android() {
        assertEquals(0, ProgressSpeech.TYPE_INT)
        assertEquals(1, ProgressSpeech.TYPE_FLOAT)
        assertEquals(2, ProgressSpeech.TYPE_PERCENT)
    }

    @Test
    fun has_progress_requires_range_info() {
        assertFalse("无 RangeInfo 不该播进度", ProgressSpeech.hasProgress(false, 0))
        assertTrue(ProgressSpeech.hasProgress(true, ProgressSpeech.TYPE_INT))
        assertTrue(ProgressSpeech.hasProgress(true, ProgressSpeech.TYPE_PERCENT))
        assertFalse("未知类型不播", ProgressSpeech.hasProgress(true, 99))
    }
}
