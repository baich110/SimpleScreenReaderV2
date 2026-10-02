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
        // 被丢弃项的回调 —— 在**锁外**统一触发
        //
        // 【为什么不在锁内调】回调是调用方提供的任意代码，
        // 可能耗时（TTS 调用、写日志、跨进程查询）。
        // 在锁内执行会阻塞其它线程的 enqueue/pollNext ——
        // 读屏的播报在无障碍线程与主线程间高频切换，
        // 持锁时间被拉长直接表现为「播报延迟、滑动变卡」。
        //
        // （注：**同线程**重入本类的 synchronized 方法不会死锁 ——
        //   JVM monitor 可重入。这一点我最初判断错了，已在测试类里更正。）
        val discardedCallbacks = ArrayList<SpeechItem>(4)

        val result = synchronized(lock) {
            val needInterrupt = SpeechFlags.has(item.flags, SpeechFlags.FLUSH)

            // FLUSH：清空待播队列
            //
            // ══════════════════════════════════════════════════════════
            // 【★ 本轮修复：被 FLUSH 丢弃的项必须通知 onDone】
            // ══════════════════════════════════════════════════════════
            //
            // 原来这里只标记 `cancelled = true` 就 `clear()`，**不调用
            // onDone**；而 `clear()` 方法对同样被丢弃的项**是会调的**。
            // 两条路径语义不一致。
            //
            // 【为什么必须统一】`onDone` 的语义是「这一项结束了
            // （无论是播完还是被取消），通知调用方」。
            // 调用方若依赖它做资源释放 / 状态复位 / 流程推进，
            // 那一项的回调就**永远不会来** —— 表现为功能静默卡住，
            // 属于目标里点名的错误模式⑨「静默失败」。
            //
            // 【真机场景】用户快速滑动时焦点高频切换，FLUSH 是常态；
            // 一旦有功能依赖播报完成回调（如连续朗读的分段推进、
            // 播报完成才解锁下一步），就会卡在那一段不再前进。
            //
            // 【当前是否已有症状】`SpeechEngine` 目前不设置 `onDone`，
            // 故尚无实际症状 —— 但这是 API 契约不一致，必须先修。
            //
            // 【注意顺序】回调必须在**锁外**执行，见下方收集后统一触发。
            if (needInterrupt) {
                var i = 0
                while (pending.isNotEmpty()) {
                    val discarded = pending.removeFirst()
                    if (!discarded.cancelled) {
                        discarded.cancelled = true
                        discardedCallbacks.add(discarded)
                    }
                    i++
                }
                val cur = current
                if (cur != null && !cur.cancelled) {
                    cur.cancelled = true
                    discardedCallbacks.add(cur)
                }
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
            EnqueueResult(needInterrupt, false)
        }

        // ★ 锁外触发被丢弃项的回调（见方法开头的说明）
        for (discarded in discardedCallbacks) {
            try {
                discarded.onDone?.invoke()
            } catch (e: Throwable) {
                ReaderLog.w("SpeechQueue", "onDone 回调异常: ${e.message}")
            }
        }

        return result
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
        // 与 enqueue 的 FLUSH 路径保持一致：**锁外**触发回调
        //
        // 【为什么改】原来 `onDone` 在 `synchronized` 内执行。
        // 回调是调用方任意代码，可能耗时（TTS 调用 / 写日志 / 跨进程查询），
        // 持锁期间会阻塞其它线程的 enqueue/pollNext ——
        // 读屏播报在无障碍线程与主线程间高频切换，
        // 持锁时间被拉长直接表现为「播报延迟、滑动变卡」。
        //
        // 【顺带统一语义】被丢弃的项**一律**通知 onDone，
        // 与 FLUSH 路径行为一致（本轮同时修了 FLUSH 不通知的缺陷）。
        val toNotify = ArrayList<SpeechItem>(4)

        val count = synchronized(lock) {
            var n = 0
            while (pending.isNotEmpty()) {
                val item = pending.removeFirst()
                if (!item.cancelled) {
                    item.cancelled = true
                    toNotify.add(item)
                    n++
                }
            }
            if (!keepCurrent) {
                current?.let {
                    if (!it.cancelled) {
                        it.cancelled = true
                        toNotify.add(it)
                        n++
                    }
                }
                current = null
            }
            n
        }

        for (item in toNotify) {
            try {
                item.onDone?.invoke()
            } catch (e: Throwable) {
                ReaderLog.w("SpeechQueue", "onDone 回调异常: ${e.message}")
            }
        }

        return count
    }

    fun stats(): String = "[$channelName] 播报 $spokenCount / 丢弃 $droppedCount / 待播 $size"
}
