package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容变化过滤测试 —— 对标 TalkBack
 * `WindowContentChangeAnnouncementFilter` + `EventTypeWindowContentChangedFeedbackRule`
 *
 * 【这批测试的价值】
 *   修复前白茶对 `TYPE_WINDOW_CONTENT_CHANGED` **完全不播报** ——
 *   直播比分、下载进度、计时器这类只靠内容变化传递信息的场景，
 *   用户完全感知不到。
 *
 *   改完的风险是反向的：播太多会刷屏。
 *   所以这批测试的重点是**限流行为**是否精确。
 */
class ContentChangeFilterTest {

    // ==================== 限流行为 ====================

    /**
     * ★ 首次变化必定放行 —— 否则整个功能等于没接
     */
    @Test
    fun first_change_is_announced() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 1000))
    }

    /**
     * ★★ 同节点在间隔内 → 丢弃（这是防刷屏的核心）
     */
    @Test
    fun same_node_within_interval_is_throttled() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 1000))
        assertFalse("100ms 后同节点应被限流", f.shouldAnnounce(nodeKey = 111, now = 1100))
        assertFalse("400ms 后仍应限流", f.shouldAnnounce(nodeKey = 111, now = 1400))
    }

    /**
     * ★★ 换节点立即放行 —— 对齐 TalkBack 的设计
     *
     * 【为什么这条重要】
     *   用户焦点移到另一个元素，那个元素的变化**立即有意义**。
     *   若换节点也受上一个节点的限流影响，用户会觉得「读屏卡了」。
     */
    @Test
    fun different_node_is_announced_immediately() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 1000))
        assertTrue(
            "换节点必须立即放行",
            f.shouldAnnounce(nodeKey = 222, now = 1001)
        )
    }

    @Test
    fun same_node_after_interval_is_announced() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 1000))
        assertTrue("正好 500ms 应放行（>= 判定）", f.shouldAnnounce(nodeKey = 111, now = 1500))
    }

    @Test
    fun boundary_just_before_interval_is_throttled() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 1000))
        assertFalse("499ms 应被限流", f.shouldAnnounce(nodeKey = 111, now = 1499))
    }

    /**
     * ★ 无节点身份 → 放行，不静默丢失
     *
     * 【为什么】取不到 source 时无法限流。
     * 宁可偶尔多播一次，也不能把事件全吞掉 ——
     * 用户滑半天没声音比多念一句糟得多。
     */
    @Test
    fun unknown_node_is_never_throttled() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        assertTrue(f.shouldAnnounce(ContentChangeFilter.NO_NODE, 1000))
        assertTrue(f.shouldAnnounce(ContentChangeFilter.NO_NODE, 1001))
        assertTrue(f.shouldAnnounce(ContentChangeFilter.NO_NODE, 1002))
    }

    /** 限流计数用于诊断 */
    @Test
    fun throttled_count_is_tracked() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        f.shouldAnnounce(111, 1000)
        f.shouldAnnounce(111, 1100)
        f.shouldAnnounce(111, 1200)
        assertEquals(2L, f.throttledCount)
    }

    @Test
    fun reset_clears_state() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        f.shouldAnnounce(111, 1000)
        f.shouldAnnounce(111, 1100)   // 被限流

        f.reset()

        assertTrue("reset 后应重新放行", f.shouldAnnounce(111, 1150))
    }

    /**
     * 典型真实场景：进度条每 100ms 更新一次
     * → 500ms 内只播一次，而不是 5 次
     */
    @Test
    fun progress_bar_updates_are_throttled_to_interval() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        var announced = 0
        // 模拟 1 秒内每 100ms 一次更新
        for (t in 1000..2000 step 100) {
            if (f.shouldAnnounce(nodeKey = 42, now = t.toLong())) announced++
        }
        // 第 1 次 + 之后每 500ms 一次 → 1000/1500/2000 = 共 3 次
        assertEquals("1 秒内应只播 3 次而不是 11 次", 3, announced)
    }

    // ==================== 常量 ====================

    @Test
    fun minimum_cycle_matches_talkback() {
        assertEquals(
            "TalkBack CONTENT_CHANGE_MINIMUM_CYCLE = 500",
            500L,
            ContentChangeFilter.CONTENT_CHANGE_MINIMUM_CYCLE
        )
    }

    @Test
    fun no_node_marker_is_zero() {
        assertEquals(0, ContentChangeFilter.NO_NODE)
    }
}

/**
 * 内容变化语义分类测试
 *
 * 位掩码常量必须是**真实 Android 值** ——
 * 写错会导致「该播的不播、不该播的乱播」，且不会有任何报错。
 */
class ContentChangeKindTest {

    /**
     * ★ 常量值已用 `javap android.jar` 逐一核对：
     *   SUBTREE=1, TEXT=2, CONTENT_DESCRIPTION=4, STATE_DESCRIPTION=64
     */
    @Test
    fun constants_match_android() {
        assertEquals(1, ContentChangeKind.CONTENT_CHANGE_TYPE_SUBTREE)
        assertEquals(2, ContentChangeKind.CONTENT_CHANGE_TYPE_TEXT)
        assertEquals(4, ContentChangeKind.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION)
        assertEquals(64, ContentChangeKind.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION)
    }

    @Test
    fun text_change_is_classified_as_text() {
        assertEquals(
            ContentChangeKind.TEXT,
            ContentChangeKind.classify(ContentChangeKind.CONTENT_CHANGE_TYPE_TEXT)
        )
    }

    @Test
    fun state_description_is_classified() {
        assertEquals(
            ContentChangeKind.STATE_DESCRIPTION,
            ContentChangeKind.classify(ContentChangeKind.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION)
        )
    }

    @Test
    fun content_description_is_classified() {
        assertEquals(
            ContentChangeKind.CONTENT_DESCRIPTION,
            ContentChangeKind.classify(
                ContentChangeKind.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
            )
        )
    }

    @Test
    fun subtree_is_classified() {
        assertEquals(
            ContentChangeKind.SUBTREE,
            ContentChangeKind.classify(ContentChangeKind.CONTENT_CHANGE_TYPE_SUBTREE)
        )
    }

    @Test
    fun no_flags_is_none() {
        assertEquals(ContentChangeKind.NONE, ContentChangeKind.classify(0))
    }

    /**
     * 多个位同时置起 → 取最有信息量的（文本优先）
     * 对齐 TalkBack 的位掩码判定顺序
     */
    @Test
    fun text_wins_when_multiple_flags_set() {
        val both = ContentChangeKind.CONTENT_CHANGE_TYPE_SUBTREE or
                ContentChangeKind.CONTENT_CHANGE_TYPE_TEXT
        assertEquals(
            "同时带 SUBTREE 和 TEXT 时应取 TEXT",
            ContentChangeKind.TEXT,
            ContentChangeKind.classify(both)
        )
    }

    @Test
    fun text_beats_state_description() {
        val both = ContentChangeKind.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION or
                ContentChangeKind.CONTENT_CHANGE_TYPE_TEXT
        assertEquals(ContentChangeKind.TEXT, ContentChangeKind.classify(both))
    }

    // ==================== 该不该播 ====================

    @Test
    fun text_state_and_description_are_worth_announcing() {
        assertTrue(ContentChangeKind.isWorthAnnouncing(ContentChangeKind.TEXT))
        assertTrue(ContentChangeKind.isWorthAnnouncing(ContentChangeKind.STATE_DESCRIPTION))
        assertTrue(ContentChangeKind.isWorthAnnouncing(ContentChangeKind.CONTENT_DESCRIPTION))
    }

    /**
     * ★ 结构变化**不播** —— 它通常伴随焦点/窗口事件，
     *   那里已有专门处理，这里再播会重复。
     */
    @Test
    fun subtree_change_is_not_announced_here() {
        assertFalse(
            "子树增删交给焦点/窗口事件处理，此处不播避免重复",
            ContentChangeKind.isWorthAnnouncing(ContentChangeKind.SUBTREE)
        )
    }

    @Test
    fun none_is_not_announced() {
        assertFalse(ContentChangeKind.isWorthAnnouncing(ContentChangeKind.NONE))
    }
}
