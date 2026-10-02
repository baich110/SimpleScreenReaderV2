package com.baicha.reader.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `lateinit` 时序检查 —— **服务启动即崩溃这类 bug 的静态防线**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这条测试：一次真机 P0】
 * ══════════════════════════════════════════════════════════
 *
 * 真机日志（用户实装后）：
 * ```
 * FATAL EXCEPTION: main
 * kotlin.UninitializedPropertyAccessException:
 *     lateinit property eventHandler has not been initialized
 *     at ReaderAccessibilityService.getEventHandler(ReaderAccessibilityService.kt:124)
 *     at ReaderAccessibilityService.onServiceConnected(ReaderAccessibilityService.kt:522)
 * ```
 *
 * 根因：`eventHandler.onEditingStateChanged = {...}` 这段赋值被排在
 * `eventHandler = AccessibilityEventHandler(...)` **之前**。
 * `lateinit` 属性在被读取时尚未赋值 → 直接抛异常 →
 * **无障碍服务启动即崩溃**。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么 1172 条单测全绿却没发现】（测试盲区的真正边界）
 * ══════════════════════════════════════════════════════════
 *
 * JVM 单元测试**不执行** `onServiceConnected()` —— 它需要
 * `AccessibilityService` 的真实生命周期，只能在设备上跑。
 * 所以这是一个**结构性**缺陷：任何依赖「服务启动」的代码路径
 * 都在单测覆盖范围之外。
 *
 * ⇒ 单测全绿 **不能** 证明服务能起来。这条必须靠**静态检查**兜住。
 *
 * ══════════════════════════════════════════════════════════
 * 【本测试的判定方法】
 * ══════════════════════════════════════════════════════════
 *
 * 对每个类：找出全部 `lateinit var X`，再看
 * ① **首次直接赋值**（`X = ...`）的行号
 * ② **首次直接读取**（`X.` 或 `X(）的行号
 *
 * 判据：`首次读取 < 首次赋值` ⇒ 报错。
 *
 * **关键：必须排除 lambda 体 / 匿名对象体内部的引用**（它们延迟执行）。
 * 例如：
 * ```kotlin
 *   getLastFocusActionTime = { eventHandler.lastFocusActionTime }
 * ```
 * 这行在 `eventHandler` 赋值**之前**，但它在 lambda 体内，
 * **运行时**才求值 —— 那时早已赋值完毕，**是安全的**。
 *
 * 若不排除这些，会产生 3 个假阳性（本文件实测）：
 * `eventHandler`（:314/315）、`haptic`（:376）、`audioFocus`（:366）。
 *
 * 【排除方法】从构造函数起点到该行，统计 `{` 与 `}` 的净差值；
 * 净差值 > 0 说明该行位于未闭合的 lambda / 匿名对象体内。
 */
class LateinitOrderTest {

    /**
     * 要检查的文件 —— 主要是服务（`onServiceConnected` 在那里），
     * 以及任何持有多个 lateinit 的类。
     */
    private fun filesToCheck(): List<File> {
        val dir = File("src/main/java/com/baicha/reader")
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("lateinit var") }
            .toList()
    }

    /**
     * 判断某行是否位于「延迟求值」的上下文里
     *
     * ══════════════════════════════════════════════════════════
     * 【我在这里连错两次，过程记录在案】
     * ══════════════════════════════════════════════════════════
     *
     * **第一版**：只统计 `{`/`}` 净差值，起点取「方法声明行」。
     * → 方法自己的 `{` 让 depth 恒 ≥ 1 ⇒ **任何位置都被判成 lambda 内**
     * ⇒ 检查完全失效（复现原 P0 时它照样通过）。
     *
     * **第二版**：起点改成「方法声明下一行」。
     * → 行 314 的 depth 算出来是 0 ⇒ 被误报为「赋值前直接读取」。
     *   但它实际上是这一行的 lambda 体：
     *   ```kotlin
     *     getLastFocusActionTime = { eventHandler.lastFocusActionTime },
     *   ```
     *   问题在于——这是**跨多行的构造调用**（`ScreenStateRecovery(` ... `)`），
     *   每个 `{ }` 都在同一行内开闭，净差值恒为 0，
     *   而**圆括号的跨行状态**才是「我们还在构造实参里」的标志。
     *
     * **第三版（现在）**：同时跟踪**圆括号**与**花括号**。
     * 只要 `(` 未闭合到 `to` 行，就说明该行位于某个**调用/构造的实参列表**
     * 里 —— 那里的 lambda 是延迟求值的。
     *
     * 【判据】`(parenDepth > 0) || (braceDepth > 0)` ⇒ 延迟求值上下文。
     */
    private fun isInsideLambda(lines: List<String>, from: Int, to: Int): Boolean {
        var brace = 0
        var paren = 0
        for (i in from until to) {
            val code = lines[i].substringBefore("//")
            // 去掉字符串字面量，避免里面的括号干扰计数
            val clean = code.replace(Regex(""""[^"]*""""), "")
            brace += clean.count { it == '{' } - clean.count { it == '}' }
            paren += clean.count { it == '(' } - clean.count { it == ')' }
        }
        return brace > 0 || paren > 0
    }

    @Test
    fun noLateinitIsReadBeforeAssignment() {
        val violations = mutableListOf<String>()

        for (f in filesToCheck()) {
            val lines = f.readText().split('\n')

            // ① 收集 lateinit 属性
            val decls = mutableMapOf<String, Int>()
            lines.forEachIndexed { i, l ->
                val m = Regex("""^\s*(?:@\w+\s+)?lateinit\s+var\s+(\w+)\s*:""").find(l)
                if (m != null) decls[m.groupValues[1]] = i
            }
            if (decls.isEmpty()) continue

            // ② 找初始化块的起点（onServiceConnected / onCreate / init）
            //
            // 【★ 这里我第一版写错了，记录在案】
            // 我原来把 `initStart` 设在**方法声明行**，然后在
            // `isInsideLambda(lines, initStart, firstUse)` 里从它开始累计括号。
            // 但方法自己的 `{` 就在那一行 —— 于是 depth 从 1 起步，
            // **任何位置都会被判成「在 lambda 内」**，
            // 检查因此**完全失效**（复现原 bug 时它照样通过）。
            //
            // 正确做法：从**方法体的第一个语句**开始累计，
            // 即跳过方法声明那一行（它的 `{` 不该计入）。
            val declIdx = lines.indexOfFirst {
                Regex("""\b(onServiceConnected|onCreate|init\s*\{)""").containsMatchIn(it)
            }
            val initStart = if (declIdx >= 0) declIdx + 1 else 0

            for ((name, declLine) in decls) {
                // ① 找**首次赋值**行
                var firstAssign = -1
                for (i in declLine + 1 until lines.size) {
                    val code = lines[i].substringBefore("//")
                    if (code.isBlank()) continue
                    if (Regex("""(?<![=!<>+\-*/])\b${Regex.escape(name)}\s*=(?!=)""")
                            .containsMatchIn(code)
                    ) {
                        firstAssign = i
                        break
                    }
                }
                if (firstAssign < 0) continue

                // ② 遍历**每一个**引用点，而不是只看首次
                //
                // ══════════════════════════════════════════════════════════
                // 【★ 这是本检查的第三次修正 —— 前两版都漏报了真机 P0】
                // ══════════════════════════════════════════════════════════
                //
                // **第一版**：只看「首次使用」。真机 P0 的代码是
                // ```kotlin
                //   eventHandler.onEditingStateChanged = { ... }   // :528 危险！
                //   eventHandler = AccessibilityEventHandler(...)  // :534
                // ```
                // 但「首次使用」其实是 **:314 的 lambda 体**
                // （`getLastFocusActionTime = { eventHandler.lastFocusActionTime }`），
                // 那是安全的 → 被跳过 → **算法直接 continue，
                // 再也看不到 :528 那处真正危险的引用**。
                //
                // ⇒ 教训：**「首次 X」这类判断天然脆弱**——
                //   只要首个样本恰好安全，后面的危险样本就全被忽略。
                //   必须遍历**全部**样本。
                //
                // **第二版**：把方法声明行的 `{` 计入，导致 depth 恒 ≥1
                //   ⇒ 任何位置都被判「在 lambda 内」⇒ 完全失效。
                //
                // **第三版（现在）**：逐个引用点判断——位于延迟求值上下文
                //   （lambda 体 / 跨行实参列表）则跳过，否则要求晚于赋值。
                for (i in declLine + 1 until lines.size) {
                    val raw = lines[i]
                    val code = raw.substringBefore("//")
                    if (code.isBlank()) continue
                    if (!Regex("""\b${Regex.escape(name)}\s*[.(]""").containsMatchIn(code)) {
                        continue
                    }
                    // 该行是否就是赋值行本身？赋值行不算「读取前使用」
                    if (i == firstAssign) continue
                    // 延迟求值上下文 → 安全
                    if (isInsideLambda(lines, initStart, i)) continue
                    // 在赋值之前被直接读取 → 违约
                    if (i < firstAssign) {
                        violations += "  " + f.name + ":" + (i + 1) +
                                " 读取 `" + name + "`，但它的赋值在 " +
                                (firstAssign + 1) + " 行 —— " +
                                "服务启动时会抛 UninitializedPropertyAccessException"
                    }
                }
            }
        }

        assertTrue(
            "★ lateinit 属性在赋值前被**直接读取** —— 这会让服务启动即崩溃" +
                    "（UninitializedPropertyAccessException）。\n" +
                    "注意：单测**测不到** onServiceConnected，只有这条静态检查能兜住。\n" +
                    violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ==================== 反向守卫：检查本身必须有效 ====================

    /**
     * ★ 自检：本检查必须**能**抓到「赋值排在读取之后」的构造
     *
     * 用一段人造源码验证 `isInsideLambda` 的判别力 ——
     * 这条测试防止「检查写得没用却一直绿」。
     */
    @Test
    fun selfCheck_lambdaDetectionWorks() {
        val sample = listOf(
            "        x = Foo()",                    // 0 顶层赋值
            "        thing = { x.method() }",        // 1 同一行 lambda（净 0）
            "        obj.apply {",                   // 2 开匿名体
            "            y = { x.other() }",         // 3 体内
            "        }",                             // 4 闭合
            "        x.use()"                        // 5 顶层使用
        )
        // 行 3 在行 2 打开的匿名对象体内 → isInsideLambda 应为 true
        assertTrue(
            "行 3 应被识别为位于 lambda/匿名体内部",
            isInsideLambda(sample, 0, 3)
        )
        // 行 5 时括号已闭合 → false
        assertTrue(
            "行 5 时匿名体已闭合并赋值完，应**不**判为 lambda 内",
            !isInsideLambda(sample, 0, 5)
        )

        // ★ 跨行构造调用的实参（第二版漏掉的情形）——
        //   这是真机上那个 P0 的**假阳性来源**，必须覆盖
        val multilineCall = listOf(
            "        obj = Foo(",
            "            a = { x.first() },",
            "            b = { x.second() }",   // ← 行 2：括号未闭合
            "        )"
        )
        // 注意 `to` 是**开区间上界**：要覆盖下标 2 的行必须传 3
        assertTrue(
            "★ 跨行构造调用的实参行必须被判为「延迟求值」——" +
                    "否则会产生假阳性（把安全的 lambda 体报成 bug）",
            isInsideLambda(multilineCall, 0, 3)
        )
        assertTrue(
            "括号闭合之后不应再算 lambda 内",
            !isInsideLambda(multilineCall, 0, 4)
        )
    }

    /** 服务必须真的还有 `eventHandler` 的赋值（防止「删掉赋值」式的假通过） */
    @Test
    fun serviceActuallyAssignsEventHandler() {
        val svc = File("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
            .readText()
        assertTrue(
            "服务必须为 eventHandler 赋值",
            Regex("""eventHandler\s*=\s*AccessibilityEventHandler\(""").containsMatchIn(svc)
        )
        assertTrue(
            "服务必须声明 eventHandler 为 lateinit",
            Regex("""lateinit\s+var\s+eventHandler""").containsMatchIn(svc)
        )
    }
}
