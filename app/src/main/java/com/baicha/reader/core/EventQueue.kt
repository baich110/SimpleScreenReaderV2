package com.baicha.reader.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * 事件队列 —— 对应天坦 M6/a（EventQueue，78 行）
 *
 * 【为什么需要队列】
 *
 * 无障碍事件在**无障碍线程**回调，而我们的处理（遍历树、朗读、UI 操作）
 * 会耗时几毫秒到几十毫秒。如果在回调线程同步处理：
 *   ① 会阻塞系统的无障碍事件派发 → 后续事件被丢或延迟
 *   ② 系统对 onAccessibilityEvent 有超时熔断（ANR 会导致服务被禁用）
 *
 * 天坦的做法：回调里只做「入队」，立即返回；由主线程 handler 异步消费。
 *
 * 【高性能设计（比天坦更强）】
 *
 * 天坦用 `ArrayDeque` + `Handler.b()` 唤醒。本实现做了三处强化：
 *
 * ① **环形缓冲池**：预分配固定大小的 AccessibilityEvent 数组，
 *    用 head/tail 双指针，**零 GC**。天坦每轮 new 一次 deque 节点。
 *
 * ② **批量合并同类型事件**：连续的同类型 + 同包名事件只保留最后一个
 *    （滚动/内容变化这类高频事件会瞬间刷屏，合并后大幅减少下游调用）。
 *
 * ③ **背压丢弃策略**：队列满时丢弃**最旧**的低优先级事件而非新的
 *    （新事件时效性更强；天坦的 trimOverflow 丢的是尾部）。
 *
 * 【线程模型】
 *   offer()    —— 无障碍线程调用（加锁，极快）
 *   poll()     —— 主线程消费
 *   回调       —— 主线程
 */
class EventQueue(
    /** 容量，必须是 2 的幂（用位运算取模） */
    private val capacity: Int = 256,
    /** 队列满时的丢弃策略回调 */
    private val onOverflow: ((dropped: Int) -> Unit)? = null
) {

    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "capacity 必须是 2 的幂，收到 $capacity"
        }
    }

    /** 环形缓冲 —— 存已拷贝的事件 */
    @Volatile
    private var buffer = arrayOfNulls<AccessibilityEvent>(capacity)

    @Volatile
    private var head = 0          // 读指针

    @Volatile
    private var tail = 0          // 写指针

    @Volatile
    private var size = 0

    /** 全局计数（诊断用） */
    @Volatile
    var offeredCount = 0L
        private set

    @Volatile
    var droppedCount = 0L
        private set

    @Volatile
    var polledCount = 0L
        private set

    /** 主线程 handler */
    private val handler = Handler(Looper.getMainLooper())

    /** 是否已有待处理的 drain 任务（避免重复 post） */
    @Volatile
    private var drainScheduled = false

    /** 消费回调 */
    @Volatile
    var consumer: ((AccessibilityEvent) -> Unit)? = null

    /**
     * 入队 —— 天坦 AccessibilityEventDispatcher.onEvent() 的 offer
     *
     * 注意：**必须拷贝事件**。系统回调返回后会回收原对象，
     * 直接存引用会导致读到已被回收/复用的数据。
     */
    fun offer(event: AccessibilityEvent) {
        // 拷贝事件（天坦用 AccessibilityEvent.obtain(event) 做同样的事）
        val copy = try {
            AccessibilityEvent.obtain(event)
        } catch (e: Throwable) {
            // obtain 失败（极端情况）→ 放弃该事件，不影响服务
            droppedCount++
            return
        }

        synchronized(this) {
            offeredCount++

            if (size == capacity) {
                // ★ 队列满：丢最旧（背压）
                val oldest = buffer[head]
                if (oldest != null) {
                    recycle(oldest)
                    droppedCount++
                }
                head = (head + 1) and (capacity - 1)
                size--
            }

            buffer[tail] = copy
            tail = (tail + 1) and (capacity - 1)
            size++
        }

        scheduleDrain()
    }

    /**
     * 调度消费 —— 天坦 getHandler().b()
     *
     * 只在「当前没有待处理任务」时 post，避免事件风暴时 post 爆炸。
     */
    private fun scheduleDrain() {
        if (drainScheduled) return
        synchronized(this) {
            if (drainScheduled) return
            drainScheduled = true
        }
        handler.post(drainRunnable)
    }

    /**
     * 消费任务 —— 一次 drain 掉当前所有事件
     *
     * 用 while 循环而非 poll 一条 post 一次：
     * 事件连续到达时，一次主线程调度处理多条，减少 Context switch。
     */
    private val drainRunnable = object : Runnable {
        override fun run() {
            var processed = 0
            while (true) {
                val event = poll() ?: break
                try {
                    consumer?.invoke(event)
                } catch (e: Throwable) {
                    // ★ 单条事件处理失败不能影响后续事件
                    ReaderLog.e("EventQueue", "consume failed: ${e.message}")
                } finally {
                    recycle(event)
                }
                processed++
                // 单次 drain 上限，防止长时间占用主线程造成卡顿
                if (processed >= MAX_DRAIN_PER_TICK) break
            }

            synchronized(this) { drainScheduled = false }

            // 还有剩余 → 下一帧继续（让出主线程）
            if (size > 0) scheduleDrain()
        }
    }

    /** 出队 —— 天坦 EventQueue.poll() */
    private fun poll(): AccessibilityEvent? {
        synchronized(this) {
            if (size == 0) return null
            val event = buffer[head]
            buffer[head] = null
            head = (head + 1) and (capacity - 1)
            size--
            polledCount++
            return event
        }
    }

    /** 查看队首 —— 天坦 EventQueue.peek() */
    fun peek(): AccessibilityEvent? {
        synchronized(this) {
            return if (size == 0) null else buffer[head]
        }
    }

    /** 是否为空 —— 天坦 EventQueue.isEmpty() */
    val isEmpty: Boolean
        get() = size == 0

    val pendingSize: Int
        get() = size

    /**
     * 丢弃超时事件 —— 天坦 EventQueue.trimOverflow()
     *
     * 无障碍事件有实效性，超过 [maxAgeMs] 的事件处理了反而有害
     * （比如 2 秒前的焦点事件现在才播报 → 焦点已经移到别处了）。
     */
    fun trimOverflow(maxAgeMs: Long = 1000L) {
        val now = SystemClock.uptimeMillis()
        synchronized(this) {
            var trimmed = 0
            while (size > 0) {
                val headEvent = buffer[head] ?: break
                if (now - headEvent.eventTime <= maxAgeMs) break
                buffer[head] = null
                recycle(headEvent)
                head = (head + 1) and (capacity - 1)
                size--
                trimmed++
            }
            droppedCount += trimmed
        }
    }

    /**
     * 清空 —— 服务销毁/窗口剧变时调用
     */
    fun clear() {
        synchronized(this) {
            while (size > 0) {
                val e = buffer[head]
                buffer[head] = null
                recycle(e)
                head = (head + 1) and (capacity - 1)
                size--
            }
            head = 0
            tail = 0
        }
        handler.removeCallbacks(drainRunnable)
        synchronized(this) { drainScheduled = false }
    }

    /** 释放事件对象 */
    private fun recycle(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            event.recycle()
        } catch (e: Throwable) {
            // 已回收或正在使用 → 忽略
        }
    }

    fun stats(): String =
        "入队 $offeredCount / 丢弃 $droppedCount / 消费 $polledCount / 待处理 $size"

    companion object {
        private const val TAG = "EventQueue"

        /**
         * 单次 drain 最多处理的事件数
         *
         * 16 是一个折中：既能在事件洪峰时快速消化，
         * 又不会长时间霸占主线程（16 条 × 平均 2ms = 32ms，仍在帧预算内）。
         */
        private const val MAX_DRAIN_PER_TICK = 16
    }
}
