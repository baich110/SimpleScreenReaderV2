package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 陈旧回调识别 —— **失败模式测试**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这道防线】
 * ══════════════════════════════════════════════════════════
 *
 * `SpeechEngine` 的三个 TTS 回调此前**全部忽略 utteranceId**，
 * 直接操作**队首当前项**。而 utteranceId
 * （`"baicha_${item.id}"`）唯一的作用就是识别
 * 「刚完成的是哪一项」—— 忽略了它，陈旧回调就会
 * 把**新项**误判为完成，导致**漏读一条内容**且日志一切正常。
 *
 * 三种真实触发场景见 [UtteranceGuard] 类注释。
 *
 * 【本类覆盖的重点】
 * ① 明确不匹配 → 必须拦（这是修复的核心）
 * ② **callbackId 为 null → 必须放行**（这条是安全性关键：
 *    部分旧引擎不传 ID，拦掉会导致**完全不出声**，
 *    比偶尔漏读严重得多）
 * ③ 队列无当前项 → 必须拦（stop 之后的残留回调）
 */
class UtteranceGuardTest {

    // ==================== 基本往返 ====================

    @Test
    fun idOf_buildsExpectedFormat() {
        assertEquals("baicha_1", UtteranceGuard.idOf(1L))
        assertEquals("baicha_12345", UtteranceGuard.idOf(12345L))
    }

    @Test
    fun itemIdOf_roundTrips() {
        for (id in listOf(1L, 2L, 999L, 123456789L)) {
            assertEquals(id, UtteranceGuard.itemIdOf(UtteranceGuard.idOf(id)))
        }
    }

    @Test
    fun itemIdOf_rejectsMalformed() {
        assertNull("null 应返回 null", UtteranceGuard.itemIdOf(null))
        assertNull("无前缀应返回 null", UtteranceGuard.itemIdOf("123"))
        assertNull("前缀后不是数字应返回 null", UtteranceGuard.itemIdOf("baicha_abc"))
        assertNull("空白应返回 null", UtteranceGuard.itemIdOf("baicha_"))
    }

    // ==================== ① 匹配 → 放行 ====================

    @Test
    fun matchingId_isAccepted() {
        assertTrue(
            "回调 ID 与当前项一致 → 应处理",
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(7L), 7L)
        )
    }

    // ==================== ② 不匹配 → 拦截（核心修复）====================

    /**
     * ★★ 陈旧回调必须被拦下
     *
     * 这是漏读内容的直接原因：旧实例/旧项的 onDone 到达时，
     * 队列里已经是**新的一项**。
     */
    @Test
    fun staleId_isRejected() {
        assertFalse(
            "★ 陈旧回调（ID 属于上一项）必须被拦下 —— " +
                    "否则会把当前新项误判为完成，用户**漏听一条内容**",
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(1L), 2L)
        )
        assertFalse(
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(100L), 101L)
        )
    }

    /** 完全不同来源的 ID（不是本引擎发的）也必须拦 */
    @Test
    fun foreignId_isRejected() {
        assertFalse(
            "非本引擎构造的 ID 必须拦下",
            UtteranceGuard.matchesCurrent("some-other-id", 5L)
        )
        assertFalse(UtteranceGuard.matchesCurrent("", 5L))
    }

    // ==================== ③ 队列无当前项 → 拦截 ====================

    /**
     * ★ `stop()` 后的残留回调：队列已清空 → 必须拦
     */
    @Test
    fun noCurrentItem_rejectsEverything() {
        assertFalse(
            "队列无当前项时，任何回调都是残留 → 必须拦",
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(1L), null)
        )
        assertFalse(UtteranceGuard.matchesCurrent(null, null))
        assertFalse(UtteranceGuard.matchesCurrent("baicha_9", null))
    }

    // ==================== ④ null ID → 保守放行（安全性关键）====================

    /**
     * ★★★ callbackId 为 null 时必须**放行**
     *
     * 【为什么】部分 TTS 引擎（尤其旧版/第三方）在
     * `onStart`/`onDone` 里传 null。
     * 若一律拦掉 ⇒ 播报**永远不触发后续** ⇒
     * **完全不出声**，比「偶尔漏读」严重得多。
     *
     * 所以策略是：**只拦明确不匹配的**，无法判断时放行。
     */
    @Test
    fun nullCallbackId_isAcceptedConservatively() {
        assertTrue(
            "★ callbackId 为 null 时必须放行 —— 部分引擎不传 ID，" +
                    "拦掉会导致完全不出声（比偶尔漏读严重得多）",
            UtteranceGuard.matchesCurrent(null, 5L)
        )
    }

    // ==================== ⑤ 真实场景走查 ====================

    /**
     * 场景 A：引擎切换
     *
     * 旧实例（项 1）的 onDone 在新项（项 2）入队之后才到达。
     */
    @Test
    fun scenario_engineSwitch_staleDoneDoesNotSkipNewItem() {
        val oldItemId = 1L
        val newItemId = 2L

        // 队列里现在是新项
        assertFalse(
            "★ 旧实例的完成回调不得动到新项",
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(oldItemId), newItemId)
        )
        // 而新项自己的回调应被接受
        assertTrue(
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(newItemId), newItemId)
        )
    }

    /**
     * 场景 B：stop() 后立刻有新播报
     */
    @Test
    fun scenario_stopThenSpeak_staleDoneRejected() {
        // stop() 清空队列（current = null）→ 残留回调没有可匹配的项
        assertFalse(
            "残留回调在队列空时必须被拦",
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(1L), null)
        )
        // 立刻新播报（项 9）→ 旧回调（项 1）仍不匹配
        assertFalse(
            "★ stop 后立刻新播报时，旧回调不得动到新项",
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(1L), 9L)
        )
    }

    /**
     * 场景 C：QUEUE_FLUSH 打断
     */
    @Test
    fun scenario_flushInterrupt_staleDoneRejected() {
        val interrupted = 10L
        val urgent = 11L
        assertFalse(
            "★ 被 FLUSH 丢掉的旧项，其完成回调不得动到新项",
            UtteranceGuard.matchesCurrent(UtteranceGuard.idOf(interrupted), urgent)
        )
    }
}

/**
 * 接线守卫 —— `SpeechEngine` 必须**真的**用上陈旧回调识别
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么必须单独守】上面的测试只验证 [UtteranceGuard] **自身逻辑**，
 * 完全没约束 `SpeechEngine` 是否调用它。
 * 本项目已 **6 次**出现「引擎写对、测试全绿、但没人接上去」
 * （最近一次：第二十轮 `clearFilter()` 死代码）。
 *
 * 【为什么用结构断言而不是行为断言】
 * `SpeechEngine` 构造 `TextToSpeech`，在 JVM 单测里无法实例化，
 * 故回调路径无法行为测试。这里断言**源码结构**：
 * 每个 TTS 回调里都必须出现 `matchesCurrent`。
 */
class SpeechEngineUtteranceWiringTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val engine by lazy {
        codeOf("src/main/java/com/baicha/reader/core/SpeechEngine.kt")
    }

    /** 取出 `setOnUtteranceProgressListener` 的整个匿名类体 */
    private fun listenerBody(): String {
        val start = engine.indexOf("setOnUtteranceProgressListener")
        assertTrue("必须注册 UtteranceProgressListener", start >= 0)
        // 到该匿名类结束（粗略取 3000 字符，足够覆盖四个回调）
        return engine.substring(start, minOf(engine.length, start + 3000))
    }

    @Test
    fun onDone_checksUtteranceGuard() {
        val body = listenerBody()
        val idx = body.indexOf("override fun onDone(")
        assertTrue("必须有 onDone", idx >= 0)
        val fn = body.substring(idx, minOf(body.length, idx + 900))
        assertTrue(
            "★ onDone 必须调用 UtteranceGuard.matchesCurrent —— " +
                    "否则陈旧回调会把新项误判完成，用户漏听一条内容:\n$fn",
            fn.contains("UtteranceGuard.matchesCurrent(")
        )
    }

    @Test
    fun onError_overloads_checkUtteranceGuard() {
        val body = listenerBody()
        val matches = Regex("UtteranceGuard\\.matchesCurrent\\(").findAll(body).count()
        assertTrue(
            "★ 四个回调（onStart/onDone/两个 onError 重载）都应校验；" +
                    "实际只找到 $matches 处 —— 漏掉的回调仍是漏洞",
            matches >= 4
        )
    }

    @Test
    fun onStart_checksUtteranceGuard() {
        val body = listenerBody()
        val idx = body.indexOf("override fun onStart(")
        assertTrue("必须有 onStart", idx >= 0)
        val fn = body.substring(idx, minOf(body.length, idx + 700))
        assertTrue(
            "onStart 也必须校验（陈旧 onStart 会触发错误的开始回调）:\n$fn",
            fn.contains("UtteranceGuard.matchesCurrent(")
        )
    }

    /**
     * ★ 第二道防线：`onUtteranceDone` 必须**再复核一次**
     *
     * 从 TTS 回调到主线程执行之间有延迟（`mainHandler.post`），
     * 期间队列可能变化。
     */
    @Test
    fun onUtteranceDone_hasSecondLineDefense() {
        val idx = engine.indexOf("private fun onUtteranceDone(")
        assertTrue("必须有 onUtteranceDone", idx >= 0)
        val fn = engine.substring(idx, minOf(engine.length, idx + 1200))
        assertTrue(
            "★ onUtteranceDone 必须有 utteranceId 参数做第二道防线:\n$fn",
            fn.contains("utteranceId")
        )
        assertTrue(
            "必须用 UtteranceGuard.itemIdOf 反解回调项 ID:\n$fn",
            fn.contains("UtteranceGuard.itemIdOf(")
        )
    }

    /**
     * utteranceId 必须由 `UtteranceGuard.idOf` 构造（单一来源）
     *
     * 不得再出现裸字符串拼接 —— 那样两处格式一旦不一致，
     * 校验就永远不通过（把正常回调全拦掉 ⇒ 完全不出声）。
     */
    @Test
    fun utteranceId_isBuiltByHelperOnly() {
        assertTrue(
            "必须用 UtteranceGuard.idOf(item.id) 构造",
            engine.contains("UtteranceGuard.idOf(item.id)")
        )
        assertFalse(
            "不得再出现裸的 \"baicha_\" 拼接（两份格式会不一致 → 把正常回调全拦掉）",
            engine.contains("\"baicha_\${item.id}\"") || engine.contains("\"baicha_\" +")
        )
    }

    /** 反向守卫：不得把 matchesCurrent 的调用删成注释 */
    @Test
    fun guardCalls_areNotCommentedOut() {
        val raw = java.io.File("src/main/java/com/baicha/reader/core/SpeechEngine.kt").readText()
        val commented = Regex("//\\s*.*UtteranceGuard\\.matchesCurrent").findAll(raw).count()
        assertEquals(
            "不得把 matchesCurrent 调用注释掉",
            0, commented
        )
    }
}
