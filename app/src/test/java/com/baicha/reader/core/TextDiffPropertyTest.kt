package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本差分引擎 —— **不变式测试**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么用「不变式」而不是「逐例断言」】
 * ══════════════════════════════════════════════════════════
 *
 * diff 算法对**任意**输入都应满足几条数学性质。
 * 逐例断言只能覆盖我想到的输入；不变式断言能覆盖我没想到的 ——
 * 用随机/穷举输入批量验证，一旦某条性质被破坏立刻失败。
 *
 * 【本类的三条不变式】
 * ① **重建性**：把 diff 结果按序拼接，必须**逐字等于** newText
 *    （这是 diff 最基本的正确性 —— 拼接回来必须还原）
 * ② **增量正确性**：`addedText(old,new)` 必须等于 newText 中
 *    「不在 old 里保持原序的最大公共子序列之外」的部分 ——
 *    实际断言为：**added 必须是 newText 的子序列**，
 *    且 added 全非空时 `isWorthSpeaking` 为 true
 * ③ **稳定性**：同一输入重复调用结果相同（无隐藏状态/随机性）
 *
 * 【为什么这些性质对读屏至关重要】
 *   增量朗读只播 `addedText`。若 ①不成立，播报的内容就与屏幕不符；
 *   若 ③不成立，同一次界面变化可能播报两次不同内容。
 */
class TextDiffPropertyTest {

    private val engine = TextDiffEngine()

    // ==================== ① 重建性 ====================

    private fun reconstruct(segs: List<DiffSegment>, newText: String): String {
        // EQUAL 与 INSERT 都出现在新文本里；DELETE 只存在于旧文本
        val sb = StringBuilder()
        for (s in segs) {
            if (s.op == DiffOp.EQUAL || s.op == DiffOp.INSERT) sb.append(s.text)
        }
        return sb.toString()
    }

    @Test
    fun invariant_reconstruction_equalsNewText() {
        val cases = listOf(
            "你好" to "你好世界",
            "abc" to "axc",
            "一二三四五" to "一二",
            "开头相同中间变了结尾也相同" to "开头相同XX变了结尾也相同",
            "" to "全新",
            "全删" to "",
            "同一段" to "同一段",
            "a" to "b",
            "前缀abc后缀" to "前缀XYZ后缀",
            "完全不同的两段文字甲" to "完全不同的两段文字乙",
            "重复重复重复" to "重复重复",
            "重复" to "重复重复重复"
        )
        for ((o, n) in cases) {
            val segs = engine.diff(o, n)
            if (segs.size == 1 && segs[0].op == DiffOp.INSERT && segs[0].text == n) {
                // 降级路径（整体替换）—— 重建必然成立，跳过精细断言
                continue
            }
            assertEquals(
                "★ diff 结果拼接必须逐字还原 newText：'$o' -> '$n'",
                n, reconstruct(segs, n)
            )
        }
    }

    /**
     * 穷举短字符串，批量验证重建性
     *
     * 字母表取 {a,b}、长度 0..5 —— 组合数可控但已能覆盖大量边界
     * （空串、单字符、纯增、纯删、替换、重复字符）。
     */
    @Test
    fun invariant_reconstruction_exhaustiveShortStrings() {
        val alphabet = listOf('a', 'b')
        val strings = mutableListOf("")
        var layer = listOf("")
        repeat(5) {
            layer = layer.flatMap { p -> alphabet.map { p + it } }
            strings += layer
        }

        var checked = 0
        for (o in strings) {
            for (n in strings) {
                val segs = engine.diff(o, n)
                val isFallback = segs.size == 1 &&
                        segs[0].op == DiffOp.INSERT &&
                        segs[0].text == n &&
                        o.isNotEmpty() && n.isNotEmpty() && o != n
                if (!isFallback) {
                    assertEquals(
                        "重建失败：'$o' -> '$n'",
                        n, reconstruct(segs, n)
                    )
                }
                checked++
            }
        }
        assertTrue("应检查大量组合，实际 $checked", checked > 100)
    }

    // ==================== ② 增量正确性 ====================

    /** added 必须是 newText 的**子序列**（顺序一致，可跳过字符） */
    private fun isSubsequence(sub: String, s: String): Boolean {
        var i = 0
        for (c in s) {
            if (i < sub.length && sub[i] == c) i++
        }
        return i == sub.length
    }

    @Test
    fun invariant_added_isSubsequenceOfNewText() {
        val cases = listOf(
            "你好" to "你好世界",
            "abc" to "axc",
            "开头相同中间变了结尾也相同" to "开头相同XX变了结尾也相同",
            "前缀abc后缀" to "前缀XYZ后缀",
            "a" to "b"
        )
        for ((o, n) in cases) {
            val added = engine.addedText(o, n)
            if (added != null) {
                assertTrue(
                    "★ 增量的字符顺序必须来自新文本：added='$added', new='$n'",
                    isSubsequence(added, n)
                )
            }
        }
    }

    @Test
    fun addedText_normalCases() {
        assertEquals("尾部新增", "世界", engine.addedText("你好", "你好世界"))
        assertEquals("中间替换", "x", engine.addedText("abc", "axc"))
        assertEquals("首部新增", "新", engine.addedText("内容", "新内容"))
        assertEquals("全新", "全新", engine.addedText("", "全新"))
    }

    @Test
    fun addedText_noChange_returnsNull() {
        assertEquals(null, engine.addedText("一样", "一样"))
        assertEquals(null, engine.addedText("x", "x"))
    }

    @Test
    fun addedText_pureDeletion_returnsNull() {
        assertEquals("纯删除没有新增内容", null, engine.addedText("一二三", "一"))
        assertEquals(null, engine.addedText("abc", ""))
    }

    // ==================== ③ 稳定性 ====================

    @Test
    fun invariant_repeatedCallsAreStable() {
        val cases = listOf(
            "你好" to "你好世界",
            "abc" to "axc",
            "开头相同中间变了结尾也相同" to "开头相同XX变了结尾也相同"
        )
        for ((o, n) in cases) {
            val first = engine.diff(o, n)
            repeat(5) {
                assertEquals(
                    "★ 同一输入重复 diff 必须结果相同（不得有隐藏状态）",
                    first, engine.diff(o, n)
                )
            }
        }
    }

    // ==================== 降级路径 ====================

    /** 超长文本必须降级（不抛异常、不卡死） */
    @Test
    fun overlongText_fallsBackWithoutHanging() {
        val long1 = "很长的文本".repeat(120)      // 600 字符 > 400
        val long2 = long1 + "新增"
        val started = System.currentTimeMillis()
        val added = engine.addedText(long1, long2)
        val elapsed = System.currentTimeMillis() - started

        assertNotNull("降级路径应返回内容（整体替换）", added)
        assertTrue("降级必须在极短时间内完成，实际 ${elapsed}ms", elapsed < 100)
    }

    /** 长度差过大必须降级 */
    @Test
    fun hugeLengthDifference_fallsBack() {
        val a = "x".repeat(200)
        val b = a + "y".repeat(200)             // 长度差 200 > 100
        assertNotNull(engine.addedText(a, b))
    }

    /** DP 规模超限时不得抛异常 */
    @Test
    fun oversizedDp_doesNotThrow() {
        val a = "ab".repeat(150)                // 300 字符
        val b = "cd".repeat(150)
        // mid 部分乘积可能超 40000 —— 必须安全降级
        val segs = engine.diff(a, b)
        assertTrue("必须返回非空结果", segs.isNotEmpty())
    }

    // ==================== isWorthSpeaking ====================

    @Test
    fun isWorthSpeaking_filtersNoise() {
        assertFalse("无变化不该播", engine.isWorthSpeaking("同", "同"))
        assertFalse("纯删除不该播", engine.isWorthSpeaking("一二", "一"))
        assertFalse("null 新文本不该播", engine.isWorthSpeaking("x", null))
        assertTrue("有新增该播", engine.isWorthSpeaking("你好", "你好世界"))
    }

    // ==================== 与 NodeTextMonitor 的协作 ====================

    /**
     * ★ 端到端：焦点节点的文本变化 → 只播增量
     *
     * 这条把「引擎」与「监听器」串起来验证接线正确。
     */
    @Test
    fun endToEnd_focusNodeTextChange_speaksOnlyDelta() {
        val monitor = NodeTextMonitor().apply { enabled = true }
        val spoken = mutableListOf<String>()
        monitor.onIncrementalText = { spoken += it }

        val node = FakeNode(1, textValue = "未读消息")
        monitor.watch(node)

        // 界面更新为「未读消息 3」——只应播「3」相关增量
        node.textValue = "未读消息 3"
        monitor.onTextChanged(node)

        assertEquals("应只播新增部分", listOf(" 3"), spoken)
    }
}
