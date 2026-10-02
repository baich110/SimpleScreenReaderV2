package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量朗读监听器 —— **容量与配对正确性**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么测这个】读屏是常驻服务，`watched` 里只要有一处
 * 不配对（watch 了但永不 unwatch），节点对象就会一直被强引用 ——
 * `IdentityHashMap` 持有的是强引用，节点回收不了，
 * 长跑数小时就是内存泄漏。
 *
 * ══════════════════════════════════════════════════════════
 * 【先说我的一个错误判断】
 * ══════════════════════════════════════════════════════════
 *
 * 我读 `AccessibilityEventHandler` 时一度以为「每次焦点事件都
 * `watch()` 新节点，旧的只靠一个 `watchedNode` 引用去 unwatch，
 * 若 `event.source` 每次是新对象就会累积」。
 *
 * **实测证明不会**（见下）：`watchedNode` 保存的正是上次 `watch()`
 * 传进去的**同一个引用**，`unwatch(watchedNode)` 必然命中那个 key。
 * 只要 `watch` 与 `unwatch` 成对使用同一个引用，`watched` 就恒为 0~1 项。
 *
 * 所以本测试的价值不在于「发现了泄漏」，而在于
 * **把这个不变量钉住** —— 将来若有人改成「按内容比较」或
 * 「忘记维护 watchedNode」，这里会立刻失败。
 */
class NodeTextMonitorBoundTest {

    private fun node(id: Int, text: String) =
        FakeNode(id, textValue = text)

    // ==================== 配对不变量 ====================

    /**
     * ★ 模拟事件处理器「焦点切换」的配对逻辑，验证 watched 恒为 0~1
     *
     * 复刻 `AccessibilityEventHandler.kt:272~280`：
     * ```
     *   event.source?.let { src ->
     *       if (src !== watchedNode) {
     *           watchedNode?.let { textMonitor.unwatch(it) }
     *           textMonitor.watch(src)
     *           watchedNode = src
     *       }
     *   }
     * ```
     */
    @Test
    fun focusSwitching_keepsWatchedAtMostOne() {
        val m = NodeTextMonitor().apply { enabled = true }
        var watchedNode: FakeNode? = null

        // 反复切换焦点：A → B → C → A → ...，每次都换**新对象实例**
        repeat(100) { i ->
            val src = node(i, "焦点 $i")
            if (src !== watchedNode) {
                watchedNode?.let { m.unwatch(it) }
                m.watch(src)
                watchedNode = src
            }
            assertTrue(
                "第 $i 次切换后 watched 应 ≤ 1，实际 ${m.watchedCount}",
                m.watchedCount <= 1
            )
        }
        assertEquals("100 次焦点切换后仍应只有 1 项", 1, m.watchedCount)
    }

    /**
     * 同一个对象重复 watch 不产生多份（幂等）
     */
    @Test
    fun repeatedWatch_sameInstance_isIdempotent() {
        val m = NodeTextMonitor().apply { enabled = true }
        val n = node(1, "文本")
        repeat(50) { m.watch(n) }
        assertEquals("对同一对象重复 watch 应仍为 1 项", 1, m.watchedCount)
    }

    /**
     * ★ 不同实例但**相同 nodeId** 必须视为不同 key（IdentityHashMap 语义）
     *
     * 【为什么这条重要】`AccessibilityNodeInfo` 的 `equals` 是**内容哈希**，
     * 两个不同对象只要内容相同就相等。若 `watched` 用普通 `HashMap`，
     * 它们会被当成同一个 key → 后 watch 的覆盖前一个 →
     * **前一个节点静默失去监听**（读了半天没有增量播报）。
     * 用 `IdentityHashMap` 正是为了避免这个。
     */
    @Test
    fun identitySemantics_distinctInstancesAreDistinctKeys() {
        val m = NodeTextMonitor().apply { enabled = true }
        val a = node(7, "同样文本")
        val b = node(7, "同样文本")

        // 前提：按内容相等
        assertEquals("FakeNode 的 equals 是内容相等（与真实节点一致）", a, b)

        m.watch(a)
        m.watch(b)
        assertEquals(
            "两个内容相同但实例不同的节点必须各占一个 key（Identity 语义）",
            2, m.watchedCount
        )

        m.unwatch(a)
        assertEquals("移除 a 后应剩 b", 1, m.watchedCount)
    }

    @Test
    fun unwatchAll_clearsEverything() {
        val m = NodeTextMonitor().apply { enabled = true }
        repeat(10) { m.watch(node(it, "t$it")) }
        assertEquals(10, m.watchedCount)
        m.unwatchAll()
        assertEquals("unwatchAll 后应清空", 0, m.watchedCount)
    }

    // ==================== 增量播报语义 ====================

    @Test
    fun onTextChanged_returnsAddedTextOnly() {
        val m = NodeTextMonitor().apply { enabled = true }
        val n = node(1, "你好")

        val spoken = mutableListOf<String>()
        m.onIncrementalText = { spoken += it }

        m.watch(n)
        // 节点 text 变了 —— FakeNode 的 textValue 可直接改
        n.textValue = "你好世界"
        val added = m.onTextChanged(n)

        assertEquals("应只播报增量部分", "世界", added)
        assertEquals(listOf("世界"), spoken)
    }

    @Test
    fun onTextChanged_sameText_returnsNull() {
        val m = NodeTextMonitor().apply { enabled = true }
        val n = node(1, "不变")
        m.watch(n)
        assertNull("文本未变应返回 null（不打扰用户）", m.onTextChanged(n))
    }

    @Test
    fun onTextChanged_unwatchedNode_returnsNull() {
        val m = NodeTextMonitor().apply { enabled = true }
        val n = node(1, "没监听")
        assertNull("未监听的节点不应产生播报", m.onTextChanged(n))
    }

    @Test
    fun disabledMonitor_doesNothing() {
        val m = NodeTextMonitor()      // enabled 默认 false
        val n = node(1, "x")
        m.watch(n)
        assertEquals("未启用时不应记录监听", 0, m.watchedCount)
        assertNull("未启用时不应产生播报", m.onTextChanged(n))
    }

    @Test
    fun onTextChanged_nullNode_isSafe() {
        val m = NodeTextMonitor().apply { enabled = true }
        assertNull("null 节点必须安全返回 null（真机会遇到无 source 的事件）", m.onTextChanged(null))
    }

    /** 文本缩短（删除）时不应播报「删除」内容，只播增量 */
    @Test
    fun onTextChanged_shrinking_producesNoSpuriousSpeech() {
        val m = NodeTextMonitor().apply { enabled = true }
        val n = node(1, "一二三四五")
        m.watch(n)

        n.textValue = "一二"
        val added = m.onTextChanged(n)
        // addedText 的语义是「新增的部分」；缩短时新增为空
        assertTrue(
            "文本缩短时不应播报新增内容（实际: $added）",
            added.isNullOrBlank()
        )
    }
}

/**
 * 窗口变化时必须清理监听状态 —— **抄漏 clear() 类缺陷**
 *
 * ══════════════════════════════════════════════════════════
 * 【缺陷】`AccessibilityEventHandler.resetState()` 重置了 4 个字段：
 * ```kotlin
 *   fun resetState() {
 *       lastSpokenIdentity = -1
 *       lastHoverIdentity = -1
 *       lastScrollNodeKey = -1
 *       lastScrollWindowId = -1
 *   }
 * ```
 * **但没有清 `watchedNode`，也没有调 `textMonitor.unwatchAll()`**。
 *
 * 而项目里其他状态在窗口变化时**都清了**：
 *   · `FocusManager.onWindowChanged()` 清 focusNode / anchorNode
 *   · `autoScroll.resetScrollState()` 清滚动快照
 *   · `voiceScroll.reset()`
 *   · `CacheRegistry.invalidate(WINDOW)`
 * 唯独增量朗读的监听状态漏了 —— 属目标里点名的错误模式⑤「抄漏 clear()」。
 *
 * ══════════════════════════════════════════════════════════
 * 【两条真实后果】
 * ══════════════════════════════════════════════════════════
 *
 * ① **跨窗口残留**：若窗口变化后不再产生任何焦点事件
 *    （切到无焦点元素的界面、或用户随即锁屏），
 *    `watched` 里仍留着上一窗口的节点且被**强引用**
 *    （`IdentityHashMap` 持强引用）→ 该节点无法回收。
 *
 * ② **更严重：播报后台页面的内容**
 *    上一窗口的节点若之后文本又变（后台页面仍在更新，
 *    如聊天窗口收到新消息、股票页刷新），
 *    `onTextChanged(旧节点)` 会命中 entry 并**播报那句增量** ——
 *    用户在当前页面上听到**另一个页面的内容**，
 *    完全无从理解来源。对视障用户这是高度困惑的噪音。
 *
 * 【修复】在 `resetState()` 里清 `watchedNode` 并 `unwatchAll()`。
 */
class NodeTextMonitorWindowResetTest {

    private fun node(id: Int, text: String) = FakeNode(id, textValue = text)

    /**
     * ★ 复刻 `resetState()` 的**修复后**语义：
     * 窗口变化后必须清空监听
     *
     * 这条测试直接针对本类（NodeTextMonitor）的能力 ——
     * Activity 侧的接线由 `HandlerResetWiringTest` 守住。
     */
    @Test
    fun unwatchAll_afterWindowChange_preventsStaleBroadcast() {
        val m = NodeTextMonitor().apply { enabled = true }
        val spoken = mutableListOf<String>()
        m.onIncrementalText = { spoken += it }

        // 窗口 A 的焦点节点
        val oldFocus = node(1, "页面A")
        m.watch(oldFocus)

        // 窗口变化 → 清理（这是修复后 resetState 必须做的事）
        m.unwatchAll()

        // 旧节点在后台更新了文本
        oldFocus.textValue = "页面A（后台新消息）"
        val added = m.onTextChanged(oldFocus)

        assertNull(
            "★ 窗口变化后，旧窗口节点的文本变化**不得**再产生播报 —— " +
                    "否则用户在 B 页面听到 A 页面的内容",
            added
        )
        assertEquals("不应有任何播报", emptyList<String>(), spoken)
    }

    /**
     * 对照：**不清理**时会播报旧窗口内容（证明缺陷真实存在）
     */
    @Test
    fun withoutCleanup_staleBroadcastHappens() {
        val m = NodeTextMonitor().apply { enabled = true }
        val spoken = mutableListOf<String>()
        m.onIncrementalText = { spoken += it }

        val oldFocus = node(1, "页面A")
        m.watch(oldFocus)
        // ⚠️ 故意**不**清理，模拟修复前的行为

        oldFocus.textValue = "页面A（后台新消息）"
        val added = m.onTextChanged(oldFocus)

        assertEquals(
            "不清理时确实会播报旧窗口内容 —— 这就是缺陷的直接证据",
            "（后台新消息）", added
        )
        assertEquals(listOf("（后台新消息）"), spoken)
    }
}

/**
 * 接线守卫 —— `resetState()` 必须**真的**清理监听状态
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么必须单独守】本项目已 4 次出现
 * 「引擎写对、测试全绿、但没人接上去」（最近一次是第二十轮的
 * `clearFilter()` 死代码）。上面的测试验证的是 `NodeTextMonitor`
 * **自身能力**，完全没约束 `AccessibilityEventHandler` 是否调用它。
 */
class HandlerResetWiringTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val handler by lazy {
        codeOf("src/main/java/com/baicha/reader/core/AccessibilityEventHandler.kt")
    }

    @Test
    fun resetState_clearsWatchedNode() {
        val idx = handler.indexOf("fun resetState()")
        assertTrue("必须存在 resetState()", idx >= 0)
        val body = handler.substring(idx, minOf(handler.length, idx + 1600))

        assertTrue(
            "resetState() 必须把 watchedNode 置空 —— " +
                    "否则跨窗口残留上一窗口的焦点节点",
            body.contains("watchedNode = null")
        )
        assertTrue(
            "resetState() 必须调用 textMonitor.unwatchAll() —— " +
                    "否则旧窗口节点的文本变化会被播报（用户听到别页面的内容）",
            body.contains("textMonitor.unwatchAll()")
        )
    }

    /**
     * 反向守卫：`resetState()` 原有的 4 个字段**不得**被删
     */
    @Test
    fun resetState_keepsOriginalResets() {
        val idx = handler.indexOf("fun resetState()")
        val body = handler.substring(idx, minOf(handler.length, idx + 1600))
        for (f in listOf(
            "lastSpokenIdentity = -1",
            "lastHoverIdentity = -1",
            "lastScrollNodeKey = -1",
            "lastScrollWindowId = -1"
        )) {
            assertTrue("resetState 不得丢掉原有重置：$f", body.contains(f))
        }
    }

    /**
     * `watch` 与 `unwatch` 必须成对使用**同一个引用**
     *
     * 【为什么这条重要】`NodeTextMonitor.watched` 是 `IdentityHashMap`，
     * unwatch 必须传入**与 watch 相同的实例**才能命中。
     * 若有人写成 `unwatch(event.source)`（新对象）就永远移除不掉。
     */
    @Test
    fun watchUnwatch_useSameReference() {
        val idx = handler.indexOf("TYPE_VIEW_ACCESSIBILITY_FOCUSED")
        assertTrue("找不到焦点事件分支", idx >= 0)
        val body = handler.substring(idx, minOf(handler.length, idx + 800))

        assertTrue(
            "必须用 watchedNode 做 unwatch（它就是上次 watch 的同一引用）:\n$body",
            body.contains("textMonitor.unwatch(it)") && body.contains("watchedNode?.let")
        )
        assertTrue("watch 必须记录到 watchedNode", body.contains("watchedNode = src"))
    }
}
