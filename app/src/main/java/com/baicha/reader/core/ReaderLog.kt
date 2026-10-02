package com.baicha.reader.core

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 白茶读屏 —— 独立日志系统
 *
 * 为什么不用系统 logcat：
 *   ① MIUI 会过滤 Log.d/v，抓不到
 *   ② 多个读屏的日志混在一起，分不清
 *   ③ 系统 logcat 缓冲区滚动快，历史丢失
 *
 * 方案：
 *   ① 写文件 /sdcard/Download/baicha-reader.log（外部可读，我能直接 cat）
 *   ② 同时保留内存环形缓冲（App 内日志面板用）
 *   ③ 异步单线程写入（不阻塞无障碍事件处理）
 *   ④ 超过 2MB 自动轮转（保留 .1 备份）
 *
 * 用法：
 *   ReaderLog.init(context)
 *   ReaderLog.d("FocusController", "moveFocus NEXT")
 *   ReaderLog.e("TTS", "init failed", throwable)
 */
object ReaderLog {

    private const val TAG = "ReaderLog"

    /** 日志文件名 */
    private const val LOG_FILE_NAME = "baicha-reader.log"

    /** 单文件最大 2MB，超过轮转 */
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L

    /** 内存缓冲最大条数 */
    private const val MAX_MEMORY_LINES = 500

    /** 时间格式 */
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    /** 内存环形缓冲（App 内日志面板用） */
    private val memoryLog = ArrayDeque<String>(MAX_MEMORY_LINES)

    /** 日志文件 */
    @Volatile
    private var logFile: File? = null

    /** 备用文件（外部存储不可用时用 App 私有目录） */
    @Volatile
    private var fallbackFile: File? = null

    /** 异步写入线程 */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BaiChaLogWriter").apply { isDaemon = true }
    }

    /** 是否已初始化 */
    private val initialized = AtomicBoolean(false)

    /** 日志级别开关 */
    @Volatile
    var verbose = true

    /** 日志更新回调（App 内面板用） */
    @Volatile
    var onLogUpdate: ((String) -> Unit)? = null

    // ==================== 初始化 ====================

    /**
     * 初始化日志系统
     * 优先写 /sdcard/Download/baicha-reader.log（我能直接读）
     * 失败则写 App 私有目录
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        // ① 尝试外部存储
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, LOG_FILE_NAME)
                // 测试可写
                FileWriter(f, true).use { it.write("") }
                logFile = f
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "external storage not writable: ${e.message}")
        }

        // ② 兜底：App 私有目录
        try {
            val dir = File(context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            fallbackFile = File(dir, LOG_FILE_NAME)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "fallback dir failed", e)
        }

        // ③ 写启动分隔线
        val startLine = "\n" + "=".repeat(70) + "\n" +
                "白茶读屏启动 ${timeFormat.format(Date())}\n" +
                "=".repeat(70)
        writeToFile(startLine)

        i("ReaderLog", "日志系统就绪，文件: ${currentLogPath()}")
    }

    /** 当前日志文件路径 */
    fun currentLogPath(): String {
        return logFile?.absolutePath ?: fallbackFile?.absolutePath ?: "(未初始化)"
    }

    /** 清空日志 */
    fun clear() {
        synchronized(memoryLog) { memoryLog.clear() }
        try {
            logFile?.let { FileWriter(it, false).use { fw -> fw.write("") } }
            fallbackFile?.let { FileWriter(it, false).use { fw -> fw.write("") } }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "clear failed", e)
        }
        i("ReaderLog", "日志已清空")
    }

    // ==================== 日志方法 ====================

    fun d(tag: String, msg: String) {
        if (!verbose) return
        log("D", tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        log("I", tag, msg, null)
    }

    fun w(tag: String, msg: String) {
        log("W", tag, msg, null)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        log("E", tag, msg, tr)
    }

    /**
     * 安全获取单调时间 —— 纯 JVM 单测环境没有 android.os.SystemClock
     *
     * 优先用 SystemClock（Android 运行时），失败则回退到 System.nanoTime。
     */
    private fun uptimeMillis(): Long = try {
        SystemClock.uptimeMillis()
    } catch (e: Throwable) {
        System.nanoTime() / 1_000_000L
    }

    /**
     * 核心日志方法
     */
    private fun log(level: String, tag: String, msg: String, tr: Throwable?) {
        val now = uptimeMillis()
        val time = timeFormat.format(Date())
        val thread = Thread.currentThread().name

        val sb = StringBuilder()
        sb.append('[').append(time).append(']')
        sb.append(' ').append(level)
        sb.append('/').append(tag)
        sb.append(" (").append(thread).append("): ")
        sb.append(msg)

        if (tr != null) {
            sb.append("\n  ⚠ ").append(tr.javaClass.simpleName).append(": ").append(tr.message)
            tr.stackTrace.take(8).forEach { el ->
                sb.append("\n      at ").append(el.toString())
            }
        }

        val line = sb.toString()

        // ① 内存缓冲
        synchronized(memoryLog) {
            memoryLog.addLast(line)
            while (memoryLog.size > MAX_MEMORY_LINES) memoryLog.removeFirst()
        }

        // ② 回调（App 内面板）
        onLogUpdate?.invoke(line)

        // ③ 异步写文件
        writeToFile(line)

        // ④ 同时输出到系统 logcat（开发时用）
        when (level) {
            "E" -> android.util.Log.e("BaiCha-$tag", msg, tr)
            "W" -> android.util.Log.w("BaiCha-$tag", msg)
            "I" -> android.util.Log.i("BaiCha-$tag", msg)
            else -> android.util.Log.d("BaiCha-$tag", msg)
        }
    }

    /** 异步写文件 */
    private fun writeToFile(line: String) {
        writer.execute {
            try {
                val target = logFile?.takeIf { it.exists() || it.parentFile?.canWrite() == true }
                    ?: fallbackFile
                    ?: return@execute

                // 轮转检查
                if (target.exists() && target.length() > MAX_FILE_SIZE) {
                    rotate(target)
                }

                FileWriter(target, true).use { fw ->
                    fw.write(line)
                    fw.write("\n")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "write failed", e)
            }
        }
    }

    /** 日志轮转：当前文件 → .1，新建空文件 */
    private fun rotate(file: File) {
        try {
            val backup = File(file.parentFile, file.name + ".1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "rotate failed", e)
        }
    }

    // ==================== 读取 ====================

    /** 读取内存日志（最新在前） */
    fun readMemory(): List<String> {
        synchronized(memoryLog) {
            return memoryLog.reversed().toList()
        }
    }

    /** 读取文件日志（最后 n 行） */
    fun readFileLines(maxLines: Int = 500): List<String> {
        val target = logFile?.takeIf { it.exists() } ?: fallbackFile?.takeIf { it.exists() } ?: return emptyList()
        return try {
            val lines = target.readLines()
            if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 导出日志到指定路径 */
    fun export(destPath: String): Boolean {
        val target = logFile?.takeIf { it.exists() } ?: fallbackFile?.takeIf { it.exists() } ?: return false
        return try {
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            target.copyTo(dest, overwrite = true)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "export failed", e)
            false
        }
    }
}
