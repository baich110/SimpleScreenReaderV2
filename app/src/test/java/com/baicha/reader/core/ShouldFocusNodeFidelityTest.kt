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
     * ★★ 我引入的 `nn()` 是**多余且错误**的 —— 已删除
     *
     * 【为什么删】可读版 `FocusFinder.java:293~309` 显示
     * `hasActionableChild`（我的 `m()`）里**没有** `H(node) && N(node)`
     * 这个组合；那句「父节点既可点击又可选中」是我推断出来的，
     * 天坦实际用的是：
     *     `isActionableForAccessibility(node) && isLongClickable(node)`
     * 即「父节点可点击**且**可长按」。
     *
     * `nn()`（isSelected || hasAction(32)）在天坦侧对应的是
     * `N(node)`，但它只出现在 `isActionable(node, true)` 的
     * `matchActionMask3(1,16,32)` 里，**不作为独立判定使用**。
     * 保留一个没人正确使用的函数只会误导后来者。
     */
    @Test
    fun redundant_nn_function_is_removed() {
        assertFalse(
            "nn() 是我误加的（天坦 m() 里没有这个组合），应已删除",
            code.contains("fun nn(")
        )
        assertTrue(
            "m() 里应改用 isActionableForAccessibility(node) && node.isLongClickable",
            code.contains("isActionableForAccessibility(node) && node.isLongClickable")
        )
    }

    // ==================== ⑦ 可读版反编译核对后的修正 ====================

    /**
     * ★★ `k()` 必须是 `isActionableForAccessibility || isLongClickable`
     *
     * 【依据】可读版反编译 `w7/NodeUtils.java:142~146` 的
     * `isFocusableOrSelectable`：
     *   return AccessibilityNodeInfoUtils.isActionableForAccessibility(nodeInfo)
     *       || AccessibilityNodeInfoUtils.isLongClickable(nodeInfo);
     * 展开 = `(isClickable || hasAction(16)) || isLongClickable`
     *
     * 【我原来的实现】(isFocusable || hasAction(16)) || (isSelected || hasAction(32))
     * —— 两处都错。该函数用在 `FocusFinder.shouldFocus()` 可读版第 395 行的
     * 收尾判定，错了会让该分支的接受/拒绝整体偏离天坦。
     */
    @Test
    fun k_function_matches_isFocusableOrSelectable() {
        assertTrue(
            "k() 应实现为 isActionableForAccessibility || isLongClickable",
            code.contains("isActionableForAccessibility(node) || node.isLongClickable")
        )
        assertFalse(
            "k() 不该用 isFocusable 起头（那是另一个函数 isFocusable）",
            code.contains("val h = node.isFocusable || hasAction(node, 16)")
        )
    }

    /**
     * ★ `isActionableForAccessibility` = `isClickable || hasAction(16)`
     * 依据 `w7/AccessibilityNodeInfoUtils.java:414~419`
     */
    @Test
    fun isActionableForAccessibility_matches_source() {
        assertTrue(
            "应含 isClickable 判定",
            code.contains("node.isClickable || hasActionMask(node, 16)")
        )
    }

    /**
     * ★ `y()` / `z()` 必须是「位掩码 OR actionList」双路
     *
     * 依据 `w7/NodeUtils.java:277~297`：
     *   matchActionMask  = (actions & id) == id || hasAction(id)
     *   matchActionMask2 = 两个 id 各查两路
     *   matchActionMask3 = 三个 id 各查两路
     *
     * 我原来只实现 actionList 扫描，**整条位掩码路径丢失**。
     */
    @Test
    fun y_and_z_use_both_bitmask_and_action_list() {
        // ★ 注意：不能只断言「字符串出现」—— 变异测试暴露过这个漏洞：
        //   把 y() 的位掩码两项删掉后，断言仍然通过（因为别处也出现这些串）。
        //   必须**限定在 y()/z() 函数体内部**再断言。
        val yBody = extractFunctionBody("private fun y(node: AccessibilityNodeInfo, id1: Int, id2: Int): Boolean")
        val zBody = extractFunctionBody(
            "private fun z(node: AccessibilityNodeInfo, id1: Int, id2: Int, id3: Int): Boolean"
        )

        assertTrue("y() 函数体里必须查位掩码：$yBody", yBody.contains("hasActionMask(node, id1)"))
        assertTrue("y() 函数体里必须查 actionList：$yBody", yBody.contains("hasAction(node, id1)"))
        assertTrue("z() 函数体里必须查位掩码：$zBody", zBody.contains("hasActionMask(node, id3)"))
        assertTrue("z() 函数体里必须查 actionList：$zBody", zBody.contains("hasAction(node, id3)"))
        assertTrue("必须有 hasActionMask 实现", code.contains("fun hasActionMask("))
    }

    /**
     * 抽取某个函数从签名到下一个同级声明之间的代码体
     *
     * 【为什么要这个 helper】只看「全文件是否含某字符串」会让
     * 变异测试失效 —— 把目标函数的某一行删掉后，断言可能因为
     * 别处也出现该串而继续通过。
     */
    private fun extractFunctionBody(signature: String): String {
        val start = code.indexOf(signature)
        if (start < 0) return ""
        val end = code.indexOf("\n    private fun ", start + signature.length)
            .let { if (it < 0) code.length else it }
        return code.substring(start, end)
    }

    /**
     * ★ `hasActionMask` 的语义必须是 `(actions and id) == id`
     */
    @Test
    fun hasActionMask_uses_bit_and_comparison() {
        assertTrue(
            "位掩码判定必须是 (node.actions and id) == id",
            code.contains("(node.actions and id) == id")
        )
    }

/**
 * 可读版反编译（jadx-ultimate）对照的第二轮修正测试
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么要单独立一个类】
 * ══════════════════════════════════════════════════════════
 *
 * 第一轮核查用的是**混淆版**反编译（函数名全是 C3678d / AbstractC3688i
 * 这种），而可读版把它们命名成了 FocusFinder / NodeUtils /
 * AccessibilityNodeInfoUtils，并**给每个方法起了语义名**。
 *
 * 只用可读版才看出的 5 处错误：
 *   ① `m()` 漏了 `!isActionable(child, true)` 排除项
 *   ② `m()` 的三层条件被我压成了两层
 *   ③ `m()` 的子节点去重集合应是**静态**的（跨递归共享）
 *   ④ `W()` 漏了 `hasScrollAction(parent)` 与三星 AdapterView 特判
 *   ⑤ `l()` 漏了前置短路 `f39056f`（是滚动容器则直接 false）
 *   ⑥ `hasSpeakableContent()` 的 isCheckable 写宽、rangeInfo 缺区间校验
 *
 * 【测试方法】同样只能用源码结构断言 —— JVM 里 AccessibilityNodeInfo
 * 是空壳，行为测不出。但这次加了 `extractFunctionBody()` 把断言
 * **限定在目标函数体内**（第一轮吃过亏：全局串匹配让变异测试逃逸）。
 */
class ShouldFocusNodeReadableAuditTest {

    private val src: String by lazy {
        java.io.File("src/main/java/com/baicha/reader/core/ShouldFocusNode.kt").readText()
    }

    private val code: String by lazy {
        src.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /** 抽取函数体（从签名到下一个同级 private fun） */
    private fun body(signature: String): String {
        val start = code.indexOf(signature)
        if (start < 0) return ""
        val end = code.indexOf("\n    private fun ", start + signature.length)
            .let { if (it < 0) code.length else it }
        return code.substring(start, end)
    }

    // ==================== ① m() 漏了排除项 ====================

    /**
     * ★★ `m()` 必须含 `!isActionable(child, true)` 排除项
     *
     * 依据可读版 `FocusFinder.java:302`：
     *   `child.isVisibleToUser() && !bridgeIsActionable_DefaultTrue(this, child, false, 2, null) && ...`
     * 桥接 `:162~166` 把 z9 改写为 true → `!isActionable(child, true)`
     */
    @Test
    fun m_excludes_actionable_children() {
        val b = body("private fun m(node: AccessibilityNodeInfo): Boolean")
        assertTrue("m() 必须排除「自身已可操作」的子节点：$b".take(300), b.contains("if (n(child, true)) continue"))
    }

    /**
     * ★★ `m()` 的三层条件必须完整
     *
     * 天坦：(!hasContentOrActionableChild(child)
     *        || !isTopLevelScrollItem(child)
     *        || (isActionableForAccessibility(node) && isLongClickable(node)))
     *       && hasContentOrActionableChild(child)
     */
    @Test
    fun m_has_three_layer_condition() {
        val b = body("private fun m(node: AccessibilityNodeInfo): Boolean")
        assertTrue("应有第一层取反：!childHasContentOrActionable", b.contains("!childHasContentOrActionable"))
        assertTrue("应有第二层取反：!topLevelScroll", b.contains("!topLevelScroll"))
        assertTrue("应有第三层：父节点可点击且可长按", b.contains("parentClickableAndLongClickable"))
        assertTrue("末项必须是与 hasContentOrActionable 相与", b.contains("inner && childHasContentOrActionable"))
    }

    /**
     * ★★ `m()` 的去重集合必须是**静态**的（跨递归共享）
     *
     * 依据 `FocusFinder.java:59`：`public static final HashSet f39022g`
     */
    @Test
    fun m_uses_shared_seen_set() {
        val b = body("private fun m(node: AccessibilityNodeInfo): Boolean")
        assertFalse(
            "不该在函数内新建局部去重集合（天坦是静态共享的）",
            b.contains("val seen = java.util.IdentityHashMap")
        )
        assertTrue("应使用类级的 seenChildren", b.contains("seenChildren.put(child"))
    }

    // ==================== ② W() 漏两个条件 ====================

    /**
     * ★★ `W()` 必须含 `hasScrollAction(parent)` 且优先于角色判定
     *
     * 依据可读版 `AccessibilityNodeInfoUtils.java:547`：
     *   `if (hasScrollAction(parent)) return true;`
     */
    @Test
    fun w_checks_scroll_action_first() {
        val b = body("private fun W(node: AccessibilityNodeInfo): Boolean")
        assertTrue("W() 必须检查父节点是否有滚动 action：$b".take(300), b.contains("hasScrollAction(parent)"))
        // 且必须在角色判定之前
        val scrollIdx = b.indexOf("hasScrollAction(parent)")
        val roleIdx = b.indexOf("NodeRoleHelper.roleOf(parent)")
        assertTrue("hasScrollAction 应优先于角色判定", scrollIdx in 0 until roleIdx)
    }

    /**
     * ★★ `W()` 必须含三星 AdapterView 特判
     * 依据 `AccessibilityNodeInfoUtils.java:554` 与 `:284`
     */
    @Test
    fun w_checks_samsung_adapter_view() {
        val b = body("private fun W(node: AccessibilityNodeInfo): Boolean")
        assertTrue("W() 必须含三星 AdapterView 特判", b.contains("isSamsungAdapterView(parent)"))
        assertTrue(
            "三星类名必须与天坦一致（f39055e）",
            code.contains("com.sec.android.touchwiz.widget.TwAdapterView")
        )
    }

    /**
     * ★ `hasScrollAction` 必须检查 6 个滚动 action
     * 依据 `:489~491`
     */
    @Test
    fun hasScrollAction_covers_six_actions() {
        val b = body("private fun hasScrollAction(node: AccessibilityNodeInfo): Boolean")
        listOf("ACTION_SCROLL_FORWARD", "ACTION_SCROLL_BACKWARD", "ACTION_SCROLL_UP",
               "ACTION_SCROLL_DOWN", "ACTION_SCROLL_LEFT", "ACTION_SCROLL_RIGHT")
            .forEach { assertTrue("hasScrollAction 应含 $it：$b", b.contains(it)) }
    }

    // ==================== ③ l() 漏前置短路 ====================

    /**
     * ★★ `l()` 必须有「是滚动容器则短路」的前置判断
     *
     * 依据可读版 `FocusFinder.java:278~280` + `AccessibilityNodeInfoUtils.java:173~181`
     */
    @Test
    fun l_short_circuits_for_scrollable_container() {
        val b = body("private fun l(node: AccessibilityNodeInfo, count: Int): Boolean")
        assertTrue("l() 必须含滚动容器前置短路：$b".take(300), b.contains("isScrollableContainerNode(node)"))
        assertTrue("应在循环之前", b.indexOf("isScrollableContainerNode") < b.indexOf("for (idx in"))
    }

    @Test
    fun scrollable_container_check_exists() {
        assertTrue("需要 isScrollableContainerNode 实现", code.contains("fun isScrollableContainerNode("))
        val b = body("private fun isScrollableContainerNode(node: AccessibilityNodeInfo): Boolean")
        assertTrue("应检查 hasScrollAction", b.contains("hasScrollAction(node)"))
        assertTrue("应检查可见性", b.contains("isVisibleToUser"))
    }

    // ==================== ④ hasSpeakableContent ====================

    /**
     * ★★ `hasSpeakableContent` 的勾选判定**只能**用 isCheckable
     *
     * 依据可读版 `AccessibilityNodeInfoUtils.java:334`：
     *   `!TextUtils.isEmpty(stateDescription) || nodeInfo.isCheckable() || hasRangeInfo(nodeInfo)`
     */
    @Test
    fun speakable_content_uses_only_isCheckable() {
        val b = body("private fun hasSpeakableContent(node: AccessibilityNodeInfo): Boolean")
        assertTrue("应含 isCheckable", b.contains("node.isCheckable"))
        assertFalse(
            "不该含 isChecked/isSelected（天坦只有 isCheckable）",
            b.contains("isChecked") || b.contains("isSelected")
        )
    }

    /**
     * ★★ `hasRangeInfo` 必须校验 current ∈ [min, max]
     *
     * 依据 `:348~357`：
     *   `max - min > 0 && cur >= min && cur <= max`
     */
    @Test
    fun range_info_checks_current_within_bounds() {
        val b = body("private fun hasRangeInfo(node: AccessibilityNodeInfo): Boolean")
        assertTrue("应含 current >= min", b.contains("range.current >= range.min"))
        assertTrue("应含 current <= max", b.contains("range.current <= range.max"))
        assertTrue("应含区间有效判定", b.contains("(range.max - range.min) > 0f"))
    }

    // ==================== ⑤ 命名准确性 ====================

    /**
     * ★ 那个函数应叫 `isType15` 而不是 `isWebView`
     *
     * 依据可读版 `NodeUtils.java:255~260`：
     *   `isType15(node) = matchActionMask2(node, 1024, 2048)`
     * 真正的 WebView 判定是 `NodeNavigator.isInWebView()`（`:105`）。
     */
    @Test
    fun function_is_named_isType15_not_isWebView() {
        assertTrue(
            "应按可读版命名为 isType15",
            code.contains("fun isType15(")
        )
        assertFalse(
            "不该叫 isWebView（那是另一个函数 NodeNavigator.isInWebView）",
            code.contains("fun isWebView(")
        )
    }

    /** 1024/2048 的语义注释必须在场（避免下次又被误读为滚动 action） */
    @Test
    fun html_element_constants_are_documented() {
        assertTrue(code.contains("ACTION_NEXT_HTML_ELEMENT"))
        assertTrue(code.contains("ACTION_PREVIOUS_HTML_ELEMENT"))
    }
}

}
