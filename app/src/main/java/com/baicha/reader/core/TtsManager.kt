package com.baicha.reader.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * TTS 管理器 —— 借鉴天坦 Z6/J.java（TTS 引擎封装）+ Z6/r.java（DoubleSpeedTextToSpeech）
 *
 * 能力：
 * 1. 指定语音库（引擎包名）—— 解决"和天坦抢同一个引擎"的问题
 * 2. 语速 / 音调 / 音量 可调
 * 3. 语言 / 语音（Voice）可指定
 * 4. 打断策略（QUEUE_FLUSH / QUEUE_ADD）
 * 5. 引擎切换（运行时热切换，不用重启服务）
 *
 * 天坦的关键设计：
 * - 用 `new TextToSpeech(context, listener, enginePackage)` 指定引擎
 * - 引擎切换时先 shutdown 旧的，再创建新的（E0 方法）
 * - 用 AtomicLong 生成 utteranceId 防乱序
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private val counter = AtomicLong(0)
    private var onReadyCallback: (() -> Unit)? = null

    /** 当前引擎包名（null = 系统默认） */
    @Volatile
    var enginePackage: String? = null
        private set

    /** 当前正在播报的 utteranceId */
    @Volatile
    var currentUtteranceId: String? = null
        private set

    /** 当前语速/音调/音量 */
    private var speechRate = TtsEngineManager.getRate(context)
    private var pitch = TtsEngineManager.getPitch(context)
    private var volume = TtsEngineManager.getVolume(context)

    init {
        initEngine(TtsEngineManager.getSavedEngine(context))
    }

    /**
     * 初始化/切换引擎
     * 对应天坦 Z6/J.E0()
     */
    fun initEngine(enginePkg: String?) {
        // 先关旧的
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        tts = null
        ready = false
        enginePackage = enginePkg

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                applySettings()
                Log.i(TAG, "TTS ready, engine=${tts?.defaultEngine}, pkg=${enginePkg ?: "默认"}")
                onReadyCallback?.invoke()
                onReadyCallback = null
            } else {
                Log.e(TAG, "TTS init failed: $status, engine=$enginePkg")
            }
        }

        try {
            tts = if (enginePkg.isNullOrEmpty()) {
                TextToSpeech(context.applicationContext, listener)
            } else {
                // 指定引擎创建（天坦 Z6/J.E0 的核心）
                TextToSpeech(context.applicationContext, listener, enginePkg)
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    currentUtteranceId = utteranceId
                }

                override fun onDone(utteranceId: String?) {
                    if (currentUtteranceId == utteranceId) currentUtteranceId = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS error: $utteranceId")
                    if (currentUtteranceId == utteranceId) currentUtteranceId = null
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "create TTS failed for engine=$enginePkg", e)
            tts = null
            ready = false
        }
    }

    /** 应用语速/音调/音量/语言设置 */
    private fun applySettings() {
        val t = tts ?: return
        try {
            t.setSpeechRate(speechRate)
            t.setPitch(pitch)
            // 语言：用户指定优先，否则中文
            val langTag = TtsEngineManager.getLanguage(context)
            val langResult = if (!langTag.isNullOrEmpty()) {
                t.setLanguage(Locale.forLanguageTag(langTag))
            } else {
                t.setLanguage(Locale.CHINA)
            }
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "language not supported: $langTag, fallback to default")
                t.setLanguage(Locale.getDefault())
            }

            // 语音（Voice）：用户指定优先
            val voiceName = TtsEngineManager.getVoiceName(context)
            if (!voiceName.isNullOrEmpty()) {
                try {
                    val voices = t.voices
                    val target: Voice? = voices?.firstOrNull { it.name == voiceName }
                    if (target != null) {
                        t.voice = target
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setVoice failed: $voiceName", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applySettings failed", e)
        }
    }

    fun setOnReady(cb: () -> Unit) {
        if (ready) cb() else onReadyCallback = cb
    }

    // ==================== 参数设置 ====================

    /** 设置语速（0.5 - 3.0） */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(TtsEngineManager.RATE_MIN, TtsEngineManager.RATE_MAX)
        TtsEngineManager.saveRate(context, speechRate)
        tts?.setSpeechRate(speechRate)
        Log.i(TAG, "speechRate = $speechRate")
    }

    fun getSpeechRate(): Float = speechRate

    /** 设置音调（0.5 - 2.0） */
    fun setPitch(p: Float) {
        pitch = p.coerceIn(TtsEngineManager.PITCH_MIN, TtsEngineManager.PITCH_MAX)
        TtsEngineManager.savePitch(context, pitch)
        tts?.setPitch(pitch)
        Log.i(TAG, "pitch = $pitch")
    }

    fun getPitch(): Float = pitch

    /** 设置音量（0 - 1） */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        TtsEngineManager.saveVolume(context, volume)
        Log.i(TAG, "volume = $volume")
    }

    fun getVolume(): Float = volume

    /** 设置语言 */
    fun setLanguage(langTag: String) {
        TtsEngineManager.saveLanguage(context, langTag)
        val locale = Locale.forLanguageTag(langTag)
        tts?.setLanguage(locale)
        Log.i(TAG, "language = $langTag")
    }

    /** 设置语音（Voice 名称） */
    fun setVoice(voiceName: String?) {
        TtsEngineManager.saveVoiceName(context, voiceName)
        if (voiceName.isNullOrEmpty()) return
        try {
            val voices = tts?.voices
            val target = voices?.firstOrNull { it.name == voiceName }
            if (target != null) {
                tts?.voice = target
                Log.i(TAG, "voice = $voiceName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "setVoice failed", e)
        }
    }

    // ==================== 播报 ====================

    /**
     * 朗读文本
     *
     * @param text 内容
     * @param interrupt true = 打断当前（QUEUE_FLUSH），false = 排队（QUEUE_ADD）
     */
    fun speak(text: String, interrupt: Boolean = true) {
        if (!enabled) return
        if (!ready) {
            Log.w(TAG, "TTS not ready, skip: $text")
            return
        }
        if (text.isBlank()) return

        val id = "baicha_${counter.incrementAndGet()}"
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        // 用 Bundle 传音量（天坦 Z6/r.P() 的做法）
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }

        tts?.speak(text, mode, params, id)
        Log.d(TAG, "speak[$id]: $text")
    }

    /** 停止播报 */
    fun stop() {
        tts?.stop()
        currentUtteranceId = null
    }

    /** 是否正在播报 */
    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    /** 读屏开关状态 */
    @Volatile
    private var enabled = true

    fun isEnabled(): Boolean = enabled

    fun setEnabled(e: Boolean) {
        enabled = e
        if (!e) stop()
    }

    /** 当前引擎是否就绪 */
    fun isReady(): Boolean = ready

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        tts = null
        ready = false
    }
}