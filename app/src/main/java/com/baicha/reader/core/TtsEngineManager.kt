package com.baicha.reader.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * 语音库管理器 —— 借鉴天坦 Z6/J.java（TTS 引擎封装）+ TalkBack FailoverTextToSpeech
 *
 * 职责：
 * 1. 枚举系统所有 TTS 引擎（语音库）
 * 2. 保存/读取用户选择的引擎
 * 3. 提供跳转系统 TTS 设置的能力
 *
 * 天坦的配置 key：
 *   pref_primary_tts        主语音库（引擎包名）
 *   pref_primary_tts_speed  语速
 *   pref_primary_tts_volume_multiple 音量倍数
 *   pref_primary_tts_audio_usage     音频流
 */
object TtsEngineManager {

    private const val TAG = "TtsEngineManager"
    private const val PREF_NAME = "baicha_tts"

    // ==================== 配置 key（对齐天坦）====================
    const val KEY_PRIMARY_TTS = "pref_primary_tts"
    const val KEY_SPEECH_RATE = "pref_primary_tts_speed"
    const val KEY_PITCH = "pref_primary_tts_pitch"
    const val KEY_LANGUAGE = "pref_primary_tts_language"
    const val KEY_VOICE_NAME = "pref_primary_tts_voice"
    const val KEY_VOLUME = "pref_primary_tts_volume"

    // ==================== 默认值 ====================
    const val DEFAULT_RATE = 1.0f
    const val DEFAULT_PITCH = 1.0f
    const val DEFAULT_VOLUME = 1.0f
    const val RATE_MIN = 0.5f
    const val RATE_MAX = 3.0f
    const val PITCH_MIN = 0.5f
    const val PITCH_MAX = 2.0f

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ==================== 引擎列表 ====================

    /**
     * 获取系统所有可用语音库
     * @return 引擎列表（含包名、名称、是否默认）
     */
    fun getEngines(context: Context): List<TtsEngine> {
        val result = ArrayList<TtsEngine>()
        try {
            val tts = TextToSpeech(context) { }
            val engines = tts.engines ?: emptyList()
            val defaultEngine = tts.defaultEngine

            for (engine in engines) {
                result.add(
                    TtsEngine(
                        packageName = engine.name,
                        label = engine.label?.toString() ?: engine.name,
                        isDefault = engine.name == defaultEngine
                    )
                )
            }
            tts.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "getEngines failed", e)
        }
        return result
    }

    /**
     * 获取指定引擎支持的语音（Voice）列表
     */
    fun getVoices(context: Context, enginePackage: String?): List<TtsVoice> {
        val result = ArrayList<TtsVoice>()
        try {
            val tts = if (enginePackage.isNullOrEmpty()) {
                TextToSpeech(context) { }
            } else {
                TextToSpeech(context, { }, enginePackage)
            }
            // 等初始化（简化：直接读，可能为空）
            val voices = tts.voices ?: emptySet()
            for (v in voices) {
                result.add(
                    TtsVoice(
                        name = v.name,
                        locale = v.locale?.toLanguageTag() ?: "unknown",
                        displayName = v.locale?.displayName ?: v.name,
                        quality = v.quality,
                        latency = v.latency,
                        isNetwork = v.isNetworkConnectionRequired
                    )
                )
            }
            tts.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "getVoices failed", e)
        }
        return result
    }

    // ==================== 保存/读取 ====================

    /** 保存选中的语音库包名 */
    fun saveEngine(context: Context, enginePackage: String) {
        prefs(context).edit().putString(KEY_PRIMARY_TTS, enginePackage).apply()
        Log.i(TAG, "saved engine: $enginePackage")
    }

    /** 读取选中的语音库包名（null = 系统默认） */
    fun getSavedEngine(context: Context): String? =
        prefs(context).getString(KEY_PRIMARY_TTS, null)

    /**
     * 清除保存的引擎选择 → 回到系统默认
     *
     * 【为什么要这个】引擎**可能"存在但合成不了"**（真机 P0：
     * `es.codefactory.vocalizertts` 的包在、服务在、onInit 返回 SUCCESS，
     * 但每次 speak 都返回 `ERROR_SYNTHESIS(-5)`）。
     * `SpeechEngine` 检测到连续失败后会调本方法，
     * 让**下一次启动**不再选中那个坏引擎 —— 自愈。
     */
    fun clearEngine(context: Context) {
        prefs(context).edit().remove(KEY_PRIMARY_TTS).apply()
        Log.i(TAG, "cleared engine selection → system default")
    }

    /** 保存语速 */
    fun saveRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat(KEY_SPEECH_RATE, rate.coerceIn(RATE_MIN, RATE_MAX)).apply()
    }

    /** 读取语速 */
    fun getRate(context: Context): Float =
        prefs(context).getFloat(KEY_SPEECH_RATE, DEFAULT_RATE)

    /** 保存音调 */
    fun savePitch(context: Context, pitch: Float) {
        prefs(context).edit().putFloat(KEY_PITCH, pitch.coerceIn(PITCH_MIN, PITCH_MAX)).apply()
    }

    /** 读取音调 */
    fun getPitch(context: Context): Float =
        prefs(context).getFloat(KEY_PITCH, DEFAULT_PITCH)

    /** 保存音量 */
    fun saveVolume(context: Context, volume: Float) {
        prefs(context).edit().putFloat(KEY_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    /** 读取音量 */
    fun getVolume(context: Context): Float =
        prefs(context).getFloat(KEY_VOLUME, DEFAULT_VOLUME)

    /** 保存语言 */
    fun saveLanguage(context: Context, langTag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, langTag).apply()
    }

    /** 读取语言（null = 自动） */
    fun getLanguage(context: Context): String? =
        prefs(context).getString(KEY_LANGUAGE, null)

    /** 保存语音名称 */
    fun saveVoiceName(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_VOICE_NAME, name).apply()
    }

    /** 读取语音名称 */
    fun getVoiceName(context: Context): String? =
        prefs(context).getString(KEY_VOICE_NAME, null)

    // ==================== 跳转系统设置 ====================

    /** 跳转系统 TTS 设置（天坦 pref_primary_tts_settings 行为） */
    fun openSystemTtsSettings(context: Context): Boolean {
        return try {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "openSystemTtsSettings failed", e2)
                false
            }
        }
    }

    /** 跳转指定引擎的设置页 */
    fun openEngineSettings(context: Context, enginePackage: String): Boolean {
        return try {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            intent.putExtra("engine", enginePackage)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            openSystemTtsSettings(context)
        }
    }

    // ==================== 数据类 ====================

    data class TtsEngine(
        val packageName: String,
        val label: String,
        val isDefault: Boolean
    ) {
        override fun toString(): String = if (isDefault) "$label（默认）" else label
    }

    data class TtsVoice(
        val name: String,
        val locale: String,
        val displayName: String,
        val quality: Int,
        val latency: Int,
        val isNetwork: Boolean
    ) {
        override fun toString(): String =
            "$displayName${if (isNetwork) "（需联网）" else ""}"
    }
}