package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过滤器缓存 —— **跨过滤器污染**（潜在缺陷，已实证）
 *
 * ══════════════════════════════════════════════════════════
 * 【问题】`NodeCache.filterAccept()` 的缓存键**只有 node**：
 * ══════════════════════════════════════════════════════════
 *
 * ```kotlin
 *   fun filterAccept(node: AccessibilityNodeInfo, evaluator: () -> Boolean): Boolean {
 *       val gen = generation
 *       filterCache[node]?.let {
 *           if (it.gen == gen) return it.value      // ← 只按 node 查
 *       }
 *       val result = evaluator()
 *       ...
 *       filterCache[node] = CacheEntry(result, gen) // ← 只按 node 存
 *   }
 * ```
 *
 * 而 `NodeFilter.CachedFilter` 把**任意**过滤器挂上去：
 * ```kotlin
 *   private class CachedFilter(val inner: NodeFilter) : NodeFilter() {
 *       override fun accept(node: AccessibilityNodeInfo?): Boolean {
 *           ...
 *           return c.filterAccept(node) { inner.accept(node) }
 *       }
 *   }
 * ```
 *
 * ⇒ 两个**不同**的过滤器作用在**同一个节点**上时会互相污染：
 * ```
 *   filterA.cached().accept(n)  → 写入 filterCache[n] = A(n)
 *   filterB.cached().accept(n)  → **命中**，直接返回 A(n)   ✗ 应为 B(n)
 * ```
 *
 * 这与 `NodeCache` 注释里已经意识到的问题同类
 * （那里写明了「filterCache 必须与 focusCache 隔离，
 *  否则用过滤器的结果覆盖可聚焦判定的结果 = 静默的数据污染」）——
 * 只是隔离了第一层，没隔离第二层。
 *
 * ══════════════════════════════════════════════════════════
 * 【当前是否影响真机：不影响】
 * ══════════════════════════════════════════════════════════
 *
 * `grep` 全项目：`.cached()` 在**生产代码中没有任何调用点**
 * （只在 `NavigationMode` 的注释里被提到）。
 * ⇒ 目前是**潜在**缺陷，不是现行 bug。
 *
 * 【处置】按「基础优先、不修真死代码」原则：
 *   · 不接线（无法真机验证）
 *   · 但用**本测试把语义钉死**，避免将来有人一用就踩
 *   · 在 `NodeCache.filterAccept` 的注释里写明这个限制
 *
 * 【若要修复，正确做法】给缓存键加上「过滤器身份」维度 ——
 * 例如 `filterAccept(key: Any, node, evaluator)`，
 * 或让每个 `CachedFilter` 实例持有自己的小 Map。
 */
class NodeFilterCachePollutionTest {

    @After
    fun tearDown() {
        NodeCache().hardClear()
        NodeOrderComparator.cache = null
    }

    private fun acceptTrue() = object : NodeFilter() {
        override fun accept(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean = true
        override val name: String get() = "alwaysTrue"
    }

    private fun acceptFalse() = object : NodeFilter() {
        override fun accept(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean = false
        override val name: String get() = "alwaysFalse"
    }

    // ==================== ★ 核心：跨过滤器污染 ====================

    /**
     * ★★ 实证：两个不同过滤器共用同一节点时，后者拿到前者的结果
     *
     * 这条测试**故意断言「污染确实发生」**—— 它记录的是当前行为，
     * 目的是让缺陷显式可见，而不是藏在代码里等将来爆。
     */
    @Test
    fun twoDifferentCachedFilters_onSameNode_polluteEachOther() {
        val cache = NodeCache()
        NodeOrderComparator.cache = cache

        val n = FakeNode(1, "节点")

        val a = acceptTrue().cached()
        val b = acceptFalse().cached()

        assertTrue("A 过滤器恒真", a.accept(n))
        // ★ 实测：B 恒假却返回 true —— 拿到了 A 的缓存结果
        //
        // 【我第一版把这条断言写反了】我写成 `assertFalse(b.accept(n))`
        // 并以为「污染会让它返回 true 所以应该断言 false」——
        // 逻辑上说反了：**要证明污染发生，就应该断言它返回 true**
        // （即「与 B 自己的语义不符」）。
        // 已用探针实测确认：a=true, b=true。
        assertTrue(
            "★ B 过滤器恒假，却因为缓存键只有 node 而拿到 A 的结果（true）—— " +
                    "这就是跨过滤器污染。探针实测确认。",
            b.accept(n)
        )
    }

    /**
     * 对照：**同一个**过滤器实例重复查询，缓存应正常命中（这是缓存的正当用途）
     */
    @Test
    fun sameCachedFilter_reusesResultWithinGeneration() {
        val cache = NodeCache()
        NodeOrderComparator.cache = cache

        var calls = 0
        val f = object : NodeFilter() {
            override fun accept(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                calls++
                return true
            }
            override val name: String get() = "counting"
        }.cached()

        val n = FakeNode(1)
        repeat(5) { f.accept(n) }
        assertEquals("同一过滤器+同一节点+同一代数 → 只求值一次", 1, calls)
    }

    /** 代数失效后必须重新求值（缓存不能永不失效） */
    @Test
    fun afterInvalidate_reEvaluates() {
        val cache = NodeCache()
        NodeOrderComparator.cache = cache

        var calls = 0
        val f = object : NodeFilter() {
            override fun accept(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                calls++
                return true
            }
            override val name: String get() = "counting"
        }.cached()

        val n = FakeNode(1)
        f.accept(n)
        cache.invalidateAll()
        f.accept(n)
        assertEquals("失效后应重新求值", 2, calls)
    }

    // ==================== 组合子语义（与缓存无关，独立正确性）====================

    @Test
    fun andFilter_requiresBoth() {
        val t = acceptTrue()
        val f = acceptFalse()
        val n = FakeNode(1)

        assertTrue(t.and(t).accept(n))
        assertFalse(t.and(f).accept(n))
        assertFalse(f.and(t).accept(n))
        assertFalse(f.and(f).accept(n))
    }

    @Test
    fun orFilter_requiresAny() {
        val t = acceptTrue()
        val f = acceptFalse()
        val n = FakeNode(1)

        assertTrue(t.or(t).accept(n))
        assertTrue(t.or(f).accept(n))
        assertTrue(f.or(t).accept(n))
        assertFalse(f.or(f).accept(n))
    }

    @Test
    fun notFilter_inverts() {
        val n = FakeNode(1)
        assertFalse(acceptTrue().not().accept(n))
        assertTrue(acceptFalse().not().accept(n))
    }

    /**
     * 组合过滤器遇 null 必须**不崩** —— 但不保证返回 false
     *
     * 【我第一版写错了这条测试】
     * 我断言「AND/OR 遇 null 应返回 false」。实测**返回 true**：
     * `AndFilter.accept(null) = a.accept(null) && b.accept(null)`，
     * 而我的 `acceptTrue()` 过滤器**无条件返回 true**（它不看 node），
     * 所以 `true && true = true`。
     *
     * 这不是缺陷 —— 组合子只是转发给内层，内层怎么写就怎么算。
     * 真正的约束是「组合子本身不得对 null 崩溃」，
     * 以及 `CachedFilter` 明确对 null 返回 false（它有自己的 null 检查）。
     */
    @Test
    fun combinatedFilters_handleNullWithoutCrashing() {
        val t = acceptTrue()
        val f = acceptFalse()

        // 不崩即为通过；值取决于内层过滤器
        assertTrue("无条件 true 的过滤器组合后遇 null 仍为 true（内层不看 node）",
            t.and(t).accept(null))
        assertFalse("内层 false 则组合结果为 false", t.and(f).accept(null))
        assertTrue("OR 同理", t.or(f).accept(null))
        f.or(f).accept(null)
        t.not().accept(null)

        // CachedFilter 有显式 null 检查 —— 必须返回 false
        assertFalse("CachedFilter 明确对 null 返回 false", t.cached().accept(null))
    }

    /** `and(null)` / `or(null)` 应返回自身（不做无谓包装） */
    @Test
    fun andOr_null_returnsSelf() {
        val t = acceptTrue()
        assertEquals("and(null) 应返回自身", t, t.and(null))
        assertEquals("or(null) 应返回自身", t, t.or(null))
    }

    /** 组合过滤器的 name 应可读（诊断用） */
    @Test
    fun combinedFilter_name_isReadable() {
        val t = acceptTrue()
        assertTrue(t.and(t).name.contains("且"))
        assertTrue(t.or(t).name.contains("或"))
        assertTrue(t.not().name.contains("非"))
        assertTrue(t.cached().name.contains("缓存"))
    }
}
