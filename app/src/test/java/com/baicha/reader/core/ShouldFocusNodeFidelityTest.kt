package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可聚焦判定的**抄写忠实度**测试
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这类测试】
 * ══════════════════════════════════════════════════════════
 *
 * 真机 P0（用户：「左右滑动从来都没有正常获取到焦点过，
 * 一到边界或者是没有焦点的那种震动反馈」）的根因是
 * `ShouldFocusNode.kt` 里的**三处抄写错误**：
 *
 *   ① `return k(node) && !k(node)` —— 恒为 false
 *      天坦原文是两个不同函数：`AbstractC3688i.k(wVar) && !k(wVar)`
 *      （静态 k = H||N；实例 k = 子节点结构判定）
 *
 *   ② `i(node) = isClickable || isLongClickable` —— 错
 *      天坦 `AbstractC3688i.i()` = `C(wVar) || B(wVar)`
 *      （有文本 或 有状态/进度）
 *
 *   ③ `p(node) = hasText && hasChildContent` —— 我自己拼的，非天坦语义
 *      天坦 `C3678d.p()` = `i(wVar) || m(wVar)`（带缓存）
 *
 * 三者都「编译通过、语法合法」，但语义全错。
 * JVM 里 AccessibilityNodeInfo 是空壳，行为测试测不出来 ——
 * 所以本测试锁定**源码结构**：确保这些表达式形态正确。
 *
 * ══════════════════════════════════════════════════════════
 * 【测试边界】
 * ══════════════════════════════════════════════════════════
 *   ✅ 能测：自否定表达式不存在、关键调用形态正确、
 *           缓存清理、递归有终止保障
 *   ❌ 测不到：真实节点下的判定结果是否正确（需真机）
 */
class ShouldFocusNodeFidelityTest {

    private val src: String by lazy {
        java.io.File("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt").readText()
    }

    /** 去掉注释后的代码 —— 避免注释里的示例文字干扰断言 */
    private val code: String by lazy {
        src.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    // ==================== ① 恒假表达式 ====================

    /**
     * ★★ `k(node) && !k(node)` 这类自否定表达式绝不能存在
     *
     * 这是 P0 的直接成因：一个条件与自己取反做与运算 = 恒 false，
     * 导致该分支永不返回 true → 所有节点都被判为不可聚焦。
     */
    @Test
    fun no_self_negating_expression() {
        val regex = Regex("""(\w+)\(([^)]*)\)\s*&&\s*!\s*\1\(""")
        val found = regex.findAll(code).map { it.value }.toList()
        assertTrue(
            "发现自否定表达式（恒为 false，会让焦点判定全部失败）：$found",
            found.isEmpty()
        )
    }

    /**
     * ★★ 该分支必须同时调用两个**不同**的判定函数
     *
     * 天坦：`AbstractC3688i.k(wVar) && !k(wVar)`
     * 本项目：`k(node) && !hasChildContent(node)`
     */
    @Test
    fun branch_uses_two_distinct_functions() {
        assertTrue(
            "该分支应是 k(node) && !hasChildContent(node)",
            code.contains("return k(node) && !hasChildContent(node)")
        )
    }

    // ==================== ② i() 的语义 ====================

    /**
     * ★★ `i()` 必须是 `hasText || hasSpeakableContent`，
     * 不能是 isClickable/isLongClickable
     */
    @Test
    fun i_function_uses_text_or_speakable_content() {
        assertTrue(
            "i() 应实现为 hasText(node) || hasSpeakableContent(node)",
            code.contains("hasText(node) || hasSpeakableContent(node)")
        )
        // 确保不是被误写成 clickable
        assertFalse(
            "i() 不该是 isClickable || isLongClickable —— 那是另一个函数的语义",
            code.contains("fun i(node: AccessibilityNodeInfo): Boolean {\n        return node.isClickable")
        )
    }

    @Test
    fun speakable_content_function_exists() {
        assertTrue(
            "必须实现 B(node) 的等价物（状态描述/勾选/进度）",
            code.contains("hasSpeakableContent")
        )
    }

    // ==================== ③ p() 的语义 ====================

    /**
     * ★★ `p()` 必须是 `i(node) || m(node)`
     */
    @Test
    fun p_function_uses_i_or_m() {
        assertTrue(
            "p() 应实现为 i(node) || m(node)（天坦 C3678d.p）",
            code.contains("i(node) || m(node)")
        )
    }

    /**
     * ★★ `p()` 必须有缓存 —— 它同时也是**互递归的终止条件**
     *
     * `p()` 调 `m()`，`m()` 又调 `p()`。没有缓存会栈溢出。
     */
    @Test
    fun p_function_has_cache() {
        assertTrue("p() 必须有缓存（也是互递归终止的必要条件）",
            code.contains("pCache"))
        assertTrue("应先查缓存", code.contains("val cached = pCache[node]"))
        assertTrue("查不到再计算并写回", code.contains("pCache[node] = result"))
    }

    /**
     * ★★ 缓存必须被 clear() 清空 —— 否则内存泄漏 + 拖住已回收节点
     */
    @Test
    fun cache_is_cleared() {
        val clearBlock = Regex("""fun clear\(\) \{[\s\S]*?\n    \}""")
            .find(code)?.value ?: ""
        assertTrue(
            "clear() 必须清空 pCache —— 否则 IdentityHashMap 会持续增长",
            clearBlock.contains("pCache.clear()")
        )
    }

    /** 缓存应有容量上限，防止单次操作内极端膨胀 */
    @Test
    fun cache_has_capacity_guard() {
        assertTrue(
            "缓存应有容量上限",
            code.contains("P_CACHE_CAPACITY")
        )
    }

    // ==================== ④ m() 与 l() ====================

    @Test
    fun m_function_exists() {
        assertTrue("必须实现 m(node)（可操作子节点判定）", code.contains("fun m("))
    }

    @Test
    fun l_function_is_not_a_guessed_threshold() {
        assertTrue("必须实现 l(node, count)", code.contains("fun l(node: AccessibilityNodeInfo, count: Int)"))
        assertFalse(
            "l() 的全部逻辑不能是「子节点数 > 阈值」这种凭空猜的实现",
            code.contains("count > MAX_CHILDREN_FOR_CONTAINER")
        )
    }

    /**
     * ★ l() 的语义必须是「不可见 + 有文本 + 不可聚焦 + 不可操作」
     */
    @Test
    fun l_checks_the_four_conditions() {
        val lBlock = Regex("""private fun l\(node: AccessibilityNodeInfo, count: Int\): Boolean \{[\s\S]*?\n    \}""")
            .find(code)?.value ?: ""
        assertTrue("l() 应检查是否可见：$lBlock".take(200), lBlock.contains("isVisibleToUser"))
        assertTrue("l() 应检查是否有文本", lBlock.contains("hasText"))
        assertTrue("l() 应检查可聚焦性", lBlock.contains("isFocusableLike"))
        assertTrue("l() 应检查可操作性", lBlock.contains("isActionableLike"))
    }

    // ==================== ⑤ 辅助函数存在性 ====================

    @Test
    fun required_helpers_all_exist() {
        listOf(
            "fun n(", "fun k(", "fun i(", "fun p(", "fun m(", "fun l(",
            "fun W(", "fun hasText(", "fun hasChildContent(",
            "fun hasSpeakableContent(", "fun isFocusableLike(", "fun isActionableLike("
        ).forEach { sig ->
            assertTrue("缺少辅助函数 $sig", code.contains(sig))
        }
    }

    /**
     * ★ 天坦里 `k` 与 `nn` 是「可点击」与「可选中」两个独立判定，
     *   `m()` 里的组合 `H(node) && N(node)` 需要两者都存在
     */
    @Test
    fun clickable_and_selectable_are_separate() {
        assertTrue("需要「可点击」判定 k()", code.contains("fun k("))
        assertTrue("需要「可选中」判定 nn()", code.contains("fun nn("))
    }
}
