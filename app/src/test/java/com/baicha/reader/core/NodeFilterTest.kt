package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点过滤器组合测试 —— 对应 TalkBack `Filter` / 天坦 `w7/M`
 *
 * 组合语义写错会导致遍历时「该停的不停」或「不该停的停了」，
 * 属于最难排查的一类问题，所以必须有测试锁定。
 */
class NodeFilterTest {

    // ==================== 基础行为 ====================

    @Test
    fun any_rejects_null() {
        assertFalse("任意过滤器对 null 仍应返回 false", NodeFilter.ANY.accept(null))
    }

    @Test
    fun visible_rejects_null() {
        assertFalse("可见过滤器对 null 应返回 false", NodeFilter.VISIBLE.accept(null))
    }

    @Test
    fun focusable_rejects_null() {
        assertFalse(NodeFilter.FOCUSABLE.accept(null))
    }

    @Test
    fun clickable_rejects_null() {
        assertFalse(NodeFilter.CLICKABLE.accept(null))
    }

    @Test
    fun scrollable_rejects_null() {
        assertFalse(NodeFilter.SCROLLABLE.accept(null))
    }

    @Test
    fun all_presets_reject_null() {
        val presets = listOf(
            NodeFilter.VISIBLE, NodeFilter.FOCUSABLE, NodeFilter.CLICKABLE,
            NodeFilter.SCROLLABLE, NodeFilter.GRID, NodeFilter.IME,
            NodeFilter.CONTROL, NodeFilter.HAS_TEXT, NodeFilter.HEADING
        )
        for (p in presets) {
            assertFalse("${p.name} 对 null 应返回 false", p.accept(null))
        }
    }

    // ==================== 组合语义 ====================

    /** 构造可控的测试过滤器 */
    private fun filterOf(value: Boolean, n: String = "t") = object : NodeFilter() {
        override fun accept(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean = value
        override val name: String get() = n
    }

    @Test
    fun and_requires_both() {
        val t = filterOf(true)
        val f = filterOf(false)

        assertTrue("真 且 真 = 真", t.and(t).accept(null))
        assertFalse("真 且 假 = 假", t.and(f).accept(null))
        assertFalse("假 且 真 = 假", f.and(t).accept(null))
        assertFalse("假 且 假 = 假", f.and(f).accept(null))
    }

    @Test
    fun or_requires_either() {
        val t = filterOf(true)
        val f = filterOf(false)

        assertTrue("真 或 真 = 真", t.or(t).accept(null))
        assertTrue("真 或 假 = 真", t.or(f).accept(null))
        assertTrue("假 或 真 = 真", f.or(t).accept(null))
        assertFalse("假 或 假 = 假", f.or(f).accept(null))
    }

    @Test
    fun not_inverts() {
        val t = filterOf(true)
        val f = filterOf(false)

        assertFalse("非真 = 假", t.not().accept(null))
        assertTrue("非假 = 真", f.not().accept(null))
    }

    @Test
    fun and_with_null_returns_self() {
        val f = filterOf(true)
        assertEquals("与 null 组合应返回自身", f, f.and(null))
    }

    @Test
    fun or_with_null_returns_self() {
        val f = filterOf(true)
        assertEquals("或 null 组合应返回自身", f, f.or(null))
    }

    // ==================== 短路求值 ====================

    @Test
    fun and_short_circuits() {
        var rightCalled = false
        val left = filterOf(false, "左")
        val right = object : NodeFilter() {
            override fun accept(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                rightCalled = true
                return true
            }
        }
        // 左为假 → 右不应被求值
        left.and(right).accept(null)
        assertFalse("且 应短路（左假时不求值右）", rightCalled)
    }

    @Test
    fun or_short_circuits() {
        var rightCalled = false
        val left = filterOf(true, "左")
        val right = object : NodeFilter() {
            override fun accept(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                rightCalled = true
                return true
            }
        }
        // 左为真 → 右不应被求值
        left.or(right).accept(null)
        assertFalse("或 应短路（左真时不求值右）", rightCalled)
    }

    // ==================== 链式组合 ====================

    @Test
    fun chained_composition_works() {
        val t = filterOf(true)
        val f = filterOf(false)

        // (真 且 真) 或 假 = 真
        assertTrue(t.and(t).or(f).accept(null))
        // (真 且 假) 或 假 = 假
        assertFalse(t.and(f).or(f).accept(null))
        // 非(真 且 假) = 真
        assertTrue(t.and(f).not().accept(null))
    }

    @Test
    fun deep_chain_is_stable() {
        val t = filterOf(true)
        var f: NodeFilter = t
        repeat(50) { f = f.and(t) }
        assertTrue("50 层组合应仍可求值", f.accept(null))
    }

    // ==================== 命名（可诊断性）====================

    @Test
    fun composed_filters_have_readable_names() {
        val a = filterOf(true, "甲")
        val b = filterOf(true, "乙")

        assertTrue("且组合名应含两部分", a.and(b).name.contains("甲"))
        assertTrue("或组合名应含两部分", a.or(b).name.contains("乙"))
        assertTrue("非组合名应含内层名", a.not().name.contains("甲"))
    }

    @Test
    fun presets_have_chinese_names() {
        assertEquals("任意", NodeFilter.ANY.name)
        assertEquals("可见", NodeFilter.VISIBLE.name)
        assertEquals("可聚焦", NodeFilter.FOCUSABLE.name)
        assertEquals("可点击", NodeFilter.CLICKABLE.name)
        assertEquals("可滚动", NodeFilter.SCROLLABLE.name)
        assertEquals("网格", NodeFilter.GRID.name)
        assertEquals("交互控件", NodeFilter.CONTROL.name)
        assertEquals("有文本", NodeFilter.HAS_TEXT.name)
        assertEquals("标题", NodeFilter.HEADING.name)
    }

    // ==================== 缓存包装 ====================

    @Test
    fun cached_filter_returns_wrapped() {
        val c = filterOf(true).cached()
        assertNotNull(c)
        assertTrue("缓存包装名应标明", c.name.contains("缓存"))
    }

    @Test
    fun cached_filter_rejects_null() {
        val c = filterOf(true).cached()
        assertFalse(c.accept(null))
    }

    // ==================== 转换辅助 ====================

    @Test
    fun as_predicate_works() {
        val pred = filterOf(true).asPredicate()
        // 只需确认类型正确、可调用
        assertNotNull(pred)
    }

    @Test
    fun lambda_as_filter_works() {
        val f = ({ _: android.view.accessibility.AccessibilityNodeInfo -> true }).asFilter()
        assertEquals("自定义", f.name)
        assertFalse("自定义过滤器对 null 应返回 false", f.accept(null))
    }
}
