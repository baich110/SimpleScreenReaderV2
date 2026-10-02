package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 修复回归测试
 *
 * ══════════════════════════════════════════════════════════
 * 【本批四项的共同点：都是「用错了一个近似的 API」】
 * ══════════════════════════════════════════════════════════
 *
 *   ① `isImportantForAccessibility` 误用在需要 `isVisibleToUser` 的地方
 *   ② `getBoundsInScreen` 误用在需要 `getBoundsInParent` 的地方
 *      （同一个 API 调两次 → 条件恒假 → 整段逻辑成死代码）
 *   ③ `compareContain` 只抄了天坦 `A()` 的分支①，漏了分支②
 *   ④ `AutoScrollManager` 三个跨线程字段漏 `@Volatile`
 *
 * ①② 都属于「函数存在、能编译、语义不同」—— 与本项目历史上
 * `k && !k`、`i()` 抄错、`n()` 传参同类。
 */
class P2FixesRegressionTest {

    private fun codeOf(path: String) = java.io.File(path).readText().lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val orderCode by lazy {
        codeOf("src/main/java/com/baicha/reader/core/NodeOrderComparator.kt")
    }
    private val boundsCode by lazy {
        codeOf("src/main/java/com/baicha/reader/core/NodeBoundsComparator.kt")
    }
    private val autoScrollCode by lazy {
        codeOf("src/main/java/com/baicha/reader/core/AutoScrollManager.kt")
    }

    // ==================== ① 可见性判定 ====================

    /**
     * ★★ `NodeOrderComparator` 不得再用 `isImportantForAccessibility`
     *
     * 可读版全项目 grep 确认：天坦**从未调用**它
     * （只有 `o0/NodeInfo` 的包装定义）。它在真机上几乎恒真，
     * 用它代替可见性判定会让所有「不可见节点」分支短路。
     */
    @Test
    fun no_longer_uses_isImportantForAccessibility() {
        assertFalse(
            "不得使用 isImportantForAccessibility（天坦从不调用它，且真机几乎恒真）",
            orderCode.contains("isImportantForAccessibility")
        )
        assertTrue(
            "应改用天坦的 isVisible 语义",
            orderCode.contains("private fun isVisible(")
        )
    }

    /**
     * ★★ 必须区分「裸 isVisibleToUser」与「带 RecyclerView 回退的 isVisible」
     *
     * 天坦有两个不同函数：
     *   · `d7/C1417r.java:172` 排序有效性用**裸** `isVisibleToUser()`
     *   · `w7/AccessibilityNodeInfoUtils.java:570~577` 通用 `isVisible()`
     *     带回退：`isVisibleToUser() || (isInRecyclerView && type != 15)`
     * 混用会引入偏差。
     */
    @Test
    fun distinguishes_bare_visible_to_user_from_isVisible() {
        assertTrue(
            "应有 trackVisible（裸 isVisibleToUser）",
            orderCode.contains("private fun trackVisible(")
        )
        assertTrue(
            "isVisible 应含 RecyclerView 回退",
            orderCode.contains("isRecyclerView(node) && NodeRoleHelper.roleOf(node) != ROLE_TYPE_15")
        )
    }

    // ==================== ② 内容边界 ====================

    /**
     * ★★ `sortRecycler` 必须用 `getBoundsInParent` 取内容边界
     *
     * 原来 `rect` 与 `contentRect` 都用 `getBoundsInScreen` ——
     * 同一个 API 调两次必然相等 ⇒ `contentRect.height() != rect.height()`
     * **恒为 false** ⇒ 整段边界修正逻辑永不执行（死代码）。
     *
     * 天坦 `d7/C1417r.java:314`：`nodeInfo2.getBoundsInParent(rect2)`。
     */
    @Test
    fun sort_recycler_uses_bounds_in_parent() {
        val start = orderCode.indexOf("private fun sortRecycler(")
        org.junit.Assert.assertTrue("应存在 sortRecycler", start > 0)
        val body = orderCode.substring(start, minOf(orderCode.length, start + 2000))

        assertTrue(
            "内容边界必须用 getBoundsInParent：$body".take(300),
            body.contains("getBoundsInParent(contentRect)")
        )
        // 屏幕边界仍应是 getBoundsInScreen（两处用途不同，不能都改）
        assertTrue("屏幕边界仍用 getBoundsInScreen", body.contains("getBoundsInScreen(rect)"))
    }

    // ==================== ③ compareContain 分支② ====================

    /**
     * ★★ `compareContain` 必须有分支②（并排卡片按水平重叠比例排序）
     *
     * 天坦 `d7/C1417r.java:227~271` 的 `A()` 有两条分支；
     * 原来只抄了①（垂直重叠+水平不重叠 → 按左右排），
     * 漏了②（水平相交+垂直重叠过半 → 按水平重叠比例三档）。
     *
     * 漏掉的后果：并排卡片退化成按 top 排序，而它们 top 常相同 → 顺序错乱。
     */
    @Test
    fun compare_contain_has_second_branch() {
        val start = boundsCode.indexOf("fun compareContain(")
        val end = boundsCode.indexOf("private inline fun pick(", start)
        val body = boundsCode.substring(start, if (end > start) end else minOf(boundsCode.length, start + 4000))

        assertTrue("分支①应存在（垂直重叠、水平不重叠）".take(300), body.contains("!intersectsHorizontally(ra, rb)"))
        assertTrue(
            "分支②应存在（水平相交 + 垂直重叠过半）",
            body.contains("intersectsHorizontally(ra, rb)") && body.contains("minHeight")
        )
        assertTrue(
            "分支②应含 0.3 与 0.9 两档阈值（天坦原文）",
            body.contains("0.3f") && body.contains("0.9f")
        )
        // ★ 变异测试暴露的漏洞：原来断言只看「函数体内是否出现这些名字」，
        //   把 `if (overlapV > ... && intersectsHorizontally(...))` 换成
        //   `if (false)` 后，变量定义与阈值串仍在，测试照样通过。
        //   必须断言**条件表达式本身**存在。
        assertTrue(
            "分支②的条件本身必须在（不能只留变量定义）：$body".take(400),
            body.contains("if (overlapV > THRESHOLD_20 &&") &&
                body.contains("intersectsHorizontally(ra, rb)")
        )
        org.junit.Assert.assertFalse(
            "不得出现被禁用的分支（if (false)）",
            body.contains("if (false)")
        )
    }

    /** 分支②的细分必须有实际比较（不是空实现） */
    @Test
    fun compare_contain_branch_two_actually_compares() {
        assertTrue("应有按右边界对齐的比较", boundsCode.contains("private fun orderByRight("))
        assertTrue("应有水平居中的比较", boundsCode.contains("private fun orderByCenterX("))
        assertTrue("应有按左边界比较", boundsCode.contains("private fun orderByLeft("))
        assertTrue("应有 pick 组合器", boundsCode.contains("private inline fun pick("))
    }

    // ==================== ④ 线程安全 ====================

    /**
     * ★★ `AutoScrollManager` 的三个跨线程字段必须 `@Volatile`
     *
     * 写：无障碍线程（`onScrollEvent`）
     * 读+清：主线程（`checkScrollResult`）
     * 无内存屏障时主线程可能读到过期值 → 「滚动成功却判定未移动」
     * → 触发多余重试/恢复。
     */
    @Test
    fun cross_thread_fields_are_volatile() {
        listOf("lastScrollDirection", "scrollDeltaX", "scrollDeltaY").forEach { name ->
            val idx = autoScrollCode.indexOf("private var $name")
            org.junit.Assert.assertTrue("应存在字段 $name", idx > 0)

            // ★ 变异测试暴露的漏洞：原来「往前 200 字符内出现 @Volatile」——
            //   但注释里也写着「@Volatile」字样，删掉真注解后断言照样通过。
            //   改为：**紧邻上一行**（跳过空行）必须就是 `@Volatile`。
            val lines = autoScrollCode.substring(0, idx).trimEnd().lines()
            val prevLine = lines.lastOrNull()?.trim().orEmpty()
            assertTrue(
                "$name 的紧邻上一行必须是 @Volatile（实际是「$prevLine」）",
                prevLine == "@Volatile"
            )
        }
    }
}
