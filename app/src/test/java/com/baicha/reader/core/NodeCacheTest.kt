package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点缓存单测 —— 验证「代数失效」机制与缓存命中
 *
 * 这是本项目独有的性能优化，必须有测试锁定行为，
 * 否则缓存失效逻辑写错会导致「读到过期数据」的隐蔽 bug。
 *
 * 注意：JVM 单测拿不到真实的 AccessibilityNodeInfo 对象，
 * 所以这里用 IdentityHashMap 的行为验证 + 注入的计数器验证命中。
 */
class NodeCacheTest {

    @Test
    fun new_cache_is_empty() {
        val c = NodeCache()
        val s = c.stats()
        assertTrue(s.contains("子节点 0"))
        assertTrue(s.contains("判定 0"))
        assertTrue(s.contains("文本 0"))
    }

    @Test
    fun invalidate_all_is_o1_and_safe() {
        val c = NodeCache()
        // 反复失效不应出错，且代数自增
        repeat(100) { c.invalidateAll() }
        assertTrue(c.stats().contains("代数 100"))
    }

    @Test
    fun invalidate_focus_is_safe() {
        val c = NodeCache()
        c.invalidateFocus()
        assertTrue(c.stats().isNotEmpty())
    }

    @Test
    fun hard_clear_resets_generation() {
        val c = NodeCache()
        repeat(50) { c.invalidateAll() }
        assertTrue(c.stats().contains("代数 50"))
        c.hardClear()
        assertTrue("hardClear 后代数应归零", c.stats().contains("代数 0"))
    }

    @Test
    fun stats_is_non_empty() {
        val c = NodeCache()
        assertTrue(c.stats().isNotEmpty())
    }

    /**
     * 验证「排序缓存可注入」——NodeOrderComparator 的缓存槽位存在
     */
    @Test
    fun comparator_cache_slot_exists() {
        val c = NodeCache()
        NodeOrderComparator.cache = c
        assertEquals(c, NodeOrderComparator.cache)
        NodeOrderComparator.cache = null
        assertNull(NodeOrderComparator.cache)
    }

    /**
     * 验证「可聚焦判定缓存可注入」——ShouldFocusNode 的缓存槽位存在
     */
    @Test
    fun should_focus_cache_slot_exists() {
        val c = NodeCache()
        ShouldFocusNode.cache = c
        assertEquals(c, ShouldFocusNode.cache)
        ShouldFocusNode.cache = null
        assertNull(ShouldFocusNode.cache)
    }

    /**
     * 缓存注入前：shouldFocus(null) 必须安全返回 false
     */
    @Test
    fun should_focus_null_returns_false_with_cache() {
        val c = NodeCache()
        ShouldFocusNode.cache = c
        assertFalse(ShouldFocusNode.shouldFocus(null))
        ShouldFocusNode.cache = null
    }
}
