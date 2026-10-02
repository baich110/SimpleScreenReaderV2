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
                appendLogLine(line)
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        // 恢复已有日志
        renderLog()
    }

    /**
     * 追加一行日志，并把 UI 文本裁剪到上限。
     *
     * 必须裁剪：日志会持续增长，若不限长度，锁屏时 [onSaveInstanceState] 会把整段文本
     * 塞进 Bundle 经 Binder 传给 system_server，超过 1MB 即抛
     * TransactionTooLargeException 并杀进程（2026-10-03 真机崩溃：3208220 bytes）。
     * 这里做双保险：XML 侧已 saveEnabled=false，UI 侧再限制长度避免内存与渲染开销。
     */
    private fun appendLogLine(line: String) {
        tvLog.append("$line\n")
        val text = tvLog.text
        if (text.length > MAX_LOG_CHARS) {
            // 按行裁剪，保留最后 MAX_LOG_LINES 行，避免从行中间截断
            val lines = text.split('\n')
            val keep = lines.takeLast(MAX_LOG_LINES)
            tvLog.text = keep.joinToString("\n")
        }
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

    private companion object {
        /** UI 最多保留的日志行数（与 ReaderLog.MAX_MEMORY_LINES 对齐）。 */
        const val MAX_LOG_LINES = 500

        /** 超过该字符数就触发裁剪，避免长文本带来的内存与渲染开销。 */
        const val MAX_LOG_CHARS = 64 * 1024
    }
}