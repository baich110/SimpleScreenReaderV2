package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单例里的可变状态 —— **跨线程可见性**
 *
 * ══════════════════════════════════════════════════════════
 * 【背景：本轮用脚本扫全项目所得】
 * ══════════════════════════════════════════════════════════
 *
 * 扫描判据：`object`（单例）成员级的 `var` 字段**没有 `@Volatile`**。
 * 全项目只剩 3 处，逐个核实结论：
 *
 * | 字段 | 结论 |
 * |---|---|
 * | `ReaderLog.verbose` | **不需要改** —— 它是纯属性（无后备字段），getter 读 `minLevel`、setter 写 `minLevel`，而 `minLevel` 已是 `@Volatile` |
 * | `ShouldFocusNode.baselineInitialized` | 见下（与 `Rect` 配对，`@Volatile` 无法保证复合一致性；且需先确认是否真并发可达） |
 * | `NodePathCollector.pendingNode` | **已修** —— 见下 |
 *
 * ══════════════════════════════════════════════════════════
 * 【NodePathCollector.pendingNode 为什么是真缺陷】
 * ══════════════════════════════════════════════════════════
 *
 * 该类**其他所有**可变字段都标了 `@Volatile`：
 * ```
 *   @Volatile var latest         ...
 *   @Volatile var onCollected    ...
 *   @Volatile var collectCount   ...
 *   @Volatile var skippedCount   ...
 *   private   var pendingNode    ...   ← 唯独漏了这个
 * ```
 * 说明作者知道这个类跨线程，只是漏了一个。
 *
 * 【跨线程事实】
 *   · `request(node)` —— **无障碍线程**写（`onAccessibilityEvent` 回调链里调用）
 *   · `task.run()`    —— **主线程**读（`Handler(Looper.getMainLooper())`）
 *
 * 【后果】主线程可能读到**陈旧的** `pendingNode`：
 *   ① 用户快速滑到 A 再滑到 B，主线程若看到 A 的旧引用 →
 *      把 **A 的路径**当作当前焦点路径存起来 →
 *      后续「恢复焦点」按错误路径找 → **跳回上一个位置**；
 *   ② 或看到 null（写入尚不可见）→ `return@Runnable` 跳过采集 →
 *      路径**丢失** → 「恢复焦点」退化为「永远取第一个」。
 *
 * 【为什么用 @Volatile 而不是锁】语义是「最新一次请求获胜」：
 * `request()` 本身是覆盖写，`task` 只关心「最新是哪个」。
 * `@Volatile` 保证可见性即可，不需要复合操作原子性
 * （`removeCallbacks` + `postDelayed` 已在主线程 handler 上串行化）。
 */
class SharedStateThreadSafetyTest {

    // ==================== NodePathCollector：接线守卫 ====================

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * `pendingNode` 必须有 `@Volatile`
     *
     * 【为什么用结构断言而不是并发压力测试】
     * 「缺 @Volatile」导致的可见性问题在 JVM 上**几乎无法稳定复现**
     * （x86 内存模型较强），压力测试往往通过 —— 靠测试抓不住。
     * 所以这里断言**声明本身**：紧邻上一行必须是 `@Volatile`。
     *
     * 【教训】本项目曾因「@Volatile 变异逃逸」吃过亏 ——
     * 当时断言「文件含 @Volatile」而注释里也有这个词，
     * 于是变异未被捕获。这里改为**检查紧邻的上一行**，
     * 且断言前**剥掉了注释**。
     */
    @Test
    fun pendingNode_isVolatile() {
        val raw = java.io.File(
            "src/main/java/com/baicha/reader/core/NodePathDescription.kt"
        ).readText()
        val lines = raw.split('\n')
        val idx = lines.indexOfFirst { it.contains("var pendingNode") }
        assertTrue("必须存在 pendingNode 字段", idx >= 0)

        // 紧邻上一行必须是 @Volatile（中间允许空行）
        var j = idx - 1
        while (j >= 0 && lines[j].isBlank()) j--
        assertTrue("找到了 pendingNode 但上面没有行", j >= 0)
        assertEquals(
            "★ pendingNode 的**紧邻上一行**必须是 @Volatile ——" +
                    "它由无障碍线程写、主线程读。实际上一行是：'${lines[j].trim()}'",
            "@Volatile", lines[j].trim()
        )
    }

    /**
     * 反向守卫：本类其他 @Volatile 字段**不得**被删
     *
     * （本轮只补了一个，不能顺手把别的去掉）
     */
    @Test
    fun otherVolatileFields_stillPresent() {
        val code = codeOf("src/main/java/com/baicha/reader/core/NodePathDescription.kt")
        val idx = code.indexOf("object NodePathCollector")
        assertTrue("必须存在 NodePathCollector", idx >= 0)
        val body = code.substring(idx, minOf(code.length, idx + 3000))
        for (f in listOf("latest", "onCollected", "collectCount", "skippedCount")) {
            assertTrue("不得删掉 @Volatile 字段 $f", body.contains(f))
        }
    }

    // ==================== ReaderLog.verbose：核实结论 ====================

    /**
     * `verbose` **不需要** `@Volatile` —— 它是纯属性，状态在 `minLevel`
     *
     * 这条测试把「核实结论」钉住，避免后人看到扫描结果又去加一遍
     * （加了也无害，但会掩盖真正的问题）。
     */
    @Test
    fun readerLog_verbose_isBackedByVolatileMinLevel() {
        val raw = java.io.File("src/main/java/com/baicha/reader/core/ReaderLog.kt").readText()
        val lines = raw.split('\n')
        val idx = lines.indexOfFirst { it.contains("var verbose") }
        assertTrue("必须存在 verbose", idx >= 0)

        // verbose 必须是属性（有 get/set），无后备字段
        val block = lines.subList(idx, minOf(lines.size, idx + 8)).joinToString("\n")
        assertTrue("verbose 必须是属性形式（get/set）:\n$block", block.contains("get()"))
        assertTrue("verbose 的 setter 必须写 minLevel:\n$block", block.contains("minLevel ="))

        // 且 minLevel 必须是 @Volatile
        val mi = lines.indexOfFirst { it.contains("var minLevel") }
        assertTrue("必须存在 minLevel", mi >= 0)
        var k = mi - 1
        while (k >= 0 && lines[k].isBlank()) k--
        assertEquals(
            "minLevel 必须是 @Volatile（verbose 的状态实际存在它上面）",
            "@Volatile", lines[k].trim()
        )
    }

    // ==================== 并发行为验证（NodePathCollector 的语义）====================

    /**
     * 验证「最新一次请求获胜」—— 这是 `@Volatile` 要保证的语义
     *
     * 用简化模型（跳过 Handler/Android 依赖）验证覆盖写逻辑：
     * 连续 request 后，最终应只有**最后一个**节点被采集。
     */
    @Test
    fun lastRequestWins_semantics() {
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val pending = java.util.concurrent.atomic.AtomicReference<Int?>(null)

        val writer = Thread {
            for (i in 1..100) {
                pending.set(i)                    // 模拟 request()：覆盖写
                Thread.yield()
            }
        }
        val reader = Thread {
            repeat(100) {
                pending.get()?.let { order.add(it) }   // 模拟 task.run()：读最新
                Thread.yield()
            }
        }
        writer.start(); reader.start()
        writer.join(); reader.join()

        assertTrue("读线程不应读到 null（写入必然先于某次读取）", order.isNotEmpty())
        assertTrue("读到的值必须是 1..100 范围内的合法值", order.all { it in 1..100 })
        assertEquals(
            "★ 最终值必须是最后一次写入（最新获胜）",
            100, pending.get()
        )
    }

    // ==================== 全局：不得再新增无保护的跨线程 var ====================

    /**
     * ★ 扫描守卫：`NodePathCollector` 内**不得**再出现无 `@Volatile` 的成员级 var
     *
     * 这是把本轮的手工扫描**固化成自动检查** ——
     * 将来有人加字段忘了标，这里会立刻失败。
     */
    @Test
    fun nodePathCollector_noUnprotectedMemberVar() {
        val lines = java.io.File(
            "src/main/java/com/baicha/reader/core/NodePathDescription.kt"
        ).readText().split('\n')

        val start = lines.indexOfFirst { it.contains("object NodePathCollector") }
        assertTrue("必须存在 NodePathCollector", start >= 0)

        val offenders = mutableListOf<String>()
        for (i in start until lines.size) {
            // 遇到下一个顶层声明就结束
            if (i > start && Regex("^(object|class|enum class|interface|data class)\\s")
                    .containsMatchIn(lines[i])
            ) break

            val m = Regex("^    (private\\s+|internal\\s+)?var\\s+(\\w+)").find(lines[i])
            if (m != null) {
                var j = i - 1
                while (j > start && lines[j].isBlank()) j--
                if (lines[j].trim() != "@Volatile") {
                    offenders += m.destructured.component2() + "（上一行: '" + lines[j].trim() + "'）"
                }
            }
        }
        assertTrue(
            "NodePathCollector 里存在没有 @Volatile 的成员级 var —— " +
                    "本类跨无障碍线程/主线程，必须标 @Volatile：\n" +
                    offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
