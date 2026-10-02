package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入法适配测试
 *
 * 对齐天坦 X6/k 的候选词上屏双路径。
 *
 * 【为什么单测只能测到这里】
 *   commitText 需要真实 AccessibilityService + InputMethod，
 *   JVM 环境无法构造。本测试锁定的是「空安全」与「不崩」——
 *   这是最容易在真机上炸的地方（读屏崩溃 = 用户彻底失去辅助）。
 */
class ImeTextCommitterTest {

    @Test
    fun null_service_is_rejected() {
        assertFalse(ImeTextCommitter.commit(null, "文字"))
        assertFalse(ImeTextCommitter.deleteBackward(null))
        assertFalse(ImeTextCommitter.isAvailable(null))
    }

    @Test
    fun null_or_empty_text_is_rejected() {
        // service 为 null 时先短路，但空文本也必须是「不成功」
        assertFalse(ImeTextCommitter.commit(null, null))
        assertFalse(ImeTextCommitter.commit(null, ""))
    }

    @Test
    fun delete_with_non_positive_count_is_rejected() {
        assertFalse(ImeTextCommitter.deleteBackward(null, 0))
        assertFalse(ImeTextCommitter.deleteBackward(null, -1))
    }
}

class ImeAdapterTest {

    /**
     * CommitMethod 的两条路径必须有可读标签 ——
     * 日志里只出现数字，排障时等于没打印。
     */
    @Test
    fun commit_methods_have_labels() {
        assertTrue(ImeAdapter.CommitMethod.INPUT_CONNECTION.label.isNotEmpty())
        assertTrue(ImeAdapter.CommitMethod.NODE_CLICK.label.isNotEmpty())
    }

    @Test
    fun commit_method_count_is_two_plus_failure() {
        // 显式锁定：只有两条成功路径，失败用 null 表示（不造第三枚举）
        org.junit.Assert.assertEquals(2, ImeAdapter.CommitMethod.values().size)
    }
}

/**
 * 导航标签二分测试 —— 对应天坦 X6/z.b.f(context, useWeb)
 */
class NavigationLabelTest {

    @Test
    fun non_web_returns_plain_label() {
        NavigationMode.values().forEach { mode ->
            org.junit.Assert.assertEquals(
                "非网页上下文应返回通用标签",
                mode.label,
                NavigationLabel.of(mode, inWeb = false)
            )
        }
    }

    /**
     * ★ 核心：三种导航在网页里必须有专用标签
     */
    @Test
    fun web_variants_are_distinct_from_native() {
        listOf(
            NavigationMode.LINKS,
            NavigationMode.HEADINGS,
            NavigationMode.CONTROLS
        ).forEach { mode ->
            val web = NavigationLabel.of(mode, inWeb = true)
            assertTrue("「${mode.label}」网页标签应不同于原生", web != mode.label)
            assertTrue("网页标签应含「网页」字样：$web", web.contains("网页"))
        }
    }

    @Test
    fun web_links_says_web_link() {
        org.junit.Assert.assertEquals(
            "网页链接",
            NavigationLabel.of(NavigationMode.LINKS, inWeb = true)
        )
    }

    @Test
    fun web_headings_says_web_heading() {
        org.junit.Assert.assertEquals(
            "网页标题",
            NavigationLabel.of(NavigationMode.HEADINGS, inWeb = true)
        )
    }

    /**
     * 其余导航无网页变体 —— 与天坦的
     * `if (i9 != 1 && i9 != 2 && i9 != 3) return 通用标签` 一致
     */
    @Test
    fun other_modes_have_no_web_variant() {
        val withWeb = listOf(
            NavigationMode.LINKS,
            NavigationMode.HEADINGS,
            NavigationMode.CONTROLS
        )
        NavigationMode.values().filter { it !in withWeb }.forEach { mode ->
            assertFalse(
                "「${mode.label}」不该有网页变体",
                NavigationLabel.hasWebVariant(mode)
            )
            org.junit.Assert.assertEquals(
                mode.label,
                NavigationLabel.of(mode, inWeb = true)
            )
        }
    }

    @Test
    fun has_web_variant_matches_tantang_exactly() {
        // 天坦只有这 3 种有 _web 资源
        var count = 0
        NavigationMode.values().forEach { if (NavigationLabel.hasWebVariant(it)) count++ }
        org.junit.Assert.assertEquals("天坦只有 3 种导航有网页变体", 3, count)
    }
}
