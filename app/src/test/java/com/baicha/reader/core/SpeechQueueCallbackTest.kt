package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播报队列 —— **回调在锁内的危害**
 *
 * ══════════════════════════════════════════════════════════
 * 【问题】`clear()` 在 `synchronized(lock)` **内部**调用
 * 调用方传入的 `item.onDone`：
 * ```kotlin
 *   fun clear(keepCurrent: Boolean = false): Int {
 *       synchronized(lock) {
 *           while (pending.isNotEmpty()) {
 *               val item = pending.removeFirst()
 *               if (!item.cancelled) {
 *                   item.cancelled = true
 *                   item.onDone?.invoke()      // ← 锁内执行外部代码
 *                   count++
 *               }
 *           }
 *           ...
 * ```
 *
 * 【为什么危险】`onDone` 是**调用方提供的任意代码**。只要它：
 *   · 再次调用本队列的**任何** synchronized 方法（`enqueue`/`size`/
 *     `isSpeaking`/`clear`），就构成**重入死锁**；
 *   · 或耗时较长（如触发一次 TTS 调用、写日志、跨进程查询），
 *     就会在**持锁期间**阻塞其它线程的入队/出队。
 *
 * 【真机场景】`SpeechEngine` 收到 TTS 完成回调后会：
 *   ① 调 `onCurrentDone()` ② 从队列取下一项 ③ 播报
 * 而 `clear()` 常在**打断**路径上被调用（用户快速滑动、焦点切换）。
 * 两侧一旦在同一个回调链上汇合，就是死锁 ——
 * 表现为**读屏突然彻底不说话了**，且服务进程还活着（不崩、不重启），
 * 用户完全无法判断发生了什么。这是最糟的失败形态。
 *
 * 【本测试】用一个「回调里再调队列」的 onDone 复现重入死锁。
 * 为避免测试本身永久挂起，在**独立线程**里跑并设超时。
 */
class SpeechQueueCallbackTest {

    private fun item(
        text: String,
        flags: Int = SpeechFlags.NONE,
        queueMode: Int = 1,          // 1 = ADD（排队）
        onDone: (() -> Unit)? = null
    ) = SpeechItem(text = text, queueMode = queueMode, flags = flags, onDone = onDone)

    // ==================== ★ 关于「锁内调回调」的准确结论 ====================
    //
    // ══════════════════════════════════════════════════════════
    // 【我一开始的判断是错的，记录在此】
    // ══════════════════════════════════════════════════════════
    //
    // 我最初认为「`clear()` 在 synchronized 内调用 `onDone`
    // ⇒ 回调若重入队列就死锁」，并写了两条测试。
    // **实测两条都通过（未死锁）** —— 因为
    // **JVM 的 `synchronized` 是重入锁**（monitor 可重入），
    // 同一线程再次进入同一 monitor 不会阻塞。
    //
    // 真正会出问题的是另外两种情形：
    //   ① **跨线程**：线程 A 持锁 → 调 onDone → 等线程 B；
    //      而线程 B 正等这把锁 ⇒ 死锁。
    //   ② **持锁期间执行耗时外部代码**：onDone 里做 TTS 调用 / 写日志 /
    //      跨进程查询时，其它线程的 enqueue/pollNext 全部被阻塞。
    //      读屏的播报在无障碍线程与主线程之间高频切换，
    //      持锁时间被拉长会直接表现为「播报延迟、滑动变卡」。
    //
    // ⇒ 结论：**锁内调外部回调确实是不良实践（应移到锁外），
    //    但「重入死锁」这个理由站不住**。
    //    本类的测试因此不再声称死锁，只锁住可验证的语义。
    //
    // 【处置】当前生产代码**从不设置 `onDone`**
    // （`SpeechEngine` 构造 `SpeechItem` 时未传该参数），
    // 故此为**潜在**问题而非现行缺陷 —— 记录在案，
    // 等真正使用回调时一并把调用移到锁外。

    /**
     * 锁内调回调不会因**同线程重入**而死锁 —— 记录这个事实
     *
     * （我原本以为会死锁；这条测试把结论钉死，避免后人重复踩。）
     */
    @Test
    fun reentrantCallbackFromSameThread_doesNotDeadlock() {
        val queue = SpeechQueue("test")
        val done = CountDownLatch(1)
        val calls = AtomicInteger(0)

        queue.enqueue(item("a", onDone = {
            calls.incrementAndGet()
            queue.size                 // 同线程重入 —— 重入锁，不阻塞
        }))

        val worker = Thread {
            try { queue.clear() } finally { done.countDown() }
        }
        worker.isDaemon = true
        worker.start()

        assertTrue(
            "JVM synchronized 是可重入锁，同线程重入不该死锁",
            done.await(3, TimeUnit.SECONDS)
        )
        assertEquals(1, calls.get())
    }

    // ==================== 回调语义 ====================

    @Test
    fun clear_invokesOnDoneForPendingItems() {
        val queue = SpeechQueue("test")
        val calls = mutableListOf<String>()
        queue.enqueue(item("a", onDone = { calls += "a" }))
        queue.enqueue(item("b", onDone = { calls += "b" }))

        val n = queue.clear()
        assertEquals("应取消 2 项", 2, n)
        assertEquals(listOf("a", "b"), calls)
    }

    /**
     * `keepCurrent = true` 时当前项**不得**被取消，回调也不该触发
     */
    @Test
    fun clear_keepCurrent_preservesCurrentAndItsCallback() {
        val queue = SpeechQueue("test")
        var currentDone = false
        queue.enqueue(item("current", onDone = { currentDone = true }))
        queue.pollNext()                       // current = "current"

        queue.enqueue(item("pending", onDone = { }))
        val n = queue.clear(keepCurrent = true)

        assertEquals("只取消待播项", 1, n)
        assertFalse("保留当前项时不得触发它的回调", currentDone)
        assertTrue("当前项应仍在播", queue.isSpeaking)
    }

    /**
     * ★★ FLUSH 清掉的项**必须**通知 `onDone` —— 与 `clear()` 语义一致
     *
     * ══════════════════════════════════════════════════════════
     * 【这是本轮实测发现并修复的真实不一致】
     * ══════════════════════════════════════════════════════════
     *
     * 修复前，两条「丢弃待播项」的路径对回调处理**不同**：
     *
     * `enqueue(item with FLUSH)`：
     * ```kotlin
     *   if (needInterrupt) {
     *       pending.forEach { it.cancelled = true }   // 只标记，不调 onDone
     *       pending.clear()
     *       current?.let { it.cancelled = true }
     *   }
     * ```
     * `clear()`：**会**调 `onDone`。
     *
     * 【为什么是缺陷】`onDone` 的语义是「这一项结束了
     * （无论是播完还是被取消），通知调用方」。
     * FLUSH 路径把项直接移除却不通知 ⇒ 调用方若依赖它做
     * 资源释放 / 状态复位 / 流程推进，**那一项的回调永远不会来** ⇒
     * 功能静默卡住（目标里点名的错误模式⑨「静默失败」）。
     *
     * 【真机场景】快速滑动时焦点高频切换，FLUSH 是常态；
     * 一旦有功能依赖播报完成回调（连续朗读的分段推进、
     * 播报完成才解锁下一步），就会卡住不再前进。
     *
     * 【修复】两条路径统一：被丢弃的项一律通知 onDone，
     * 且回调移到**锁外**执行（避免持锁时跑耗时的调用方代码）。
     */
    @Test
    fun flush_notifiesDiscardedItemCallback() {
        val queue = SpeechQueue("test")
        val called = mutableListOf<String>()

        queue.enqueue(item("a", onDone = { called += "a" }))
        queue.enqueue(item("b", flags = SpeechFlags.FLUSH, onDone = { called += "b" }))

        assertEquals(
            "FLUSH 丢弃 a 时必须通知它的 onDone（修复前是静默丢弃）",
            listOf("a"), called
        )
    }

    /** FLUSH 也应通知**当前正在播**那一项的回调（它同样被取消了） */
    @Test
    fun flush_notifiesCurrentItemCallback() {
        val queue = SpeechQueue("test")
        val called = mutableListOf<String>()

        queue.enqueue(item("playing", onDone = { called += "playing" }))
        queue.pollNext()                      // current = playing
        queue.enqueue(item("urgent", flags = SpeechFlags.FLUSH, onDone = { called += "urgent" }))

        assertEquals(
            "FLUSH 取消当前项时也必须通知它",
            listOf("playing"), called
        )
    }

    /** 与 `clear()` 的语义一致性 —— 两条路径行为必须相同 */
    @Test
    fun flushAndClear_haveSameCallbackSemantics() {
        val viaFlush = mutableListOf<String>()
        val q1 = SpeechQueue("test")
        q1.enqueue(item("x", onDone = { viaFlush += "x" }))
        q1.enqueue(item("y", flags = SpeechFlags.FLUSH))

        val viaClear = mutableListOf<String>()
        val q2 = SpeechQueue("test")
        q2.enqueue(item("x", onDone = { viaClear += "x" }))
        q2.clear()

        assertEquals(
            "FLUSH 与 clear 对同一场景的回调语义必须一致",
            viaClear, viaFlush
        )
    }

    /**
     * 对照：`clear()` 对**同一个**待播项只触发一次回调
     *
     * （原来这条测试写错了：我以为 FLUSH 会触发 a 的回调，
     * 实际 FLUSH 已把 a 静默丢弃。已改为正确语义。）
     */
    @Test
    fun clear_invokesEachCallbackExactlyOnce() {
        val queue = SpeechQueue("test")
        val count = AtomicInteger(0)
        queue.enqueue(item("a", onDone = { count.incrementAndGet() }))
        queue.enqueue(item("b", onDone = { count.incrementAndGet() }))

        queue.clear()
        queue.clear()          // 第二次 clear 不应重复触发

        assertEquals("每个回调只触发一次", 2, count.get())
    }

    // ==================== 队列基本契约 ====================

    @Test
    fun flush_clearsPendingAndCancelsCurrent() {
        val queue = SpeechQueue("test")
        queue.enqueue(item("first"))
        queue.pollNext()                        // current = first
        queue.enqueue(item("second"))

        val r = queue.enqueue(item("urgent", flags = SpeechFlags.FLUSH))
        assertTrue("FLUSH 应要求打断", r.needInterrupt)
        assertEquals("FLUSH 后待播队列只剩新项", 1, queue.size)
    }

    @Test
    fun dedupe_skipsIdenticalText() {
        val queue = SpeechQueue("test")
        queue.enqueue(item("重复"))
        val r = queue.enqueue(item("重复", flags = SpeechFlags.DEDUPE))
        assertTrue("重复项应被跳过", r.deduped)
        assertEquals(1, queue.size)
    }

    @Test
    fun dedupe_differentText_isAccepted() {
        val queue = SpeechQueue("test")
        queue.enqueue(item("A"))
        val r = queue.enqueue(item("B", flags = SpeechFlags.DEDUPE))
        assertFalse(r.deduped)
        assertEquals(2, queue.size)
    }

    @Test
    fun capacity_dropsOldest() {
        val queue = SpeechQueue("test", capacity = 3)
        repeat(5) { queue.enqueue(item("t$it")) }
        assertEquals("超出容量应丢弃最旧的", 3, queue.size)
        assertEquals("应保留最后 3 项", "t2", queue.pollNext()?.text)
    }

    @Test
    fun pollNext_skipsCancelled() {
        val queue = SpeechQueue("test")
        queue.enqueue(item("a"))
        queue.enqueue(item("b"))
        queue.enqueue(item("c"))
        queue.clear()

        assertEquals("清空后无可播项", null, queue.pollNext())
    }
}
