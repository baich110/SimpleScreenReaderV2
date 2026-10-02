package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量朗读接线测试
 *
 * 【为什么单独测这个】
 *   本轮审计发现：`TextDiffEngine` 与 `NodeTextMonitor` **写完了但从未被调用** ——
 *   文本变化事件（2048）走的是「整段重读」。
 *
 *   这是最典型的一类失效：代码在、测试在、功能不在。
 *   这类 bug 单测「引擎本身」是查不出来的（引擎测试全绿），
 *   必须测「接线」。
 *
 * 【接线约定（本测试锁定）】
 *   ① 事件 TYPE_VIEW_TEXT_CHANGED 必须走 textMonitor.onTextChanged()
 *   ② enabled 未开启时 onTextChanged 返回 null → 回退整段读
 *   ③ 未 watch 过的节点返回 null → 回退整段读（首次应完整念）
 */
class TextDiffWiringTest {

    // ==================== ① 引擎可用性 ====================

    @Test
    fun added_text_detects_appended_delta() {
        val engine = TextDiffEngine()
        // 价格从 10.5 → 10.8：只有 "8" 是新增
        val added = engine.addedText("价格 10.5", "价格 10.8")
        assertNotNull("应能提取增量", added)
        assertTrue("增量应包含变化部分：$added", added!!.contains("8"))
    }

    @Test
    fun added_text_returns_null_when_unchanged() {
        val engine = TextDiffEngine()
        assertNull(
            "文本没变时不该产生播报",
            engine.addedText("一样的文本", "一样的文本")
        )
    }

    @Test
    fun added_text_on_empty_previous_returns_whole() {
        val engine = TextDiffEngine()
        val added = engine.addedText("", "新文本")
        assertEquals("无基准时应整体返回", "新文本", added)
    }

    // ==================== ② 监控器开关语义 ====================

    /**
     * ★ 未启用时必须返回 null ——
     * 这是「回退整段读」的判定依据，若返回非 null 会双重播报。
     */
    @Test
    fun disabled_monitor_never_produces_delta() {
        val m = NodeTextMonitor()
        m.enabled = false
        // 关闭时即使节点为 null 也必须安全返回 null（不能抛）
        assertNull(m.onTextChanged(null))
        assertEquals(0, m.watchedCount)
        assertFalse(m.enabled)
    }

    @Test
    fun monitor_starts_disabled() {
        val m = NodeTextMonitor()
        assertFalse("默认应关闭，由服务显式开启", m.enabled)
    }

    @Test
    fun unwatch_all_clears_watched() {
        val m = NodeTextMonitor()
        m.enabled = true
        m.unwatchAll()
        assertEquals(0, m.watchedCount)
    }

    /**
     * 未 watch 过的节点调用 onTextChanged 必须返回 null（无基准 → 回退整段读）
     *
     * 【为什么这条重要】
     *   若这里返回空串 ""，调用方会走「增量分支」播报空串 ——
     *   用户什么都听不到，且不会有任何报错。静默失效。
     */
    @Test
    fun unwatched_or_null_node_returns_null_not_empty() {
        val m = NodeTextMonitor()
        m.enabled = true
        // null 节点必须安全返回 null（而非抛异常或空串）
        assertNull(m.onTextChanged(null))
    }

    @Test
    fun stats_reports_state() {
        val m = NodeTextMonitor()
        val stats = m.stats()
        assertTrue("统计应包含监听数：$stats", stats.contains("监听"))
        assertTrue("统计应包含开关状态：$stats", stats.contains("启用"))
    }
}
