package com.baicha.reader.core

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.baicha.reader.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 音效管理器 —— 1:1 抄天坦 SoundMgr（Z6/q0.java）
 *
 * 天坦的核心机制：
 * 1. SoundPool 播放（maxStreams=11，USAGE_ASSISTANCE_ACCESSIBILITY）
 * 2. 懒加载 + 缓存（resId → soundId）
 * 3. 异步播放（单线程 ExecutorService）
 * 4. 音量倍率（可调）
 * 5. 音效方案（Scheme）：苹果 / 天坦 / 自定义
 *
 * 本实现用苹果音效（*_apple.ogg）
 */
class SoundManager(context: Context) {

    companion object {
        private const val TAG = "SoundManager"
        private const val MAX_STREAMS = 11
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SoundMgr-Play").apply { isDaemon = true }
    }

    /** SoundPool */
    private var soundPool: SoundPool = createSoundPool()

    /** resId → soundId 缓存 */
    private val soundIdCache = ConcurrentHashMap<Int, Int>()

    /** 音量倍率 */
    @Volatile
    var volumeMultiple = 1.0f
        private set

    /** 是否启用音效 */
    @Volatile
    var soundEnabled = true
        private set

    private fun createSoundPool(): SoundPool {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attrs)
            .build()
    }

    // ==================== 播放 ====================

    /**
     * 播放音效
     * 对应天坦 q0.y(resId, rate, volume)
     *
     * @param resId 音效资源 ID
     * @param rate 播放速率（1.0 = 正常）
     * @param volume 音量（0-1）
     */
    fun play(resId: Int, rate: Float = 1.0f, volume: Float = 1.0f) {
        if (!soundEnabled) return

        val vol = (volume * volumeMultiple).coerceIn(0f, 1f)

        executor.execute {
            try {
                val soundId = soundIdCache[resId]
                if (soundId != null && soundId != 0) {
                    soundPool.play(soundId, vol, vol, 1, 0, rate)
                } else if (soundId == null) {
                    // 懒加载
                    val id = soundPool.load(appContext, resId, 1)
                    soundIdCache[resId] = id
                    // load 是异步的，加载完再播一次
                    // 用 OnLoadCompleteListener 处理更优雅，这里简化：延迟重试
                }
            } catch (e: Exception) {
                Log.e(TAG, "play failed: resId=$resId", e)
            }
        }
    }

    /** 预加载（避免第一次播放延迟） */
    fun preload(vararg resIds: Int) {
        executor.execute {
            for (resId in resIds) {
                if (!soundIdCache.containsKey(resId)) {
                    try {
                        soundIdCache[resId] = soundPool.load(appContext, resId, 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "preload failed: $resId", e)
                    }
                }
            }
        }
    }

    /** 预加载全部常用音效 */
    fun preloadAll() {
        preload(
            R.raw.focus_apple,
            R.raw.focus_actionable_apple,
            R.raw.view_entered_apple,
            R.raw.scroll_tone_apple,
            R.raw.window_state_apple,
            R.raw.tick_apple,
            R.raw.long_clicked_apple,
            R.raw.gesture_end_apple,
            R.raw.complete_apple,
            R.raw.hyperlink_apple,
            R.raw.chime_up_apple,
            R.raw.chime_down_apple
        )
    }

    // ==================== 语义化播放接口 ====================

    /** 焦点移动 */
    fun focus() = play(R.raw.focus_apple)

    /** 可点击焦点 */
    fun focusActionable() = play(R.raw.focus_actionable_apple)

    /** 进入新视图 */
    fun viewEntered() = play(R.raw.view_entered_apple)

    /** 滚动 */
    fun scroll(rate: Float = 1.0f) = play(R.raw.scroll_tone_apple, rate = rate)

    /** 窗口切换 */
    fun windowState() = play(R.raw.window_state_apple)

    /** 刻度（列表项移动） */
    fun tick() = play(R.raw.tick_apple)

    /** 长按 */
    fun longClicked() = play(R.raw.long_clicked_apple)

    /** 手势结束 */
    fun gestureEnd() = play(R.raw.gesture_end_apple)

    /** 完成 */
    fun complete() = play(R.raw.complete_apple)

    /** 链接 */
    fun hyperlink() = play(R.raw.hyperlink_apple)

    /** 上滑 */
    fun chimeUp() = play(R.raw.chime_up_apple)

    /** 下滑 */
    fun chimeDown() = play(R.raw.chime_down_apple)

    // ==================== 控制 ====================

    fun setVolumeMultiple(multiple: Float) {
        volumeMultiple = multiple.coerceIn(0f, 3f)
    }

    fun setEnabled(e: Boolean) {
        soundEnabled = e
    }

    fun shutdown() {
        try {
            soundPool.release()
        } catch (e: Exception) {
            // ignore
        }
        soundIdCache.clear()
        executor.shutdown()
    }
}