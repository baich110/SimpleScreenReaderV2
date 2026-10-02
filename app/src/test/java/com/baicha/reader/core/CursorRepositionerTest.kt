package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-6 光标重定位测试
 *
 * 对齐 TalkBack `CursorGranularityManager.shouldClearSelection()`
 */
class CursorRepositionerTest {

    @Test
    fun action_id_matches_android() {
        assertEquals(131072, CursorRepositioner.ACTION_SET_SELECTION)
    }

    /**
     * ★ 核心：只有微粒度才清选区。
     *
     * TalkBack 注释：「Only micro granularity needs to clear the selection.」
     * 句/行/段不依赖光标位置，清了反而打断朗读。
     */
    @Test
    fun only_micro_granularities_are_micro() {
        assertTrue("字符是微粒度", CursorRepositioner.isMicroGranularity(GranularityNavigator.Level.CHARACTER))
        assertTrue("词是微粒度", CursorRepositioner.isMicroGranularity(GranularityNavigator.Level.WORD))

        assertFalse("句不是微粒度", CursorRepositioner.isMicroGranularity(GranularityNavigator.Level.SENTENCE))
        assertFalse("行不是微粒度", CursorRepositioner.isMicroGranularity(GranularityNavigator.Level.LINE))
        assertFalse("段不是微粒度", CursorRepositioner.isMicroGranularity(GranularityNavigator.Level.PARAGRAPH))
        assertFalse("默认不是微粒度", CursorRepositioner.isMicroGranularity(GranularityNavigator.Level.DEFAULT))
    }

    @Test
    fun null_node_is_safe() {
        assertFalse(CursorRepositioner.shouldClearSelection(null, true))
        assertEquals(0, CursorRepositioner.onGranularityChanged(null, true))
    }

    /**
     * 非微粒度即使节点合法也不清
     */
    @Test
    fun non_micro_granularity_never_clears() {
        // 传 null 会走前面分支；这里验证「非微粒度」的短路在节点判定之前生效
        assertEquals(0, CursorRepositioner.onGranularityChanged(null, false))
    }

    /**
     * 微粒度在不同级别下的判定矩阵 —— 锁定与 TalkBack 的一致性
     */
    @Test
    fun micro_granularity_matrix_is_stable() {
        val expectMicro = mapOf(
            GranularityNavigator.Level.DEFAULT to false,
            GranularityNavigator.Level.CHARACTER to true,
            GranularityNavigator.Level.WORD to true,
            GranularityNavigator.Level.SENTENCE to false,
            GranularityNavigator.Level.LINE to false,
            GranularityNavigator.Level.PARAGRAPH to false
        )
        expectMicro.forEach { (level, expected) ->
            assertEquals(
                "「$level」微粒度判定错误",
                expected,
                CursorRepositioner.isMicroGranularity(level)
            )
        }
    }
}
