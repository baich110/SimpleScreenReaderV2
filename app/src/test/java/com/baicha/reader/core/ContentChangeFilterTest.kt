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
    /**
     * ★ 说明「跳过首次」的语义
     *
     * TalkBack 的 `lastUpdatedNode` 初值为 null，
     * `node.equals(null)` 为 false → **第一次调用也走「换节点」分支 →
     * 同样跳过**。这是对的：第一个内容变化事件通常与焦点事件
     * 同一时刻发生，焦点事件已经念过了。
     *
     * 后续需要「首次即播」的场景（测试限流行为）显式关闭该开关。
     */
    @Test
    fun first_call_also_skips_by_default() {
        val f = ContentChangeFilter(minIntervalMs = 500)
        assertFalse(
            "首次调用也走换节点分支 → 跳过（与 TalkBack 一致）",
            f.shouldAnnounce(nodeKey = 111, now = 1000)
        )
    }

    @Test
    fun first_change_is_announced_when_skip_disabled() {
        val f = ContentChangeFilter(minIntervalMs = 500, skipFirstOnNodeChange = false)
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 1000))
    }

    /**
     * ★★ 同节点在间隔内 → 丢弃（这是防刷屏的核心）
     */
    @Test
    fun same_node_within_interval_is_throttled() {
        val f = ContentChangeFilter(minIntervalMs = 500, skipFirstOnNodeChange = false)
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 1000))
        assertFalse("100ms 后同节点应被限流", f.shouldAnnounce(nodeKey = 111, now = 1100))
        assertFalse("400ms 后仍应限流", f.shouldAnnounce(nodeKey = 111, now = 1400))
    }

    /**
     * ★★ 换节点**跳过首次播报** —— 对齐 TalkBack（初版我理解反了）
     *
     * TalkBack `WindowContentChangeAnnouncementFilter:68~78`：
     *   "Skip the first announcement, to avoid two announcements in
     *    quick succession: one from accessibility focus and the other
     *    from this window content change."
     *
     * 即：节点刚被聚焦时，焦点事件已经念过一次内容了，
     * 紧接着的内容变化事件再念一遍就是重复。
     */
    /**
     * ★★ 换节点跳过首次，且**计时也重置**
     *
     * TalkBack：
     *   ```java
     *   } else {
     *       lastUpdatedNode = node;
     *       timeStamp = now;        // ← 计时同样重置
     *       ... return false;       // 跳过首次
     *   }
     *   ```
     * 所以「跳过首次」之后的第二次调用仍受 minInterval 限制 ——
     * 不是「跳过一次就立刻能播第二次」。
     */
    @Test
    fun different_node_skips_first_and_resets_timer() {
        val g = ContentChangeFilter(minIntervalMs = 30000)
        assertFalse(
            "换到新节点的第一次应跳过（避免与焦点事件重复）",
            g.shouldAnnounce(nodeKey = 222, now = 1001)
        )
        assertFalse(
            "跳过后的第二次仍受间隔限制（计时已重置）",
            g.shouldAnnounce(nodeKey = 222, now = 1002)
        )
        assertTrue(
            "满 30 秒后应放行",
            g.shouldAnnounce(nodeKey = 222, now = 1001 + 30000)
        )
    }

    /**
     * ★★ 但 live region **不跳过** —— 对齐 TalkBack
     *   "Live regions should not be skipped."
     *
     * live region 的语义就是「内容变了要主动告知」，
     * 跳过首次会让它失去意义。
     */
    @Test
    fun live_region_does_not_skip_first_announcement() {
        val f = ContentChangeFilter(minIntervalMs = 30000)
        // 先让 111 成为「上一次」，再换到 live region 节点
        f.shouldAnnounce(nodeKey = 111, now = 1000, isLiveRegion = true)
        assertTrue(
            "live region 的首播不该被跳过",
            f.shouldAnnounce(nodeKey = 222, now = 1001, isLiveRegion = true)
        )
    }

    @Test
    fun same_node_after_interval_is_announced() {
        val f = ContentChangeFilter(minIntervalMs = 500, skipFirstOnNodeChange = false)
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 1000))
        assertTrue("正好 500ms 应放行（>= 判定）", f.shouldAnnounce(nodeKey = 111, now = 1500))
    }

    /** 默认间隔下，30 秒内同节点只播一次 */
    @Test
    fun default_interval_is_thirty_seconds() {
        val f = ContentChangeFilter(skipFirstOnNodeChange = false)   // 用默认间隔
        assertTrue(f.shouldAnnounce(nodeKey = 111, now = 0))
        assertFalse("1 秒后应被限流", f.shouldAnnounce(nodeKey = 111, now = 1000))
        assertFalse("29 秒后仍应被限流", f.shouldAnnounce(nodeKey = 111, now = 29000))
        assertTrue("30 秒后应放行", f.shouldAnnounce(nodeKey = 111, now = 30000))
    }

    @Test
    fun boundary_just_before_interval_is_throttled() {
        val f = ContentChangeFilter(minIntervalMs = 500, skipFirstOnNodeChange = false)
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
        val f = ContentChangeFilter(minIntervalMs = 500, skipFirstOnNodeChange = false)
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
        val f = ContentChangeFilter(minIntervalMs = 500, skipFirstOnNodeChange = false)
        var announced = 0
        // 模拟 1 秒内每 100ms 一次更新
        for (t in 1000..2000 step 100) {
            if (f.shouldAnnounce(nodeKey = 42, now = t.toLong())) announced++
        }
        // 第 1 次 + 之后每 500ms 一次 → 1000/1500/2000 = 共 3 次
        assertEquals("1 秒内应只播 3 次而不是 11 次", 3, announced)
    }

    // ==================== 常量 ====================

    /**
     * ★★ 常量值必须与 TalkBack 一致 —— **这条测试修正了我自己写错的值**
     *
     * 初版我写 500 并在注释里声称「TalkBack 取值为 500」，
     * 真实值是 **30000**，差了 60 倍。
     * 源码：`WindowContentChangeAnnouncementFilter.java:15~19`
     */
    @Test
    fun minimum_cycle_matches_talkback() {
        assertEquals(
            "TalkBack CONTENT_CHANGE_MINIMUM_CYCLE = 30000（30 秒）",
            30000L,
            ContentChangeFilter.CONTENT_CHANGE_MINIMUM_CYCLE
        )
    }

    @Test
    fun short_and_long_durations_match_talkback() {
        assertEquals("SHORT_DURATION = 5000", 5000L, ContentChangeFilter.SHORT_DURATION)
        assertEquals("LONG_DURATION = 10000", 10000L, ContentChangeFilter.LONG_DURATION)
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

    /**
     * ★ 0 的含义 —— TalkBack `CONTENT_CHANGE_TYPE_UNDEFINED = 0`，
     *   所以「无标志位」被分类为 [ContentChangeKind.UNDEFINED]，
     *   而不是 [ContentChangeKind.NONE]。
     *
     * 【为什么要区分】UNDEFINED 表示「有内容变化事件但没标类型」，
     * 而 NONE 表示「不属于任何已知类别」。
     */
    @Test
    fun zero_flags_is_undefined() {
        assertEquals(
            "0 == CONTENT_CHANGE_TYPE_UNDEFINED（TalkBack 语义）",
            ContentChangeKind.UNDEFINED,
            ContentChangeKind.classify(0)
        )
    }

    /**
     * ★★ **判定顺序必须严格对齐 TalkBack** —— 这条测试是补上的
     *
     * 变异测试发现：把 classify 的顺序改成我初版写的那样
     * （TEXT 优先于 CONTENT_DESCRIPTION），**所有测试仍然通过** ——
     * 因为原测试只测了「单个位」的情形，而单个位无论顺序如何结果都一样。
     *
     * 顺序只有在**多个位同时置起**时才起作用。
     * 所以必须构造「两位都置」的输入才能锁住顺序。
     *
     * 源码依据 `EventTypeWindowContentChangedFeedbackRule:276~302`：
     *   CONTENT_DESCRIPTION → TEXT → STATE_DESCRIPTION
     */
    @Test
    fun content_description_wins_over_text() {
        val both = ContentChangeKind.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION or
                ContentChangeKind.CONTENT_CHANGE_TYPE_TEXT
        assertEquals(
            "TalkBack 里 CONTENT_DESCRIPTION 优先于 TEXT",
            ContentChangeKind.CONTENT_DESCRIPTION,
            ContentChangeKind.classify(both)
        )
    }

    @Test
    fun text_wins_over_state_description() {
        val both = ContentChangeKind.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION or
                ContentChangeKind.CONTENT_CHANGE_TYPE_TEXT
        assertEquals(
            "TEXT 优先于 STATE_DESCRIPTION",
            ContentChangeKind.TEXT,
            ContentChangeKind.classify(both)
        )
    }

    @Test
    fun state_description_wins_over_drag() {
        val both = ContentChangeKind.CONTENT_CHANGE_TYPE_DRAG_STARTED or
                ContentChangeKind.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
        assertEquals(
            "STATE_DESCRIPTION 优先于 DRAG_STARTED",
            ContentChangeKind.STATE_DESCRIPTION,
            ContentChangeKind.classify(both)
        )
    }

    @Test
    fun text_wins_over_subtree() {
        val both = ContentChangeKind.CONTENT_CHANGE_TYPE_SUBTREE or
                ContentChangeKind.CONTENT_CHANGE_TYPE_TEXT
        assertEquals(ContentChangeKind.TEXT, ContentChangeKind.classify(both))
    }

    /**
     * ★ 完整顺序链：把 TalkingBack 的 9 类两两组合，
     *   验证「靠前的胜出」
     */
    @Test
    fun full_priority_chain_matches_talkback() {
        // 按 TalkBack 的判定顺序排列（越靠前优先级越高）
        val order = listOf(
            ContentChangeKind.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION to
                ContentChangeKind.CONTENT_DESCRIPTION,
            ContentChangeKind.CONTENT_CHANGE_TYPE_TEXT to ContentChangeKind.TEXT,
            ContentChangeKind.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION to
                ContentChangeKind.STATE_DESCRIPTION,
            ContentChangeKind.CONTENT_CHANGE_TYPE_DRAG_STARTED to
                ContentChangeKind.DRAG_STARTED,
            ContentChangeKind.CONTENT_CHANGE_TYPE_DRAG_DROPPED to
                ContentChangeKind.DRAG_DROPPED,
            ContentChangeKind.CONTENT_CHANGE_TYPE_DRAG_CANCELLED to
                ContentChangeKind.DRAG_CANCELLED,
            ContentChangeKind.CONTENT_CHANGE_TYPE_ERROR to ContentChangeKind.ERROR,
            ContentChangeKind.CONTENT_CHANGE_TYPE_ENABLED to ContentChangeKind.ENABLED
        )
        // 相邻两项组合 → 前一项必须胜出
        for (i in 0 until order.size - 1) {
            val (higherBit, higherKind) = order[i]
            val (lowerBit, _) = order[i + 1]
            assertEquals(
                "第 $i 位（${order[i].second}）应优先于第 ${i + 1} 位",
                higherKind,
                ContentChangeKind.classify(higherBit or lowerBit)
            )
        }
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
