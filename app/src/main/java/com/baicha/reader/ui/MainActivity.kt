package com.baicha.reader.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import android.view.View
import com.baicha.reader.R
import com.baicha.reader.core.LogCategory
import com.baicha.reader.core.ReaderAccessibilityService
import com.baicha.reader.core.ReaderLog

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnLogFilter: Button

    /**
     * 当前日志筛选 —— 空集 = 不过滤（显示全部）
     *
     * 【为什么筛选是「视图」状态而不是「写入」状态】
     *   见 [ReaderLog.categoryFilter] 的注释：写入侧过滤会连带丢掉磁盘归档。
     *   这里是纯展示层，切筛选不影响文件里已经记下的内容。
     */
    private var activeFilter: Set<LogCategory> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnLogFilter = findViewById(R.id.btnLogFilter)

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
            // 清空必须同时重置限流器，否则同一秒内同类日志会被当成
            // 「已经输出过」而静默吞掉，用户面对空白面板以为读屏卡死
            ReaderLog.clear()
            ReaderLog.clearBuffers()
            tvLog.text = ""
        }

        // 日志筛选（读屏用户靠它把日志收窄到一类）
        btnLogFilter.setOnClickListener { showFilterDialog() }
        // 读屏用户单指上下滑浏览操作项 —— 与项目无障碍铁律一致
        ViewCompat.addAccessibilityAction(
            btnLogFilter,
            getString(R.string.log_filter_label) + getString(R.string.log_filter_none)
        ) { _, _ ->
            activeFilter = emptySet()
            applyFilter()
            announce(getString(R.string.log_filter_cleared))
            true
        }
        updateFilterButton()

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

    // ==================== 日志筛选 ====================

    /**
     * 选择要显示的分类（可多选）
     *
     * 【为什么用多选对话框而不是逐个分类的按钮】
     *   7 个分类平铺就是 7 个按钮，把主界面挤满。
     *   对话框用系统自带的列表 + 多选，TalkBack 直接能读、
     *   能逐项开关，不需要自己实现无障碍语义。
     */
    private fun showFilterDialog() {
        val categories = LogCategory.entries.toTypedArray()
        val labels = categories.map { categoryLabel(it) }.toTypedArray()
        val checked = BooleanArray(categories.size) { categories[it] in activeFilter }

        AlertDialog.Builder(this)
            .setTitle(R.string.log_filter_label)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                activeFilter = categories.filterIndexed { i, _ -> checked[i] }.toSet()
                applyFilter()
                updateFilterButton()
                announce(
                    if (activeFilter.isEmpty()) getString(R.string.log_filter_cleared)
                    else getString(
                        R.string.log_filter_applied,
                        activeFilter.joinToString("、") { categoryLabel(it) }
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 取消筛选（按钮长按） */
    private fun clearFilter() {
        activeFilter = emptySet()
        applyFilter()
        updateFilterButton()
        announce(getString(R.string.log_filter_cleared))
    }

    private fun applyFilter() {
        ReaderLog.categoryFilter = activeFilter
        renderLog()
    }

    private fun updateFilterButton() {
        val text = if (activeFilter.isEmpty()) {
            getString(R.string.log_filter_all)
        } else {
            activeFilter.joinToString("、") { categoryLabel(it) }
        }
        btnLogFilter.text = text
        btnLogFilter.contentDescription =
            getString(R.string.log_filter_label) + text
    }

    private fun categoryLabel(c: LogCategory): String = getString(categoryLabelRes(c))

    private fun categoryLabelRes(c: LogCategory): Int = when (c) {
        LogCategory.FOCUS -> R.string.log_cat_focus
        LogCategory.EVENT -> R.string.log_cat_event
        LogCategory.SPEECH -> R.string.log_cat_speech
        LogCategory.GESTURE -> R.string.log_cat_gesture
        LogCategory.CACHE -> R.string.log_cat_cache
        LogCategory.SYSTEM -> R.string.log_cat_system
        LogCategory.OTHER -> R.string.log_cat_other
    }

    /** 筛选结果为空时给读屏用户一句明确的话，而不是一片安静 */
    private fun announceFilterEmptyIfNeeded(rendered: String) {
        if (rendered.isBlank() && activeFilter.isNotEmpty()) {
            announce(getString(R.string.log_filter_empty))
        }
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
        announceFilterEmptyIfNeeded(sb.toString())
    }

    /**
     * 播报一句提示
     *
     * 【为什么用 sendAccessibilityEvent 而不是 Toast】
     *   Toast 在部分 ROM 上不播报；TYPE_ANNOUNCEMENT 是
     *   读屏的标准「通知」通道，TalkBack/天坦都会读。
     */
    private fun announce(text: String) {
        try {
            tvLog.post {
                tvLog.announceForAccessibility(text)
            }
        } catch (e: Throwable) {
            // 播报失败不能影响界面
        }
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