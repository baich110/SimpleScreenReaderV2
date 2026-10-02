package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 引擎选择 —— **真机「完全没朗读」的修复**
 *
 * ══════════════════════════════════════════════════════════
 * 【真机 P0】服务起来了、焦点也在动，但**一句都不念**
 * ══════════════════════════════════════════════════════════
 *
 * ```
 *   06:04:41.310 通道A 就绪, engine=es.codefactory.vocalizertts   ← onInit SUCCESS
 *   06:05:21.463 TTS 播报出错: baicha_36 code=-5                  ← 但合成失败
 *   06:05:22.805 TTS 播报出错: baicha_38 code=-5
 * ```
 *
 * 手机上实际可用的引擎（`pm query-services -a android.intent.action.TTS_SERVICE`）
 * 里，**系统默认是 `com.oplus.ttsaccessibilityengine.mod`**
 * （`settings get secure tts_default_synth`），
 * 而白茶用的是 `es.codefactory.vocalizertts`（Vocalizer，targetSdk=29）——
 * 它的中文语音很可能是未安装的试用版 ⇒ 每次 `speak` 都 `-5`。
 *
 * 【怎么变成这样的】`TtsSettingsActivity.setupEngineSpinner()` 原来：
 * ```kotlin
 *   spinnerEngine.setSelection(idx)                    // ① 先设选中项
 *   spinnerEngine.onItemSelectedListener = object ...  // ② 后装监听器
 * ```
 * `setSelection` 会**异步派发** `onItemSelected` ⇒ 用户只要**打开过设置页**，
 * 就会把一次「程序化的默认选择」**持久化**进 prefs。
 *
 * 【本测试覆盖两层】
 *   ① `TtsEngineResolver` 的选择逻辑（纯函数）
 *   ② `SpeechEngine` 的自愈降级条件（连续 -5 达阈值即换引擎）
 */
class TtsEngineResolverTest {

    private val oplus = "com.oplus.ttsaccessibilityengine.mod"   // 系统默认
    private val vocalizer = "es.codefactory.vocalizertts"        // 真机出问题的那个
    private val tback = "com.android.tback"

    private val available = listOf(oplus, vocalizer, tback)

    // ==================== ① 选择逻辑 ====================

    /** 用户显式保存且可用 → 尊重用户选择 */
    @Test
    fun savedAndAvailable_isRespected() {
        assertEquals(
            "用户显式选过的可用引擎必须尊重",
            tback, TtsEngineResolver.resolve(tback, available, oplus)
        )
    }

    /** 未保存 → 系统默认 */
    @Test
    fun notSaved_usesSystemDefault() {
        assertEquals(
            "未保存时应跟随系统默认（`tts_default_synth`）",
            oplus, TtsEngineResolver.resolve(null, available, oplus)
        )
    }

    /** 空串（旧版 `pkg ?: \"\"` 写下的）也应视为「未保存」 */
    @Test
    fun emptyString_isTreatedAsNotSaved() {
        assertEquals(
            "空串必须等同「未保存」—— 旧版代码用 `pkg ?: \"\"` 写过空串，" +
                    "若把它当包名用会创建出无效引擎",
            oplus, TtsEngineResolver.resolve("", available, oplus)
        )
        assertEquals(oplus, TtsEngineResolver.resolve("   ", available, oplus))
    }

    /** ★ 保存的引擎已被卸载/禁用 → 退回系统默认 */
    @Test
    fun savedButUnavailable_fallsBackToSystemDefault() {
        assertEquals(
            "★ 保存的引擎不在可用列表时（被卸载/禁用）必须退回系统默认 —— " +
                    "否则会创建出一个永远 init 失败的引擎",
            oplus,
            TtsEngineResolver.resolve("com.gone.engine", available, oplus)
        )
    }

    /** 系统默认也不可用 → 返回 null（用无参构造，让系统决定） */
    @Test
    fun systemDefaultUnavailable_returnsNull() {
        assertNull(
            "★ 系统默认不在可用列表时返回 null —— 用 " +
                    "`TextToSpeech(context, listener)`（不传包名）由系统决定。" +
                    "**不能随便挑一个**：会让用户听到没选过的陌生声音，比不出声更困惑。",
            TtsEngineResolver.resolve(null, listOf(tback), "com.gone.default")
        )
    }

    /** 全都不可用 → null */
    @Test
    fun nothingAvailable_returnsNull() {
        assertNull(TtsEngineResolver.resolve(null, emptyList(), null))
    }

    /**
     * ★ 真机场景复现：prefs 里存着 vocalizer（被设置页误写）
     *
     * **它仍在可用列表里** ⇒ resolve() 仍会选它 ——
     * 这说明**选择逻辑本身不足以修复这个 P0**，
     * 必须靠 `SpeechEngine` 的**运行时自愈降级**。
     * 本测试把这个事实钉住，避免将来有人以为「改 resolve 就够了」。
     */
    @Test
    fun vocalizerCase_resolveAloneIsNotEnough() {
        assertEquals(
            "vocalizer 的包**还在**可用列表里 ⇒ resolve() 仍会选中它。" +
                    "「包存在」≠「能合成」，所以必须靠运行时自愈（见下一组测试）。",
            vocalizer,
            TtsEngineResolver.resolve(vocalizer, available, oplus)
        )
    }

    // ==================== ② 运行时自愈降级 ====================

    @Test
    fun fallbackThreshold_isThree() {
        assertEquals(3, TtsEngineResolver.FAIL_THRESHOLD)
        assertFalse("1 次不降级（可能是偶发）", TtsEngineResolver.shouldFallback(1))
        assertFalse("2 次不降级", TtsEngineResolver.shouldFallback(2))
        assertTrue("★ 3 次连续失败 ⇒ 判定引擎不可用", TtsEngineResolver.shouldFallback(3))
        assertTrue("4 次当然也降级", TtsEngineResolver.shouldFallback(4))
    }

    @Test
    fun onlySynthesisFailureCounts() {
        assertTrue("★ -5 = ERROR_SYNTHESIS ⇒ 参与降级判定",
            TtsEngineResolver.isSynthesisFailure(-5))
        assertFalse("-1 = ERROR_INVALID_REQUEST：调用方 bug，不该降级",
            TtsEngineResolver.isSynthesisFailure(-1))
        assertFalse("-2 = ERROR_NETWORK：网络类引擎暂时不可达",
            TtsEngineResolver.isSynthesisFailure(-2))
        assertFalse("-3 = ERROR_NETWORK_TIMEOUT", TtsEngineResolver.isSynthesisFailure(-3))
        assertFalse("-4 = ERROR_OUTPUT：输出设备问题", TtsEngineResolver.isSynthesisFailure(-4))
        assertFalse("正数不是错误码", TtsEngineResolver.isSynthesisFailure(0))
    }

    // ==================== 接线守卫 ====================

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * ★★ `SpeechEngine.onError` 必须触发降级检查（不能只打日志）
     */
    @Test
    fun speechEngine_onError_triggersFallbackCheck() {
        val src = codeOf("src/main/java/com/baicha/reader/core/SpeechEngine.kt")
        assertTrue(
            "★ SpeechEngine 必须有 onSpeakError —— 只打日志不降级的话，" +
                    "坏引擎会让读屏**永远不出声**（真机 P0）",
            src.contains("private fun onSpeakError(")
        )
        assertTrue(
            "必须有连续失败计数",
            src.contains("consecutiveSynthFails")
        )
        assertTrue(
            "必须在降级时清空 enginePackage（回到系统默认）",
            src.contains("enginePackage = null")
        )
        assertTrue(
            "必须通知外部清掉持久化的引擎选择",
            src.contains("onEngineUnusable")
        )
    }

    /** ★ 服务必须注入 onEngineUnusable 并清 prefs */
    @Test
    fun service_clearsPersistedEngineOnFallback() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
        assertTrue(
            "★ 服务必须注入 onEngineUnusable 回调 —— 否则降级只在内存生效，" +
                    "下次启动又选中那个坏引擎",
            src.contains("onEngineUnusable = {")
        )
        assertTrue(
            "必须调用 TtsEngineManager.clearEngine",
            src.contains("TtsEngineManager.clearEngine(")
        )
        assertTrue(
            "必须**告知用户**切换了引擎 —— 否则「突然换了个声音」无法解释",
            src.contains("已自动切换为系统默认语音")
        )
    }

    /**
     * ★★ 设置页**不得**在程序化恢复时写入 prefs
     *
     * 这是真机 P0 的**起点**：`setSelection` 异步派发 `onItemSelected`，
     * 用户只是打开过设置页，就被写入了 Vocalizer。
     */
    @Test
    fun settingsPage_doesNotPersistOnProgrammaticRestore() {
        val src = codeOf("src/main/java/com/baicha/reader/ui/TtsSettingsActivity.kt")
        assertTrue(
            "★ 设置页必须有 `restoring` 标志区分「程序化恢复」与「用户主动选择」",
            src.contains("restoring")
        )
        val idx = src.indexOf("override fun onItemSelected(")
        assertTrue("必须存在 onItemSelected", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 900))
        assertTrue(
            "★ onItemSelected 开头必须检查 `if (restoring)` 并提前返回 —— " +
                    "否则 `setSelection` 的异步派发会把默认选择**持久化**，" +
                    "用户没选过却被写进一个坏引擎（真机 P0 的起点）。\n$body",
            body.contains("if (restoring)")
        )
        assertFalse(
            "不得再出现 `pkg ?: \"\"` —— 空串会被当成包名用",
            src.contains("pkg ?: \"\"")
        )
    }
}
