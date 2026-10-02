package com.baicha.reader.core

/**
 * TTS 引擎选择 —— **纯逻辑，可单测**
 *
 * ══════════════════════════════════════════════════════════
 * 【真机 P0】服务起来了、焦点也动了，但**完全没朗读**
 * ══════════════════════════════════════════════════════════
 *
 * 真机日志：
 * ```
 *   06:04:41.310 通道A 就绪, engine=es.codefactory.vocalizertts
 *   06:05:21.463 TTS 播报出错: baicha_36 code=-5
 *   06:05:22.805 TTS 播报出错: baicha_38 code=-5
 * ```
 * `code = -5` = `TextToSpeech.ERROR_SYNTHESIS`。
 *
 * 手机上实际可用的引擎里，**系统默认是 `com.oplus.ttsaccessibilityengine.mod`**
 * （`settings get secure tts_default_synth`），而白茶用的是
 * `es.codefactory.vocalizertts`（Vocalizer，`targetSdk=29` 的老应用，
 * 中文语音很可能是**未安装的试用版**）⇒ 每次合成都失败。
 *
 * ══════════════════════════════════════════════════════════
 * 【选错的链路】为什么用户没设置过，却用了 Vocalizer
 * ══════════════════════════════════════════════════════════
 *
 * `TtsSettingsActivity.setupEngineSpinner()`（`:126~151`）：
 * ```kotlin
 *   // 定位到已保存的引擎
 *   val saved = TtsEngineManager.getSavedEngine(this)
 *   val idx = if (saved.isNullOrEmpty()) { 0 } else { ... }
 *   spinnerEngine.setSelection(idx)          // ★ 会**立即触发** onItemSelected
 *
 *   spinnerEngine.onItemSelectedListener = object : ... {
 *       override fun onItemSelected(parent, view, position, id) {
 *           val pkg = if (position == 0) null else engines[position - 1].packageName
 *           TtsEngineManager.saveEngine(this@TtsSettingsActivity, pkg ?: "")   // ★ pkg=null → 存 ""
 *           ...
 *       }
 *   }
 * ```
 *
 * 两个问题：
 * ① **`setSelection` 在设置监听器之前调用**，Android 的 Spinner 在
 *    `setSelection` 后**异步**派发一次 `onItemSelected` ——
 *    这次"自动选择"会把用户的**未设置**状态**写成持久化的值**。
 * ② `pkg ?: ""` 把 `null`（=系统默认）存成**空串**；
 *    而 `getSavedEngine` 返回空串后，`isNullOrEmpty()` 判真 ⇒ 逻辑上
 *    还行，但空串一旦被别的代码路径当包名用就会出问题。
 *
 * ⇒ 用户只要**打开过一次语音设置页**，就会被写入一个引擎选择。
 *
 * ══════════════════════════════════════════════════════════
 * 【本类做什么】给出「该用哪个引擎」的纯函数决策
 * ══════════════════════════════════════════════════════════
 *
 * 原则（按优先级）：
 *   ① 用户**显式保存**且该引擎**当前可用** → 用它
 *   ② 用户保存的引擎**已不在可用列表**（被卸载/禁用）→ 退回系统默认
 *   ③ 未保存 / 保存为空 → **系统默认**
 *   ④ 系统默认也不可用 → 列表里第一个标记 isDefault 的
 *   ⑤ 都没有 → null（交给 `TextToSpeech(context, listener)` 的默认构造）
 *
 * 【为什么这样能修 P0】用户的 prefs 里存的是 `vocalizertts` ——
 * 它在**可用列表里存在**（包还在），所以 ① 仍会选中它。
 * 因此本类**不足以**单独修好这个 P0，必须配合下面这条：
 *
 * ══════════════════════════════════════════════════════════
 * 【真正的修复：语音不可用时自动降级】
 * ══════════════════════════════════════════════════════════
 *
 * 「引擎包存在」≠「引擎能合成」——Vocalizer 就是活例子：
 * 包在、服务在、`onInit` 返回 `SUCCESS`，但 `speak` 每次都 `-5`。
 *
 * 故 `SpeechEngine` 增加**失败计数 + 自动降级**：
 * 连续 [FAIL_THRESHOLD] 次 `ERROR_SYNTHESIS` ⇒ 判定该引擎不可用 ⇒
 * 清掉保存值、改用系统默认、重建引擎。
 *
 * 这条对用户是**自愈**的：装上一个有问题的引擎，读屏不会彻底哑掉。
 */
object TtsEngineResolver {

    /**
     * 连续多少次合成失败后判定「引擎不可用」并降级
     *
     * 【为什么是 3】1 次可能是偶发（引擎忙、文本异常）；
     * 3 次连续失败基本可断定是引擎本身的问题，
     * 而 3 次对用户来说只是「头几句没声」，不会太久。
     */
    const val FAIL_THRESHOLD = 3

    /**
     * 选出该用的引擎
     *
     * @param savedPackage `getSavedEngine()` 的结果（可能为 null 或空串）
     * @param available 当前可用引擎包名（顺序不重要）
     * @param systemDefault `TextToSpeech.getDefaultEngine()` 的结果
     * @return 要用的引擎包名；**null = 用系统默认的默认构造**
     */
    fun resolve(
        savedPackage: String?,
        available: List<String>,
        systemDefault: String?
    ): String? {
        // ① 用户显式保存且可用 → 尊重用户选择
        val saved = savedPackage?.takeIf { it.isNotBlank() }
        if (saved != null && available.contains(saved)) return saved

        // ②③ 未保存 / 保存的已失效 → 系统默认（若可用）
        if (!systemDefault.isNullOrBlank() && available.contains(systemDefault)) {
            return systemDefault
        }

        // ④ 系统默认不在可用列表 → 退回 null（用默认构造）
        //
        // 【为什么不随便挑一个】挑"第一个可用引擎"会让用户听到
        // 一个他没选过的陌生声音 —— 比不出声更困惑。
        // 而 `TextToSpeech(context, listener)`（不传包名）
        // 由系统决定默认引擎，是最稳的兜底。
        return null
    }

    /**
     * 判断一次合成失败是否应触发降级
     *
     * @param consecutiveFails 当前连续失败计数（调用方在失败时 +1）
     */
    fun shouldFallback(consecutiveFails: Int): Boolean = consecutiveFails >= FAIL_THRESHOLD

    /**
     * `ERROR_SYNTHESIS` 是否属于「引擎坏了」而非「这一次文本有问题」
     *
     * 【只对 -5 降级，不对所有错误降级】其他错误码的语义不同：
     *   · `ERROR_INVALID_REQUEST(-1)` —— 请求本身有问题（如 speak 前没 setLanguage）
     *   · `ERROR_NETWORK(-2)` / `ERROR_NETWORK_TIMEOUT(-3)` —— 网络类引擎暂时不可达
     *   · `ERROR_OUTPUT(-4)` —— 输出设备问题
     *   · `ERROR_SYNTHESIS(-5)` —— **合成失败**（引擎自身问题的最典型表现）
     * 一律降级会掩盖真正的调用方 bug，故只对 -5 计数。
     */
    fun isSynthesisFailure(errorCode: Int): Boolean = errorCode == -5
}
