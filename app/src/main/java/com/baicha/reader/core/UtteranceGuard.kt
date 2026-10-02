package com.baicha.reader.core

/**
 * TTS 播报回调的**陈旧回调识别** —— 纯逻辑，可单测
 *
 * ══════════════════════════════════════════════════════════
 * 【解决的问题：utteranceId 被忽略 ⇒ 偶尔漏读一条内容】
 * ══════════════════════════════════════════════════════════
 *
 * `SpeechEngine` 每发起一次播报都会带一个 utteranceId：
 * ```kotlin
 *   val utteranceId = "baicha_${item.id}"
 *   tts.speak(item.text, mode, null, utteranceId)
 * ```
 * 它**唯一的作用**就是让回调能识别「刚完成的是哪一项」。
 *
 * 但三个回调此前**全部忽略它**，直接操作**队首当前项**：
 * ```kotlin
 *   override fun onDone(utteranceId: String?) {
 *       mainHandler.post { onUtteranceDone(channel) }   // ← 不看 utteranceId
 *   }
 * ```
 * 而 `onUtteranceDone()` 里是：
 * ```kotlin
 *   val item = queue.current()      // ← 拿的是**当前**队首，不一定是刚完成那条
 *   queue.onCurrentDone()
 *   trySpeakNext(channel)
 * ```
 *
 * ══════════════════════════════════════════════════════════
 * 【触发场景（真机上真实存在）】
 * ══════════════════════════════════════════════════════════
 *
 * ① **引擎切换/重建**：`setEngine()` / `createEngine()` 会
 *    `stop()` + `shutdown()` 旧实例，但旧实例**仍可能异步回调**
 *    `onDone`/`onError`。此时队列里已是**新的一项** →
 *    旧回调把新项判为完成 → **新项没念就被跳过**。
 *
 * ② **`stop()` 后残留回调**：`tts.stop()` 之后 TTS 仍可能回调
 *    `onDone`。若清空队列后**立刻**有新播报入队（用户快速滑动），
 *    `current()` 就是那条新项 → 被误判完成。
 *
 * ③ **QUEUE_FLUSH 打断**：新项以 `QUEUE_FLUSH` 发出时，
 *    TTS 会丢弃旧队列并可能回调**旧项**的 `onDone` →
 *    把新项误判完成。
 *
 * 【统一后果】用户滑动时**偶尔漏读一条内容**，
 * 且日志上一切正常（显示为正常完成）—— 典型的静默失败（模式⑨）。
 *
 * ══════════════════════════════════════════════════════════
 * 【修法】比对 utteranceId 与「当前项应有的 ID」
 * ══════════════════════════════════════════════════════════
 *
 * 不匹配 ⇒ 陈旧回调，忽略（并记 DEBUG 日志便于排障）。
 * 本对象只做纯字符串判断，便于单测（SpeechEngine 依赖
 * `TextToSpeech`，在 JVM 里无法构造）。
 */
object UtteranceGuard {

    /** utteranceId 前缀 —— 与 `SpeechEngine` 构造时一致 */
    const val PREFIX = "baicha_"

    /** 由播报项 ID 构造 utteranceId */
    fun idOf(itemId: Long): String = "$PREFIX$itemId"

    /**
     * 该回调是否属于**当前正在播的那一项**
     *
     * @param callbackId 回调收到的 utteranceId
     * @param currentItemId 队列里当前项的 ID；null = 队列无当前项
     * @return true = 是当前项的完成回调，应处理；false = 陈旧回调，应忽略
     */
    fun matchesCurrent(callbackId: String?, currentItemId: Long?): Boolean {
        // 队列里没有当前项 → 任何回调都是陈旧的
        if (currentItemId == null) return false

        // 没有 ID 的回调（某些引擎不传）→ 无法判断，**保守处理**
        //
        // 【为什么保守放行而不是拦截】部分 TTS 引擎（尤其旧版）
        // 在 `onStart`/`onDone` 里传 null。若一律拦掉，
        // 就会**完全不出声** —— 那比「偶尔漏读」严重得多。
        // 故 null 时放行（保持原有行为），只对**明确不匹配**的拦截。
        if (callbackId == null) return true

        return callbackId == idOf(currentItemId)
    }

    /**
     * 从 utteranceId 反解出项 ID（诊断用）
     *
     * @return 项 ID；格式不符时返回 null
     */
    fun itemIdOf(utteranceId: String?): Long? {
        if (utteranceId == null) return null
        if (!utteranceId.startsWith(PREFIX)) return null
        return utteranceId.removePrefix(PREFIX).toLongOrNull()
    }
}
