package com.baicha.reader.core

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * TTS 系统设置监听 —— 对标 TalkBack `FailoverTextToSpeech`(1839 行)
 * 与天坦 `Z6/J`(TTS 引擎)
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * 用户可能在**系统设置**里改 TTS 引擎/语速/音调
 * （设置 → 无障碍 → 文字转语音）。
 *
 * 白茶现状：设置只在**服务启动时读一次**。
 * 后果：用户在系统里改了语速，白茶**不会生效**，
 *       必须重启无障碍服务 —— 对用户来说等于「改了没用」。
 *
 * ══════════════════════════════════════════════════════════
 * 【两方对标（已读源码，行号在下方）】
 * ══════════════════════════════════════════════════════════
 *
 * **TalkBack** `FailoverTextToSpeech.java:293~300`：
 * ```java
 * final Uri defaultSynth = Secure.getUriFor(Secure.TTS_DEFAULT_SYNTH);
 * final Uri defaultPitch = Secure.getUriFor(Secure.TTS_DEFAULT_PITCH);
 * final Uri defaultRate  = Secure.getUriFor(Secure.TTS_DEFAULT_RATE);
 * resolver.registerContentObserver(defaultSynth, false, mSynthObserver);
 * resolver.registerContentObserver(defaultPitch, false, mPitchObserver);
 * resolver.registerContentObserver(defaultRate,  false, mRateObserver);
 * ```
 * 另有第 4 个 `mLocaleObserver`（`:1452`）观察 `TTS_DEFAULT_LOCALE`，
 * 那是为修 Google TTS 的 locale bug，白茶不需要。
 *
 * **天坦** `Z6/J.java:508~515`：
 * ```java
 * Uri uriFor  = Settings.Secure.getUriFor("tts_default_synth");
 * Uri uriFor2 = Settings.Secure.getUriFor("tts_default_pitch");
 * Uri uriFor3 = Settings.Secure.getUriFor("tts_default_rate");
 * contentResolver.registerContentObserver(uriFor,  false, bVar);
 * contentResolver.registerContentObserver(uriFor2, false, cVar);
 * contentResolver.registerContentObserver(uriFor3, false, dVar);
 * ```
 *
 * **两方观察的是完全相同的 3 个 URI** —— 无 A/B 分歧。
 *
 * ══════════════════════════════════════════════════════════
 * 【变更后的行为（两方一致：重读并应用，不重启引擎）】
 * ══════════════════════════════════════════════════════════
 *
 * 天坦 `Z6/J.java:1080~1094`：
 * ```java
 * public final void T0() {                       // pitch 变了
 *     this.f11725A = Settings.Secure.getInt(
 *         this.f11755e, "tts_default_pitch", 100) / 100.0f;
 * }
 * public final void U0() {                       // rate 变了
 *     this.f11776z = Settings.Secure.getInt(
 *         this.f11755e, "tts_default_rate", 100) / 100.0f;
 * }
 * ```
 * 注意：**只重读字段值，不动引擎** ——
 * 因为 TTS 参数本就支持运行时设置，重启引擎会让正在朗读的内容中断。
 *
 * ══════════════════════════════════════════════════════════
 * 【已验证 / 未验证】
 * ══════════════════════════════════════════════════════════
 *   ✅ 已验证：URI 常量正确、变更分派正确、注册/注销幂等
 *   ❌ 测不到：系统设置变更是否真的送达（需真机）
 */
class TtsSettingsObserver(
    private val context: Context,
    /** 引擎包名变化 */
    private val onEngineChanged: (String?) -> Unit,
    /** 音调变化（倍率，1.0 = 正常） */
    private val onPitchChanged: (Float) -> Unit,
    /** 语速变化（倍率，1.0 = 正常） */
    private val onRateChanged: (Float) -> Unit,
    private val log: (String) -> Unit = {}
) {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val engineObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val pkg = readEngine()
            log("TTS 设置变更：引擎 → ${pkg ?: "(默认)"}")
            onEngineChanged(pkg)
        }
    }

    private val pitchObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val pitch = readPitch()
            log("TTS 设置变更：音调 → $pitch")
            onPitchChanged(pitch)
        }
    }

    private val rateObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val rate = readRate()
            log("TTS 设置变更：语速 → $rate")
            onRateChanged(rate)
        }
    }

    /**
     * 注册监听。
     *
     * 【为什么幂等】服务可能被系统反复重连（onServiceConnected 多次），
     * 重复注册会导致同一个变更回调触发多次。
     */
    fun register(): Boolean {
        if (registered) return true
        return try {
            val resolver = context.contentResolver
            resolver.registerContentObserver(
                Uri.parse(URI_DEFAULT_SYNTH), false, engineObserver
            )
            resolver.registerContentObserver(
                Uri.parse(URI_DEFAULT_PITCH), false, pitchObserver
            )
            resolver.registerContentObserver(
                Uri.parse(URI_DEFAULT_RATE), false, rateObserver
            )
            registered = true
            log("TTS 设置监听：已注册")
            true
        } catch (e: Throwable) {
            log("TTS 设置监听：注册失败 ${e.message}")
            false
        }
    }

    fun unregister() {
        if (!registered) return
        try {
            val resolver = context.contentResolver
            resolver.unregisterContentObserver(engineObserver)
            resolver.unregisterContentObserver(pitchObserver)
            resolver.unregisterContentObserver(rateObserver)
        } catch (e: Throwable) {
            log("TTS 设置监听：注销异常 ${e.message}")
        }
        registered = false
    }

    /** 读取当前引擎包名；未设置返回 null（表示用系统默认） */
    fun readEngine(): String? = try {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH
        )
    } catch (e: Throwable) {
        null
    }

    /**
     * 读取音调（倍率）。
     *
     * 系统存的是**百分数**（100 = 正常），要除 100 —— 对齐天坦
     * `Z6/J.java:1081` 的 `/ 100.0f`。
     */
    fun readPitch(): Float = readPercent(Settings.Secure.TTS_DEFAULT_PITCH)

    /** 读取语速（倍率）—— 对齐天坦 `Z6/J.java:1093` */
    fun readRate(): Float = readPercent(Settings.Secure.TTS_DEFAULT_RATE)

    private fun readPercent(key: String): Float = try {
        Settings.Secure.getInt(context.contentResolver, key, DEFAULT_PERCENT) /
            PERCENT_TO_RATIO
    } catch (e: Throwable) {
        1.0f
    }

    companion object {
        /**
         * 三个被观察的 URI。
         *
         * 【为什么用字符串而非 `Settings.Secure.TTS_DEFAULT_SYNTH`】
         *   `getUriFor()` 需要 `Settings.Secure` 的常量，
         *   而这些常量在**部分 SDK 上不可见**（`@hide`）。
         *   直接用 `content://settings/secure/<name>` 更稳 ——
         *   天坦 `Z6/J.java:508~510` 用的也是字符串字面量。
         *
         * 【URI 格式已核实】`Settings.Secure.getUriFor(name)`
         * 返回的就是 `content://settings/secure/<name>`。
         */
        const val URI_DEFAULT_SYNTH = "content://settings/secure/tts_default_synth"
        const val URI_DEFAULT_PITCH = "content://settings/secure/tts_default_pitch"
        const val URI_DEFAULT_RATE = "content://settings/secure/tts_default_rate"

        /** 系统默认百分数（100 = 1.0 倍） */
        const val DEFAULT_PERCENT = 100

        /** 百分数 → 倍率 */
        const val PERCENT_TO_RATIO = 100.0f
    }
}
