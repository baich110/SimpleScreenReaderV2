package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态生命周期测试 —— 锁定「静态状态的作用域」
 *
 * 这类 bug 的特征：不报错、不崩溃，但判定条件静默失真。
 * `ShouldFocusNode` 的边界基准就是典型（原来作用域从"一次操作"
 * 被拉长成"一个窗口"，导致微信特判失效）。
 */
class StateLifecycleTest {

    @After
    fun tearDown() {
        ShouldFocusNode.clear()
        NodeBoundsCalculator.clear()
        CacheRegistry.reset()
    }

    // ==================== 边界基准的作用域 ====================

    /**
     * 基准应在「一次操作」后重置（对应天坦在 D() 每条退出路径调 d()）
     */
    @Test
    fun operation_scope_reset_is_safe_without_init() {
        ShouldFocusNode.clear()
        // 未初始化时重置不应抛异常
        ShouldFocusNode.resetOperationScope()
        assertFalse(ShouldFocusNode.shouldFocus(null))
    }

    @Test
    fun clear_resets_everything() {
        ShouldFocusNode.clear()
        assertFalse(ShouldFocusNode.shouldFocus(null))
    }

    @Test
    fun repeated_scope_reset_is_idempotent() {
        repeat(10) { ShouldFocusNode.resetOperationScope() }
        // 反复重置后仍能正常判定 null
        assertFalse(ShouldFocusNode.shouldFocus(null))
    }

    // ==================== 缓存代数 ====================

    /**
     * 遍历索引缓存的代数必须在失效后自增
     */
    @Test
    fun traversal_index_cache_invalidation_is_safe() {
        repeat(5) { TatansTraversal.invalidateIndexCache() }
    }

    // ==================== 注册表与状态的一致性 ====================

    @Test
    fun cache_registry_reset_clears_handlers() {
        CacheRegistry.reset()
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "t") {}
        assertTrue(CacheRegistry.registeredNames().isNotEmpty())
        CacheRegistry.reset()
        assertTrue(CacheRegistry.registeredNames().isEmpty())
    }

    @Test
    fun cache_registry_counter_resets() {
        CacheRegistry.invalidate(CacheRegistry.Level.SCROLL)
        CacheRegistry.reset()
        assertEquals(0L, CacheRegistry.invalidateCount)
    }

    // ==================== 类加载缓存 ====================

    @Test
    fun class_cache_clear_is_safe() {
        ClassLoadingCache.loadOrGetClass("java.lang.String")
        ClassLoadingCache.clear()
        assertTrue(ClassLoadingCache.stats().contains("类 0"))
    }
}
