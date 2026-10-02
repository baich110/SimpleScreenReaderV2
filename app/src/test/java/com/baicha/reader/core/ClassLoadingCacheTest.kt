package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 类加载缓存单测
 *
 * 这是**性能关键路径**：角色判定每次节点访问都会走这里，
 * 缓存失效会导致 Class.forName 被反复调用（拿 ClassLoader 锁）。
 */
class ClassLoadingCacheTest {

    @After
    fun tearDown() {
        ClassLoadingCache.clear()
    }

    // ==================== 基础加载 ====================

    @Test
    fun loads_existing_class() {
        val c = ClassLoadingCache.loadOrGetClass("java.lang.String")
        assertNotNull("已存在的类应能加载", c)
        assertEquals(String::class.java, c)
    }

    @Test
    fun missing_class_returns_null() {
        assertNull(ClassLoadingCache.loadOrGetClass("com.not.exist.FakeClass12345"))
    }

    @Test
    fun null_and_empty_return_null() {
        assertNull(ClassLoadingCache.loadOrGetClass(null))
        assertNull(ClassLoadingCache.loadOrGetClass(""))
    }

    /**
     * 负结果缓存：加载失败也要缓存，避免反复尝试
     * （对齐 TalkBack 行为）
     */
    @Test
    fun failed_load_is_cached() {
        val name = "com.not.exist.CachedFailure999"
        ClassLoadingCache.clear()

        assertNull(ClassLoadingCache.loadOrGetClass(name))
        val sizeAfterFirst = ClassLoadingCache.stats()

        assertNull(ClassLoadingCache.loadOrGetClass(name))
        val sizeAfterSecond = ClassLoadingCache.stats()

        // 第二次不应新增缓存条目（已缓存失败）
        assertEquals("失败结果应被缓存", sizeAfterFirst, sizeAfterSecond)
    }

    @Test
    fun repeated_load_returns_same_instance() {
        val a = ClassLoadingCache.loadOrGetClass("java.util.ArrayList")
        val b = ClassLoadingCache.loadOrGetClass("java.util.ArrayList")
        assertNotNull(a)
        assertTrue("缓存的应是同一个 Class 对象", a === b)
    }

    // ==================== 继承关系 ====================

    @Test
    fun same_class_is_instance_of_itself() {
        assertTrue(ClassLoadingCache.isInstanceOf("java.lang.String", "java.lang.String"))
    }

    @Test
    fun subclass_is_instance_of_parent() {
        // ArrayList 继承 AbstractList
        assertTrue(
            ClassLoadingCache.isInstanceOf(
                "java.util.ArrayList",
                "java.util.AbstractList"
            )
        )
    }

    @Test
    fun unrelated_classes_are_not_instances() {
        assertFalse(
            ClassLoadingCache.isInstanceOf("java.lang.String", "java.util.ArrayList")
        )
    }

    @Test
    fun null_child_returns_false() {
        assertFalse(ClassLoadingCache.isInstanceOf(null, "java.lang.Object"))
    }

    @Test
    fun missing_classes_return_false() {
        assertFalse(
            ClassLoadingCache.isInstanceOf("com.not.exist.A", "com.not.exist.B")
        )
    }

    /**
     * 继承关系结果应被缓存 —— 这是相对 TalkBack 的强化点
     */
    @Test
    fun assignability_result_is_cached() {
        ClassLoadingCache.clear()
        ClassLoadingCache.isInstanceOf("java.util.ArrayList", "java.util.AbstractList")
        val stats = ClassLoadingCache.stats()
        assertTrue("继承关系应被缓存", stats.contains("继承关系"))
        assertFalse("缓存数不应为 0", stats.contains("继承关系 0"))
    }

    // ==================== 接口判定 ====================

    @Test
    fun implements_interface_detected() {
        // ArrayList 实现 List 接口
        assertTrue(
            ClassLoadingCache.isInstanceOf(
                "java.util.ArrayList",
                "java.util.List"
            )
        )
    }

    // ==================== checkInstanceOf ====================

    @Test
    fun check_instance_of_works() {
        assertTrue(ClassLoadingCache.checkInstanceOf("hello", "java.lang.String"))
        assertFalse(ClassLoadingCache.checkInstanceOf("hello", "java.lang.Integer"))
        assertFalse(ClassLoadingCache.checkInstanceOf(null, "java.lang.String"))
    }

    // ==================== 维护 ====================

    @Test
    fun clear_empties_cache() {
        ClassLoadingCache.loadOrGetClass("java.lang.String")
        ClassLoadingCache.clear()
        assertTrue(ClassLoadingCache.stats().contains("类 0"))
    }

    @Test
    fun stats_is_non_empty() {
        assertTrue(ClassLoadingCache.stats().isNotEmpty())
    }

    @Test
    fun repeated_calls_are_stable() {
        // 反复调用不应抛异常（覆盖并发与容量控制路径）
        repeat(200) {
            ClassLoadingCache.isInstanceOf("java.util.ArrayList", "java.util.List")
        }
        assertTrue(
            ClassLoadingCache.isInstanceOf("java.util.ArrayList", "java.util.List")
        )
    }
}
