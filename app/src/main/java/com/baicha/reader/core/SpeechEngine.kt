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

    /**
     * 连续合成失败计数 —— 用于**自动降级到系统默认引擎**
     *
     * ══════════════════════════════════════════════════════════
     * 【★ 真机 P0：引擎"存在但合成不了"】
     * ══════════════════════════════════════════════════════════
     *
     * 真机日志：
     * ```
     *   通道A 就绪, engine=es.codefactory.vocalizertts     ← onInit 返回 SUCCESS
     *   TTS 播报出错: baicha_36 code=-5                    ← 但每次合成都失败
     *   TTS 播报出错: baicha_38 code=-5
     * ```
     * **「引擎包存在」≠「引擎能合成」** —— Vocalizer 的包在、服务在、
     * `onInit` 返回 `SUCCESS`，但中文语音是未安装的试用版，
     * `speak()` 每次返回 `ERROR_SYNTHESIS(-5)`。
     *
     * 【原实现的后果】读屏**完全不出声** —— 而这是"能不能用手机"的问题。
     * 焦点在动、音效在响、但一句都不念，用户等于没有读屏。
     *
     * 【修复】连续 [TtsEngineResolver.FAIL_THRESHOLD] 次 -5 ⇒ 判定
     * 「该引擎不可用」⇒ 清掉保存值、改用系统默认、重建引擎。
     * 这样**装上一个坏引擎不会让读屏彻底哑掉**（自愈）。
     *
     * 【为什么只对 -5 计数】其他错误码语义不同（见
     * `TtsEngineResolver.isSynthesisFailure` 的说明），
     * 一律降级会掩盖真正的调用方 bug。
     */
    @Volatile
    private var consecutiveSynthFails = 0

    /**
     * 当前播报项的开始时刻（`uptimeMillis`）—— 供**卡死看门狗**判断
     *
     * 见 `speakInternal` 里的看门狗说明。
     */
    @Volatile
    private var queueCurrentSince = 0L

    /**
     * 队列卡死判定阈值（毫秒）
     *
     * 最长单条播报（长段落）通常 < 8s；10s 既兜住真卡死，
     * 又不会误杀正常长文本。
     */
    private val STUCK_QUEUE_MS = 10_000L

    /**
     * 是否已因合成失败而降级过 —— 防无限重试循环
     *
     * 【为什么需要】若系统默认引擎也失败，不加限制会形成
     * 「失败 → 降级 → 又失败 → 又降级」的死循环。
     * 降级**只做一次**：降级后仍失败就只记日志（让用户自己去设置页换）。
     */
    @Volatile
    private var hasFallenBack = false

    /** 降级回调 —— 由服务注入，用于清掉持久化的引擎选择 */
    @Volatile
    var onEngineUnusable: ((failedPackage: String?) -> Unit)? = null

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
                val currentId = q.current()?.id
                // ★ 本轮修复：先确认这条回调属于**当前项**
                if (!UtteranceGuard.matchesCurrent(utteranceId, currentId)) {
                    ReaderLog.d(TAG, "忽略陈旧的 onStart: $utteranceId（当前=$currentId）")
                    return
                }
                q.current()?.onStart?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                // ★ 合成成功 → 清零失败计数（引擎是好的）
                onSpeakSuccess()

                // ★ 朗读完成 → 通知（A5）
                //   若两个通道都空闲才真正算「结束」，
                //   否则 A 通道读完但 B 通道还在播时会提前解锁
                if (!isSpeaking()) notifySpeaking(false)

                // ★★ 本轮修复：**必须**先确认这条回调属于当前项
                //
                // 【原实现的缺陷】三个回调全部忽略 utteranceId，
                // 直接 `onUtteranceDone(channel)` —— 而后者操作的是
                // **队首当前项**，不一定是刚完成那条。
                //
                // 后果：引擎切换（旧实例仍异步回调）、stop() 后残留回调、
                // QUEUE_FLUSH 打断 —— 这三种场景下旧回调会把**新项**
                // 误判为完成，用户**漏听一条内容**且日志显示一切正常。
                //
                // 【在哪个线程判断】本回调在 TTS 线程，
                // 而 `q.current()` 是线程安全的（SpeechQueue 内部有锁），
                // 故可以在这里安全读取；不一致则**不投递**主线程任务。
                val q = if (channel == CHANNEL_A) channelA else channelB
                val currentId = q.current()?.id
                if (!UtteranceGuard.matchesCurrent(utteranceId, currentId)) {
                    ReaderLog.d(TAG, "忽略陈旧的 onDone: $utteranceId（当前=$currentId）")
                    return
                }
                mainHandler.post { onUtteranceDone(channel, utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                ReaderLog.w(TAG, "TTS 播报出错: $utteranceId")
                val q = if (channel == CHANNEL_A) channelA else channelB
                val currentId = q.current()?.id
                if (!UtteranceGuard.matchesCurrent(utteranceId, currentId)) {
                    ReaderLog.d(TAG, "忽略陈旧的 onError: $utteranceId（当前=$currentId）")
                    return
                }
                mainHandler.post { onUtteranceDone(channel, utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                ReaderLog.w(TAG, "TTS 播报出错: $utteranceId code=$errorCode")

                // ★ 合成失败计数 → 必要时自动降级到系统默认引擎
                onSpeakError(errorCode)

                val q = if (channel == CHANNEL_A) channelA else channelB
                val currentId = q.current()?.id
                if (!UtteranceGuard.matchesCurrent(utteranceId, currentId)) {
                    ReaderLog.d(TAG, "忽略陈旧的 onError: $utteranceId code=$errorCode（当前=$currentId）")
                    return
                }
                mainHandler.post { onUtteranceDone(channel, utteranceId) }
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

    // ==================== 引擎可用性自愈 ====================

    /**
     * 合成失败时的自愈逻辑 —— **引擎坏了就换掉**
     *
     * 【真机 P0】`es.codefactory.vocalizertts` 的包存在、服务在、
     * `onInit` 返回 SUCCESS，但中文语音是未安装的试用版 ⇒
     * 每次 `speak` 都返回 `ERROR_SYNTHESIS(-5)` ⇒ **读屏完全不出声**。
     *
     * 本方法在连续失败达阈值时：
     *   ① 记一条 **ERROR 级**日志（让用户/排障者一眼看到引擎有问题）
     *   ② 通过 [onEngineUnusable] 通知服务**清掉持久化的引擎选择**
     *   ③ 把 [enginePackage] 置空 ⇒ 下次 `createEngine` 用系统默认
     *   ④ 重建两条通道的引擎
     *
     * 【只降级一次】若系统默认也失败，不再反复重建（防死循环），
     * 只记日志 —— 那种情况需要用户去设置页手选引擎。
     */
    private fun onSpeakError(errorCode: Int) {
        if (!TtsEngineResolver.isSynthesisFailure(errorCode)) {
            // 其他错误码语义不同，不参与降级判定
            return
        }
        consecutiveSynthFails++

        if (hasFallenBack) {
            ReaderLog.w(
                TAG,
                "合成失败（已降级过，不再重试）: 连续 $consecutiveSynthFails 次，" +
                        "当前引擎=${enginePackage ?: "系统默认"}"
            )
            return
        }

        if (!TtsEngineResolver.shouldFallback(consecutiveSynthFails)) {
            ReaderLog.w(
                TAG,
                "合成失败 $consecutiveSynthFails/${TtsEngineResolver.FAIL_THRESHOLD} 次，" +
                        "引擎=${enginePackage ?: "系统默认"}"
            )
            return
        }

        // ★ 触发降级
        val failedPkg = enginePackage
        hasFallenBack = true
        ReaderLog.e(
            TAG,
            "★ 引擎不可用（连续 $consecutiveSynthFails 次合成失败，" +
                    "错误码 $errorCode）→ 自动降级到系统默认引擎。" +
                    "原引擎=$failedPkg"
        )

        enginePackage = null
        consecutiveSynthFails = 0
        try {
            onEngineUnusable?.invoke(failedPkg)
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "引擎降级回调失败: ${e.message}")
        }

        // 重建两条通道（置 ready 为 false 后重新创建）
        try {
            readyA = false
            readyB = false
            createEngine(CHANNEL_A, null)
        } catch (e: Throwable) {
            ReaderLog.e(TAG, "降级后重建引擎失败: ${e.message}")
        }
    }

    /** 合成成功时清零失败计数 */
    private fun onSpeakSuccess() {
        if (consecutiveSynthFails != 0) consecutiveSynthFails = 0
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
        // ══════════════════════════════════════════════════════════
        // 【★ 本轮补：speak 入口日志 —— 定位"导航成功但不播报"】
        // ══════════════════════════════════════════════════════════
        //
        // 【真机事实】导航成功 20+ 次（`下一焦点: 菜单` 都打出来了），
        // 但 813 行日志里 **SpeechEngine 一条都没有**（除 3 次触摸的 -5）。
        // ⇒ `speak()` 被调了，但**在函数内部就被静默丢弃**。
        //
        // 本函数有 4 个静默 return（`!enabled` / 空文本 / 重复守卫 /
        // 语音输入闸门），加上 `trySpeakNext` 的 3 个早退，
        // 任何一个都表现为"安静"。这条日志把它们分开。
        ReaderLog.d(TAG, "speak 请求: '${text.take(16)}' enabled=$enabled " +
                "interrupt=$interrupt flags=$extraFlags")

        if (!enabled) {
            ReaderLog.w(TAG, "★ speak 被丢弃：读屏开关已关闭（enabled=false）")
            return
        }
        if (text.isEmpty()) {
            ReaderLog.w(TAG, "★ speak 被丢弃：文本为空")
            return
        }

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
            ReaderLog.d(
                TAG,
                "★ speak 被丢弃：重复守卫（'${text.take(12)}' 在 " +
                        "${guardNow - lastSpokenTime}ms 内重复，阈值 ${REPEAT_GUARD_MS}ms）"
            )
            return
        }
        lastSpokenText = text
        lastSpokenTime = guardNow

        // ★ 语音输入静音闸门（天坦行为：录音中不出声）
        if (speechGate?.invoke() == true) {
            ReaderLog.w("SpeechEngine", "★ speak 被丢弃：语音输入中静音: ${text.take(20)}")
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

        // ══════════════════════════════════════════════════════════
        // 【★★ 本轮修复：队列卡死看门狗】
        // ══════════════════════════════════════════════════════════
        //
        // 【真机 P0】导航成功 20+ 次、`下一焦点: 菜单` 都打出来了，
        // 但 `SpeechEngine` **一条日志都没有**，用户听到彻底安静。
        //
        // 【机制】`trySpeakNext` 有 `if (queue.isSpeaking) return` ——
        // 而 `isSpeaking` = `current != null`，`current` 只在
        // `pollNext()` 设置、`onCurrentDone()` 清空。
        // 若某次播报后 **`onDone`/`onError` 从未到达**
        // （部分引擎在 `ERROR_SYNTHESIS` 后**不回调**是已知行为，
        //   真机日志里 3 次 `code=-5` 全来自触摸路径，
        //   而导航路径**连错误都没有** —— 高度符合"丢回调"特征），
        // 则 `current` 永久卡住 ⇒ 之后每次都因 `isSpeaking` 静默返回
        // ⇒ **读屏永久静默，且没有任何日志**。
        //
        // 【修复】入队时检查「当前项已卡住多久」：
        // 超过 [STUCK_QUEUE_MS] 就强制清空当前项并继续。
        // 这是**防御性**的：正常路径永远不会触发（正常播报几百 ms 内完成），
        // 但一旦回调丢失，读屏能**自愈**而不是永久哑掉。
        //
        // 【为什么阈值取 10 秒】最长的单条播报（长段落）通常 < 8s；
        // 10s 既能兜住真正的卡死，又不会误杀正常长文本。
        val stuck = queue.current()
        if (stuck != null) {
            val age = SystemClock.uptimeMillis() - queueCurrentSince
            if (age > STUCK_QUEUE_MS) {
                ReaderLog.e(
                    TAG,
                    "★ 队列卡死看门狗：当前项 id=${stuck.id} " +
                            "'${stuck.text.take(16)}' 已 ${age}ms 未完成" +
                            "（onDone/onError 很可能丢失）→ 强制清空并继续"
                )
                queue.onCurrentDone()
                // 卡死时也重置失败计数（否则计数会误判引擎坏）
                consecutiveSynthFails = 0
            }
        }

        val result = queue.enqueue(item)

        if (result.deduped) {
            ReaderLog.w(TAG, "★ speak 被丢弃：队列去重（'${text.take(12)}' 已在队列中）")
            return
        }

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

        // ══════════════════════════════════════════════════════════
        // 【★ 本轮补诊断：这三个早退**原来全是静默的**】
        // ══════════════════════════════════════════════════════════
        //
        // 【真机 P0】导航成功 20+ 次，但**一次 SpeechEngine 日志都没有** ——
        // `speak()` 被调了（`下一焦点: 菜单` 打出来了），
        // 却没有任何 TTS 活动，也没错误。
        //
        // 本函数的三条早退都能造成这种"静默无声"：
        //   ① `!ready`        —— 引擎未就绪
        //   ② `tts == null`   —— 引擎实例为空
        //   ③ `queue.isSpeaking` —— **当前项未清空**（卡住）
        // 而它们**全都没有日志**。用户听到的是彻底安静。
        //
        // 【为什么 ③ 最可疑】`current` 只在 `pollNext()` 设置、
        // `onCurrentDone()` 清空。若某次播报后 `onDone/onError` 没到达
        // （引擎返回 -5 后**不回调**是某些实现的真实行为），
        // `current` 会**永远卡住** ⇒ 之后每一次 `speak()` 都入队成功、
        // 但 `trySpeakNext` 因 `isSpeaking` 直接返回 ⇒ **永久静默**。
        //
        // 这条日志是二分的唯一依据，必须打。
        if (!ready || tts == null) {
            ReaderLog.w(
                TAG,
                "trySpeakNext 早退：ready=$ready tts=${tts != null} " +
                        "通道=${if (channel == CHANNEL_A) "A" else "B"}"
            )
            return
        }

        // 已有正在播的项 → 不打断
        //
        // ══════════════════════════════════════════════════════════
        // 【★★ 真机 P0 的疑似根因：这里会**永久阻塞**】
        // ══════════════════════════════════════════════════════════
        //
        // 【真机事实】导航成功 20+ 次（`下一焦点: 菜单` 都打出来了），
        // 而 813 行日志里 **SpeechEngine 一条都没有**——
        // 说明 `speak()` 进来了，但 `trySpeakNext` 在这里静默返回了。
        //
        // 【为什么会永久卡住】`queue.isSpeaking` = `current != null`，
        // 而 `current` 只在 `pollNext()` 设置、`onCurrentDone()` 清空。
        // `onCurrentDone()` 只由 TTS 回调（`onDone`/`onError`）触发。
        // **若某次播报后回调从未到达**（引擎 `ERROR_SYNTHESIS` 后
        // 不回调是已知行为），`current` 就永远不清 ——
        // 之后每一次播报都在这一行被丢掉 ⇒ **读屏永久静默**。
        //
        // 【修复】这里也加看门狗：当前项超时未完成 ⇒ 强制清空继续。
        // 这比"只在下次入队时清"更及时（入队本身就可能被这条路挡住）。
        if (queue.isSpeaking) {
            val cur = queue.current()
            val age = SystemClock.uptimeMillis() - queueCurrentSince
            if (age > STUCK_QUEUE_MS) {
                ReaderLog.e(
                    TAG,
                    "★ 队列卡死看门狗（trySpeakNext）：当前项 id=${cur?.id} " +
                            "'${cur?.text?.take(16)}' 已 ${age}ms 未完成 → 强制清空"
                )
                queue.onCurrentDone()
                queueCurrentSince = SystemClock.uptimeMillis()
                // 继续往下走（不再 return）——把新项播出来
            } else {
                ReaderLog.d(
                    TAG,
                    "trySpeakNext 早退：队列忙（当前项 id=${cur?.id} " +
                            "'${cur?.text?.take(12)}'，已 ${age}ms）"
                )
                return
            }
        }

        val item = queue.pollNext() ?: run {
            // 队列空 → 检查是否该释放音频焦点
            checkAudioFocus()
            return
        }

        // 记录「当前项开始时刻」—— 供卡死看门狗使用
        queueCurrentSince = SystemClock.uptimeMillis()

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
            val utteranceId = UtteranceGuard.idOf(item.id)

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
    /**
     * 单条播报完成
     *
     * 【★ 本轮修复：新增 `utteranceId` 参数做第二道防线】
     *
     * 回调线程已经用 [UtteranceGuard] 拦过陈旧回调，但这里**再校验一次**：
     * 从 TTS 回调到主线程执行之间有一段延迟（`mainHandler.post`），
     * 期间队列可能已经变化（用户滑动 → 清空 → 新入队）。
     * 若不复核，就仍可能把**别的项**判为完成。
     *
     * 两道防线的成本可忽略（一次 Long 比较），
     * 而漏读一条内容的代价对用户是实实在在的信息丢失。
     */
    private fun onUtteranceDone(channel: Int, utteranceId: String? = null) {
        val queue = if (channel == CHANNEL_A) channelA else channelB

        // 第二道防线：主线程执行时再确认一次
        val callbackItemId = UtteranceGuard.itemIdOf(utteranceId)
        val currentId = queue.current()?.id
        if (callbackItemId != null && currentId != null && callbackItemId != currentId) {
            ReaderLog.d(
                TAG,
                "主线程复核：完成回调属于项 $callbackItemId，但当前是项 $currentId → 忽略"
            )
            return
        }

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
