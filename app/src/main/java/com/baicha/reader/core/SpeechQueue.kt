package com.baicha.reader.core

/**
 * 播报项 —— 对应天坦 Z6/O（一次完整播报）
 *
 * 天坦的 O 类包含多个片段（M 类），支持逐片段回调。
 * 本实现简化为一整个文本 + 标志位，但保留全部语义字段。
 */
class SpeechItem(
    /** 要朗读的文本 */
    @JvmField val text: String,

    /** 队列模式：0=FLUSH（打断当前）1=ADD（排队） */
    @JvmField val queueMode: Int,

    /** 标志位（见 SpeechFlags） */
    @JvmField val flags: Int,

    /** 来源包名（用于调试） */
    @JvmField val sourcePackage: String? = null,

    /** 完成回调 */
    @JvmField var onDone: (() -> Unit)? = null,

    /** 开始回调 */
    @JvmField var onStart: (() -> Unit)? = null
) {
    /** 唯一 ID（用于回调匹配） */
    @JvmField
    val id: Long = nextId()

    /** 是否已取消 */
    @Volatile
    @JvmField
    var cancelled: Boolean = false

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        private fun nextId(): Long = counter.incrementAndGet()
    }
}

/**
 * 播报标志位 —— 对应天坦的 flags 常量
 */
object SpeechFlags {
    /** 无标志 */
    const val NONE = 0

    /**
     * 打断通道 —— 对应天坦的 8192
     *
     * 带此标志的播报走**独立的 TTS 实例**，不会阻塞普通播报队列。
     * 用于：错误提示、状态反馈、短促的操作确认。
     */
    const val INTERRUPT_CHANNEL = 1 shl 0

    /** 只回调不朗读 —— 对应天坦的 128 */
    const val CALLBACK_ONLY = 1 shl 1

    /** 强制打断当前 —— 对应天坦的 64 (FLUSH) */
    const val FLUSH = 1 shl 2

    /** 去重：相同文本在队列中已存在则跳过 —— 对应天坦的 256 */
    const val DEDUPE = 1 shl 3

    /** 允许在朗读时调整（不打断）—— 用于进度类播报 */
    const val ALLOW_ADJUST = 1 shl 4

    fun has(flags: Int, bit: Int): Boolean = (flags and bit) != 0
}

/**
 * 播报队列 —— 对应天坦 Z6/SpeechController 的队列体系
 *
 * 【天坦的队列结构】
 *   f11641u/f11642v/f11643w : 3 个 PriorityQueue
 *   f11644x/f11645y         : 2 个 ArrayDeque（A/B 通道）
 *   f11646z                 : 1 个 LinkedList
 *
 * 【本实现（更简单但等价）】
 *   两条通道各自一个 ArrayDeque，加一个"当前正在播"的引用。
 *   不做优先级队列 —— 因为天坦的优先队列实际只在特定场景用（如连续朗读分段），
 *   框架层先用 FIFO，功能层需要时再加。
 *
 * 【双通道的意义】
 *   通道 A（普通）：滑动浏览、点击反馈 —— 会排队，保证每个焦点都读到
 *   通道 B（打断）：错误提示、状态变化 —— 立即执行，不等 A
 *
 *   例：用户正在读一段长文本，此时来了"网络已断开"提示，
 *       通道 B 立即播报，通道 A 的长文本继续 —— 互不干扰。
 */
class SpeechQueue(
    private val channelName: String,
    /** 队列上限，超出丢弃最旧 */
    private val capacity: Int = 32
) {

    private val lock = Any()

    /** 待播队列 */
    private val pending = ArrayDeque<SpeechItem>(capacity)

    /** 当前正在播的项 */
    @Volatile
    private var current: SpeechItem? = null

    @Volatile
    var droppedCount = 0L
        private set

    @Volatile
    var spokenCount = 0L
        private set

    // ==================== 入队 ====================

    /**
     * 入队
     *
     * @return 需要立即执行打断的信号数（0 = 只入队，1 = 需要打断当前）
     */
    fun enqueue(item: SpeechItem): EnqueueResult {
        synchronized(lock) {
            val needInterrupt = SpeechFlags.has(item.flags, SpeechFlags.FLUSH)

            // FLUSH：清空待播队列
            if (needInterrupt) {
                pending.forEach { it.cancelled = true }
                pending.clear()
                current?.let { it.cancelled = true }
            }

            // 去重：相同文本已在队列中 → 跳过
            if (SpeechFlags.has(item.flags, SpeechFlags.DEDUPE)) {
                if (current?.text == item.text || pending.any { it.text == item.text }) {
                    item.cancelled = true
                    return EnqueueResult(false, true)
                }
            }

            // 容量控制：丢弃最旧的
            while (pending.size >= capacity) {
                val dropped = pending.removeFirst()
                dropped.cancelled = true
                droppedCount++
            }

            pending.addLast(item)
            return EnqueueResult(needInterrupt, false)
        }
    }

    /** 入队结果 */
    data class EnqueueResult(
        /** 是否需要打断当前播报 */
        val needInterrupt: Boolean,
        /** 是否为重复项被跳过 */
        val deduped: Boolean
    )

    // ==================== 出队 ====================

    /**
     * 取下一项
     *
     * @return 下一项；null = 队列空
     */
    fun pollNext(): SpeechItem? {
        synchronized(lock) {
            while (true) {
                val next = pending.removeFirstOrNull() ?: run {
                    current = null
                    return null
                }
                if (next.cancelled) continue
                current = next
                spokenCount++
                return next
            }
        }
    }

    /** 当前项播报完成 */
    fun onCurrentDone() {
        synchronized(lock) {
            current = null
        }
    }

    /** 当前正在播的项 */
    fun current(): SpeechItem? = current

    /** 队列是否为空 */
    val isEmpty: Boolean
        get() = synchronized(lock) { pending.isEmpty() && current == null }

    /** 是否正在播报 */
    val isSpeaking: Boolean
        get() = synchronized(lock) { current != null }

    /** 待播数量 */
    val size: Int
        get() = synchronized(lock) { pending.size }

    // ==================== 清空 ====================

    /**
     * 清空队列
     *
     * @param keepCurrent 是否保留当前正在播的项
     * @return 被取消的项数
     */
    fun clear(keepCurrent: Boolean = false): Int {
        synchronized(lock) {
            var count = 0
            // 触发被取消项的回调（让上层知道）
            while (pending.isNotEmpty()) {
                val item = pending.removeFirst()
                if (!item.cancelled) {
                    item.cancelled = true
                    item.onDone?.invoke()
                    count++
                }
            }
            if (!keepCurrent) {
                current?.let {
                    if (!it.cancelled) {
                        it.cancelled = true
                        it.onDone?.invoke()
                        count++
                    }
                }
                current = null
            }
            return count
        }
    }

    fun stats(): String = "[$channelName] 播报 $spokenCount / 丢弃 $droppedCount / 待播 $size"
}
