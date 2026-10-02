package com.baicha.reader.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 音频焦点管理 + 语音输入检测
 *
 * 解决"读屏播报打断语音输入"的问题：
 *
 * ① 检测语音输入是否在进行
 *    - 输入法是语音输入法（讯飞/搜狗/百度）
 *    - 麦克风被占用（AudioManager.isMicrophoneMute / 录音状态）
 *
 * ② 语音输入中 → 读屏静音（不抢音频）
 *
 * ③ 音频焦点：用 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
 *    - 读屏播报时压低其他音频（而不是抢断）
 *    - 播报完归还焦点
 */
class AudioFocusManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioFocusManager"

        /** 已知的语音输入法包名 */
        private val VOICE_INPUT_PACKAGES = setOf(
            "com.iflytek.inputmethod",
            "com.iflytek.inputmethod.miui",
            "com.sohu.inputmethod.sogou",
            "com.baidu.input",
            "com.baidu.input_mi",
            "com.google.android.inputmethod.latin",
            "com.tencent.qqpinyin",
            "com.miui.securityinputmethod"
        )

        /** 已知的语音助手/录音包名 */
        private val VOICE_ASSISTANT_PACKAGES = setOf(
            "com.miui.voiceassist",
            "com.xiaomi.mibrain.speech",
            "com.google.android.googlequicksearchbox",
            "com.iflytek.vflynote",
            "com.tencent.mm"   // 微信语音消息
        )
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    /** 当前前台包名（由服务更新） */
    @Volatile
    var foregroundPackage: String = ""

    /** 是否检测到语音输入 */
    @Volatile
    var isVoiceInputActive = false
        private set

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    hasFocus = change == AudioManager.AUDIOFOCUS_GAIN
                    Log.d(TAG, "audio focus changed: $change")
                }
                .setWillPauseWhenDucked(false)
                .build()
        }
    }

    /**
     * 检查当前是否应该静音（语音输入中）
     *
     * 判断依据：
     * 1. 前台是语音输入法
     * 2. 麦克风正在被录音
     */
    fun shouldMute(): Boolean {
        // ① 前台是语音输入法 / 语音助手
        if (foregroundPackage in VOICE_INPUT_PACKAGES) {
            isVoiceInputActive = true
            Log.d(TAG, "mute: voice input method in foreground ($foregroundPackage)")
            return true
        }
        if (foregroundPackage in VOICE_ASSISTANT_PACKAGES) {
            isVoiceInputActive = true
            Log.d(TAG, "mute: voice assistant in foreground ($foregroundPackage)")
            return true
        }

        // ② 麦克风被占用（有人在录音）
        if (isMicrophoneInUse()) {
            isVoiceInputActive = true
            Log.d(TAG, "mute: microphone in use")
            return true
        }

        isVoiceInputActive = false
        return false
    }

    /** 麦克风是否正在被使用 */
    private fun isMicrophoneInUse(): Boolean {
        return try {
            // Android 10+ 可以查活跃录音
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeRecordings = audioManager.activeRecordingConfigurations
                if (activeRecordings.isNotEmpty()) {
                    // 排除自己
                    val otherRecording = activeRecordings.any { config ->
                        config.clientAudioSessionId != -1
                    }
                    if (otherRecording) return true
                }
            }
            // 兜底：麦克风静音状态
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 请求音频焦点（播报前调用）
     * @return true = 可以播报
     */
    fun requestFocus(): Boolean {
        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ACCESSIBILITY,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "requestFocus: $result")
            hasFocus
        } catch (e: Exception) {
            Log.e(TAG, "requestFocus failed", e)
            true   // 失败也允许播报
        }
    }

    /** 释放音频焦点（播报完调用） */
    fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            hasFocus = false
        } catch (e: Exception) {
            Log.e(TAG, "abandonFocus failed", e)
        }
    }

    fun hasAudioFocus(): Boolean = hasFocus
}