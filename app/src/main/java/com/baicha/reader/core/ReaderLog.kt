package com.baicha.reader.core

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 白茶读屏 —— 独立日志系统（v2：分级 + 分类 + 限流聚合）
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么不用系统 logcat】
 * ══════════════════════════════════════════════════════════
 *   ① MIUI 会过滤 Log.d/v，抓不到
 *   ② 多个读屏的日志混在一起，分不清
 *   ③ 系统 logcat 缓冲区滚动快，历史丢失
 *
 * ══════════════════════════════════════════════════════════
 * 【v2 要解决的三件事（真机 P0「左右滑动找不到焦点」的教训）】
 * ══════════════════════════════════════════════════════════
 *   ① **刷屏**：一秒内「焦点清除」64 行、「无障碍焦点」60 行；
 *      排查者被带偏，先怀疑了错误方向，浪费一轮。
 *   ② **分不清**：没有级别、没有分类，172 行里哪几行是关键靠人眼看。
 *   ③ **会崩**：日志文本进过 `onSaveInstanceState` 的 Bundle，
 *      3 208 220 bytes 超 Binder 上限 → `TransactionTooLargeException` 杀进程。
 *
 * 对策分别是：**限流聚合**（[LogLimiter]）、**级别 + 分类**（[LogLevel] /
 * [LogCategory]）、**有界环形缓冲**（[RateLimitedRingBuffer]）。
 * 完整设计见 `docs/日志系统设计.md`。
 *
 * ══════════════════════════════════════════════════════════
 * 【用法】
 * ══════════════════════════════════════════════════════════
 * ```kotlin
 * ReaderLog.init(context)
 *
 * // ① 旧写法照旧可用（向后兼容 125 处调用点），分类按 tag 自动推断
 * ReaderLog.d("FocusManager", "moveFocus NEXT")
 * ReaderLog.e("TTS", "init failed", throwable)
 *
 * // ② 关键路径显式分类（推荐）
 * ReaderLog.log(LogLevel.INFO, "BaiChaReader", "TTS 就绪", LogCategory.SPEECH)
 * ReaderLog.focus("下一焦点 | 通过 → 「Wi-Fi」")
 * ReaderLog.speech("播报: 你好")
 * ReaderLog.gesture("手势: 下滑")
 *
 * // ③ 筛选与导出
 * ReaderLog.readRecords(setOf(LogCategory.SPEECH))          // 只看 TTS（结构化）
 * ReaderLog.export(path, categories = setOf(LogCategory.FOCUS))  // 只导出焦点
 * ```
 *
 * ══════════════════════════════════════════════════════════
 * 【三条不可动摇的约束】
 * ══════════════════════════════════════════════════════════
 *   ① **热路径零重活**：`log()` 在无障碍交互线程上被调用。
 *      只做「读时钟 + 查限流表 + 写环形缓冲」，格式化与磁盘 I/O 全在
 *      `BaiChaLogWriter` 线程。被聚合掉的行**连字符串都不生成**。
 *   ② **有界**：内存缓冲 500 行、结构化缓冲 2000 条、限流表 512 项、
 *      文件 2 MB 轮转。四处都有上限，任何一处都不可能无限增长。
 *   ③ **不崩**：回调抛异常被吞、写盘失败被吞、执行器被关闭被吞。
 *      日志系统自己的故障**永远不许**冒泡到读屏主流程。
 */
object ReaderLog {

    private const val TAG = "ReaderLog"

    /** 日志文件名 */
    private const val LOG_FILE_NAME = "baicha-reader.log"

    /** 单文件最大 2MB，超过轮转 */
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L

    /** 内存缓冲最大条数 */
    const val MAX_MEMORY_LINES = 500

    /** 一条记录最多输出的异常栈帧数 */
    private const val MAX_STACK_LINES = 8

    /** 焦点追踪的默认 tag（与 [FocusTrace] 保持一致） */
    private const val TAG_FOCUS = "焦点"

    /** 语音日志的默认 tag */
    private const val TAG_SPEECH = "语音"

    /** 手势日志的默认 tag */
    private const val TAG_GESTURE = "手势"

    /**
     * 时间格式 —— **每线程一份**（`SimpleDateFormat` 非线程安全）
     *
     * ══════════════════════════════════════════════════════════
     * 【★ 本轮修复：原来是一个 static 单例，多线程共用会输出错乱时间】
     * ══════════════════════════════════════════════════════════
     *
     * `java.text.SimpleDateFormat` **不是线程安全的**：
     * 它用内部一个可变的 `Calendar` 字段做格式化，并发调用时互相踩。
     *
     * 【为什么在本项目是真问题】`ReaderLog.log()` 至少从三个线程被调用：
     *   ① 无障碍线程（`onAccessibilityEvent` 回调）
     *   ② 主线程（UI、手势、播报回调）
     *   ③ 写盘线程池
     * 而真机实测焦点抖动时**一秒 64 条日志** —— 三线程同时格式化完全可能。
     *
     * 【实测复现】（`SimpleDateFormatThreadSafetyTest`）
     * ```
     *   16 线程 × 20000 次、不同时间值：
     *     共享一个实例  → 错乱 224015 次
     *     每线程一实例  → 错乱 0 次
     * ```
     * 即**22 万次格式化给出了错误的时间字符串**。
     *
     * 【后果】日志里的时间戳不可信 —— 而排障时正是靠时间戳
     * 判断「谁先谁后」来还原因果。时间错乱会**推出完全相反的结论**
     * （目标里纪律③「不许被日志带偏」，前提是日志本身没错）。
     *
     * 【为什么用 ThreadLocal 而不是加锁】格式化在**热路径**上
     * （每条日志一次），加锁会让三线程互相等待；
     * `ThreadLocal` 零竞争，且线程池线程数固定、实例数有界。
     */
    private val timeFormat: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA) }

    /** 内存环形缓冲（App 内日志面板用；有上限，见 [MAX_MEMORY_LINES]） */
    private val memoryLog = ArrayDeque<String>(MAX_MEMORY_LINES)

    /**
     * 结构化事件缓冲 —— 与 [memoryLog] **同一批记录的两副面孔**
     *
     * 【为什么要同时留两份】
     *   面板要的是「一行行能读的文本」（[memoryLog]，String）；
     *   诊断要的是「能按分类/级别筛的结构」（本缓冲，LogRecord）。
     *   若只留文本，筛选就必须回头解析字符串（`contains("/SPEECH/")`），
     *   一旦格式变动筛选就静默失效 —— 项目里已经吃过「静默失效」的亏
     *   （见 `docs/静默失效审计-2026-10-02.md`）。
     *   两份都进同一段同步块，代价是每条约 40 字节引用。
     */
    private val records = RateLimitedRingBuffer()

    /**
     * 限流/聚合器
     *
     * 【为什么是实例而不是全局静态】便于单测直接构造、注入自定义时钟。
     */
    private val limiter = LogLimiter()

    /**
     * 聚合逐出总线 —— 让「被聚合掉的那些行」也能被外部感知
     *
     * 【为什么需要】限流把 64 条压成 1 条时，另外 63 条是**静默丢弃**的。
     * 诊断面板（以及将来的焦点追踪面板）需要在那一刻知道
     * 「刚才有 63 条同类被打掉了」—— 否则用户会以为日志丢帧。
     * 回调里拿到的是聚合摘要（如「焦点清除 x64（最近 1000ms）」），
     * 而不是 64 行原文，所以不会把面板重新刷爆。
     */
    private val flushers = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** 日志文件 */
    @Volatile
    private var logFile: File? = null

    /** 备用文件（外部存储不可用时用 App 私有目录） */
    @Volatile
    private var fallbackFile: File? = null

    /**
     * 异步写入线程
     *
     * 【为什么持引用而不是只用 Executors 返回值】
     * 合并写要读 `queue`：单线程执行器里，
     * 排在当前任务之后的任务必然还没执行，因此「前置队列」是安全的。
     * 另外单独持引用便于测试注入同步执行器。
     */
    @Volatile
    private var writer: ThreadPoolExecutor = newWriter()

    private fun newWriter(): ThreadPoolExecutor =
        ThreadPoolExecutor(
            1, 1,
            0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) { r -> Thread(r, "BaiChaLogWriter").apply { isDaemon = true } }

    /** 是否已初始化 */
    private val initialized = AtomicBoolean(false)

    /** 日志级别开关 —— 低于该级别的记录在入口就被丢弃 */
    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    /** 总开关（面板「暂停日志」用） */
    @Volatile
    var enabled: Boolean = true

    /** 兼容旧字段：`verbose == false` 等价于只保留 INFO 及以上 */
    var verbose: Boolean
        get() = minLevel.priority <= LogLevel.INFO.priority
        set(value) {
            minLevel = if (value) LogLevel.DEBUG else LogLevel.INFO
        }

    /**
     * 当前分类过滤集合（空 = 不过滤）
     *
     * 【为什么过滤放在展示侧而不是写入侧】
     *   写入侧过滤会让「关掉 TTS 分类」同时丢掉磁盘归档，
     *   真机排查往往正是事后才想起来要看某一类。
     *   这里的过滤只影响面板/导出，不影响文件与环形缓冲。
     */
    @Volatile
    var categoryFilter: Set<LogCategory> = emptySet()

    /** 日志更新回调（App 内面板用） */
    @Volatile
    var onLogUpdate: ((String) -> Unit)? = null

    /** 聚合摘要回调（含「被合并掉了多少条」） */
    fun addAggregationListener(listener: (String) -> Unit) {
        flushers.add(listener)
    }

    fun removeAggregationListener(listener: (String) -> Unit) {
        flushers.remove(listener)
    }

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
                "白茶读屏启动 ${timeFormat.get()!!.format(Date())}\n" +
                "=".repeat(70)
        writeToFile(startLine)

        log(LogLevel.INFO, "ReaderLog", "日志系统就绪，文件: ${currentLogPath()}", LogCategory.SYSTEM)
    }

    /** 当前日志文件路径 */
    fun currentLogPath(): String {
        return logFile?.absolutePath ?: fallbackFile?.absolutePath ?: "(未初始化)"
    }

    /** 清空日志 */
    fun clear() {
        synchronized(memoryLog) {
            memoryLog.clear()
            records.clear()
        }
        limiter.reset()
        try {
            logFile?.let { FileWriter(it, false).use { fw -> fw.write("") } }
            fallbackFile?.let { FileWriter(it, false).use { fw -> fw.write("") } }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "clear failed", e)
        }
        log(LogLevel.INFO, "ReaderLog", "日志已清空", LogCategory.SYSTEM)
    }

    // ==================== 日志方法 ====================

    /**
     * 兼容入口：旧的 `ReaderLog.d(tag, msg)`
     *
     * 【向后兼容】125 处调用点不需要改。级别与分类都由这里补齐：
     * 级别 = [LogLevel.DEBUG]，分类 = [LogCategory.classifyByTag] 按 tag 推断。
     * 想精确分类时用重载 `d(tag, msg, category)`，或直接用 [log]。
     */
    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg, null)

    /** 兼容入口：旧的 `ReaderLog.i(tag, msg)` → INFO */
    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg, null)

    /** 兼容入口：旧的 `ReaderLog.w(tag, msg)` → WARN */
    fun w(tag: String, msg: String) = log(LogLevel.WARN, tag, msg, null)

    /** 兼容入口：旧的 `ReaderLog.e(tag, msg, throwable)` → ERROR */
    fun e(tag: String, msg: String, tr: Throwable? = null) =
        log(LogLevel.ERROR, tag, msg, null, tr)

    /**
     * 带分类的日志 —— 新日志系统的入口（旧 [d]/[i]/[w]/[e] 保持可用，自动归类）
     *
     * 【为什么把分类做成可选参数而不是新方法名】
     *   全项目 125 处调用点。若改成 `ReaderLog.log(category, level, ...)`
     *   就必须一次性全改，风险大且与本轮「先建能力、再逐步迁移」的节奏冲突。
     *   默认参数让调用点可以**逐个**迁移：改一处就多一处分类，
     *   没改的落到 [LogCategory.classifyByTag] 的自动归类上，行为不变。
     *
     * 用法：
     * ```kotlin
     * ReaderLog.i("BaiChaReader", "TTS 就绪", LogCategory.SPEECH)
     * ReaderLog.focus("下一焦点", "通过 → 「Wi-Fi」")
     * ```
     */
    fun log(
        level: LogLevel,
        tag: String,
        msg: String,
        category: LogCategory? = null,
        tr: Throwable? = null
    ) {
        // 分级闸门放在最前面：低于门槛时**连分类推断都不做**，零成本丢弃
        if (!enabled || level.priority < minLevel.priority) return
        emit(LogRecord(level, category ?: LogCategory.classifyByTag(tag), tag, msg, tr))
    }

    /** 带分类的 DEBUG */
    fun d(tag: String, msg: String, category: LogCategory?) = log(LogLevel.DEBUG, tag, msg, category)

    /** 带分类的 INFO */
    fun i(tag: String, msg: String, category: LogCategory?) = log(LogLevel.INFO, tag, msg, category)

    /** 带分类的 WARN */
    fun w(tag: String, msg: String, category: LogCategory?) = log(LogLevel.WARN, tag, msg, category)

    /** 带分类的 ERROR */
    fun e(tag: String, msg: String, tr: Throwable?, category: LogCategory?) =
        log(LogLevel.ERROR, tag, msg, category, tr)

    // ---- 分类快捷方式：结构化关键路径用（焦点 / 语音 / 手势）----

    /** 焦点导航 —— 排查「这次滑动为什么没找到焦点」的主入口 */
    fun focus(msg: String, tag: String = TAG_FOCUS) = log(LogLevel.INFO, tag, msg, LogCategory.FOCUS)

    /** 焦点导航（DEBUG 级，用于被判定过滤掉的中间步骤） */
    fun focusDebug(msg: String, tag: String = TAG_FOCUS) =
        log(LogLevel.DEBUG, tag, msg, LogCategory.FOCUS)

    /** TTS 播报 */
    fun speech(msg: String, tag: String = TAG_SPEECH) = log(LogLevel.INFO, tag, msg, LogCategory.SPEECH)

    /** 手势触发 */
    fun gesture(msg: String, tag: String = TAG_GESTURE) =
        log(LogLevel.INFO, tag, msg, LogCategory.GESTURE)

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
     * 核心日志方法（新日志系统的唯一入口）
     *
     * 【为什么整条链路都没有锁】
     *   这个方法在**无障碍交互线程**上被调用（事件回调 / 手势 / 焦点导航）。
     *   所以热路径上必须做到：
     *     ① 一次时间读取（[uptimeMillis]，Android 上是无锁的 SystemClock）
     *     ② 一次 ConcurrentHashMap 查找（限流器，读多写少且无锁）
     *     ③ 一次 synchronized 的环形缓冲写入（纳秒级，无 I/O）
     *   真正的重活（格式化成文本、写磁盘）全部丢给 [writer] 线程。
     *
     * 【为什么限流在格式化之前】
     *   被聚合掉的第 2…N 条**不生成字符串**。
     *   这条日志刷屏时（一秒 64 条），性能收益是 64 倍。
     */
    private fun emit(record: LogRecord) {
        val now = uptimeMillis()

        // ① 限流/聚合 —— 命中则只累加计数，不产出任何文本
        //
        // 【★ 本轮修复：时间戳格式化移到限流判定**之后**】
        //
        // 原实现第一行就是 `val ts = wallClockText()`，而下面注释写着
        // 「被聚合掉的第 2…N 条**不生成字符串**，性能收益 64 倍」——
        // **注释与实现不符**：`wallClockText()` 对**每一条**日志都执行，
        // 包括那些本该被廉价丢弃的。
        //
        // 而 `wallClockText()` 是最贵的部分之一：`SimpleDateFormat.format()`
        // 要构造 `Date`、驱动 `Calendar` 计算、再拼接字符串。
        // 真机刷屏时一秒 64 条 ⇒ 64 次无用格式化。
        //
        // 移到判定之后，被聚合掉的行真正做到「零字符串产出」，
        // 与注释描述一致。
        val verdict = limiter.samplePattern(
            record.category,
            record.message,
            record.pattern,
            now
        )
        if (!verdict.emit) {
            // 被聚合掉的行**不进屏、不进文件**，但要让面板知道「刚才压掉了多少」
            safely { for (f in flushers) f(verdict.summary()) }
            return
        }

        // ② 格式化（只对确定要落盘的记录做）
        val ts = wallClockText()
        val line = format(record, ts)
        val summary = verdict.summary()

        // ③ 内存缓冲：文本 + 结构化，一次同步块写完（有界，绝不增长）
        synchronized(memoryLog) {
            memoryLog.addLast(line)
            while (memoryLog.size > MAX_MEMORY_LINES) memoryLog.removeFirst()
            records.add(record)
        }

        // ④ 回调（App 内面板）
        safely { onLogUpdate?.invoke(line) }
        safely { for (f in flushers) f(summary) }

        // ⑤ 异步写文件（唯一的重活，在别的线程）
        writeToFile(line)

        // ⑥ 同时输出到系统 logcat（开发时用）
        val logTag = "BaiCha-${record.tag}"
        when (record.level) {
            LogLevel.ERROR -> android.util.Log.e(logTag, record.message, record.throwable)
            LogLevel.WARN -> android.util.Log.w(logTag, record.message)
            LogLevel.INFO -> android.util.Log.i(logTag, record.message)
            LogLevel.DEBUG -> android.util.Log.d(logTag, record.message)
        }
    }

    /** 一条记录的完整文本（含异常栈，最多 [MAX_STACK_LINES] 帧） */
    private fun format(r: LogRecord, ts: String): String {
        val sb = StringBuilder(r.message.length + 96)
        sb.append('[').append(ts).append(']')
        sb.append(' ').append(r.level.name)
        sb.append('/').append(r.category.name)
        sb.append('/').append(r.tag)
        sb.append(" (").append(Thread.currentThread().name).append("): ")
        sb.append(r.message)

        val tr = r.throwable
        if (tr != null) {
            sb.append("\n  ⚠ ").append(tr.javaClass.simpleName).append(": ").append(tr.message)
            val frames = tr.stackTrace
            val n = if (frames.size > MAX_STACK_LINES) MAX_STACK_LINES else frames.size
            for (i in 0 until n) sb.append("\n      at ").append(frames[i].toString())
            if (frames.size > n) sb.append("\n      … 另有 ${frames.size - n} 帧")
        }
        return sb.toString()
    }

    /** 墙钟文本 —— 纯 JVM 单测没有 SimpleDateFormat 也没关系，失败时降级为单调秒 */
    private fun wallClockText(): String = try {
        timeFormat.get()!!.format(Date())
    } catch (e: Throwable) {
        (uptimeMillis() / 1000).toString()
    }

    /** 回调不能把日志系统自己拖崩 —— 面板抛异常不该影响读屏 */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "log callback failed", e)
        }
    }

    /**
     * 异步写文件
     *
     * ══════════════════════════════════════════════════════
     * 【为什么是「一个任务写一个 StringBuilder」而不是跨任务合并】
     * ══════════════════════════════════════════════════════
     *
     * 这里最初写的是「后一个任务把队列里前一个任务的文本偷出来一起写」，
     * 想省下几次 `FileWriter` 的 open/close。实测出现**静默丢日志**：
     *
     *   第一条写成功，第二条消失，第三条又能成功 —— 严格按 2 条丢 1 条。
     *   根因是「偷取方把被偷任务置 done，但偷取方自己是否真的写出去
     *   取决于它当时拿到的文本与目标文件」，两者一旦不同步，
     *   被偷的那条就永远没人写。
     *
     * 这个 bug 之所以危险，是因为它**只在连续写入时出现**，
     * 且排查日志时看到的正是「某几条莫名不见了」——
     * 与「日志系统本身坏了」的表现完全一样。
     *
     * 所以现在的取舍是：**放弃跨任务合并，换取「每一行都由持有它的任务写」**。
     * 代价是每条日志一次 file open/close；但这条路径在
     * `BaiChaLogWriter` 单线程上，不在无障碍交互线程上，
     * 与「日志不能拖慢读屏」的要求不冲突。
     *
     * 【还会合并吗】会 —— 合并发生在 [emit] 层：
     * 同一窗口内的重复日志在**进入写队列之前**就已被限流器聚合成一条，
     * 真正的刷屏在源头就被压掉了，不需要靠写盘合并去救。
     */
    private fun writeToFile(line: String) {
        try {
            writer.execute(WriteTask(line))
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 执行器被关闭（进程退出 / 测试拆场）。此时**绝不能抛** ——
            // 这一行日志已经在内存缓冲里了，面板仍看得到，
            // 但把 RejectedExecutionException 抛回无障碍交互线程会直接搞崩服务。
            // 真实崩溃案例：注入的执行器在测试 tearDown 里 shutdown 之后，
            // 后续测试类再打日志就全线失败。
        }
    }

    /** 一次写盘任务 —— 自己持有自己的文本，不与其他任务共享所有权 */
    private class WriteTask(private val line: String) : Runnable {
        override fun run() = writeLine(line)
    }

    /**
     * 真正落盘
     *
     * 【失败时的行为】目标不可用（未初始化 / 外部存储被拔）时**静默返回**：
     * 这时日志只留在内存缓冲里，仍有 [readMemory] / 面板可用。
     * 绝不让写盘失败冒泡 —— 它是后台线程，抛出去只会变成未捕获异常。
     */
    private fun writeLine(line: String) {
        try {
            val target = currentTarget() ?: return
            if (target.exists() && target.length() + line.length + 1 > MAX_FILE_SIZE) {
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

    /** 当前要写的文件；都不可用时返回 null */
    private fun currentTarget(): File? =
        logFile?.takeIf { it.exists() || it.parentFile?.canWrite() == true } ?: fallbackFile

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

    // ==================== 读取 / 筛选 / 导出 ====================

    /** 读取内存日志（最新在前，应用 [categoryFilter]） */
    fun readMemory(): List<String> {
        val all = synchronized(memoryLog) { memoryLog.toList() }
        return all.asReversed().filter { matchesLineFilter(it) }
    }

    /** 按分类读取内存日志（最新在前） */
    fun readByCategory(vararg categories: LogCategory): List<String> =
        filterLines(synchronized(memoryLog) { memoryLog.toList() }, categories.toList())

    /** 按级别读取内存日志（该级别及以上，最新在前） */
    fun readByLevel(min: LogLevel): List<String> {
        val all = synchronized(memoryLog) { memoryLog.toList() }
        return all.asReversed().filter { line ->
            val lv = levelOfLine(line) ?: return@filter true
            lv.priority >= min.priority
        }
    }

    // ---- 结构化存取（按分类筛选的首选路径）----

    /** 事件缓冲容量（条），恒定不增长 */
    fun recordCapacity(): Int = records.capacity

    /** 当前存活的记录条数 */
    fun recordCount(): Int = records.size

    /** 因环形覆盖而丢失的历史条数 */
    fun recordDroppedTotal(): Long = records.droppedTotal

    /** 每个分类各多少条 —— 面板的「分类计数」用 */
    fun countsByCategory(): Map<LogCategory, Int> = records.countsByCategory()

    /**
     * 按分类 + 最低级别取**结构化记录**
     *
     * 【为什么结构化筛选是首选】不需要解析文本，格式怎么改都不会失效。
     */
    fun readRecords(
        categories: Set<LogCategory> = emptySet(),
        minLevel: LogLevel = LogLevel.DEBUG,
        limit: Int = 0
    ): List<LogRecord> = records.filter(categories, minLevel, limit)

    /** 把结构化记录渲染成文本（面板「只看 TTS」用） */
    fun renderRecords(
        categories: Set<LogCategory> = emptySet(),
        minLevel: LogLevel = LogLevel.DEBUG,
        limit: Int = 0,
        compact: Boolean = false
    ): List<String> = readRecords(categories, minLevel, limit).map { it.render(compact) }

    /**
     * 纯过滤函数 —— 供单测与面板共用
     *
     * 【为什么单独抽出来】过滤规则是「只看 TTS」这类需求的全部逻辑。
     * 抽成纯函数才能在没有 Android 环境时把边界（缺分类片段、未知级别）测干净。
     */
    fun filterLines(lines: List<String>, categories: List<LogCategory>): List<String> {
        if (categories.isEmpty()) return lines.reversed()
        val wanted = categories.toSet()
        return lines.filter { line ->
            val c = categoryOfLine(line) ?: return@filter false
            c in wanted
        }.reversed()
    }

    /** 从一行日志里解析分类；解析不出返回 null（未分类的老记录） */
    fun categoryOfLine(line: String): LogCategory? {
        for (c in LogCategory.entries) {
            if (line.contains("/${c.name}/")) return c
        }
        return null
    }

    /** 从一行日志里解析级别；解析不出返回 null */
    fun levelOfLine(line: String): LogLevel? {
        for (l in LogLevel.entries) {
            if (line.contains("] ${l.name}/")) return l
        }
        return null
    }

    private fun matchesLineFilter(line: String): Boolean {
        val want = categoryFilter
        if (want.isEmpty()) return true
        val c = categoryOfLine(line) ?: return false
        return c in want
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

    /** 导出日志到指定路径（完整文件） */
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

    /**
     * 按分类/级别导出 —— 「只看 TTS」时不能把整个文件（几十万行）交出去
     *
     * @param categories 需要的分类，空集合 = 不过滤
     * @param minLevel   最低级别
     * @param maxLines   最多导出行数（从文件尾部往回取）
     */
    fun export(
        destPath: String,
        categories: Set<LogCategory> = emptySet(),
        minLevel: LogLevel = LogLevel.DEBUG,
        maxLines: Int = 5000
    ): Boolean {
        val target = logFile?.takeIf { it.exists() } ?: fallbackFile?.takeIf { it.exists() } ?: return false
        return try {
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            val picked = target.readLines()
                .filter { line ->
                    (categories.isEmpty() || categoryOfLine(line)?.let { it in categories } == true) &&
                        (levelOfLine(line)?.priority ?: Int.MAX_VALUE) >= minLevel.priority
                }
                .takeLast(maxLines)
            dest.writeText(picked.joinToString("\n"))
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "filtered export failed", e)
            false
        }
    }

    /**
     * 当前被聚合压着、尚未输出的重复条数
     *
     * 【为什么暴露它】面板可据此显示「此刻有 63 条同类被压着」，
     * 让「日志变少了」不等于「日志丢了」。
     */
    fun pendingSuppressed(): Int = limiter.pendingSuppressed()

    /** 当前限流表的规模（诊断用；有上限，不会无限增长） */
    fun rateWindowStats(): String = limiter.stats()

    /** 重置限流状态（测试与「开始新一轮排查」用） */
    fun resetLimiter() = limiter.reset()

    // ==================== 测试支持 ====================

    /**
     * 注入同步执行器 —— 让单测可以确定性地取到文件内容
     *
     * 【为什么不直接暴露内部状态】测试要验证的是「写盘链路真的走通了」，
     * 注入一个在当前线程执行的执行器即可，不必等后台线程。
     */
    internal fun setWriterForTest(executor: ThreadPoolExecutor) {
        writer = executor
    }

    /** 清空内存缓冲（测试用；不碰文件） */
    internal fun clearMemoryForTest() {
        synchronized(memoryLog) {
            memoryLog.clear()
            records.clear()
        }
    }

    /** 当前内存缓冲的原始行（测试用；绕过 [categoryFilter]） */
    internal fun memoryLinesForTest(): List<String> =
        synchronized(memoryLog) { memoryLog.toList() }

    /**
     * 重置为「测试用」的干净状态
     *
     * 【为什么要这样一个入口】单测不能依赖执行顺序：
     * `ReaderLog` 是 object，内存缓冲、限流表、开关都会在测试间残留。
     * 这个方法把**所有可变状态**一次清干净，并可选指定日志文件
     * （传 null 表示「未初始化」，用于验证初始化前的降级行为）。
     */
    internal fun resetForTest(file: File?) {
        synchronized(memoryLog) {
            memoryLog.clear()
            records.clear()
        }
        limiter.reset()
        flushers.clear()
        onLogUpdate = null
        minLevel = LogLevel.DEBUG
        enabled = true
        categoryFilter = emptySet()
        logFile = file
        fallbackFile = null
        if (file != null) {
            file.parentFile?.mkdirs()
            file.writeText("")
        }
    }

    /**
     * 清空所有缓冲并重置限流器
     *
     * ══════════════════════════════════════════════════════
     * 【为什么「清日志」必须同时清限流器】
     * ══════════════════════════════════════════════════════
     *
     * 限流器是**有状态**的：它记得「这条模式在这个窗口里已经输出过了」。
     * 如果只清缓冲不清它，就会出现这样一条极难排查的静默失效：
     *
     *   ① 用户点「清空日志」→ 缓冲空了、文件空了
     *   ② 同一秒内那类日志又来了（例如「焦点清除」正在抖动）
     *   ③ 限流器认为「这个窗口已经输出过」，于是**什么都不输出**
     *   ④ 用户面对一个空面板，以为读屏卡死了
     *
     * 单测把这条路径打出来了（`filtered_export...` 在整套跑时失败、
     * 单跑却通过）—— 所以这里不是「为了测试」而重置，
     * 而是**清空语义本身**要求的：清空 = 从零开始观察。
     */
    fun clearBuffers() {
        synchronized(memoryLog) {
            memoryLog.clear()
            records.clear()
        }
        limiter.reset()
    }
}
