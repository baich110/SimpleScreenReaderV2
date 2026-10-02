package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存注册表单测 —— 验证分级失效的包含关系
 *
 * 这是工程化设施：让「挂失效钩子」从靠记忆变成靠机制。
 * 包含关系写错会导致「该清的没清」（读脏数据）
 * 或「不该清的清了」（性能损失）。
 */
class CacheRegistryTest {

    @After
    fun tearDown() {
        CacheRegistry.reset()
        CacheRegistry.enabled = true
    }

    // ==================== 基础登记与失效 ====================

    @Test
    fun register_and_invalidate() {
        CacheRegistry.reset()
        var called = false
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "test") { called = true }

        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)
        assertTrue("已登记的缓存应被失效", called)
    }

    @Test
    fun invalidate_returns_handler_count() {
        CacheRegistry.reset()
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "a") {}
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "b") {}
        assertEquals(2, CacheRegistry.invalidate(CacheRegistry.Level.WINDOW))
    }

    @Test
    fun disabled_registry_does_nothing() {
        CacheRegistry.reset()
        var called = false
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "test") { called = true }
        CacheRegistry.enabled = false

        assertEquals(0, CacheRegistry.invalidate(CacheRegistry.Level.WINDOW))
        assertTrue("禁用时不应执行失效", !called)
    }

    // ==================== 分级包含关系（关键）====================

    /**
     * CONFIG ⊇ WINDOW ⊇ CONTENT ⊇ SCROLL
     * 配置变化是最剧烈的，所有缓存都该清。
     */
    @Test
    fun config_invalidates_everything() {
        CacheRegistry.reset()
        var configCalled = false
        var windowCalled = false
        var contentCalled = false
        var scrollCalled = false

        CacheRegistry.register(CacheRegistry.Level.CONFIG, "c") { configCalled = true }
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "w") { windowCalled = true }
        CacheRegistry.register(CacheRegistry.Level.CONTENT, "ct") { contentCalled = true }
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "s") { scrollCalled = true }

        CacheRegistry.invalidate(CacheRegistry.Level.CONFIG)

        assertTrue("CONFIG 应清 CONFIG 级", configCalled)
        assertTrue("CONFIG 应清 WINDOW 级", windowCalled)
        assertTrue("CONFIG 应清 CONTENT 级", contentCalled)
        assertTrue("CONFIG 应清 SCROLL 级", scrollCalled)
    }

    @Test
    fun window_invalidates_window_content_scroll() {
        CacheRegistry.reset()
        var configCalled = false
        var windowCalled = false
        var contentCalled = false
        var scrollCalled = false

        CacheRegistry.register(CacheRegistry.Level.CONFIG, "c") { configCalled = true }
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "w") { windowCalled = true }
        CacheRegistry.register(CacheRegistry.Level.CONTENT, "ct") { contentCalled = true }
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "s") { scrollCalled = true }

        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)

        assertTrue("WINDOW 应清 WINDOW 级", windowCalled)
        assertTrue("WINDOW 应清 CONTENT 级", contentCalled)
        assertTrue("WINDOW 应清 SCROLL 级", scrollCalled)
        assertTrue("WINDOW 不应清 CONFIG 级", !configCalled)
    }

    /**
     * SCROLL 是最轻的：只清滚动敏感的缓存
     * （结构没变，子节点列表等重缓存应保留）
     */
    @Test
    fun scroll_only_invalidates_scroll_level() {
        CacheRegistry.reset()
        var windowCalled = false
        var contentCalled = false
        var scrollCalled = false

        CacheRegistry.register(CacheRegistry.Level.WINDOW, "w") { windowCalled = true }
        CacheRegistry.register(CacheRegistry.Level.CONTENT, "ct") { contentCalled = true }
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "s") { scrollCalled = true }

        CacheRegistry.invalidate(CacheRegistry.Level.SCROLL)

        assertTrue("SCROLL 应清 SCROLL 级", scrollCalled)
        assertTrue("SCROLL 不应清 WINDOW 级", !windowCalled)
        assertTrue("SCROLL 不应清 CONTENT 级", !contentCalled)
    }

    @Test
    fun content_invalidates_content_and_scroll() {
        CacheRegistry.reset()
        var windowCalled = false
        var contentCalled = false
        var scrollCalled = false

        CacheRegistry.register(CacheRegistry.Level.WINDOW, "w") { windowCalled = true }
        CacheRegistry.register(CacheRegistry.Level.CONTENT, "ct") { contentCalled = true }
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "s") { scrollCalled = true }

        CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)

        assertTrue("CONTENT 应清 CONTENT 级", contentCalled)
        assertTrue("CONTENT 应清 SCROLL 级", scrollCalled)
        assertTrue("CONTENT 不应清 WINDOW 级", !windowCalled)
    }

    // ==================== 健壮性 ====================

    /**
     * 单个缓存的失效回调抛异常，不能阻断其余缓存
     */
    @Test
    fun failing_handler_does_not_block_others() {
        CacheRegistry.reset()
        var secondCalled = false

        CacheRegistry.register(CacheRegistry.Level.WINDOW, "bad") {
            throw RuntimeException("模拟失效失败")
        }
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "good") { secondCalled = true }

        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)
        assertTrue("前一个失效抛异常不应阻断后续", secondCalled)
    }

    @Test
    fun empty_registry_is_safe() {
        CacheRegistry.reset()
        assertEquals(0, CacheRegistry.invalidate(CacheRegistry.Level.WINDOW))
    }

    @Test
    fun registered_names_lists_all() {
        CacheRegistry.reset()
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "aaa") {}
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "bbb") {}
        val names = CacheRegistry.registeredNames()
        assertTrue(names.contains("aaa"))
        assertTrue(names.contains("bbb"))
    }

    @Test
    fun stats_is_non_empty() {
        CacheRegistry.reset()
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "x") {}
        assertTrue(CacheRegistry.stats().isNotEmpty())
    }

    @Test
    fun invalidate_count_tracks() {
        CacheRegistry.reset()
        val before = CacheRegistry.invalidateCount
        CacheRegistry.invalidate(CacheRegistry.Level.SCROLL)
        CacheRegistry.invalidate(CacheRegistry.Level.SCROLL)
        assertEquals(before + 2, CacheRegistry.invalidateCount)
    }

    @Test
    fun reset_clears_counter() {
        CacheRegistry.invalidate(CacheRegistry.Level.SCROLL)
        CacheRegistry.reset()
        assertEquals(0L, CacheRegistry.invalidateCount)
    }
}
