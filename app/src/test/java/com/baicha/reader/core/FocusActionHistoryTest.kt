package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点动作历史测试（A6）—— 对标 TalkBack
 * `AccessibilityFocusActionHistory`(445 行)
 *
 * 【测试依据（源码行号）】
 *   · `:117`  MAXIMUM_WINDOW_MAP_SIZE = 10
 *   · `:120`  MAXIMUM_RECORD_QUEUE_SIZE = 5
 *   · `:127`  TIMEOUT_TOLERANCE_MS = 300
 *   · `:134`  WEB_TIMEOUT_TOLERANCE_MS = 500
 *   · `:143`  TV_TIMEOUT_TOLERANCE_MS = 400
 *   · `:294~330` matchFocusActionRecordFromEvent（节点+时间双条件）
 *
 * ══════════════════════════════════════════════════════════
 * 【能测什么 / 测不到什么】
 * ══════════════════════════════════════════════════════════
 * ✅ 能测：入队淘汰、窗口 LRU、三类查询、时间容差、节点匹配
 * ❌ 测不到：真实竞态下的匹配率、多窗口真机行为
 */
class FocusActionHistoryTest {

    private fun rec(
        windowId: Int = 1,
        source: Int = FocusActionRecord.FOCUS_SOURCE_LOGICAL_NAVIGATION,
        actionTime: Long = 1000,
        role: Int = NodeRoleHelper.ROLE_BUTTON,
        isEditable: Boolean = false,
        fingerprint: Int = 111
    ) = FocusActionRecord(
        nodePath = null,
        windowId = windowId,
        source = source,
        actionTime = actionTime,
        role = role,
        isEditable = isEditable,
        textFingerprint = fingerprint
    )

    // ==================== 常量对齐 TalkBack ====================

    @Test
    fun constants_match_talkback() {
        assertEquals(
            "AccessibilityFocusActionHistory.java:120",
            5, FocusActionHistory.MAXIMUM_RECORD_QUEUE_SIZE
        )
        assertEquals(
            "AccessibilityFocusActionHistory.java:117",
            10, FocusActionHistory.MAXIMUM_WINDOW_MAP_SIZE
        )
        assertEquals(
            "AccessibilityFocusActionHistory.java:127",
            300, FocusActionHistory.TIMEOUT_TOLERANCE_MS
        )
        assertEquals(
            "AccessibilityFocusActionHistory.java:134（WebView 聚焦链路更长）",
            500, FocusActionHistory.WEB_TIMEOUT_TOLERANCE_MS
        )
        assertEquals(
            "AccessibilityFocusActionHistory.java:143（Sony ATV 焦点动画慢）",
            400, FocusActionHistory.TV_TIMEOUT_TOLERANCE_MS
        )
    }

    // ==================== 队列淘汰 ====================

    @Test
    fun initially_empty() {
        val h = FocusActionHistory()
        assertNull(h.last())
        assertEquals(0, h.size())
    }

    @Test
    fun last_returns_most_recent() {
        val h = FocusActionHistory()
        h.record(rec(actionTime = 1000, fingerprint = 111))
        h.record(rec(actionTime = 2000, fingerprint = 222))
        assertEquals("应返回最新一条", 222, h.last()?.textFingerprint)
    }

    /**
     * ★ 队列上限 5，超出丢最旧 —— 对齐 TalkBack
     */
    @Test
    fun queue_is_bounded_to_max_size() {
        val h = FocusActionHistory()
        for (i in 1..10) {
            h.record(rec(actionTime = i.toLong(), fingerprint = i))
        }
        assertEquals("队列不该超过 5 条", 5, h.size())
        assertEquals("保留最新的", 10, h.last()?.textFingerprint)
        assertEquals("总数仍要统计", 10L, h.totalRecorded)
    }

    @Test
    fun oldest_records_are_dropped() {
        val h = FocusActionHistory()
        for (i in 1..7) {
            h.record(rec(actionTime = i.toLong(), fingerprint = i))
        }
        val fps = h.records().map { it.textFingerprint }
        assertFalse("1 和 2 应被淘汰：$fps", fps.contains(1))
        assertFalse(fps.contains(2))
        assertTrue("3~7 应在：$fps", fps.containsAll(listOf(3, 4, 5, 6, 7)))
    }

    // ==================== 窗口映射 ====================

    /**
     * ★★ 窗口映射的价值：队列只有 5 条，但窗口记录能保住更早的
     *
     * 场景：用户在窗口 1 操作过，之后在窗口 2/3 各操作多次，
     * 窗口 1 的记录早被挤出队列 —— 但「窗口 1 上次焦点在哪」仍应能答。
     */
    @Test
    fun window_map_survives_queue_eviction() {
        val h = FocusActionHistory()
        h.record(rec(windowId = 1, actionTime = 1000, fingerprint = 1))
        // 在窗口 2 塞满队列
        for (i in 1..8) {
            h.record(rec(windowId = 2, actionTime = (2000 + i).toLong(), fingerprint = 100 + i))
        }

        assertNull("窗口 1 的记录应已被挤出队列（用 lastInWindow 才能拿到）",
            h.records().firstOrNull { it.windowId == 1 })

        val w1 = h.lastInWindow(1)
        assertNotNull("窗口 1 的专属记录必须还在", w1)
        assertEquals(1, w1?.textFingerprint)
    }

    @Test
    fun window_record_is_updated_on_new_action() {
        val h = FocusActionHistory()
        h.record(rec(windowId = 1, actionTime = 1000, fingerprint = 111))
        h.record(rec(windowId = 1, actionTime = 2000, fingerprint = 222))
        assertEquals(
            "同窗口应覆盖为最新",
            222, h.lastInWindow(1)?.textFingerprint
        )
    }

    @Test
    fun unknown_window_returns_null() {
        val h = FocusActionHistory()
        h.record(rec(windowId = 1))
        assertNull(h.lastInWindow(99))
    }

    /**
     * ★ 窗口映射上限 10（LRU）—— 对齐 TalkBack
     */
    @Test
    fun window_map_is_bounded() {
        val h = FocusActionHistory()
        for (i in 1..15) {
            h.record(rec(windowId = i, actionTime = i.toLong(), fingerprint = i))
        }
        assertEquals("窗口数不该超过 10", 10, h.windowCount())
        assertNull("最久未访问的窗口 1 应被淘汰", h.lastInWindow(1))
        assertNotNull("最新的窗口 15 应在", h.lastInWindow(15))
    }

    /** 重新访问会刷新 LRU 顺序 */
    @Test
    fun window_lru_refreshes_on_access() {
        val h = FocusActionHistory()
        for (i in 1..10) {
            h.record(rec(windowId = i, fingerprint = i))
        }
        // 重新记录窗口 1 → 它变成最新
        h.record(rec(windowId = 1, fingerprint = 999))
        // 再塞一个新窗口，应淘汰窗口 2（而不是 1）
        h.record(rec(windowId = 11, fingerprint = 11))

        assertNotNull("窗口 1 刚被访问过，不该淘汰", h.lastInWindow(1))
        assertNull("窗口 2 应被淘汰", h.lastInWindow(2))
    }

    // ==================== 可编辑焦点 ====================

    @Test
    fun last_editable_tracks_editable_records() {
        val h = FocusActionHistory()
        h.record(rec(isEditable = false))
        assertNull("没有可编辑记录时应为 null", h.lastEditable())

        h.record(rec(isEditable = true, fingerprint = 555))
        assertEquals(555, h.lastEditable()?.textFingerprint)
    }

    /**
     * ★ 同窗口内焦点离开可编辑节点 → 清掉记录
     *
     * 【为什么】否则「回到可编辑框」会跳到一个已经不再编辑的位置，
     * 用户按了会莫名其妙。
     */
    @Test
    fun editable_is_cleared_when_focus_leaves_same_window() {
        val h = FocusActionHistory()
        h.record(rec(windowId = 1, isEditable = true, fingerprint = 555))
        h.record(rec(windowId = 1, isEditable = false, fingerprint = 666))
        assertNull("同窗口内离开可编辑节点，记录应清除", h.lastEditable())
    }

    /** 但换窗口不应清除 —— 用户可能还要回到那个输入框 */
    @Test
    fun editable_is_kept_when_other_window_changes() {
        val h = FocusActionHistory()
        h.record(rec(windowId = 1, isEditable = true, fingerprint = 555))
        h.record(rec(windowId = 2, isEditable = false, fingerprint = 666))
        assertEquals(
            "别的窗口的普通焦点不该清掉本窗口的输入框记录",
            555, h.lastEditable()?.textFingerprint
        )
    }

    // ==================== 事件匹配（节点 + 时间）====================

    /**
     * ★★ 匹配需要**节点指纹相同且时间在容差内**
     *
     * TalkBack `AccessibilityFocusActionHistory.java:320~326`：
     *   ```java
     *   boolean timeMatches = timeDiff >= 0 && timeDiff < timeoutToleranceMs;
     *   boolean nodeMatches = eventNode.equals(recordNode);
     *   if (nodeMatches) { if (timeMatches) { ... } }
     *   ```
     */
    @Test
    fun match_requires_both_node_and_time() {
        val h = FocusActionHistory()
        h.record(rec(actionTime = 1000, fingerprint = 111))

        assertTrue("指纹相同 + 时间在容差内 → 匹配", h.isOurs(111, 1100))
        assertFalse("指纹不同 → 不匹配（即使时间对）", h.isOurs(222, 1100))
        assertFalse("时间超容差 → 不匹配（即使指纹对）", h.isOurs(111, 2000))
    }

    @Test
    fun match_time_boundary() {
        val h = FocusActionHistory()
        h.record(rec(actionTime = 1000, fingerprint = 111))

        assertTrue("299ms 在容差内", h.isOurs(111, 1299))
        assertFalse("300ms 恰好等于容差 → 不匹配（< 判定）", h.isOurs(111, 1300))
    }

    @Test
    fun match_rejects_negative_time_diff() {
        val h = FocusActionHistory()
        h.record(rec(actionTime = 1000, fingerprint = 111))
        assertFalse(
            "事件早于动作 → 不可能是该动作的结果",
            h.isOurs(111, 900)
        )
    }

    @Test
    fun match_searches_from_newest() {
        val h = FocusActionHistory()
        // 同一节点指纹两条记录
        h.record(rec(actionTime = 1000, fingerprint = 111))
        h.record(rec(actionTime = 2000, fingerprint = 111))

        // 时间靠近 2000 的应命中新记录
        val matched = h.match(111, 2100)
        assertEquals(2000L, matched?.actionTime)
    }

    /** 自定义容差（网页场景用 500） */
    @Test
    fun match_accepts_custom_tolerance() {
        val h = FocusActionHistory()
        h.record(rec(actionTime = 1000, fingerprint = 111))

        assertFalse("默认 300ms 容差下 400ms 不匹配", h.isOurs(111, 1400))
        assertTrue(
            "网页容差 500ms 下应匹配",
            h.isOurs(111, 1400, FocusActionHistory.WEB_TIMEOUT_TOLERANCE_MS)
        )
    }

    @Test
    fun match_on_empty_history_is_false() {
        val h = FocusActionHistory()
        assertFalse(h.isOurs(111, 1000))
        assertNull(h.match(111, 1000))
    }

    // ==================== 来源判定 ====================

    @Test
    fun user_initiated_sources_are_recognized() {
        assertTrue(
            rec(source = FocusActionRecord.FOCUS_SOURCE_LOGICAL_NAVIGATION).isUserInitiated
        )
        assertTrue(
            rec(source = FocusActionRecord.FOCUS_SOURCE_TOUCH_EXPLORATION).isUserInitiated
        )
        assertFalse(
            "恢复焦点不算用户主动操作 —— 否则会自己触发竞态判定",
            rec(source = FocusActionRecord.FOCUS_SOURCE_RESTORE).isUserInitiated
        )
        assertFalse(rec(source = FocusActionRecord.FOCUS_SOURCE_UNKNOWN).isUserInitiated)
    }

    @Test
    fun source_constants_are_distinct() {
        val sources = listOf(
            FocusActionRecord.FOCUS_SOURCE_UNKNOWN,
            FocusActionRecord.FOCUS_SOURCE_LOGICAL_NAVIGATION,
            FocusActionRecord.FOCUS_SOURCE_TOUCH_EXPLORATION,
            FocusActionRecord.FOCUS_SOURCE_RESTORE
        )
        assertEquals("四个来源必须互不相同", sources.size, sources.toSet().size)
    }

    // ==================== 重置与统计 ====================

    @Test
    fun reset_clears_everything() {
        val h = FocusActionHistory()
        h.record(rec(windowId = 1, isEditable = true))
        h.record(rec(windowId = 2))

        h.reset()

        assertNull(h.last())
        assertEquals(0, h.size())
        assertEquals(0, h.windowCount())
        assertNull(h.lastEditable())
        assertNull(h.lastInWindow(1))
    }

    @Test
    fun stats_reports_counts() {
        val h = FocusActionHistory()
        h.record(rec(windowId = 1))
        h.record(rec(windowId = 2))
        val st = h.stats()
        assertTrue("应含条数：$st", st.contains("2"))
        assertTrue("应含窗口数：$st", st.contains("窗口"))
    }

    @Test
    fun records_list_is_ordered_newest_first() {
        val h = FocusActionHistory()
        h.record(rec(actionTime = 1000, fingerprint = 1))
        h.record(rec(actionTime = 2000, fingerprint = 2))
        h.record(rec(actionTime = 3000, fingerprint = 3))
        assertEquals(listOf(3, 2, 1), h.records().map { it.textFingerprint })
    }
}
