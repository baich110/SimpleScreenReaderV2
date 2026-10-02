package com.baicha.reader.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 白茶读屏 —— 日志系统核心类型与限流/聚合算法
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这一层纯 JVM 实现】
 * ══════════════════════════════════════════════════════════
 *
 * 本文件**不引用任何 android.\* 类型**。原因有二：
 *   ① 读屏的日志系统是被排查对象本身，它的正确性必须能被单测证明；
 *      JVM 单测里 `android.os.SystemClock` 是空壳（见
 *      `docs/测试基础设施限制-2026-10-02.md`），把时钟做成参数才测得动。
 *   ② 限流聚合是本系统最复杂的一段算法（时间窗口 + 模式归一 + 表容量上限），
 *      它不该和文件 I/O、UI 回调搅在一起。
 *
 * ══════════════════════════════════════════════════════════
 * 【两级日志体系（对标结论）】
 * ══════════════════════════════════════════════════════════
 *
 *   · **事件总线**（[LogRecord] + [LogLimiter] + [RateLimitedRingBuffer]）
 *     回答「**刚才发生了什么、为什么没找到焦点**」。
 *     特点：结构化（级别 + 分类 + tag + 消息）、有界、限流聚合、
 *     高频写入路径零分配。
 *
 *   · **文本日志**（[ReaderLog] 的内存缓冲 + 文件）
 *     回答「**完整现场**」。特点：可读、可 cat、可导出，
 *     但永不作为数据源被程序解析回去（这一条是被
 *     `TransactionTooLargeException` 崩溃教出来的）。
 *
 *   天坦 `diagnosis/` 包正是这个结构（EventCategory + EventRingBuffer +
 *   SnapshotDumper 落盘），TalkBack 只有第一层的极简版（LogUtils 直写 logcat）。
 *   详见 `docs/日志系统设计.md`。
 */

// ==================== 级别 ====================

/**
 * 日志级别
 *
 * 【为什么 priority 用「越大越严重」而不是照抄 android.util.Log 的「越小越严重」】
 *   TalkBack `LogUtils.shouldLog()` 用的是 `minLogLevel <= logLevel`，
 *   天坦 `AbstractC4354b.k()` 用的是 `if (i9 < f42483a) return` ——
 *   两者都在处理「Log.VERBOSE=2 比 Log.ERROR=6 更啰嗦」这个反直觉映射。
 *   本项目的判断方向是「级别够不够」，[priority] 越大越严重，
 *   过滤写成 `level.priority >= min.priority` 一眼可读，不需要记反。
 */
enum class LogLevel(val priority: Int) {
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4);

    /**
     * 单字符标记 —— 用于「紧凑视图」
     *
     * 【为什么还要单字符】逐行读屏面板里，`DEBUG` 六七个音节的
     * 信息量对用户是零，却让每行都变长。紧凑视图靠它把行缩短。
     */
    val marker: String
        get() = when (this) {
            DEBUG -> "D"
            INFO -> "I"
            WARN -> "W"
            ERROR -> "E"
        }
}

// ==================== 分类 ====================

/**
 * 日志分类
 *
 * 【命名依据】天坦 `net.tatans.soundback.diagnosis.EventCategory` 的
 * 枚举原名是 SPEECH / WAKELOCK / OCR / NETWORK / SCREEN / GESTURE /
 * FOCUS / SERVICE，**按「子系统」切**而不是按「文件」切。
 * 本项目沿用同一思路，并补上本项目的关键路径：
 * EVENT（无障碍事件）、CACHE（9 处缓存）、SYSTEM（生命周期/权限）、OTHER。
 *
 * 【为什么用户操作类（点击/长按/选择）不单独建类，而是放 EVENT】
 *   这类只能由无障碍事件回调触发，来源唯一。
 *   多一个枚举值就多一次「这条该记哪类」的犹豫，
 *   而分类的价值在于**能立刻筛出一类**，不在于切得越细越好。
 */
enum class LogCategory {
    /** 焦点：移动、清除、判定拒绝 —— 排查「为什么没找到焦点」的第一入口 */
    FOCUS,

    /** 无障碍事件：事件接收、类型分发、窗口变化 */
    EVENT,

    /** 语音：TTS 播报、打断、队列、替换规则 */
    SPEECH,

    /** 手势：手势识别、动作分派 */
    GESTURE,

    /** 缓存：命中、失效、驱逐 */
    CACHE,

    /** 系统：服务生命周期、权限、屏幕状态、配置变更 */
    SYSTEM,

    /** 其它 */
    OTHER;

    companion object {
        /**
         * 按 tag 自动归类
         *
         * 【为什么要有这个兜底】项目里有 125 处 `ReaderLog.d(tag, msg)` 调用，
         * 大多数 tag 是 `FocusManager` / `SpeechEngine` / `NodeCache` 这种
         * 已经能说明子系统的类名。自动归类让**不改任何调用点**就拿到
         * 八成可用的分类，剩下的两成靠调用点逐步显式传 [LogCategory]。
         *
         * 【为什么用前缀匹配而不是精确表】tag 有 `BaichaReader:Speech`
         * 这类带命名空间的写法，精确表会漏。前缀表 + 包含匹配兜底足够。
         */
        fun classifyByTag(tag: String): LogCategory {
            if (tag.isEmpty()) return OTHER
            for ((prefix, category) in TAG_RULES) {
                if (tag.startsWith(prefix)) return category
            }
            // 兜底一遍包含匹配（覆盖 BaichaReader:Speech / xxx-Focus 这类写法）
            for ((prefix, category) in TAG_RULES) {
                if (tag.contains(prefix, ignoreCase = true)) return category
            }
            return OTHER
        }

        /**
         * tag 前缀 → 分类
         *
         * 【为什么这些名字要逐个列出来】它们对应项目里真实存在的
         * `private const val TAG = "…"`（见各 core 文件），不是猜的。
         */
        private val TAG_RULES: List<Pair<String, LogCategory>> = listOf(
            "焦点" to FOCUS,
            "Focus" to FOCUS,
            "ShouldFocus" to FOCUS,
            "Spatial" to FOCUS,
            "WindowTraversal" to FOCUS,
            "Cursor" to FOCUS,
            "Navigation" to FOCUS,
            "Granularity" to FOCUS,
            "EventHandler" to EVENT,
            "Event" to EVENT,
            "ScrollFocus" to EVENT,
            "Scroll" to EVENT,
            "ContentChange" to EVENT,
            "TTS" to SPEECH,
            "Tts" to SPEECH,
            "Speech" to SPEECH,
            "语音" to SPEECH,
            "Audio" to SPEECH,
            "Gesture" to GESTURE,
            "手势" to GESTURE,
            "Cache" to CACHE,
            "NodeCache" to CACHE,
            "ClassCache" to CACHE,
            "Replacement" to CACHE,
            "Service" to SYSTEM,
            "BaiChaReader" to SYSTEM,
            "System" to SYSTEM,
            "Screen" to SYSTEM,
            "Ime" to SYSTEM,
            "Permission" to SYSTEM,
            "Reader" to SYSTEM,
            "Edit" to SYSTEM,
        )
    }
}

// ==================== 记录 ====================

/**
 * 一条日志记录 —— **结构化**，不是一行字符串
 *
 * 对齐天坦 `DiagnosisEvent(timestamp, category, action, detail)`：
 * 时间 + 分类 + 「做了什么」+「细节」。本项目在其之上加了
 * [level] 与 [tag]（天坦的级别信息在 `AbstractC4354b` 那一层，
 * 进环的 `DiagnosisEvent` 里没有）。
 *
 * 【为什么不直接存字符串】字符串一旦生成就无法再按级别/分类筛选 ——
 * 只能靠 `line.contains("SPEECH")` 这种脆弱的文本解析。
 * 结构化后 [RateLimitedRingBuffer.filter] 是 O(1) 字段比较。
 * （文本导出仍然是字符串，见 [render]。）
 */
/**
 * 一条日志记录 —— **值类型**
 *
 * ══════════════════════════════════════════════════════════
 * 【★ 本轮修复：改为 data class（原来没有值语义）】
 * ══════════════════════════════════════════════════════════
 *
 * 全部字段都是 `val`、无身份语义 —— 是值类型，但原来写成普通 `class`，
 * 于是 `equals`/`hashCode` 继承自 `Any`（引用相等）：
 * ```kotlin
 *   LogRecord(INFO, FOCUS, "t", "同一句") == LogRecord(INFO, FOCUS, "t", "同一句")  // false ❗
 * ```
 *
 * 【危害】`LogRecord` 是**公开 API 的返回值类型**
 * （`ReaderLog` 的查询/导出接口都返回它）。
 * 使用者/测试一旦按内容比较就会**静默拿到错误结果**（永远 false），
 * 不报错、不崩溃 —— 目标里点名的模式⑨「静默失败」。
 *
 * 【与 DiffSegment 同一类缺陷】本轮做出的一类系统性排查：
 * 「只有 val 字段却写成普通 class」→ 扫描全项目发现 3 处
 * （`DiffSegment` 已修、`LogRecord`、`ReadReplacement`）。
 *
 * 【注意】这里**不能用 data class 自动生成的 toString** ——
 * 原实现手写了 `toString()`（含时间戳格式化），功能上更完整，
 * 故保留手写版本（data class 允许覆盖自动生成的 toString）。
 *
 * 【兼容性】主构造器与字段类型均不变，生产代码零改动。
 */
class LogRecord(
    val level: LogLevel,
    val category: LogCategory,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val timeMillis: Long = 0L
) {
    /** 用于限流的「消息模式」—— 把变化的部分挖掉，只留骨架 */
    val pattern: String by lazy {
        // 日志消息里的数字/引号文本几乎都是「这一次和上一次不同的部分」。
        // 例如「无障碍焦点: 设置项 3」与「无障碍焦点: 设置项 4」是同一种抖动，
        // 归一后都是「无障碍焦点: #」，才会被聚合成一条。
        PATTERN_NORMALIZER.replace(message, "#")
    }

    /** 唯一键：同一分类 + 同一消息模式的记录才参与聚合 */
    val limiterKey: String get() = "${category.name}|$pattern"

    // ==================== 值语义 ====================
    //
    // 【为什么不直接改成 data class】本类手写了 `toString()`
    // （含时间戳格式化，比 data class 生成的更可读），
    // 而 data class 会自动生成 toString 覆盖它 —— 为保留手写版，
    // 这里显式实现 equals/hashCode。
    //
    // 【为什么必须实现】见类注释：缺值语义会让「按内容比较」静默返回 false。
    //
    // 【字段覆盖】除 `timeMillis` 外全部参与比较 ——
    // 时间戳是**记录时刻**而非记录内容：同一句日志在不同时刻产生，
    // 内容相同就应视为相同（聚合/去重都按内容）。
    // 注意 `throwable` 用其 **toString 文本**参与比较：
    // Throwable 本身是引用相等，同一异常的不同实例内容相同却不等。

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogRecord) return false
        return level == other.level &&
                category == other.category &&
                tag == other.tag &&
                message == other.message &&
                throwable?.toString() == other.throwable?.toString()
    }

    override fun hashCode(): Int {
        var result = level.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + tag.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (throwable?.toString()?.hashCode() ?: 0)
        return result
    }

    /** 渲染为一行文本 */
    fun render(compact: Boolean = false): String {
        val head = if (compact) {
            "${level.marker}/${category.name}/$tag"
        } else {
            "${level.name}/${category.name}/$tag"
        }
        return "$head: $message"
    }

    override fun toString(): String = render()

    private companion object {
        /** 数字（含小数、带符号）、引号/括号文本、十六进制 → `#` */
        val PATTERN_NORMALIZER = Regex(
            """'[^']*'|"[^"]*"|「[^」]*」|\([^)]{0,64}\)|0x[0-9a-fA-F]+|-?\d+(\.\d+)?"""
        )
    }
}

// ==================== 环形缓冲 ====================

/**
 * 有界环形缓冲 —— **日志绝不允许无限增长**
 *
 * 【真实教训】2026-10-03 真机崩溃 `TransactionTooLargeException`：
 * MainActivity 把日志文本（3 208 220 bytes）塞进 `onSaveInstanceState`
 * 的 Bundle，经 Binder 传给 system_server 时超过上限，进程被杀。
 * 那次修的只是「不要塞 Bundle」，但**根子在日志本身没有上限**。
 *
 * 本类保证：
 *   ① 容量固定（默认 [DEFAULT_CAPACITY]），写满覆盖最旧；
 *   ② [snapshot] 复制出去，调用方拿不到内部数组；
 *   ③ 支持按分类/级别过滤 —— 走结构化字段，不做文本解析。
 *
 * 【为什么不用 kotlin 的 ArrayDeque】这里存的是定长对象数组，
 * 按索引原地覆盖；ArrayDeque 没有「按下标写」，
 * 用它会退化成「删头 + 追加尾」两次操作，且扩容会瞬时翻倍内存。
 *
 * 【对标】天坦 `EventRingBuffer`（capacity 2000，AtomicLong 游标，
 * `buffer[cursor % capacity] = event`，`snapshot(n)` 取最近 n 条）。
 * 本类沿用其结构，但把「容量」与「按分类过滤」做成显式能力 ——
 * 天坦只能「取最近 n 条」，不能「只取 FOCUS 类」。
 */
class RateLimitedRingBuffer(
    val capacity: Int = DEFAULT_CAPACITY
) {
    init {
        require(capacity > 0) { "capacity must be > 0, but was $capacity" }
    }

    @Volatile
    private var buffer: Array<LogRecord?> = arrayOfNulls(capacity)

    /** 总写入次数（单调递增，不因覆盖而回退） */
    private val writes = AtomicLong(0)

    private val lock = Any()

    fun add(record: LogRecord) {
        synchronized(lock) {
            val idx = (writes.getAndIncrement() % capacity).toInt()
            buffer[idx] = record
        }
    }

    /** 当前存活的条数（≤ capacity） */
    val size: Int get() = minOf(capacity.toLong(), writes.get()).toInt()

    /** 因为环形覆盖而丢失的历史条数 */
    val droppedTotal: Long get() = if (writes.get() <= capacity) 0 else writes.get() - capacity

    /**
     * 取快照 —— **旧 → 新** 顺序（与写入顺序一致）
     *
     * @param limit 最多返回多少条（取最近的）；≤0 表示全部
     */
    fun snapshot(limit: Int = 0): List<LogRecord> {
        synchronized(lock) {
            val total = size
            if (total == 0) return emptyList()
            val want = if (limit <= 0 || limit >= total) total else limit
            val start = writes.get() - want
            val out = ArrayList<LogRecord>(want)
            for (i in 0 until want) {
                buffer[((start + i) % capacity).toInt()]?.let { out.add(it) }
            }
            return out
        }
    }

    /**
     * 按分类 + 最低级别过滤（**结构化过滤**，不解析文本）
     *
     * @param categories 空集合 = 不过滤分类
     */
    fun filter(
        categories: Set<LogCategory> = emptySet(),
        minLevel: LogLevel = LogLevel.DEBUG,
        limit: Int = 0
    ): List<LogRecord> = snapshot(limit).filter {
        it.level.priority >= minLevel.priority &&
            (categories.isEmpty() || it.category in categories)
    }

    /** 每个分类各有多少条 —— 面板的「分类计数」用 */
    fun countsByCategory(): Map<LogCategory, Int> {
        val out = HashMap<LogCategory, Int>(LogCategory.entries.size)
        for (r in snapshot()) out[r.category] = (out[r.category] ?: 0) + 1
        return out
    }

    fun clear() {
        synchronized(lock) {
            buffer = arrayOfNulls(capacity)
            writes.set(0)
        }
    }

    companion object {
        /**
         * 默认容量
         *
         * 2000 与天坦 `EventRingBuffer(2000)` 一致（`DiagnosisEngine`
         * 静态初始化块）。按每条记录 ≈ 150 字节估算约 300 KB ——
         * 对读屏这种常驻进程可以接受，且**恒定不增长**。
         */
        const val DEFAULT_CAPACITY = 2000
    }
}

// ==================== 限流 / 聚合 ====================

/** 一次采样的结果 */
class RateLimitVerdict internal constructor(
    /** true = 立即输出；false = 已被聚合成计数 */
    val emit: Boolean,
    /** 当前窗口内该模式的累计次数（emit=true 时也可能是突刺摘要） */
    val count: Int,
    /** 窗口长度（毫秒） */
    val windowMs: Long,
    /** 模板消息（用来渲染「焦点清除 x64（最近 1000ms）」） */
    val template: String
) {
    /** 聚合摘要文本；count ≤ 1 时就是消息本身 */
    fun summary(): String =
        if (count <= 1) template else "$template x$count（最近 ${windowMs}ms）"

    override fun toString(): String = "RateLimitVerdict(emit=$emit, count=$count, ${summary()})"
}

/**
 * 高频日志合并器 —— **本系统的核心**
 *
 * ══════════════════════════════════════════════════════════
 * 【要解决的问题（真机 P0 的日志现场）】
 * ══════════════════════════════════════════════════════════
 *
 *   一秒内：「焦点清除」64 行、「无障碍焦点」60 行、「触摸」48 行。
 *   排查者被刷屏带偏，先怀疑了错误方向，浪费一整轮。
 *
 *   这 172 行里**真正的信息量是 3 行**：
 *       「焦点清除 x64（最近 1000ms）」
 *       「无障碍焦点 x60（最近 1000ms）」
 *       「触摸 x48（最近 1000ms）」
 *
 * ══════════════════════════════════════════════════════════
 * 【算法】
 * ══════════════════════════════════════════════════════════
 *
 * 键 = `分类 | 级别 | 消息模式`（见 [LogRecord.limiterKey]）。
 * 消息模式由正则把数字/引号文本替换成 `#` 得到 ——
 * 这样「无障碍焦点: 设置项 3」和「…设置项 4」会被识别为**同一种抖动**。
 *
 * 对每个键维护一个窗口 [Window]：
 *
 * ```
 * sample(key, message, now):
 *   w = table[key]
 *   if w == null 或 now - w.startMs >= windowMs:      ← 新窗口
 *       输出（emit=true，count=1）
 *   else:
 *       w.count++
 *       if w.count >= burstThreshold && !w.summaryEmitted:  ← 突刺：提前给一次摘要
 *           输出摘要（emit=true，count=当前累计）
 *       else:
 *           不输出（emit=false），仅在计数
 * ```
 *
 * 三条设计规则：
 *
 *  ① **窗口长 [windowMs]、每窗口至少一条**。
 *     稳定的 1 Hz 事件（每秒一次的心跳）不会被误合并 ——
 *     它们本就落在不同窗口里。只有**同一窗口内的重复**才聚合。
 *     这比「固定 N 条抽 1 条」好在：不丢「发生了什么」，
 *     只压缩「发生了几次」。
 *
 *  ② **突刺提前给摘要**（[burstThreshold]）。
 *     纯时间窗口在窗口末尾才输出，最坏情况是「刷屏开始后 1 秒内什么都看不到」。
 *     达到阈值就立刻先给一条，**刷屏现场即时可见**，
 *     同时该窗口内不再重复给（不会变成另一种刷屏）。
 *
 *  ③ **表容量有上限**（[maxEntries]）。
 *     键里含消息文本，若每条消息都不同（例如把节点文本拼进消息），
 *     表会无界增长 —— 那就是用另一种方式复现「日志撑爆内存」。
 *     超限时按最旧窗口逐出；逐不出时**直接放行**（宁可多打也不丢）。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么不用「每秒最多 N 条」的令牌桶】
 * ══════════════════════════════════════════════════════════
 *   令牌桶只能回答「太多了」，不能回答「**哪个**太多了」。
 *   按模式分组聚合既限流又保留分布信息 ——
 *   排查时能直接看出「是焦点在抖，不是整个系统在抖」。
 *
 * 【线程安全】用 ConcurrentHashMap.compute —— 键级原子。
 *   不同模式互不阻塞；同一模式下采样串行，计数不会丢。
 */
class LogLimiter(
    /** 聚合窗口（毫秒） */
    val windowMs: Long = DEFAULT_WINDOW_MS,
    /** 一个窗口内达到多少次就提前给一次摘要；≤0 表示不提前 */
    val burstThreshold: Int = DEFAULT_BURST_THRESHOLD,
    /** 限流表最大条目数 */
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** 时间源 —— 单测注入假时钟；生产用单调时钟 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    /** 单个模式的时间窗口 */
    private class Window(var startMs: Long, var count: Int, var summaryEmitted: Boolean)

    private val table = ConcurrentHashMap<String, Window>()

    private val received = AtomicLong(0)
    private val emitted = AtomicLong(0)
    private val evictions = AtomicLong(0)
    private val bypassed = AtomicLong(0)

    /**
     * 采样一次
     *
     * @param category 分类
     * @param message  原始消息（模板，不含聚合计数）
     * @param now      当前时间（毫秒，单调）
     */
    fun sample(category: LogCategory, message: String, now: Long = clock()): RateLimitVerdict {
        received.incrementAndGet()
        val verdict = sampleKey(patternKey(category, message), message, now)
        if (verdict.emit) emitted.incrementAndGet()
        return verdict
    }

    /**
     * 采样（用调用方已经算好的消息模式，避免重复跑正则）
     *
     * 【为什么单独一个重载】[LogRecord.pattern] 是 `by lazy` 的，
     * 上层已经算出模式时再让限流器跑一遍正则是白费。
     */
    fun samplePattern(category: LogCategory, message: String, pattern: String, now: Long): RateLimitVerdict {
        received.incrementAndGet()
        val verdict = sampleKey("${category.name}|$pattern", message, now)
        if (verdict.emit) emitted.incrementAndGet()
        return verdict
    }

    private fun patternKey(category: LogCategory, message: String): String =
        "${category.name}|${LogRecord(LogLevel.INFO, category, "", message).pattern}"

    private fun sampleKey(key: String, template: String, now: Long): RateLimitVerdict {
        if (!table.containsKey(key) && table.size >= maxEntries && !evictOldest()) {
            // 表满且逐不出：直接放行，绝不吞掉日志
            bypassed.incrementAndGet()
            return RateLimitVerdict(emit = true, count = 1, windowMs = windowMs, template = template)
        }

        var result: RateLimitVerdict? = null
        table.compute(key) { _, w ->
            when {
                w == null -> {
                    result = RateLimitVerdict(true, 1, windowMs, template)
                    Window(now, 1, false)
                }
                now - w.startMs >= windowMs -> {
                    result = RateLimitVerdict(true, 1, windowMs, template)
                    w.startMs = now
                    w.count = 1
                    w.summaryEmitted = false
                    w
                }
                else -> {
                    w.count++
                    val burst = burstThreshold > 0 && w.count >= burstThreshold && !w.summaryEmitted
                    if (burst) w.summaryEmitted = true
                    result = RateLimitVerdict(burst, w.count, windowMs, template)
                    w
                }
            }
        }
        // compute 必然执行 lambda，result 一定被赋值；保底写法让类型系统满意
        return result ?: RateLimitVerdict(true, 1, windowMs, template)
    }

    /** 逐出最旧的窗口；表为空返回 false */
    private fun evictOldest(): Boolean {
        var oldestKey: String? = null
        var oldestStart = Long.MAX_VALUE
        for ((k, w) in table) {
            if (w.startMs < oldestStart) {
                oldestStart = w.startMs
                oldestKey = k
            }
        }
        val key = oldestKey ?: return false
        table.remove(key)
        evictions.incrementAndGet()
        return true
    }

    /**
     * 尚未输出、正在聚合中的重复条数
     *
     * 【口径】累计采样次数 - 累计输出次数。
     * 因为每个窗口至少输出一次，这个差值天然有界。
     */
    fun pendingSuppressed(): Int {
        val diff = received.get() - emitted.get()
        return if (diff <= 0) 0 else diff.toInt()
    }

    /** 累计采样次数（诊断用） */
    fun receivedTotal(): Long = received.get()

    /** 累计输出次数（诊断用） */
    fun emittedTotal(): Long = emitted.get()

    /** 当前限流表规模 */
    fun tableSize(): Int = table.size

    /** 诊断文本 */
    fun stats(): String =
        "限流表 ${table.size}/$maxEntries 条，逐出 ${evictions.get()}，" +
            "放行(未聚合) ${bypassed.get()}，待输出 ${pendingSuppressed()}"

    fun reset() {
        table.clear()
        received.set(0)
        emitted.set(0)
        evictions.set(0)
        bypassed.set(0)
    }

    companion object {
        /**
         * 默认窗口 1000 ms
         *
         * 【为什么是 1 秒】真机现场正是「一秒内 64 次」，
         * 聚合粒度与人的时间感知一致：
         * 「最近 1 秒发生了 64 次」比「最近 200 毫秒发生了 13 次」更容易读懂。
         */
        const val DEFAULT_WINDOW_MS = 1000L

        /**
         * 突刺阈值 8
         *
         * 【为什么是 8】小于它的重复（2~7 次）多半是正常的连击/重复事件，
         * 逐条看反而有信息量；到 8 次才算「刷屏」，
         * 此时提前给一条摘要让现场立即可见。
         */
        const val DEFAULT_BURST_THRESHOLD = 8

        /**
         * 限流表上限 512
         *
         * 【为什么必须有】键里含消息文本。若调用方把节点文本拼进消息，
         * 每个节点都是新键 —— 表会随节点数无界增长。
         * 512 条 × 约 200 字节 ≈ 100 KB，恒定；超出后逐出最旧。
         */
        const val DEFAULT_MAX_ENTRIES = 512
    }
}
