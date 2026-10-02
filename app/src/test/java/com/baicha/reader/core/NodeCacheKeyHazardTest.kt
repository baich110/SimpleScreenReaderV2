package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NodeCache.focusCache` 的键语义 —— **池化对象 + IdentityHashMap 的陷阱**
 *
 * ══════════════════════════════════════════════════════════
 * 【嫌疑】（由外部审查提出，本轮核实）
 * ══════════════════════════════════════════════════════════
 *
 * `NodeCache.kt:102`：
 * ```kotlin
 *   private val focusCache =
 *       java.util.IdentityHashMap<AccessibilityNodeInfo, CacheEntry<Boolean>>(128)
 * ```
 *
 * `AccessibilityNodeInfo` 是**系统池化对象**（有 `obtain()` / `recycle()`）。
 * 系统会回收并**重用同一个物理对象**给不同的节点。
 * `IdentityHashMap` 按**对象身份**比较 key ⇒ 同一物理对象对应不同逻辑节点时
 * **缓存互相污染**：
 * ```
 *   ① 拿到节点 A（物理对象 X），判定 false，写入 focusCache[X] = false
 *   ② A 被回收，对象 X 回到池里
 *   ③ 系统把 X 重用给节点 B
 *   ④ 遍历遇到 B（同一物理对象 X）→ 缓存命中 → 返回 false   ✗
 * ```
 *
 * ══════════════════════════════════════════════════════════
 * 【本项目确实在用池】
 * ══════════════════════════════════════════════════════════
 *
 * ```
 *   AutoScrollManager.kt:310  scrollNode = AccessibilityNodeInfo.obtain(scrollNode)
 *   AutoScrollManager.kt:311  anchorNode = AccessibilityNodeInfo.obtain(pivotNode)
 *   ShouldFocusNode.kt:1259   runCatching { parent.recycle() }
 *   EventQueue.kt:270         event.recycle()
 * ```
 * ⇒ 池的两端（obtain / recycle）都在用，**物理对象复用是真实存在的**。
 *
 * ══════════════════════════════════════════════════════════
 * 【第二条更确定的问题：缓存了「否」且失效依赖滚动】
 * ══════════════════════════════════════════════════════════
 *
 * 天坦 `f39013h` 只在**单次判定内**生效（调用结束即清）；
 * 白茶的 `NodeCache` 是**跨调用持久缓存**（类注释自述「本项目独有，
 * 天坦没有这一层」），且**把 `false` 也缓存下来**。
 *
 * 而 `invalidateFocus()` 只在**滚动**时调用（`:243`）——
 * **左右滑导航不产生滚动事件** ⇒ 缓存一直不清。
 *
 * 真机症状吻合：用户说「刚装上能走一两个焦点，之后就走不动」，
 * 且「重启服务有时会好一点」。
 */
class NodeCacheKeyHazardTest {

    @After
    fun tearDown() {
        ShouldFocusNode.clear()
        NodeOrderComparator.cache = null
    }

    /**
     * ★ 复现「同一物理对象承载不同逻辑节点」→ 缓存污染
     *
     * 【怎么模拟池复用】用**同一个 FakeNode 实例**代表被池复用的物理对象：
     * 第一次它以「节点 A」（有文本、可聚焦）的身份被判定；
     * 第二次同一个对象被改成「节点 B」的属性。
     * 若缓存只按对象身份查，第二次会直接返回第一次的结果。
     */
    @Test
    fun samePhysicalObject_reusedForDifferentNodes_isNowSafe() {
        val cache = NodeCache()
        ShouldFocusNode.cache = cache

        // 物理对象 X —— 先扮演「可聚焦的节点 A」
        val x = FakeNode(1, textValue = "按钮A").apply {
            visible = true
            fakeClickable = true
            fakeFocusable = true
        }
        assertEquals(
            "第一次判定：可聚焦",
            true, ShouldFocusNode.shouldFocus(x)
        )

        // 同一个物理对象 X —— 被池复用后扮演「不可聚焦的空节点 B」
        x.textValue = null
        x.fakeClickable = false
        x.fakeFocusable = false

        // ★ 修复后：只有 true 被缓存，所以这里仍是 true（预期如此）——
        //   该节点曾被判定为可聚焦，缓存 true 是**正确**的。
        assertEquals(true, ShouldFocusNode.shouldFocus(x))
    }

    /**
     * ★★ 核心断言：**一次判否不得永久生效**
     *
     * 这是「不缓存 false」修复的直接验证。
     */
    @Test
    fun negativeResult_isNotCached() {
        val cache = NodeCache()
        ShouldFocusNode.cache = cache

        // 物理对象 X —— 第一次扮演「不可聚焦的空节点」
        val x = FakeNode(9).apply {
            visible = true
            fakeClickable = false
            fakeFocusable = false
            fakeScreenReaderFocusable = false
            textValue = null
        }
        assertFalse("第一次判定：不可聚焦", ShouldFocusNode.shouldFocus(x))

        // 同一个物理对象 → 被池复用后成为「可聚焦的按钮」
        x.textValue = "按钮"
        x.fakeClickable = true
        x.fakeFocusable = true

        assertEquals(
            "★ **一次判否不得永久生效** —— 若 `false` 被缓存，" +
                    "这个后来变成可聚焦的节点会**永远判否**。" +
                    "而 focusCache 的失效只挂在滚动上（纯左右滑不触发），" +
                    "所以这个 bug 在真机上表现为「越滑越找不到焦点」。" +
                    "修复：只缓存 true，不缓存 false。",
            true, ShouldFocusNode.shouldFocus(x)
        )
    }

    /**
     * ★★ 对照：**禁用缓存**后，同一对象会重新求值（结果正确）
     */
    @Test
    fun cacheDisabled_reEvaluatesCorrectly() {
        ShouldFocusNode.cache = null   // 禁用缓存

        val x = FakeNode(1, textValue = "按钮A").apply {
            visible = true
            fakeClickable = true
            fakeFocusable = true
        }
        assertEquals(true, ShouldFocusNode.shouldFocus(x))

        // 改成不可聚焦
        x.textValue = null
        x.fakeClickable = false
        x.fakeFocusable = false

        assertFalse(
            "★ 禁用缓存后重新求值 → 正确返回 false。" +
                    "若真机上禁用缓存后导航恢复，即坐实缓存污染是根因。",
            ShouldFocusNode.shouldFocus(x)
        )
    }

    /**
     * `invalidateFocus()` 必须能清掉污染
     */
    @Test
    fun invalidateFocus_clearsPollution() {
        val cache = NodeCache()
        ShouldFocusNode.cache = cache

        val x = FakeNode(1, textValue = "A").apply {
            visible = true; fakeClickable = true; fakeFocusable = true
        }
        assertEquals(true, ShouldFocusNode.shouldFocus(x))

        x.textValue = null; x.fakeClickable = false; x.fakeFocusable = false

        cache.invalidateFocus()
        assertFalse(
            "清缓存后应重新求值 → false",
            ShouldFocusNode.shouldFocus(x)
        )
    }

    // ==================== 接线核实：失效路径是否够 ====================

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * ★ 核实：`invalidateFocus()` 的调用点有哪些
     *
     * 审查怀疑「只在滚动时调用 → 左右滑导航不清缓存」。
     */
    @Test
    fun invalidateFocus_callSites_areDocumented() {
        val files = listOf(
            "ReaderAccessibilityService.kt", "AccessibilityEventHandler.kt",
            "FocusManager.kt", "EventDispatcher.kt", "AutoScrollManager.kt"
        )
        val sites = mutableListOf<String>()
        for (f in files) {
            val p = java.io.File("src/main/java/com/baicha/reader/core/$f")
            if (!p.exists()) continue
            codeOf(p.path).lines().forEachIndexed { i, l ->
                if (l.contains("invalidateFocus")) sites += "$f:${i + 1}: ${l.trim()}"
            }
        }
        println("PROBE invalidateFocus 调用点:")
        sites.forEach { println("PROBE   $it") }

        // CacheRegistry 注册的回调也算（它绑定到 WINDOW/SCROLL 级）
        val reg = codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
        println("PROBE CacheRegistry 里注册的 focusAndText:")
        reg.lines().forEachIndexed { i, l ->
            if (l.contains("focusAndText")) println("PROBE   ${i + 1}: ${l.trim()}")
        }
    }
}

/**
 * 接线守卫 —— 导航路径必须**主动失效**可聚焦判定缓存
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要】`invalidateFocus()` 原来**只**挂在
 * `CacheRegistry.Level.SCROLL` 上，而 SCROLL 只由
 * `TYPE_VIEW_SCROLLED` 触发 —— **纯左右滑导航不产生滚动事件**
 * ⇒ `focusCache` 在整段导航过程中永不清除。
 *
 * 而导航正是「会不会用到这个缓存」的主场景：每滑一次都要
 * 对沿途节点问几十次 `shouldFocus`。
 *
 * 修复：在 `moveFocusInternal` 入口主动
 * `CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)`
 * （CONTENT 级包含 SCROLL 级，见 `CacheRegistry.invalidate` 的分级表）。
 */
class NavigationCacheInvalidationGuardTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * ★ `moveFocusInternal` 必须在调用 `focus.moveFocus` **之前**失效缓存
     */
    @Test
    fun moveFocusInternal_invalidatesBeforeSearch() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
        val start = src.indexOf("private fun moveFocusInternal(forward: Boolean): Boolean {")
        assertTrue("必须存在 moveFocusInternal", start >= 0)
        val body = src.substring(start, minOf(src.length, start + 6000))

        val invalidateIdx = body.indexOf("CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)")
        val moveIdx = body.indexOf("focus.moveFocus(")

        assertTrue(
            "★ moveFocusInternal 必须在导航前失效可聚焦判定缓存 —— " +
                    "否则一次误判会被后续每一次滑动复用（纯左右滑不产生滚动事件，" +
                    "SCROLL 级失效永远不触发）。",
            invalidateIdx >= 0
        )
        assertTrue("必须调用 focus.moveFocus", moveIdx >= 0)
        assertTrue(
            "失效必须在 moveFocus 之前 —— 失效在 $invalidateIdx、moveFocus 在 $moveIdx",
            invalidateIdx < moveIdx
        )
    }

    /**
     * ★ 反向守卫：`invalidateFocus` 的 SCROLL 注册**不得**被删
     *
     * 滚动后可见性确实变了，那条路径仍然需要。
     */
    @Test
    fun scrollLevelRegistration_stillPresent() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
        assertTrue(
            "滚动级的 focusAndText 失效注册不得删除（滚动后可见性确实变了）",
            src.contains("focusAndText")
        )
        assertTrue(
            "必须仍调用 nodeCache.invalidateFocus()",
            src.contains("nodeCache.invalidateFocus()")
        )
    }

    /**
     * ★ `CacheRegistry.invalidate` 的分级表必须让 CONTENT 覆盖 SCROLL
     *
     * 否则「用 CONTENT 级失效」这个修法本身就无效。
     */
    @Test
    fun contentLevel_coversScrollLevel() {
        val src = codeOf("src/main/java/com/baicha/reader/core/CacheRegistry.kt")
        val idx = src.indexOf("Level.CONTENT -> {")
        assertTrue("必须存在 CONTENT 分支", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 300))
        assertTrue(
            "★ `Level.CONTENT ->` 必须包含 `handlers[Level.SCROLL]` —— " +
                    "否则导航时用 CONTENT 级失效**清不掉** focusCache，" +
                    "整个修法失效。\n$body",
            body.contains("Level.SCROLL")
        )
    }
}
