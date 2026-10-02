package com.baicha.reader.core

/**
 * 进度播报 —— 两方对标结果
 *
 * ══════════════════════════════════════════════════════════
 * 【两方做法（已逐行比对）】
 * ══════════════════════════════════════════════════════════
 *
 * **TalkBack** `AccessibilityNodeInfoUtils.getProgressPercent()`：
 *   只做 RangeInfo → 百分比换算，含三条防御：
 *     diffProgress <= 0   → 0（区间非法）
 *     current <  min      → 0（越界，夹紧）
 *     current >  max      → 100（越界，夹紧）
 *
 * **天坦** `AbstractC3693m.p()` —— **与 TalkBack 数学部分完全一致**
 *   （天坦移植了 TalkBack 这段，包括日志文案）。
 *
 *   但天坦在合成器 `z6/C4421m.java:565~585` 多了**三路分派**，
 *   按 `RangeInfo.getType()` 决定播什么：
 *     type == 2 (PERCENT)   → 直接播 current（已是百分数）
 *     type == 0 (INT) / 1 (FLOAT) → 按 (cur-min)/(max-min)*100 算
 *     RangeInfo 为 null      → 退回事件里的 itemCount 位置播报
 *   最后统一走 `getString(O.jw, h0(fP))` 格式化。
 *
 *   另外天坦给进度条配了**音高反馈**（C4421m:1134）：
 *     earcon_rate = 2^((percent/50) - 1)
 *   即 0%→0.5×音高，50%→1.0×，100%→2.0×，
 *   用户不用听完数字，凭音高就能感知进度。
 *
 * ══════════════════════════════════════════════════════════
 * 【抉择：选天坦】
 * ══════════════════════════════════════════════════════════
 *
 * 数学部分两者相同，取谁都一样；
 * 但天坦的**三路分派 + 音高反馈**是 TalkBack 没有的，
 * 因此整体选天坦。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么这个能力重要】
 * ══════════════════════════════════════════════════════════
 *
 * 音量、亮度、播放进度、下载进度、滑块 —— 全是进度控件。
 * 读屏只念「进度条」而不念数值，用户等于盲操作：
 * 调音量得靠猜，听歌拖进度条不知道拖到哪了。
 *
 * ══════════════════════════════════════════════════════════
 * 【本实现相对两方的改进：纯函数 + 可测】
 * ══════════════════════════════════════════════════════════
 *
 * TalkBack 与天坦都把这套逻辑直接绑在 `AccessibilityNodeInfo` 上，
 * 导致极难单测（要构造真实节点）。
 *
 * 白茶把**纯计算**抽出来 —— [percent] / [pitchRate] 只吃三个 Float，
 * 于是全部边界（区间非法、越界夹紧、百分数类型）都能被逐条测试。
 */
object ProgressSpeech {

    // ==================== RangeInfo 类型常量 ====================
    // 与 AccessibilityNodeInfo.RangeInfo 一致

    /** 整数型（如音量 0~15） */
    const val TYPE_INT = 0

    /** 浮点型（如亮度 0.0~1.0） */
    const val TYPE_FLOAT = 1

    /** 百分比型（current 本身就是 0~100） */
    const val TYPE_PERCENT = 2

    /**
     * 计算进度百分比（0~100）。
     *
     * 数学部分与 TalkBack `getProgressPercent()` 和
     * 天坦 `AbstractC3693m.p()` **完全一致**（两者本就相同）。
     *
     * @param current 当前值
     * @param min     区间下界
     * @param max     区间上界
     * @return 0~100 的百分比；区间非法时返回 0
     */
    fun percent(current: Float, min: Float, max: Float): Float {
        val diff = max - min
        // ① 区间非法：max <= min
        if (diff <= 0f) return 0f
        // ② 低于下界 → 夹紧到 0
        if (current < min) return 0f
        // ③ 高于上界 → 夹紧到 100
        if (current > max) return 100f
        // ④ 正常：归一化后夹紧到 [0,1] 再乘 100
        val p = (current - min) / diff
        return 100f * p.coerceIn(0f, 1f)
    }

    /**
     * 按 RangeInfo 类型分派 —— 对应天坦三路分派
     *
     * 【为什么 PERCENT 要单独处理】
     *   音量条常报 `type=PERCENT, min=0, max=100, current=45`，
     *   走通用公式也能得 45，但若某控件报
     *   `type=PERCENT, min=0, max=1, current=0.45`，
     *   通用公式会算出 45% 正确，可若 max 也是百分数标度
     *   （如 min=0,max=100）就会被算错。
     *   天坦对 PERCENT 直接采信 current，避免标度歧义。
     *
     * @return 0~100；数据缺失返回 null（表示「不播报进度」）
     */
    fun percentOf(
        rangeType: Int,
        current: Float,
        min: Float,
        max: Float
    ): Float? = when (rangeType) {
        // 百分数型：current 已是 0~100，直接采信（夹紧防御）
        TYPE_PERCENT -> current.coerceIn(0f, 100f)
        // 整数/浮点型：按区间归一化
        TYPE_INT, TYPE_FLOAT -> percent(current, min, max)
        else -> null
    }

    /**
     * 进度 → 音高倍率 —— 对应天坦 `C4421m:1134`
     *
     * ```java
     * earcon_rate = 2^((percent / 50) - 1)
     * ```
     *
     * 映射关系：
     *   0%   → 2^(-1)  = 0.5×
     *   50%  → 2^(0)   = 1.0×
     *   100% → 2^(1)   = 2.0×
     *
     * 【为什么用指数而不是线性】
     *   人耳对音高的感知是对数的 —— 线性映射听起来
     *   「前半段几乎没变化，后半段突变」。
     *   指数映射让每个百分比区间听起来变化均匀。
     *
     * 【为什么这个反馈重要】
     *   视障用户调音量时，听数字要等播报完；
     *   音高是即时的，手指一滑就能感知方向与位置。
     */
    fun pitchRate(percent: Float): Float {
        val p = percent.coerceIn(0f, 100f)
        // 2^((p/50) - 1)
        return Math.pow(2.0, (p / 50.0) - 1.0).toFloat()
    }

    /**
     * 格式化播报文本。
     *
     * 【取整而非保留小数】
     *   天坦用 `h0(double)` 做四舍五入到整数。
     *   「音量的百分之四十五」比「百分之四十五点三」更易听，
     *   且进度本身抖动时不会刷屏式播报。
     */
    fun format(percent: Float): String {
        val rounded = Math.round(percent.coerceIn(0f, 100f))
        return "百分之$rounded"
    }

    /**
     * 节点是否有可播报的进度信息。
     *
     * 用于决定「该不该在朗读里加进度段」——
     * 无 RangeInfo 的节点不该念「百分之零」。
     */
    fun hasProgress(hasRangeInfo: Boolean, rangeType: Int): Boolean =
        hasRangeInfo && rangeType in TYPE_INT..TYPE_PERCENT
}
