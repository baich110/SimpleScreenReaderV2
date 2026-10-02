package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存容量边界 —— **无界增长排查**（目标里的错误模式⑦）
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么专门测这个】
 * ══════════════════════════════════════════════════════════
 *
 * 读屏是**常驻服务**，一开就是一整天。缓存只要有一处无界增长，
 * 后果不是「慢一点」，而是**服务被系统 OOM 杀掉** ——
 * 对盲人用户等于手机突然不能用了。
 *
 * `NodeCache` 用一个容易忽略的设计：
 *   · `invalidateAll()` **O(1)** —— 只自增 `generation`，**不清 Map**
 *   · 读取时比对代数，不等则视为未命中
 *   · 真正清理只在「Map 撑到容量上限」时做
 *
 * 这个设计本身是对的（比 clear() 快），但**上限必须真的生效** ——
 * 否则「代数失效率 100%」之后，旧条目会一直躺在 Map 里直到 OOM。
 *
 * 本测试把四个缓存的容量上限逐一钉住。
 */
class NodeCacheBoundTest {

    @After
    fun tearDown() {
        NodeCache().hardClear()
    }

    private fun node(id: Int) = FakeNode(id, textValue = "n$id")

    // ==================== ① 子节点缓存 ====================

    /**
     * ★ 写入数量远超容量上限后，`stats()` 报的条目数必须仍在合理范围
     *
     * `childrenCapacity` 默认 512，`trimMap` 每次清掉一半 ——
     * 故稳态上限应在容量与容量/2 之间浮动。
     * 这里放宽到「不超过容量」即可判定「有界」。
     */
    @Test
    fun childrenCache_staysBounded() {
        val cache = NodeCache(childrenCapacity = 64)
        // 写 1000 个**不同**的 parent
        repeat(1000) { i ->
            val parent = node(i)
            cache.getSortedChildren(parent) { listOf(node(i + 10000)) }
        }
        val size = readSize(cache, "childrenCache")
        assertTrue(
            "子节点缓存必须是有界的（容量 64），实际 $size —— 无界即内存泄漏",
            size <= 64
        )
    }

    // ==================== ② 可聚焦判定缓存 ====================

    @Test
    fun focusCache_staysBounded() {
        val cache = NodeCache(focusCapacity = 64)
        repeat(1000) { i -> cache.shouldFocus(node(i)) { true } }
        val size = readSize(cache, "focusCache")
        assertTrue("判定缓存必须是有界的（容量 64），实际 $size", size <= 64)
    }

    // ==================== ③ 过滤器缓存 ====================

    @Test
    fun filterCache_staysBounded() {
        val cache = NodeCache(filterCapacity = 64)
        repeat(1000) { i -> cache.filterAccept(node(i)) { true } }
        val size = readSize(cache, "filterCache")
        assertTrue("过滤缓存必须是有界的（容量 64），实际 $size", size <= 64)
    }

    // ==================== ④ 文本缓存 ====================

    @Test
    fun textCache_staysBounded() {
        val cache = NodeCache(textCapacity = 64)
        repeat(1000) { i -> cache.composeText(node(i)) { "t$i" } }
        val size = readSize(cache, "textCache")
        assertTrue("文本缓存必须是有界的（容量 64），实际 $size", size <= 64)
    }

    // ==================== ⑤ 代数失效后仍不增长 ====================

    /**
     * ★★ 关键场景：**反复全量失效 + 持续写入**
     *
     * 这是真机上的常见模式：长列表滚动，每次都 `invalidateAll()`，
     * 然后继续缓存新节点。若失效时不清 Map，
     * 旧代数条目会一直累积 —— 必须在容量处被淘汰。
     */
    @Test
    fun invalidateAll_thenKeepWriting_staysBounded() {
        val cache = NodeCache(childrenCapacity = 64)
        repeat(50) { round ->
            cache.invalidateAll()
            repeat(100) { i ->
                val parent = node(round * 1000 + i)
                cache.getSortedChildren(parent) { emptyList() }
            }
        }
        val size = readSize(cache, "childrenCache")
        assertTrue(
            "反复失效 + 持续写入后仍必须有界（容量 64），实际 $size —— " +
                    "这是真机长列表滚动的典型模式",
            size <= 64
        )
    }

    // ==================== ⑥ 代数溢出保护 ====================

    /**
     * `generation` 逼近 `Int.MAX_VALUE` 时必须真正清一次
     *
     * 依据 `invalidateAll()`：
     * ```
     *   generation++
     *   if (generation > Int.MAX_VALUE - 1000) { hardClear() }
     * ```
     * 没有这个保护，`generation` 会溢出成负数，
     * 而 `CacheEntry.gen` 存的是**旧的正数** ——
     * 于是「代数相等」的判定在溢出后才可能意外命中，
     * 返回**过期的缓存值**（比不缓存更糟：给出错误的焦点判定）。
     */
    @Test
    fun generationOverflow_isGuardedByHardClear() {
        val cache = NodeCache()
        // 直接推代数到接近溢出（通过反射设私有字段）
        val f = NodeCache::class.java.getDeclaredField("generation")
        f.isAccessible = true
        f.setInt(cache, Int.MAX_VALUE - 1000)

        cache.invalidateAll()

        val gen = f.getInt(cache)
        assertEquals(
            "触发溢出保护后 hardClear() 应把代数重置为 0（依据 invalidateAll 的保护分支）",
            0, gen
        )
    }

    // ==================== ⑦ 命中/未命中语义 ====================

    /**
     * 同一代数内重复查询**必须命中**（否则缓存形同虚设）
     */
    @Test
    fun sameGeneration_hits() {
        val cache = NodeCache()
        val parent = node(1)
        var calls = 0

        repeat(5) {
            cache.getSortedChildren(parent) {
                calls++
                listOf(node(99))
            }
        }
        assertEquals("同一代数内应只求值一次", 1, calls)
    }

    /**
     * 失效后**必须重新求值** —— 这是「缓存必须能失效」的基本契约
     *
     * 若失效不生效，滚动后仍会拿到「滚动前」的焦点判定，
     * 真机表现：滚动过程中焦点卡在原地不动 / 停在已滚出屏幕的元素上。
     */
    @Test
    fun afterInvalidateAll_reEvaluates() {
        val cache = NodeCache()
        val parent = node(1)
        var calls = 0

        cache.getSortedChildren(parent) { calls++; listOf(node(99)) }
        cache.invalidateAll()
        cache.getSortedChildren(parent) { calls++; listOf(node(98)) }

        assertEquals("失效后应重新求值", 2, calls)
    }

    @Test
    fun afterInvalidateFocus_focusCacheReEvaluates() {
        val cache = NodeCache()
        val n = node(1)
        var calls = 0

        cache.shouldFocus(n) { calls++; true }
        cache.invalidateFocus()
        cache.shouldFocus(n) { calls++; false }

        assertEquals("invalidateFocus 后判定应重算", 2, calls)
    }

    /**
     * `hardClear()` 后全部回到空 —— 服务销毁时调用
     */
    @Test
    fun hardClear_emptiesEverything() {
        val cache = NodeCache()
        val n = node(1)
        cache.getSortedChildren(n) { listOf(n) }
        cache.shouldFocus(n) { true }
        cache.filterAccept(n) { true }
        cache.composeText(n) { "x" }

        cache.hardClear()

        assertEquals(0, readSize(cache, "childrenCache"))
        assertEquals(0, readSize(cache, "focusCache"))
        assertEquals(0, readSize(cache, "filterCache"))
        assertEquals(0, readSize(cache, "textCache"))
    }

    // ==================== 工具 ====================

    /**
     * 读私有 Map 的条目数 —— 用于「有界」判定
     *
     * 【为什么用反射】容量上限是私有实现细节，`stats()` 只给字符串。
     * 而「有没有界」恰恰是本测试要证明的核心命题，
     * 解析字符串不如直接读字段可靠。
     */
    private fun readSize(cache: NodeCache, field: String): Int {
        val f = NodeCache::class.java.getDeclaredField(field)
        f.isAccessible = true
        val map = f.get(cache) as java.util.IdentityHashMap<*, *>
        return map.size
    }
}
