package com.baicha.reader.core

import android.content.Context
import android.os.PowerManager

/**
 * 朗读期间防休眠 —— 对标 TalkBack `ProcessorVolumeStream`(WakeLock 部分)
 * 与天坦 `Z6/J`(TTS 引擎的 WakeLock)
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * 屏幕休眠后系统会限制后台进程。连续朗读/长文本朗读过程中若屏幕
 * 自动熄灭，TTS 可能被降频或打断 —— 用户听着听着就断了。
 *
 * ══════════════════════════════════════════════════════════
 * 【两方对标（已读源码 + javap 核实常量）】
 * ══════════════════════════════════════════════════════════
 *
 * **TalkBack** `ProcessorVolumeStream.java:126~129`：
 * ```java
 * wakeLock = pm.newWakeLock(
 *     PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, WL_TAG);
 * ```
 * （`FullScreenReadActor.java:147~148` 另有一处用 `SCREEN_DIM_WAKE_LOCK`，
 *   那是连续朗读专用，让屏幕变暗但不熄。）
 *
 * **天坦** `Z6/J.java:191` 与 `M6/D.java:191`：
 * ```java
 * .newWakeLock(536870922, D.class.getSimpleName());
 * ```
 * `536870922` = `0x2000000A` = `SCREEN_BRIGHT_WAKE_LOCK(10) |
 *   ON_AFTER_RELEASE(536870912)` —— 用 `javap` 核实过常量值。
 *
 * **结论：两方在「朗读期间用」这个场景上完全一致**
 * （都是 `SCREEN_BRIGHT_WAKE_LOCK | ON_AFTER_RELEASE`）。
 * 无 A/B 分歧。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么用 ON_AFTER_RELEASE】
 * ══════════════════════════════════════════════════════════
 *
 * 它让屏幕在释放 WakeLock 后**延迟一小段再熄灭**，
 * 避免「读完最后一句瞬间黑屏」的突兀感。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么用引用计数而非布尔】
 * ══════════════════════════════════════════════════════════
 *
 * 朗读可能被嵌套触发（连续朗读 → 中途播报提示）。
 * 用布尔会出现「内层释放把外层的锁也解了」——
 * 结果朗读还没结束屏幕就熄了。
 * 引用计数保证「最后一个使用者释放时才真正解锁」。
 *
 * 【直接照抄 Android WakeLock 的语义】它本身就是引用计数的，
 * 但 `newWakeLock` 每次创建新实例会各自计数，
 * 故这里在**单例内**自行计数，避免重复创建。
 */
class SpeechWakeLock(
    context: Context,
    private val log: (String) -> Unit = {}
) {

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** 引用计数 */
    private var acquireCount = 0

    private val lock: PowerManager.WakeLock? by lazy {
        try {
            powerManager?.newWakeLock(WAKE_LOCK_FLAGS, TAG)
        } catch (e: Throwable) {
            log("唤醒锁：创建失败 ${e.message}")
            null
        }
    }

    /** 当前是否持有 */
    val isHeld: Boolean
        get() = try {
            lock?.isHeld == true
        } catch (e: Throwable) {
            false
        }

    /**
     * 获取 —— 计数 +1。
     *
     * @return true = 已持有（可能是本次获取或之前就有）
     */
    fun acquire(): Boolean {
        acquireCount++
        if (acquireCount > 1) return true   // 已被外层持有，无需重复调系统
        return try {
            lock?.acquire()
            log("唤醒锁：已获取")
            true
        } catch (e: Throwable) {
            log("唤醒锁：获取失败 ${e.message}")
            acquireCount--   // 失败要回滚计数，否则永远释放不掉
            false
        }
    }

    /**
     * 释放 —— 计数 -1，归零时真正释放。
     *
     * @return true = 已真正释放
     */
    fun release(): Boolean {
        if (acquireCount <= 0) return false
        acquireCount--
        if (acquireCount > 0) return false   // 还有使用者

        return try {
            // 【重要】只释放自己持有的
            // 若从未 acquire 就 release，Android 会抛
            // RuntimeException("WakeLock under-locked")
            val l = lock
            if (l != null && l.isHeld) l.release()
            log("唤醒锁：已释放")
            true
        } catch (e: Throwable) {
            log("唤醒锁：释放失败 ${e.message}")
            false
        }
    }

    /** 强制释放（服务销毁时） */
    fun releaseAll() {
        acquireCount = 0
        try {
            val l = lock
            if (l != null && l.isHeld) l.release()
        } catch (e: Throwable) {
            log("唤醒锁：强制释放异常 ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BaichaReader:Speech"

        /**
         * `SCREEN_BRIGHT_WAKE_LOCK | ON_AFTER_RELEASE`
         *
         * 天坦的值是 `536870922`（= 0x2000000A），
         * 与 TalkBack `ProcessorVolumeStream` 的用法相同。
         *
         * 【用常量而非字面量】`javap` 核实过：
         *   SCREEN_BRIGHT_WAKE_LOCK = 10
         *   ON_AFTER_RELEASE        = 536870912
         * 和为 536870922 —— 与天坦的字面量完全一致。
         */
        const val WAKE_LOCK_FLAGS =
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
    }
}
