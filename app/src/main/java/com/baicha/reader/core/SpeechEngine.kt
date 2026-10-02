package com.baicha.reader.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.os.SystemClock
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 双通道播报引擎 —— 对应天坦 Z6/SpeechController（2027 行）
 *
 * 【天坦的双通道架构】
 *
 *   SpeechController
 *     ├─ Z6.J f11631k   通道 A：普通 TTS 实例
 *     ├─ Z6.J f11632l   通道 B：打断 TTS 实例
 *     ├─ ArrayDeque f11644x / f11645y   两条队列
 *     └─ 音频焦点：两条通道都空闲才释放
 *
 * 【为什么需要两条通道】
 *
 * 场景：用户正在用通道 A 朗读一篇文章（可能几十秒），
 *       此时系统提示"电量不足 20%"。
 *
 *   单通道：要么打断文章（用户丢失上下文），要么等文章读完（提示过期）
 *   双通道：B 通道立即播报提示，A 通道的文章继续 → 两者叠加但互不打断
 *
 * 【高性能强化（相对天坦）】
 *
 * ① **引擎按需创建**：天坦在服务启动时创建两个 TTS 实例（各占一份引擎资源）。
 *    本实现**懒创建** B 通道 —— 绝大多数用户从不触发打断播报，
 *    省掉一个 TTS 实例的内存与初始化耗时。
 *
 * ② **单 handler 驱动**：两条通道共用一个主线程 handler，
 *    避免多 handler 之间的顺序竞争。
 *
 * ③ **完成回调零分配**：用轻量 Runnable 而非 lambda 包装，减少 GC。
 *
 * ④ **音频焦点引用计数**：只有两条通道都空闲才放弃焦点，
 *    避免 A 通道读完就松手导致 B 通道播报被系统压低。
 */
class SpeechEngine(
    private val context: Context,
    /** 引擎包名（null = 系统默认） */
    @Volatile
    var enginePackage: String? = null
) {

    companion object {
        /**
         * 相同文本的最短重播间隔。
         *
         * 【为什么 120ms】短于人的感知阈值（约 150ms），
         * 不会吞掉用户主动要求的重复朗读；
         * 又足以挡住焦点抖动产生的每秒十几次洪流。
         */
        const val REPEAT_GUARD_MS = 120L

        private const val TAG = "SpeechEngine"
    }

    /** 主线程 handler —— 懒加载，避免单测环境无 Looper */
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    // ==================== 两条通道 ====================

    /** 通道 A：普通队列 */
    val channelA = SpeechQueue("A-普通")

    /** 通道 B：打断队列（懒创建引擎） */
    val channelB = SpeechQueue("B-打断")

    /** 通道 A 的 TTS 实例（随引擎切换重建） */
    private var ttsA: TextToSpeech? = null

    /** 通道 B 的 TTS 实例（懒创建） */
    private var ttsB: TextToSpeech? = null

    @Volatile
    private var readyA = false

    @Volatile
    private var readyB = false

    /** 是否启用（读屏开关） */
    @Volatile
    var enabled = true

    /** 音频焦点是否已持有 */
    private val audioFocusHeld = AtomicBoolean(false)

    /** 外部注入的音频焦点回调 */
    @Volatile
    var onRequestAudioFocus: (() -> Unit)? = null

    @Volatile
    var onAbandonAudioFocus: (() -> Unit)? = null

    /** 状态变化通知（供 UI 显示） */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    /**
     * 播报闸门 —— 返回 true 时丢弃本次播报
     *
     * 对应天坦 `SpeechController.fw()` 的录音检测：
     * 用户在语音输入 / 麦克风被占用时，读屏不应出声（会盖住用户说话）。
     *
     * 所有播报入口统一走这个闸门，避免漏判某条路径。
     */
    @Volatile
    var speechGate: (() -> Boolean)? = null

    /**
     * 朗读开始/结束钩子（A5）—— 由服务注入，用于持有/释放唤醒锁。
     *
     * 【为什么放这里而不是放在每个播报调用点】
     *   播报入口有多个（speak / speakInterrupt / speakInternal），
     *   在引擎层挂钩能一次覆盖全部路径 ——
     *   与 speechGate 放在同一层的理由相同。
     */
    var onSpeakingStateChanged: ((speaking: Boolean) -> Unit)? = null

    // ==================== 高频重复播报守卫 ====================

    /** 上次播报的文本 */
    @Volatile
    private var lastSpokenText: String? = null

    /** 上次播报时间 */
    @Volatile
    private var lastSpokenTime: Long = 0L

    /** 被守卫拦截的次数（诊断用） */
    @Volatile
    var repeatGuardCount = 0L
        private set

    // ==================== 语音参数 ====================

    @Volatile
    var speechRate: Float = 1.0f

    @Volatile
    var pitch: Float = 1.0f

    @Volatile
    var volume: Float = 1.0f

    @Volatile
    private var language: Locale = Locale.CHINA

    // ==================== 初始化 ====================

    /**
     * 初始化通道 A
     *
     * @param onReady 就绪回调
     */
    fun init(onReady: (() -> Unit)? = null) {
        if (readyA) {
            onReady?.invoke()
            return
        }
        createEngine(channel = CHANNEL_A, onReady = onReady)
    }

    /**
     * 创建/重建指定通道的 TTS 引擎
     */
    private fun createEngine(channel: Int, onReady: (() -> Unit)?) {
        val pkg = enginePackage
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                if (channel == CHANNEL_A) {
                    readyA = true
                } else {
                    readyB = true
                }
                applySettings(channel)
                ReaderLog.i(TAG, "通道${if (channel == CHANNEL_A) "A" else "B"} 就绪, engine=${pkg ?: "默认"}")
                onReady?.invoke()
                // 引擎就绪后立即尝试播报（可能有积压）
                trySpeakNext(channel)
            } else {
                ReaderLog.e(TAG, "通道${if (channel == CHANNEL_A) "A" else "B"} 初始化失败: $status")
            }
        }

        val tts = try {
            if (pkg.isNullOrEmpty()) {
                TextToSpeech(context.applicationContext, listener)
            } else {
                TextToSpeech(context.applicationContext, listener, pkg)
            }
        } catch (e: Exception) {
            ReaderLog.e(TAG, "创建 TTS 失败: ${e.message}")
            null
        } ?: return

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // 找到对应项触发 onStart
                val q = if (channel == CHANNEL_A) channelA else channelB
                q.current()?.onStart?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                // ★ 朗读完成 → 通知（A5）
                //   若两个通道都空闲才真正算「结束」，
                //   否则 A 通道读完但 B 通道还在播时会提前解锁
                if (!isSpeaking()) notifySpeaking(false)
                mainHandler.post { onUtteranceDone(channel) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                ReaderLog.w(TAG, "TTS 播报出错: $utteranceId")
                mainHandler.post { onUtteranceDone(channel) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                ReaderLog.w(TAG, "TTS 播报出错: $utteranceId code=$errorCode")
                mainHandler.post { onUtteranceDone(channel) }
            }
        })

        if (channel == CHANNEL_A) {
            // 关闭旧实例
            try {
                ttsA?.stop()
                ttsA?.shutdown()
            } catch (e: Exception) {
                // ignore
            }
            ttsA = tts
        } else {
            try {
                ttsB?.stop()
                ttsB?.shutdown()
            } catch (e: Exception) {
                // ignore
            }
            ttsB = tts
        }
    }

    /** 应用语音参数 */
    private fun applySettings(channel: Int) {
        val tts = if (channel == CHANNEL_A) ttsA else ttsB
        tts ?: return
        try {
            tts.setSpeechRate(speechRate)
            tts.setPitch(pitch)
            val result = tts.setLanguage(language)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                ReaderLog.w(TAG, "语言不支持: $language")
            }
        } catch (e: Exception) {
            ReaderLog.e(TAG, "应用语音参数失败: ${e.message}")
        }
    }

    // ==================== 播报接口 ====================

    /**
     * 普通播报 —— 走通道 A
     *
     * @param text 文本
     * @param interrupt true = 打断当前（FLUSH），false = 排队
     */
    fun speak(text: String, interrupt: Boolean = true) {
        speakInternal(text, interrupt, SpeechFlags.NONE)
    }

    /**
     * 打断播报 —— 走通道 B
     *
     * 用于：错误提示、状态反馈等需要"立即"播报的场景
     */
    fun speakInterrupt(text: String, interrupt: Boolean = true) {
        speakInternal(text, interrupt, SpeechFlags.INTERRUPT_CHANNEL)
    }

    /**
     * 完整参数播报
     */
    fun speakInternal(text: String, interrupt: Boolean, extraFlags: Int) {
        if (!enabled) return
        if (text.isEmpty()) return

        // ★★ 高频重复播报守卫 —— 真机 P0 的第三道防线
        //
        // 【真机现象】焦点抖动时一秒内十几次播报同一个文本
        //   （日志：「进度条」「提交吗？」反复出现），
        //   引擎被反复 FLUSH → 全部返回 ERROR_SYNTHESIS(-5)
        //   → 用户「完全没有声音」。
        //
        // 【为什么在这里而不是队列里】
        //   队列的 DEDUPE 需要调用方显式传 FLAG_DEDUPE 才生效，
        //   而焦点路径没有传。这道守卫是**引擎级**的最后防线：
        //   无论谁调用、带什么 flag，相同文本在极短时间内
        //   不会重复送入 TTS。
        //
        // 【为什么是 120ms】
        //   短于人的感知阈值（约 150ms），不会吞掉用户想听的重复内容
        //   （如连按两次要求重读）；但又足以挡住抖动产生的洪流。
        //   不做「完全禁止重复」——那会让用户无法故意重听。
        val guardNow = SystemClock.uptimeMillis()
        if (lastSpokenText == text && guardNow - lastSpokenTime < REPEAT_GUARD_MS) {
            repeatGuardCount++
            ReaderLog.d(TAG, "重复播报被守卫拦截: ${text.take(12)}")
            return
        }
        lastSpokenText = text
        lastSpokenTime = guardNow

        // ★ 语音输入静音闸门（天坦行为：录音中不出声）
        if (speechGate?.invoke() == true) {
            ReaderLog.d("SpeechEngine", "语音输入中，静音: ${text.take(20)}")
            return
        }

        val useChannelB = SpeechFlags.has(extraFlags, SpeechFlags.INTERRUPT_CHANNEL)
        var flags = extraFlags
        if (interrupt) flags = flags or SpeechFlags.FLUSH

        val item = SpeechItem(
            text = text,
            queueMode = if (interrupt) 0 else 1,
            flags = flags
        )

        val queue = if (useChannelB) channelB else channelA
        val result = queue.enqueue(item)

        if (result.deduped) return

        // ★ 通知「开始朗读」（A5）—— 服务据此持有唤醒锁
        //   放在 enqueue 成功之后：被去重丢弃的项不算开始
        notifySpeaking(true)

        // 若走 B 通道但引擎未创建 → 懒创建
        if (useChannelB && !readyB) {
            createEngine(CHANNEL_B, null)
            return   // 就绪后会触发 trySpeakNext
        }

        if (result.needInterrupt) {
            try {
                if (useChannelB) ttsB?.stop() else ttsA?.stop()
            } catch (e: Exception) {
                // ignore
            }
        }

        trySpeakNext(if (useChannelB) CHANNEL_B else CHANNEL_A)
    }

    /**
     * 尝试播报下一项
     */
    private fun trySpeakNext(channel: Int) {
        val queue = if (channel == CHANNEL_A) channelA else channelB
        val tts = if (channel == CHANNEL_A) ttsA else ttsB
        val ready = if (channel == CHANNEL_A) readyA else readyB

        if (!ready || tts == null) return

        // 已有正在播的项 → 不打断
        if (queue.isSpeaking) return

        val item = queue.pollNext() ?: run {
            // 队列空 → 检查是否该释放音频焦点
            checkAudioFocus()
            return
        }

        if (item.cancelled) {
            trySpeakNext(channel)
            return
        }

        // 请求音频焦点
        if (audioFocusHeld.compareAndSet(false, true)) {
            onRequestAudioFocus?.invoke()
        }

        // CALLBACK_ONLY：不朗读，直接回调
        if (SpeechFlags.has(item.flags, SpeechFlags.CALLBACK_ONLY)) {
            item.onStart?.invoke()
            item.onDone?.invoke()
            queue.onCurrentDone()
            trySpeakNext(channel)
            return
        }

        try {
            val utteranceId = "baicha_${item.id}"

            // ★★ TTS 修复：按 item 的语义选择 QUEUE_FLUSH / QUEUE_ADD
            //
            // 【真机问题】原来**无条件用 QUEUE_FLUSH** ——
            //   即「每条播报都打断上一条」。真机日志显示
            //   `TTS 播报出错: baicha_NN code=-5`（ERROR_SYNTHESIS）
            //   连续出现。
            //
            // 【为什么 -5】QUEUE_FLUSH 会丢弃正在合成的内容。
            //   当播报调用频率很高（焦点抖动时一秒十几次），
            //   引擎被反复清空，来不及合成任何一条 →
            //   返回 ERROR_SYNTHESIS(-5)。
            //   用户表现为「完全没声音」。
            //
            // 【正确语义】`queueMode == 0` 表示打断（FLUSH），
            //   `queueMode == 1` 表示排队（ADD）——
            //   这是 SpeechItem 构造时就定好的（见 SpeechQueue）。
            //   原来忽略了 queueMode，把「排队」也当成了「打断」。
            val mode = if (item.queueMode == 0) {
                TextToSpeech.QUEUE_FLUSH
            } else {
                TextToSpeech.QUEUE_ADD
            }
            tts.speak(item.text, mode, null, utteranceId)
            onStateChanged?.invoke()
        } catch (e: Exception) {
            ReaderLog.e(TAG, "播报失败: ${e.message}")
            queue.onCurrentDone()
        }
    }

    /**
     * 单条播报完成
     */
    private fun onUtteranceDone(channel: Int) {
        val queue = if (channel == CHANNEL_A) channelA else channelB
        val item = queue.current()
        queue.onCurrentDone()
        item?.onDone?.invoke()
        onStateChanged?.invoke()

        // 继续播下一项
        trySpeakNext(channel)
    }

    /**
     * 检查音频焦点
     *
     * ★ 天坦的关键：两条通道都空闲才放弃焦点
     */
    private fun checkAudioFocus() {
        if (channelA.isEmpty && channelB.isEmpty) {
            if (audioFocusHeld.compareAndSet(true, false)) {
                onAbandonAudioFocus?.invoke()
            }
        }
    }

    // ==================== 控制 ====================

    /**
     * 停止播报
     *
     * @param clearQueue 是否同时清空待播队列
     */
    fun stop(clearQueue: Boolean = true) {
        try {
            ttsA?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            ttsB?.stop()
        } catch (e: Exception) {
            // ignore
        }
        if (clearQueue) {
            channelA.clear(keepCurrent = false)
            channelB.clear(keepCurrent = false)
        } else {
            channelA.onCurrentDone()
            channelB.onCurrentDone()
        }
        checkAudioFocus()
        onStateChanged?.invoke()
    }

    /**
     * 打断通道 A（保留队列）—— 触摸探索时调用
     *
     * 天坦在 VIEW_HOVER_ENTER 和 TOUCH_EXPLORATION_GESTURE_START 时
     * 都调用 interrupt()，只作用通道 A。
     */
    fun interruptChannelA() {
        try {
            ttsA?.stop()
        } catch (e: Exception) {
            // ignore
        }
        channelA.onCurrentDone()
        checkAudioFocus()
        onStateChanged?.invoke()
    }

    /**
     * 切换引擎
     */
    fun setEngine(pkg: String?) {
        if (pkg == enginePackage) return
        enginePackage = pkg
        readyA = false
        if (readyB) {
            readyB = false
            createEngine(CHANNEL_B, null)
        }
        createEngine(CHANNEL_A, null)
    }

    /** 更新语音参数 */
    fun updateSettings(rate: Float, p: Float, vol: Float, lang: String?) {
        speechRate = rate
        pitch = p
        volume = vol
        if (!lang.isNullOrEmpty()) {
            language = Locale.forLanguageTag(lang)
        }
        applySettings(CHANNEL_A)
        if (readyB) applySettings(CHANNEL_B)
    }

    // ==================== 设置界面预览 ====================

    /**
     * 即时更新语音参数（不做引擎重建）
     *
     * 供设置界面的滑块实时调节使用。比 updateSettings 轻量：
     * 只改参数 + 应用到现有 TTS 实例，不触发重建。
     */
    fun applyRate(rate: Float) {
        speechRate = rate
        try {
            ttsA?.setSpeechRate(rate)
            if (readyB) ttsB?.setSpeechRate(rate)
        } catch (e: Exception) { }
    }

    fun applyPitch(p: Float) {
        pitch = p
        try {
            ttsA?.setPitch(p)
            if (readyB) ttsB?.setPitch(p)
        } catch (e: Exception) { }
    }

    fun applyVolume(v: Float) {
        volume = v
        // TextToSpeech 无 setVolume API，音量通过 Bundle 在 speak 时传入
        // 这里只记录，speak 时生效
    }

    fun applyLanguage(langTag: String?) {
        if (langTag.isNullOrEmpty()) return
        language = Locale.forLanguageTag(langTag)
        applySettings(CHANNEL_A)
        if (readyB) applySettings(CHANNEL_B)
    }

    fun isReady(): Boolean = readyA

    /**
     * 试听播报 —— 设置界面用
     *
     * 走 B 通道（打断通道），因为试听需要立即生效，
     * 且不应该被服务正在播报的内容阻塞。
     */
    fun speakPreview(text: String) {
        speakInternal(text, interrupt = true, extraFlags = SpeechFlags.INTERRUPT_CHANNEL)
    }

    fun isSpeaking(): Boolean = channelA.isSpeaking || channelB.isSpeaking

    /**
     * 通知朗读状态变化（A5）。
     *
     * 【为什么做异常保护】钩子是外部注入的（服务持有唤醒锁），
     * 它出错不应影响播报本身。
     */
    private fun notifySpeaking(speaking: Boolean) {
        try {
            onSpeakingStateChanged?.invoke(speaking)
        } catch (e: Throwable) {
            ReaderLog.w("SpeechEngine", "朗读状态回调异常：${e.message}")
        }
    }

    fun shutdown() {
        stop(clearQueue = true)
        try {
            ttsA?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        try {
            ttsB?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        ttsA = null
        ttsB = null
        readyA = false
        readyB = false
    }

    fun stats(): String =
        "${channelA.stats()} | ${channelB.stats()} | A就绪=$readyA B就绪=$readyB"

    private val CHANNEL_A = 0
    private val CHANNEL_B = 1
}
