package com.baicha.reader.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.baicha.reader.R
import com.baicha.reader.core.ReaderAccessibilityService
import com.baicha.reader.core.TtsEngineManager
import com.baicha.reader.core.SpeechEngine
import java.util.Locale

/**
 * 语音设置界面 —— 把语音库选择权交给用户
 *
 * 功能：
 * 1. 列出系统所有语音库，用户选
 * 2. 列出该语音库支持的语音（Voice）
 * 3. 语速 / 音调 / 音量 滑块
 * 4. 跳转系统语音库设置
 * 5. 测试朗读
 */
class TtsSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TtsSettings"
    }

    private lateinit var spinnerEngine: Spinner
    private lateinit var spinnerVoice: Spinner
    private lateinit var seekRate: SeekBar
    private lateinit var seekPitch: SeekBar
    private lateinit var seekVolume: SeekBar
    private lateinit var txtRateLabel: TextView
    private lateinit var txtPitchLabel: TextView
    private lateinit var txtVolumeLabel: TextView

    private var engines: List<TtsEngineManager.TtsEngine> = emptyList()
    private var voices: List<TtsEngineManager.TtsVoice> = emptyList()

    /**
     * 独立播报引擎实例用于试听（不干扰正在运行的读屏服务）
     *
     * 走双通道框架的 B 通道（打断通道），试听即时生效。
     */
    private var previewTts: SpeechEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_settings)

        spinnerEngine = findViewById(R.id.spinnerEngine)
        spinnerVoice = findViewById(R.id.spinnerVoice)
        seekRate = findViewById(R.id.seekRate)
        seekPitch = findViewById(R.id.seekPitch)
        seekVolume = findViewById(R.id.seekVolume)
        txtRateLabel = findViewById(R.id.txtRateLabel)
        txtPitchLabel = findViewById(R.id.txtPitchLabel)
        txtVolumeLabel = findViewById(R.id.txtVolumeLabel)

        previewTts = SpeechEngine(this).apply {
            enginePackage = TtsEngineManager.getSavedEngine(this@TtsSettingsActivity)
            speechRate = TtsEngineManager.getRate(this@TtsSettingsActivity)
            pitch = TtsEngineManager.getPitch(this@TtsSettingsActivity)
            volume = TtsEngineManager.getVolume(this@TtsSettingsActivity)
            init()
        }

        setupEngineSpinner()
        setupVoiceSpinner()
        setupSliders()

        // ══════════════════════════════════════════════════════════
        // 【★ 本轮修复：补无障碍动作（本页 4 个按钮此前一个都没有）】
        // ══════════════════════════════════════════════════════════
        // 同时修掉一处**硬编码中文**（`Toast.makeText(this, "已刷新语音库列表")`）
        // —— 硬编码文案无法本地化，且与 strings.xml 的同义文案会各自演化；
        // 读屏念的是实际渲染文本，两处不一致时用户听到的和描述对不上。
        val btnRefresh = findViewById<Button>(R.id.btnRefreshEngines)
        btnRefresh.setOnClickListener { doRefreshEngines() }
        ViewCompat.addAccessibilityAction(
            btnRefresh, getString(R.string.a11y_refresh_engines)
        ) { _, _ ->
            doRefreshEngines()
            true
        }

        val btnEngineSettings = findViewById<Button>(R.id.btnEngineSettings)
        btnEngineSettings.setOnClickListener {
            TtsEngineManager.openSystemTtsSettings(this)
        }
        ViewCompat.addAccessibilityAction(
            btnEngineSettings, getString(R.string.a11y_engine_settings)
        ) { _, _ ->
            TtsEngineManager.openSystemTtsSettings(this)
            true
        }

        val btnTestSpeak = findViewById<Button>(R.id.btnTestSpeak)
        btnTestSpeak.setOnClickListener { doTestSpeak() }
        ViewCompat.addAccessibilityAction(
            btnTestSpeak, getString(R.string.a11y_test_speak)
        ) { _, _ ->
            doTestSpeak()
            true
        }

        val btnReset = findViewById<Button>(R.id.btnReset)
        btnReset.setOnClickListener { doResetTts() }
        ViewCompat.addAccessibilityAction(
            btnReset, getString(R.string.a11y_reset_tts)
        ) { _, _ ->
            doResetTts()
            true
        }
    }

    // ==================== 语音库列表 ====================

    private fun setupEngineSpinner() {
        engines = TtsEngineManager.getEngines(this)

        val labels = ArrayList<String>()
        labels.add(getString(R.string.tts_engine_default))   // 第 0 项 = 系统默认
        engines.forEach { labels.add(it.toString()) }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEngine.adapter = adapter

        // 定位到已保存的引擎
        val saved = TtsEngineManager.getSavedEngine(this)
        val idx = if (saved.isNullOrEmpty()) {
            0
        } else {
            engines.indexOfFirst { it.packageName == saved }.let { if (it < 0) 0 else it + 1 }
        }
        spinnerEngine.setSelection(idx)

        spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pkg = if (position == 0) null else engines[position - 1].packageName
                TtsEngineManager.saveEngine(this@TtsSettingsActivity, pkg ?: "")
                Log.i(TAG, "engine selected: ${pkg ?: "默认"}")
                // 切换引擎后刷新语音列表
                setupVoiceSpinner()
                applyToService()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ==================== 语音（Voice）列表 ====================

    private fun setupVoiceSpinner() {
        val pkg = TtsEngineManager.getSavedEngine(this)
        voices = TtsEngineManager.getVoices(this, pkg)

        val labels = ArrayList<String>()
        labels.add(getString(R.string.tts_voice_auto))
        voices.forEach { labels.add(it.toString()) }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoice.adapter = adapter

        val savedVoice = TtsEngineManager.getVoiceName(this)
        val idx = if (savedVoice.isNullOrEmpty()) {
            0
        } else {
            voices.indexOfFirst { it.name == savedVoice }.let { if (it < 0) 0 else it + 1 }
        }
        spinnerVoice.setSelection(idx)

        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = if (position == 0) null else voices[position - 1].name
                TtsEngineManager.saveVoiceName(this@TtsSettingsActivity, name)
                if (position > 0) {
                    TtsEngineManager.saveLanguage(this@TtsSettingsActivity, voices[position - 1].locale)
                }
                applyToService()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ==================== 滑块 ====================

    private fun setupSliders() {
        // 语速：0.5 - 3.0 → 0-100
        seekRate.max = 100
        seekPitch.max = 100
        seekVolume.max = 100

        reloadSliders()

        seekRate.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = progressToRate(progress)
                txtRateLabel.text = "${getString(R.string.tts_rate_label)}：${String.format(Locale.CHINA, "%.2f", rate)}x"
                if (fromUser) {
                    TtsEngineManager.saveRate(this@TtsSettingsActivity, rate)
                    previewTts?.applyRate(rate)
                    applyToService()
                }
            }
        })

        seekPitch.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = progressToPitch(progress)
                txtPitchLabel.text = "${getString(R.string.tts_pitch_label)}：${String.format(Locale.CHINA, "%.2f", pitch)}"
                if (fromUser) {
                    TtsEngineManager.savePitch(this@TtsSettingsActivity, pitch)
                    previewTts?.applyPitch(pitch)
                    applyToService()
                }
            }
        })

        seekVolume.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val vol = progress / 100f
                txtVolumeLabel.text = "${getString(R.string.tts_volume_label)}：${progress}%"
                if (fromUser) {
                    TtsEngineManager.saveVolume(this@TtsSettingsActivity, vol)
                    previewTts?.applyVolume(vol)
                    applyToService()
                }
            }
        })
    }

    private fun reloadSliders() {
        val rate = TtsEngineManager.getRate(this)
        val pitch = TtsEngineManager.getPitch(this)
        val volume = TtsEngineManager.getVolume(this)

        seekRate.progress = rateToProgress(rate)
        seekPitch.progress = pitchToProgress(pitch)
        seekVolume.progress = (volume * 100).toInt()

        txtRateLabel.text = "${getString(R.string.tts_rate_label)}：${String.format(Locale.CHINA, "%.2f", rate)}x"
        txtPitchLabel.text = "${getString(R.string.tts_pitch_label)}：${String.format(Locale.CHINA, "%.2f", pitch)}"
        txtVolumeLabel.text = "${getString(R.string.tts_volume_label)}：${(volume * 100).toInt()}%"
    }

    private fun rateToProgress(rate: Float): Int =
        (((rate - TtsEngineManager.RATE_MIN) /
                (TtsEngineManager.RATE_MAX - TtsEngineManager.RATE_MIN)) * 100).toInt().coerceIn(0, 100)

    private fun progressToRate(p: Int): Float =
        TtsEngineManager.RATE_MIN + (p / 100f) * (TtsEngineManager.RATE_MAX - TtsEngineManager.RATE_MIN)

    private fun pitchToProgress(pitch: Float): Int =
        (((pitch - TtsEngineManager.PITCH_MIN) /
                (TtsEngineManager.PITCH_MAX - TtsEngineManager.PITCH_MIN)) * 100).toInt().coerceIn(0, 100)

    private fun progressToPitch(p: Int): Float =
        TtsEngineManager.PITCH_MIN + (p / 100f) * (TtsEngineManager.PITCH_MAX - TtsEngineManager.PITCH_MIN)

    // ==================== 按钮动作（点击与无障碍动作共用）====================
    //
    // 【为什么抽成方法而不是内联两遍】
    // 每个按钮现在有两处触发点（点击监听 + 无障碍动作）。
    // 若各自内联一份，两处会逐渐演化出差异 ——
    // 本项目已多次因「同一逻辑两份实现」出问题
    // （如 handleMediaGesture 按 gestureId 与映射表两份真相）。
    // 抽出来保证**单一来源**。

    /**
     * 刷新语音库列表
     *
     * 【顺带修复】原来的提示语是**硬编码中文**
     * （`Toast.makeText(this, "已刷新语音库列表", ...)`），
     * 已改为 `@string/tts_engines_refreshed`。
     */
    private fun doRefreshEngines() {
        setupEngineSpinner()
        Toast.makeText(this, getString(R.string.tts_engines_refreshed), Toast.LENGTH_SHORT).show()
    }

    /**
     * 试听 —— 用当前设置念一句
     *
     * ★ 不静默失败：TTS 未就绪时要说明，而不是「按了没反应」。
     */
    private fun doTestSpeak() {
        val tts = previewTts
        if (tts == null) {
            Toast.makeText(this, getString(R.string.tts_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        tts.speakPreview(getString(R.string.tts_test_text))
    }

    /** 恢复语音设置默认值 */
    private fun doResetTts() {
        TtsEngineManager.saveRate(this, TtsEngineManager.DEFAULT_RATE)
        TtsEngineManager.savePitch(this, TtsEngineManager.DEFAULT_PITCH)
        TtsEngineManager.saveVolume(this, TtsEngineManager.DEFAULT_VOLUME)
        TtsEngineManager.saveLanguage(this, "")
        TtsEngineManager.saveVoiceName(this, null)
        reloadSliders()
        applyToService()
        Toast.makeText(this, getString(R.string.tts_reset_done), Toast.LENGTH_SHORT).show()
    }

    /** 把设置同步到正在运行的服务 */
    private fun applyToService() {
        val svc = ReaderAccessibilityService.instance ?: return
        svc.reloadTtsSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewTts?.shutdown()
        previewTts = null
    }

    /** SeekBar 监听器简化基类 */
    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}