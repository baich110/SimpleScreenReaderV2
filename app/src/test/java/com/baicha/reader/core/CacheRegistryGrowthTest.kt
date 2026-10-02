package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存注册表 —— **服务重启不得累积 handler（无界增长 + 内存泄漏）**
 *
 * ══════════════════════════════════════════════════════════
 * 【缺陷】`CacheRegistry` 是 `object`（**进程级单例**）：
 * ══════════════════════════════════════════════════════════
 *
 * ```kotlin
 *   private val handlers = HashMap<Level, MutableList<Pair<String, () -> Unit>>>(4)
 *
 *   fun register(level: Level, name: String, onInvalidate: () -> Unit) {
 *       synchronized(this) {
 *           handlers.getOrPut(level) { ArrayList(8) }.add(name to onInvalidate)   // ← 只 add，不查重
 *       }
 *   }
 * ```
 *
 * 而服务每次 `onServiceConnected()` 都会调 `registerCacheHandlers()`，
 * 一口气注册 **7 条** handler：
 * ```
 *   WINDOW: nodeCache / boundsCache / traversalIndex / webElementNavigator
 *   SCROLL: focusAndText / boundsCache
 *   CONFIG: screenMetrics
 * ```
 *
 * 【真机场景】无障碍服务**被系统反复重启是常态**：
 *   · 服务崩溃后自动重启
 *   · 用户在设置里关掉再开启
 *   · 系统内存压力回收
 *   · 配置变化（旋转屏幕等）可能触发服务重连
 *
 * 每次重启 +7 条 handler。
 *
 * ══════════════════════════════════════════════════════════
 * 【两条后果】
 * ══════════════════════════════════════════════════════════
 *
 * ① **内存泄漏**：每条 handler 是捕获了服务实例的 lambda
 *    （`CacheRegistry.register(...) { nodeCache.invalidateAll() }`
 *     里的 `nodeCache` 就是 `this.nodeCache`）。
 *    旧服务实例本该被 GC，却因被 lambda 强引用在 **object 单例**里
 *    **永不回收**。n 次重启 ⇒ n 个服务实例（及其持有的
 *    NodeCache / SpeechEngine / 全部子组件）无法释放。
 *
 * ② **热点路径退化**：每次 `CacheRegistry.invalidate(WINDOW)`
 *    会执行 **7n** 个回调 —— 其中 7(n−1) 个是操作**已废弃实例**的
 *    纯浪费（那些 nodeCache 已无任何引用）。窗口变化是高频事件。
 *
 * 【为什么之前没暴露】服务启动即崩的那个 P0 修好之前，
 * 服务根本活不到被反复重启 —— 这个问题现在才会真正显现。
 */
class CacheRegistryGrowthTest {

    @After
    fun tearDown() {
        CacheRegistry.reset()
    }

    // ==================== ① 重启不累积 ====================

    /**
     * ★★ 模拟 N 次服务重启，每次都注册同一批 handler
     *
     * 修复后：注册表规模**不随重启次数增长**。
     */
    @Test
    fun repeatedRegistration_doesNotAccumulate() {
        val names = listOf(
            "nodeCache", "boundsCache", "traversalIndex", "webElementNavigator"
        )

        // 第 1 次「服务启动」
        for (n in names) {
            CacheRegistry.register(CacheRegistry.Level.WINDOW, n) { }
        }
        val afterFirst = CacheRegistry.registeredNames().count { it in names }
        assertEquals("首次注册应有 4 条", 4, afterFirst)

        // 再模拟 9 次「服务重启」
        repeat(9) {
            for (n in names) {
                CacheRegistry.register(CacheRegistry.Level.WINDOW, n) { }
            }
        }

        val afterTen = CacheRegistry.registeredNames().count { it in names }
        assertEquals(
            "★ 10 次重启后仍应只有 4 条 —— 同名 handler 必须覆盖而不是追加。" +
                    "否则旧服务实例被 lambda 强引用无法回收（内存泄漏），" +
                    "且每次 invalidate 执行 7n 个无效回调。实际 $afterTen 条",
            4, afterTen
        )
    }

    /**
     * ★ 同名重注册必须**替换**旧回调，而不是并存
     *
     * 用「哪个回调被调用」来验证替换语义。
     */
    @Test
    fun reRegistration_replacesOldCallback() {
        var oldCalled = 0
        var newCalled = 0

        CacheRegistry.register(CacheRegistry.Level.WINDOW, "x") { oldCalled++ }
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "x") { newCalled++ }

        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)

        assertEquals("旧回调必须被替换掉（不应再执行）", 0, oldCalled)
        assertEquals("新回调必须生效", 1, newCalled)
    }

    /** 不同名字的 handler 必须**各自保留**（覆盖只针对同名） */
    @Test
    fun differentNames_bothRetained() {
        var a = 0
        var b = 0
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "a") { a++ }
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "b") { b++ }

        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)
        assertEquals(1, a)
        assertEquals(1, b)
    }

    /**
     * 不同 level 的同名 handler **互不覆盖**（去重键是 `(level, name)`）
     *
     * 【我第一版写错了断言，记录在案】
     * 我原来断言「SCROLL 级不应被 WINDOW 触发」——**错的**。
     * 看 `invalidate()` 的实际分级表：
     * ```
     *   Level.WINDOW -> {
     *       out.addAll(handlers[Level.WINDOW] ...)
     *       out.addAll(handlers[Level.CONTENT] ...)
     *       out.addAll(handlers[Level.SCROLL]  ...)   // ← WINDOW 包含 SCROLL
     *   }
     * ```
     * 即 **`WINDOW ⊇ CONTENT` 且 `WINDOW ⊇ SCROLL`**
     * （注释里写的「SCROLL 独立」指的是**反向**：SCROLL 触发时
     *   只清 SCROLL，不会波及 WINDOW/CONTENT）。
     *
     * ⇒ 正确断言是：WINDOW 触发时 SCROLL 的回调**也会**执行。
     */
    @Test
    fun sameName_differentLevels_bothRetained() {
        var w = 0
        var s = 0
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "x") { w++ }
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "x") { s++ }

        // 去重键含 level ⇒ 两条都保留
        assertEquals(
            "同名但不同 level 应各留一条（去重键是 (level, name)）",
            2, CacheRegistry.registeredNames().count { it == "x" }
        )

        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)
        assertEquals("WINDOW 级自身应执行", 1, w)
        assertEquals(
            "★ WINDOW 包含 SCROLL（见 invalidate 的分级表）—— " +
                    "我第一版断言它不该触发，是错的",
            1, s
        )

        // 反向：SCROLL 真独立 —— 触发时不波及 WINDOW
        CacheRegistry.invalidate(CacheRegistry.Level.SCROLL)
        assertEquals("SCROLL 触发不得波及 WINDOW（这一步才是「独立」的含义）", 1, w)
        assertEquals("SCROLL 自己应再执行一次", 2, s)
    }

    // ==================== ② 语义：级别包含关系不得破坏 ====================

    /**
     * `CONFIG ⊇ WINDOW ⊇ CONTENT`，`SCROLL` 独立
     *
     * 依据 `CacheRegistry.invalidate()` 的分级表。去重改动**不得**
     * 影响这个包含关系。
     */
    @Test
    fun invalidate_levelContainmentPreserved() {
        var cfg = 0
        var win = 0
        var content = 0
        var scroll = 0

        CacheRegistry.register(CacheRegistry.Level.CONFIG, "c") { cfg++ }
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "w") { win++ }
        CacheRegistry.register(CacheRegistry.Level.CONTENT, "t") { content++ }
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "s") { scroll++ }

        CacheRegistry.invalidate(CacheRegistry.Level.CONFIG)
        assertEquals("CONFIG 应触发自身", 1, cfg)
        assertEquals("CONFIG ⊇ WINDOW", 1, win)
        assertEquals("CONFIG ⊇ CONTENT", 1, content)
        assertEquals("CONFIG ⊇ SCROLL", 1, scroll)
    }

    /** 重复失效不重复触发（每次 invalidate 各回调一次） */
    @Test
    fun invalidate_callsEachHandlerOnce() {
        var count = 0
        CacheRegistry.register(CacheRegistry.Level.WINDOW, "x") { count++ }
        CacheRegistry.invalidate(CacheRegistry.Level.WINDOW)
        assertEquals(1, count)
    }

    /** equal 名字 + 大量重注册后，注册表规模有界 */
    @Test
    fun registrySize_isBoundedUnderManyRestarts() {
        repeat(200) { round ->
            CacheRegistry.register(CacheRegistry.Level.WINDOW, "nodeCache") { }
            CacheRegistry.register(CacheRegistry.Level.SCROLL, "focusAndText") { }
        }
        val total = CacheRegistry.registeredNames().size
        assertTrue(
            "★ 200 次重注册后注册表仍应有界（≤2 条），实际 $total 条 —— " +
                    "这是服务被系统反复重启后的真实模式",
            total <= 2
        )
    }
}

/**
 * 注册类 API —— **服务重启不得累积**（通用守卫）
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么单独立这个类】
 * ══════════════════════════════════════════════════════════
 *
 * 本轮修完 `CacheRegistry` 后，按纪律⑦「主动检查服务启动路径」，
 * 我用脚本扫了 `onServiceConnected` 里所有 `register/add/attach/observe`
 * 调用，逐一核实是否有「重启累积」风险：
 *
 * | 调用 | 目标 | 判定 |
 * |---|---|---|
 * | `CacheRegistry.register` ×7 | **object 单例** | ❌ 真缺陷，本轮已修（去重） |
 * | `ttsObserver.register` | **class 实例** | ✅ 安全 —— `if (registered) return true` 守卫，且每次 onServiceConnected 新建实例 |
 * | `systemState.register` | **class 实例** | ✅ 安全 —— 同上 |
 * | `gestureChain.register` ×3 | **class 实例** | ✅ 安全 —— `gestureChain = GestureChain()` 每次重建（:352），注册在 :671 |
 *
 * ⇒ **只有 object 单例才是风险**（实例类随实例重建而自然清空）。
 *
 * 本测试把这个结论**固化成自动检查** —— 将来若有人把某个
 * `register` 的目标改成 object 单例、或去掉实例守卫，这里会失败。
 */
class RegistrationLifecycleTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * ★ object 单例的 `register` 必须有去重逻辑
     *
     * 判据：出现 `indexOfFirst` / `contains` / `distinct` 之类的查重。
     */
    @Test
    fun singletonRegister_deduplicates() {
        val cr = codeOf("src/main/java/com/baicha/reader/core/CacheRegistry.kt")
        assertTrue("CacheRegistry 必须是 object 单例", cr.contains("object CacheRegistry"))
        val idx = cr.indexOf("fun register(")
        assertTrue("必须存在 register", idx >= 0)
        val body = cr.substring(idx, minOf(cr.length, idx + 1200))
        assertTrue(
            "★ object 单例的 register 必须去重（同名覆盖）—— " +
                    "否则服务每次重启都累积 handler：旧服务实例被 lambda " +
                    "强引用无法回收（内存泄漏）+ 每次 invalidate 执行 n 倍回调。\n$body",
            body.contains("indexOfFirst") || body.contains("contains") ||
                    body.contains("distinct") || body.contains("put(")
        )
    }

    /**
     * ★ 实例类的 register 必须有「已注册」守卫
     *
     * 判据：函数体开头有 `if (registered) return true` 之类。
     */
    @Test
    fun instanceRegister_hasGuard() {
        for (f in listOf("TtsSettingsObserver", "SystemStateAnnouncer")) {
            val src = codeOf("src/main/java/com/baicha/reader/core/$f.kt")
            val idx = src.indexOf("fun register()")
            assertTrue("$f 必须有 register()", idx >= 0)
            val body = src.substring(idx, minOf(src.length, idx + 400))
            assertTrue(
                "$f.register 必须有「已注册则直接返回」的守卫（防重复注册）:\n$body",
                body.contains("if (registered) return")
            )
        }
    }

    /**
     * ★ `gestureChain` 必须在注册 Actor **之前**重建
     *
     * 否则重启后会拿着旧实例往里加 Actor，导致 Actor 数量随重启增长。
     */
    @Test
    fun gestureChain_isRecreatedBeforeRegisteringActors() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
        val createIdx = src.indexOf("gestureChain = GestureChain()")
        val setupCallIdx = src.indexOf("setupGestureChain()")
        assertTrue("必须重建 gestureChain", createIdx >= 0)
        assertTrue("必须调用 setupGestureChain", setupCallIdx >= 0)
        assertTrue(
            "★ `gestureChain = GestureChain()` 必须排在 `setupGestureChain()` 之前 —— " +
                    "否则重启后 Actor 会在旧实例上累积。" +
                    "实际：创建在 $createIdx，注册在 $setupCallIdx",
            createIdx < setupCallIdx
        )
    }
}
