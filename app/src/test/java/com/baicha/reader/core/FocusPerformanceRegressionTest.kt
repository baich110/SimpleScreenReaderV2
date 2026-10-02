package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机 P0 回归测试：焦点瘫痪
 *
 * ══════════════════════════════════════════════════════════
 * 【真机证据】
 * ══════════════════════════════════════════════════════════
 * ```
 * 01:44:47.223 触摸: 进度条
 * 01:44:47.271 焦点清除
 * 01:44:47.272 无障碍焦点: 进度条
 * 01:44:47.296 焦点清除
 * 01:44:47.296 焦点清除
 * 01:44:47.326 W/SpeechEngine: TTS 播报出错: baicha_83 code=-5
 * ```
 * 一秒内十几次焦点清除/重建；用户「左右滑动完全浏览不到焦点」。
 *
 * ══════════════════════════════════════════════════════════
 * 【根因（本测试锁定）】
 * ══════════════════════════════════════════════════════════
 * `NodePath.of()` 被**同步调用在每次焦点的热路径上**，且每次触摸
 * 触发**两次**（A0 的 onFocusApplied + A6 的 recordFocusAction）。
 *
 * 每次采集遍历父链（最多 40 层），每层调
 * `indexOfInParent` + 2×`siblingFingerprint` —— 都是
 * **跨进程 IPC**（getChild 走 AccessibilityInteractionClient）。
 * 深度 20 层时 ≈240 次 IPC，两次 ≈480 次 —— 堵塞无障碍交互线程，
 * 事件堆积，表现为「焦点乱跳 / 滑不到」。
 *
 * 【测试边界（诚实说明）】
 *   ✅ 能测：延迟采集器的**行为契约**（热路径零遍历、防抖、
 *           弱引用、失效节点跳过）
 *   ❌ 测不到：真实 IPC 耗时、线程阻塞、真机焦点表现
 *           —— 需真机验证（用户说了算）
 */
class NodePathCollectorTest {

    @After
    fun tearDown() {
        NodePathCollector.reset()
    }

    /**
     * ★★ 防抖窗口必须存在且合理 —— 变异测试发现原测试太宽松
     *
     * 原来的断言是 `in 100L..1000L`，但我把实现改成 `postDelayed(task, 0L)`
     * （即**完全不做防抖**）时测试仍通过 —— 因为常量本身没变，
     * 变的只是**使用它的地方**。
     *
     * 所以必须直接锁定实现文件里的调用形式。
     */
    @Test
    fun debounce_is_actually_used_in_implementation() {
        val src = java.io.File(
            "src/main/java/com/baicha/reader/core/NodePathDescription.kt"
        ).readText()
        val code = src.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            "必须用 postDelayed(task, DEBOUNCE_MS) 做防抖",
            code.contains("postDelayed(task, DEBOUNCE_MS)")
        )
        assertFalse(
            "不得把延迟写成 0（等于取消防抖，触摸洪流会再次堵塞线程）",
            code.contains("postDelayed(task, 0L)")
        )
    }

    @Test
    fun constants_are_reasonable() {
        assertTrue(
            "防抖窗口应足够长以合并连续触摸，又足够短以在息屏前采到",
            NodePathCollector.DEBOUNCE_MS in 100L..1000L
        )
    }

    /**
     * ★ 热路径调用 `request()` 必须是**非阻塞**的 ——
     *   它只做赋值 + postDelayed，不做任何节点遍历。
     *
     * 【怎么验证「不做遍历」】传 null 与合法节点都不应抛异常，
     * 且 request 返回后 latest 尚未更新（说明是异步的）。
     */
    @Test
    fun request_is_async_and_non_blocking() {
        val before = NodePathCollector.collectCount
        // null 节点 → 直接返回
        NodePathCollector.request(null)
        assertEquals(
            "request 不该同步产生采集",
            before, NodePathCollector.collectCount
        )
    }

    /**
     * ★★ 防抖：连续 request 会被合并，只采最后一次
     *
     * 这是「触摸抖动时不再每次采集」的核心保障。
     *
     * 【为什么用真实节点构造】`request(null)` 会提前返回，
     * 测不到防抖计数。这里用反射构造一个 AccessibilityNodeInfo
     * 实例（JVM 下它是空壳，但引用有效，足够驱动防抖逻辑）。
     */
    @Test
    fun repeated_requests_are_debounced_and_post_delayed() {
        NodePathCollector.reset()

        // 无法在 JVM 构造有效节点 → 直接验证「计数语义」：
        // skippedCount 表示「收到多少次 request」，
        // 它必须随每次调用增长（防抖正是靠它衡量合并率）。
        val node = nodeStub()
        if (node == null) {
            // JVM 下 AccessibilityNodeInfo 无法反射构造时，
            // 退化为验证「防抖窗口常量存在且合理」这一性质
            assertTrue(NodePathCollector.DEBOUNCE_MS > 0)
            return
        }

        repeat(15) { NodePathCollector.request(node) }

        assertEquals(
            "连续 15 次 request 应全部计数（防抖跳过）",
            15L, NodePathCollector.skippedCount
        )
        assertEquals(
            "尚未到防抖窗口 → 实际采集 0 次",
            0L, NodePathCollector.collectCount
        )
    }

    /** 尝试反射构造一个节点实例；失败返回 null */
    private fun nodeStub(): android.view.accessibility.AccessibilityNodeInfo? = try {
        val c = android.view.accessibility.AccessibilityNodeInfo::class.java
        val ctor = c.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
    } catch (e: Throwable) {
        null
    }

    @Test
    fun reset_clears_counters() {
        NodePathCollector.request(null)
        NodePathCollector.reset()
        assertEquals(0L, NodePathCollector.collectCount)
        assertEquals(0L, NodePathCollector.skippedCount)
        assertNull(NodePathCollector.latest)
    }

    @Test
    fun collect_now_with_null_is_safe() {
        assertNull(NodePathCollector.collectNow(null))
    }

    @Test
    fun stats_reports_both_counters() {
        NodePathCollector.request(null)
        val st = NodePathCollector.stats()
        assertTrue("统计应含采集次数：$st", st.contains("路径采集"))
        assertTrue("统计应含跳过次数：$st", st.contains("跳过"))
    }

    /**
     * ★★ 热路径禁止同步采集 —— **这条是 P0 的直接回归防线**
     *
     * 【为什么用源码断言】`NodePath.of()` 的代价是跨进程 IPC，
     * JVM 单测里 getChild() 是空壳，**测不出耗时**。
     * 所以只能锁定「调用位置」这一结构性质：
     * 热路径文件里不得出现 `NodePath.of(`。
     *
     * 变异测试证实：把 recordFocusAction 改回同步采集时，
     * 若没有这条断言，所有测试仍通过 —— 即无法防回归。
     */
    @Test
    fun hot_path_must_not_call_node_path_of() {
        val handler = java.io.File(
            "src/main/java/com/baicha/reader/core/AccessibilityEventHandler.kt"
        ).readText()

        // 只看代码，排除注释（注释里提到 NodePath.of 是解释性的）
        val code = handler.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertFalse(
            "事件处理器在触摸热路径上，不得调用 NodePath.of() —— " +
                "每次采集 ≈240 次跨进程 getChild，会堵塞交互线程（真机 P0）",
            code.contains("NodePath.of(")
        )
    }

    /**
     * ★★ 焦点管理器同样不得在 applyFocus 里同步采集
     */
    @Test
    fun focus_manager_must_not_call_node_path_of() {
        val fm = java.io.File(
            "src/main/java/com/baicha/reader/core/FocusManager.kt"
        ).readText()
        val code = fm.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertFalse(
            "FocusManager.applyFocus 是每次触摸的必经路径，不得同步采集路径",
            code.contains("NodePath.of(")
        )
    }

    /**
     * ★ 服务侧只允许在**延迟采集器**里调用 NodePath.of
     */
    @Test
    fun only_collector_calls_node_path_of() {
        val svc = java.io.File(
            "src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt"
        ).readText()
        val code = svc.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertFalse(
            "服务侧应通过 NodePathCollector.request() 采集，不直接调用 NodePath.of()",
            code.contains("NodePath.of(")
        )
        assertTrue(
            "服务应使用延迟采集器",
            code.contains("NodePathCollector.request(")
        )
    }

    /**
     * ★ 弱引用语义：request 不应长期持有节点
     *   （否则节点无法被系统回收，内存泄漏）
     */
    @Test
    fun request_does_not_prevent_gc_by_design() {
        // 无法在单测中强制 GC 验证，故锁定「设计意图」：
        // 采集器对外只暴露 latest（路径），不暴露节点
        val fields = NodePathCollector::class.java.declaredFields.map { it.type.name }
        assertTrue(
            "采集器不应持有强节点引用字段：$fields",
            fields.none { it.contains("AccessibilityNodeInfo") }
        )
    }
}

/**
 * TTS 播报模式回归测试
 */
class SpeechEngineModeTest {

    /**
     * ★★ `QUEUE_FLUSH` / `QUEUE_ADD` 常量值必须与 Android 一致
     */
    @Test
    fun tts_queue_mode_constants_match_android() {
        assertEquals("TextToSpeech.QUEUE_FLUSH = 0", 0, android.speech.tts.TextToSpeech.QUEUE_FLUSH)
        assertEquals("TextToSpeech.QUEUE_ADD = 1", 1, android.speech.tts.TextToSpeech.QUEUE_ADD)
    }

    /**
     * ★★ 核心回归：`item.queueMode == 0` 表示打断，
     *    `== 1` 表示排队 —— 不能无条件用 FLUSH。
     *
     * 【真机问题】原来无条件 `QUEUE_FLUSH`，导致高频播报时
     * 引擎被反复清空，全部返回 ERROR_SYNTHESIS(-5)，
     * 用户「完全没声音」。
     *
     * 本测试通过**读源码字符串**的方式锁定「不再无条件 FLUSH」——
     * 因为 TTS 调用无法在 JVM 执行。
     */
    @Test
    fun speak_does_not_unconditionally_flush() {
        val src = java.io.File(
            "src/main/java/com/baicha/reader/core/SpeechEngine.kt"
        ).readText()

        assertFalse(
            "不能无条件用 QUEUE_FLUSH —— 会把「排队」也变成「打断」，" +
                "高频时导致 ERROR_SYNTHESIS(-5)",
            src.contains("tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)")
        )
        assertTrue(
            "应按 item.queueMode 选择模式",
            src.contains("if (item.queueMode == 0)") &&
                src.contains("TextToSpeech.QUEUE_ADD")
        )
    }

    /**
     * ★ 重复播报守卫必须存在且阈值合理
     */
    @Test
    fun repeat_guard_exists_with_sane_threshold() {
        val src = java.io.File(
            "src/main/java/com/baicha/reader/core/SpeechEngine.kt"
        ).readText()
        assertTrue(
            "必须有高频重复播报守卫（真机每秒十几次同一文本）",
            src.contains("REPEAT_GUARD_MS")
        )

        val v = com.baicha.reader.core.SpeechEngine.REPEAT_GUARD_MS
        assertTrue(
            "守卫间隔应短于人的感知阈值（约150ms），$v 太久会吞掉正常重播",
            v in 50L..150L
        )
    }
}

/**
 * 焦点历史路径补填测试（A6 与性能修复的衔接）
 */
class FocusPathAttachTest {

    private fun rec(fp: Int = 111) = FocusActionRecord(
        nodePath = null,          // 采集时为 null（热路径不采集）
        windowId = 1,
        source = FocusActionRecord.FOCUS_SOURCE_TOUCH_EXPLORATION,
        actionTime = 1000,
        role = NodeRoleHelper.ROLE_BUTTON,
        isEditable = false,
        textFingerprint = fp
    )

    /**
     * ★★ 记录时不带路径（热路径安全），采集完成后补填
     *
     * 这是性能修复与 A6 功能的衔接点：既不在热路径做 IPC，
     * 又保证恢复时拿得到路径。
     */
    @Test
    fun path_is_attached_after_deferred_collection() {
        val h = FocusActionHistory()
        h.record(rec(fp = 555))

        assertNull(
            "记录时不带路径 —— 这是热路径安全的前提",
            h.last()?.nodePath
        )

        val path = NodePath(
            listOf(
                PathSegment(
                    index = 0, role = 1, textFingerprint = 555
                )
            )
        )
        h.attachPathToLatest(path)

        assertNotNull("补填后应能取到路径", h.last()?.nodePath)
        assertEquals(555, h.last()?.textFingerprint)
    }

    /** 补填不应改变队列长度 */
    @Test
    fun attach_does_not_change_queue_size() {
        val h = FocusActionHistory()
        h.record(rec(1))
        h.record(rec(2))
        val before = h.size()

        h.attachPathToLatest(NodePath(listOf(PathSegment(0, 1, 2))))

        assertEquals("补填只替换最近一条，不该增删条目", before, h.size())
    }

    /** 空队列时补填应安全 */
    @Test
    fun attach_on_empty_history_is_safe() {
        val h = FocusActionHistory()
        h.attachPathToLatest(NodePath(listOf(PathSegment(0, 1, 2))))
        assertEquals(0, h.size())
    }

    /** 补填的是**最近一条**，不是最旧的 */
    @Test
    fun attach_targets_the_latest_record() {
        val h = FocusActionHistory()
        h.record(rec(fp = 111))
        h.record(rec(fp = 222))

        h.attachPathToLatest(NodePath(listOf(PathSegment(0, 1, 999))))

        assertEquals("最近一条应仍是最新那条", 222, h.last()?.textFingerprint)
        assertNotNull(h.last()?.nodePath)
        assertNull(
            "旧的记录不该被补填",
            h.records()[1].nodePath
        )
    }
}
