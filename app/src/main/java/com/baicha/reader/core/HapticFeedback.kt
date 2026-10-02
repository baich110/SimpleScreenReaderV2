package com.baicha.reader.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 震动反馈 —— 借鉴天坦 VibratorController
 *
 * 读屏的震动反馈分几类：
 * - 焦点移动：短促轻震
 * - 可点击焦点：稍强
 * - 边界（到头了）：双震
 * - 长按：持续
 */
class HapticFeedback(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** 焦点移动：短促轻震（10ms） */
    fun focus() = vibrate(10, 60)

    /** 可点击焦点：稍强（15ms） */
    fun focusClickable() = vibrate(15, 100)

    /** 到达边界：双震 */
    fun boundary() = vibratePattern(longArrayOf(0, 20, 40, 20), -1)

    /** 点击确认 */
    fun click() = vibrate(20, 120)

    /** 长按 */
    fun longPress() = vibrate(40, 150)

    /** 错误/无操作 */
    fun error() = vibratePattern(longArrayOf(0, 30, 50, 30, 50, 30), -1)

    private fun vibrate(durationMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // 忽略震动异常
        }
    }

    private fun vibratePattern(pattern: LongArray, repeat: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, repeat)
            }
        } catch (e: Exception) {
            // 忽略
        }
    }
}