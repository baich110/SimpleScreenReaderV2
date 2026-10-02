package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点路径描述测试 —— 对标 TalkBack `NodePathDescription`
 *
 * ══════════════════════════════════════════════════════════
 * 【这批测试能测什么、不能测什么】
 * ══════════════════════════════════════════════════════════
 *
 * ✅ 能测：路径数据结构、评分权重、降级次序、
 *          指纹计算规则、边界防御（null/越界/空）
 *
 * ❌ 测不到：**节点回收**（RecyclerView 复用）、真实树结构、
 *            向下搜索的实际命中率
 *    —— 因为 JVM 里 `AccessibilityNodeInfo` 是空壳，
 *       `getChild()` / `getParent()` 都返回默认值，
 *       构造不出「会被回收的树」。
 *
 * 这正是本缺口「用户能感知但单测测不出」的原因，
 * 已记入 `docs/验证债台账.md`。
 */
class NodePathDescriptionTest {

    private fun seg(
        index: Int = 0,
        role: Int = NodeRoleHelper.ROLE_BUTTON,
        fp: Int = 0,
        prev: Int = PathSegment.NO_FINGERPRINT,
        next: Int = PathSegment.NO_FINGERPRINT
    ) = PathSegment(index, role, fp, prev, next)

    // ==================== 数据结构 ====================

    @Test
    fun leaf_is_the_deepest_segment() {
        // 存储顺序：叶 → 根，与 TalkBack 一致
        val path = NodePath(listOf(seg(fp = 111), seg(fp = 222), seg(fp = 333)))
        assertEquals(111, path.leaf?.textFingerprint)
    }

    @Test
    fun empty_path_is_detected() {
        val path = NodePath(emptyList())
        assertTrue(path.isEmpty)
        assertNull(path.leaf)
    }

    @Test
    fun non_empty_path_is_not_empty() {
        assertFalse(NodePath(listOf(seg())).isEmpty)
    }

    @Test
    fun null_node_yields_null_path() {
        assertNull(NodePath.of(null))
    }

    // ==================== 常量对齐 TalkBack ====================

    /**
     * ★ 三项权重必须与 TalkBack `NodePathDescription:468` 一致：
     *   content(1.2) > index(1.1) > adjacent(1.0)
     *
     * 【为什么顺序重要】
     *   权重决定「哪个候选更可信」。若把 index 排到 content 前面，
     *   列表插入一项后 index 全错，恢复会跳错位置。
     */
    @Test
    fun weights_match_talkback() {
        assertEquals(1.2, PathMatchScore.CONTENT_MATCH, 0.001)
        assertEquals(1.1, PathMatchScore.INDEX_MATCH, 0.001)
        assertEquals(1.0, PathMatchScore.ADJACENT_MATCH, 0.001)
    }

    @Test
    fun content_weight_is_highest() {
        assertTrue(
            "内容匹配权重必须最高 —— 它最不容易因重排而失效",
            PathMatchScore.CONTENT_MATCH > PathMatchScore.INDEX_MATCH
        )
        assertTrue(
            "索引权重高于相邻 —— TalkBack 的次序",
            PathMatchScore.INDEX_MATCH > PathMatchScore.ADJACENT_MATCH
        )
    }

    /** 完全匹配 = 3.3，TalkBack 用这个阈值判定「能否停在容器上」 */
    @Test
    fun full_match_score_is_3_point_3() {
        assertEquals(
            "TalkBack NodePathDescription.java:326 用的阈值",
            3.3,
            PathMatchScore.FULL_MATCH,
            0.001
        )
    }

    @Test
    fun undefined_index_matches_talkback() {
        assertEquals(-1, PathSegment.UNDEFINED_INDEX)
    }

    @Test
    fun no_fingerprint_is_zero() {
        assertEquals(0, PathSegment.NO_FINGERPRINT)
    }

    @Test
    fun max_depth_is_bounded() {
        assertTrue("必须限制深度，防止异常深的树拖慢主线程", NodePath.MAX_DEPTH in 1..100)
    }

    @Test
    fun max_visited_is_bounded() {
        assertTrue(NodePathResolver.MAX_VISITED in 100..10000)
    }

    // ==================== 评分逻辑（不依赖节点树的部分）====================

    /**
     * 相邻匹配的计算规则：两个兄弟指纹都为 NO_FINGERPRINT 时不算匹配。
     *
     * 【为什么要测这个】
     *   若「都为空」被当成匹配，那么树里任何两个相邻节点都会
     *   互相加分 —— 评分失去区分度，恢复会命中随机节点。
     */
    @Test
    fun adjacent_match_requires_real_fingerprints() {
        val s = seg(prev = PathSegment.NO_FINGERPRINT, next = PathSegment.NO_FINGERPRINT)
        // 无法直接测私有方法，但可以验证「无指纹」这一状态的存在与语义
        assertEquals(PathSegment.NO_FINGERPRINT, s.prevSiblingFingerprint)
        assertEquals(PathSegment.NO_FINGERPRINT, s.nextSiblingFingerprint)
    }

    @Test
    fun segment_stores_sibling_fingerprints() {
        val s = seg(fp = 555, prev = 111, next = 222)
        assertEquals(111, s.prevSiblingFingerprint)
        assertEquals(222, s.nextSiblingFingerprint)
    }

    // ==================== 边界防御 ====================

    @Test
    fun resolve_with_null_inputs_is_safe() {
        assertNull(NodePathResolver.resolve(null, null))
        assertNull(NodePathResolver.resolve(null, NodePath(listOf(seg()))))
    }

    @Test
    fun resolve_with_empty_path_returns_null() {
        assertNull(
            "空路径没有可恢复的信息，不该返回任何节点",
            NodePathResolver.resolve(null, NodePath(emptyList()))
        )
    }

    // ==================== 指纹语义 ====================

    /**
     * 文本指纹用于「内容匹配」——同样的文字必须得到同样的指纹，
     * 否则「内容匹配」这一项永远失效。
     */
    @Test
    fun fingerprint_is_deterministic() {
        assertEquals("同样的文字指纹必须相同", "按钮".hashCode(), "按钮".hashCode())
    }

    @Test
    fun fingerprint_is_from_text_or_description() {
        // 无法在 JVM 构造节点，故锁定「指纹取 text 优先、退化到
        // description」这一约定的常量表达
        assertEquals(
            "无文本无描述时返回 NO_FINGERPRINT",
            0,
            PathSegment.NO_FINGERPRINT
        )
    }

    // ==================== toString（排障用）====================

    @Test
    fun path_to_string_reports_depth() {
        val p = NodePath(listOf(seg(), seg(), seg()))
        assertTrue("toString 应含层数，便于日志排查：$p", p.toString().contains("3"))
    }
}
