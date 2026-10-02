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
import com.baicha.reader.R
import com.baicha.reader.core.ReaderAccessibilityService
import com.baicha.reader.core.TtsEngineManager
import com.baicha.reader.core.TtsManager
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

    /** 独立 TTS 实例用于试听（不干扰服务） */
    private var previewTts: TtsManager? = null

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

        previewTts = TtsManager(this)

        setupEngineSpinner()
        setupVoiceSpinner()
        setupSliders()

        findViewById<Button>(R.id.btnRefreshEngines).setOnClickListener {
            setupEngineSpinner()
            Toast.makeText(this, "已刷新语音库列表", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnEngineSettings).setOnClickListener {
            TtsEngineManager.openSystemTtsSettings(this)
        }

        findViewById<Button>(R.id.btnTestSpeak).setOnClickListener {
            previewTts?.speak(getString(R.string.tts_test_text), true)
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            TtsEngineManager.saveRate(this, TtsEngineManager.DEFAULT_RATE)
            TtsEngineManager.savePitch(this, TtsEngineManager.DEFAULT_PITCH)
            TtsEngineManager.saveVolume(this, TtsEngineManager.DEFAULT_VOLUME)
            TtsEngineManager.saveLanguage(this, "")
            TtsEngineManager.saveVoiceName(this, null)
            reloadSliders()
            applyToService()
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show()
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
                    previewTts?.setSpeechRate(rate)
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
                    previewTts?.setPitch(pitch)
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
                    previewTts?.setVolume(vol)
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