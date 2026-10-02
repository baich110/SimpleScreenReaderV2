package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动去重判定测试 —— 对齐天坦 `M6/r.b`（NodeIdentifier）
 *
 * 【为什么这批测试是补上的】
 *   变异测试发现：把判定改成恒 `false`（去重完全失效），
 *   全部测试仍然通过 —— 因为判定内联在 `handleScroll(event)` 里，
 *   而 `AccessibilityEvent` 在 JVM 中构造不出来。
 *   抽出纯函数 [ScrollDedup.isDuplicate] 后才可覆盖。
 */
class ScrollDedupTest {

    /**
     * ★ 同节点 + 同窗口 → 重复
     */
    @Test
    fun same_node_and_window_is_duplicate() {
        assertTrue(
            ScrollDedup.isDuplicate(
                nodeKey = 12345, windowId = 7,
                lastNodeKey = 12345, lastWindowId = 7
            )
        )
    }

    @Test
    fun different_node_is_not_duplicate() {
        assertFalse(
            ScrollDedup.isDuplicate(
                nodeKey = 999, windowId = 7,
                lastNodeKey = 12345, lastWindowId = 7
            )
        )
    }

    /**
     * ★★ 最关键的一条：同节点哈希但不同窗口 → **不是重复**
     *
     * 【为什么这是最关键的设计点】
     *   不同窗口可能出现 hashCode 相同的节点。
     *   若只比节点哈希，用户在 A 窗口滚动后切到 B 窗口滚动，
     *   事件会被判为重复而**被吞掉不播报** —— 用户以为读屏卡了。
     *
     *   天坦 M6/r.b 用 (hashCode, windowId) 二元组正是为此。
     */
    @Test
    fun same_node_different_window_is_not_duplicate() {
        assertFalse(
            "跨窗口的同哈希节点绝不能互相吞掉",
            ScrollDedup.isDuplicate(
                nodeKey = 12345, windowId = 8,      // 不同窗口
                lastNodeKey = 12345, lastWindowId = 7
            )
        )
    }

    @Test
    fun different_window_same_node_reverse_order_too() {
        assertFalse(
            ScrollDedup.isDuplicate(
                nodeKey = 555, windowId = 3,
                lastNodeKey = 555, lastWindowId = 9
            )
        )
    }

    /**
     * ★ 两个都取不到时不判定为重复
     *
     * 否则：无 source 的滚动事件（系统合成事件）会被全部吞掉，
     * 用户滑半天没声音。宁可重复播报，也不静默丢失。
     */
    @Test
    fun unknown_both_is_never_duplicate() {
        assertFalse(
            "两个都取不到时应放行，不能静默吞掉",
            ScrollDedup.isDuplicate(
                nodeKey = ScrollDedup.UNKNOWN,
                windowId = ScrollDedup.UNKNOWN,
                lastNodeKey = ScrollDedup.UNKNOWN,
                lastWindowId = ScrollDedup.UNKNOWN
            )
        )
    }

    @Test
    fun unknown_marker_value_is_minus_one() {
        assertEquals(-1, ScrollDedup.UNKNOWN)
    }

    /**
     * 只有 node 取不到、窗口取得到 → 仍可比较（窗口可信）
     */
    @Test
    fun partial_unknown_still_compares_on_available_key() {
        assertTrue(
            ScrollDedup.isDuplicate(
                nodeKey = ScrollDedup.UNKNOWN, windowId = 5,
                lastNodeKey = ScrollDedup.UNKNOWN, lastWindowId = 5
            )
        )
        assertFalse(
            ScrollDedup.isDuplicate(
                nodeKey = ScrollDedup.UNKNOWN, windowId = 6,
                lastNodeKey = ScrollDedup.UNKNOWN, lastWindowId = 5
            )
        )
    }

    /**
     * 状态机性质：连续两个不同事件都放行，
     * 第三个与第二个相同 → 吞掉
     */
    @Test
    fun dedup_sequence_behaves_like_state_machine() {
        // 事件1
        assertFalse(ScrollDedup.isDuplicate(100, 1, -1, -1))
        // 事件2（不同节点）→ 放行
        assertFalse(ScrollDedup.isDuplicate(200, 1, 100, 1))
        // 事件2 重复 → 吞掉
        assertTrue(ScrollDedup.isDuplicate(200, 1, 200, 1))
    }
}

/**
 * 导航码解析测试 —— 覆盖 [NavigationStore.resolve] 的回退链
 *
 * 【为什么补这批】
 *   变异测试发现：把 `DEFAULT_CODE` 改成 99，全部测试仍通过 ——
 *   `load()` 需要真实 Context，而「非法码如何回退」这条
 *   最需要防御逻辑的分支反而没有覆盖。
 */
class NavigationStoreResolveTest {

    @Test
    fun valid_code_resolves_to_mode() {
        NavigationMode.values().forEach { mode ->
            assertEquals(
                "码 ${mode.typeCode} 应解析回「${mode.label}」",
                mode,
                NavigationStore.resolve(mode.typeCode)
            )
        }
    }

    @Test
    fun default_code_points_at_default_mode() {
        assertEquals(
            "默认码必须指向 DEFAULT",
            NavigationMode.DEFAULT,
            NavigationStore.resolve(NavigationStore.defaultCode())
        )
        assertEquals(0, NavigationStore.defaultCode())
    }

    /**
     * ★ 非法码必须回退到 DEFAULT，而不是崩溃或返回 null。
     * 读屏是常驻服务，任何路径都不允许抛异常。
     */
    @Test
    fun invalid_code_falls_back_to_default() {
        assertEquals(
            NavigationMode.DEFAULT,
            NavigationStore.resolve(9999)
        )
        assertEquals(
            NavigationMode.DEFAULT,
            NavigationStore.resolve(-1)
        )
        assertEquals(
            NavigationMode.DEFAULT,
            NavigationStore.resolve(Int.MAX_VALUE)
        )
        assertEquals(
            NavigationMode.DEFAULT,
            NavigationStore.resolve(Int.MIN_VALUE)
        )
    }

    /**
     * 已删除导航的场景：用户曾存过某个导航码，
     * 新版本删掉了该导航 → 必须优雅回退，不能崩。
     */
    @Test
    fun removed_navigation_code_falls_back_gracefully() {
        // 找一个当前不存在的码
        val existing = NavigationMode.values().map { it.typeCode }.toSet()
        val removed = (0..100).first { it !in existing }
        assertEquals(
            NavigationMode.DEFAULT,
            NavigationStore.resolve(removed)
        )
    }
}
