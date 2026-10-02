package com.baicha.reader.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.baicha.reader.R
import com.baicha.reader.core.ReaderAccessibilityService
import com.baicha.reader.core.ReaderLog

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 语音设置入口
        findViewById<Button>(R.id.btnTtsSettings).setOnClickListener {
            startActivity(Intent(this, TtsSettingsActivity::class.java))
        }
        // 手势配置（A7/D2）—— 让「手势可配置」真正落地
        findViewById<Button>(R.id.btnGestureSettings).setOnClickListener {
            startActivity(Intent(this, GestureSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            ReaderAccessibilityService.instance?.speakTest("测试朗读，白茶读屏")
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            ReaderLog.clear()
            tvLog.text = ""
        }

        // 日志回调（独立日志系统）
        ReaderLog.onLogUpdate = { line ->
            runOnUiThread {
                tvLog.append("$line\n")
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        // 恢复已有日志
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        renderLog()
    }

    private fun updateStatus() {
        val running = ReaderAccessibilityService.isRunning
        tvStatus.text = if (running) {
            getString(R.string.status_running) + "  " +
                (ReaderAccessibilityService.instance?.stats() ?: "") +
                "\n日志: " + ReaderLog.currentLogPath()
        } else {
            getString(R.string.status_stopped)
        }
    }

    private fun renderLog() {
        val sb = StringBuilder()
        ReaderLog.readMemory().forEach { sb.append(it).append('\n') }
        tvLog.text = sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        ReaderLog.onLogUpdate = null
    }
}