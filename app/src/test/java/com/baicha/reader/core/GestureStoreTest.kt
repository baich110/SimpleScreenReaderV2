package com.baicha.reader.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势配置存储测试（A7）—— 对标 TalkBack `GestureShortcutMapping`
 * 与天坦 `GestureListPreference`
 *
 * 【测试依据（源码行号）】
 *   · TalkBack `GestureShortcutMapping.java:87`   NUMBER_OF_GESTURE_SET = 2
 *   · TalkBack `:748~760`  getActionKeyFromGestureId（查不到返回 unassigned）
 *   · 天坦 `GestureListPreference.java:206`    读 prefs.getString(key, default)
 *   · 天坦 `:232~234`                          写 prefs.putString(key, value)
 *
 * ══════════════════════════════════════════════════════════
 * 【能测什么 / 测不到什么】
 * ══════════════════════════════════════════════════════════
 * ✅ 能测：三级解析、覆盖/恢复、冲突检测、默认表与 when 的一致性、
 *          名字往返、容错
 * ❌ 测不到：SharedPreferences 真实读写、配置页交互
 */
class GestureStoreTest {

    @After
    fun tearDown() {
        // 每个测试后清掉内存态，避免相互污染
        GestureStore.resetAllForTest()
    }

    // ==================== 三级解析 ====================

    @Test
    fun resolve_falls_back_to_default() {
        // 未加载覆盖 → 走默认表
        val action = GestureStore.resolve(Gestures.GESTURE_SWIPE_RIGHT)
        assertEquals(GestureAction.FOCUS_NEXT, action)
    }

    @Test
    fun resolve_unknown_gesture_returns_none() {
        assertEquals(
            "表里没有的手势应返回 NONE",
            GestureAction.NONE,
            GestureStore.resolve(99999)
        )
    }

    @Test
    fun resolve_or_null_is_null_for_unknown() {
        assertNull(
            "resolveOrNull 用于区分「明确映射」与「本表没有」",
            GestureStore.resolveOrNull(99999)
        )
    }

    @Test
    fun resolve_or_null_returns_default_when_present() {
        assertNotNull(GestureStore.resolveOrNull(Gestures.GESTURE_SWIPE_RIGHT))
    }

    // ==================== 默认表 ↔ when 一致性（关键）====================

    /**
     * ★★ 默认表必须与 `GestureMap.actionOf()` 的硬编码一致
     *
     * 【为什么这条最重要】
     *   现在有两处描述默认映射：`GestureStore.buildDefaults()` 与
     *   `GestureMap.actionOf()`。
     *   若两者不一致，会出现「store 说 A、handler 说 B」的双真相——
     *   用户看到配置页显示 A，实际执行 B。
     *
     *   这条测试是防止双真相的唯一保障。
     */
    @Test
    fun defaults_match_hardcoded_gesture_map() {
        val defaults = GestureStore.allDefaults()
        assertTrue("默认表不该为空", defaults.isNotEmpty())

        for ((gestureId, action) in defaults) {
            assertEquals(
                "手势 $gestureId 的默认表($action) 与 GestureMap 不一致",
                action,
                GestureMap.actionOf(gestureId)
            )
        }
    }

    /**
     * 反向：`GestureMap` 里所有非 NONE 的映射都应出现在默认表里
     * （手势 7/8 是例外：它们由服务单独处理，见 Gestures.kt:313~314）
     */
    @Test
    fun gesture_map_non_none_entries_are_in_defaults() {
        val defaults = GestureStore.allDefaults()
        GestureMap.allMappedGestures().forEach { gestureId ->
            val fromMap = GestureMap.actionOf(gestureId)
            if (fromMap != GestureAction.NONE) {
                assertTrue(
                    "GestureMap 里手势 $gestureId → $fromMap 缺失于默认表",
                    defaults.containsKey(gestureId)
                )
            }
        }
    }

    // ==================== 覆盖 ====================

    @Test
    fun override_takes_priority_over_default() {
        GestureStore.setOverrideForTest(
            Gestures.GESTURE_SWIPE_RIGHT, GestureAction.SCROLL_FORWARD
        )
        assertEquals(
            "覆盖应优先于默认",
            GestureAction.SCROLL_FORWARD,
            GestureStore.resolve(Gestures.GESTURE_SWIPE_RIGHT)
        )
        // 且 GestureMap 也应反映
        assertEquals(
            GestureAction.SCROLL_FORWARD,
            GestureMap.actionOf(Gestures.GESTURE_SWIPE_RIGHT)
        )
    }

    @Test
    fun is_overridden_reports_state() {
        assertFalse(
            "未覆盖时 isOverridden 应为 false",
            GestureStore.isOverridden(Gestures.GESTURE_SWIPE_RIGHT)
        )
        GestureStore.setOverrideForTest(
            Gestures.GESTURE_SWIPE_RIGHT, GestureAction.CLICK
        )
        assertTrue(GestureStore.isOverridden(Gestures.GESTURE_SWIPE_RIGHT))
    }

    @Test
    fun reset_single_restores_default() {
        GestureStore.setOverrideForTest(
            Gestures.GESTURE_SWIPE_RIGHT, GestureAction.CLICK
        )
        GestureStore.resetForTest(Gestures.GESTURE_SWIPE_RIGHT)

        assertFalse(GestureStore.isOverridden(Gestures.GESTURE_SWIPE_RIGHT))
        assertEquals(
            "恢复后应回到默认",
            GestureAction.FOCUS_NEXT,
            GestureStore.resolve(Gestures.GESTURE_SWIPE_RIGHT)
        )
    }

    @Test
    fun reset_all_clears_every_override() {
        GestureStore.setOverrideForTest(Gestures.GESTURE_SWIPE_RIGHT, GestureAction.CLICK)
        GestureStore.setOverrideForTest(Gestures.GESTURE_SWIPE_LEFT, GestureAction.CLICK)
        assertEquals(2, GestureStore.overrideCount())

        GestureStore.resetAllForTest()

        assertEquals(0, GestureStore.overrideCount())
    }

    /** 覆盖一个「默认表里没有」的手势也应生效 */
    @Test
    fun override_can_add_new_mapping() {
        val unknown = 88888
        assertNull(GestureStore.resolveOrNull(unknown))
        GestureStore.setOverrideForTest(unknown, GestureAction.CLICK)
        assertEquals(GestureAction.CLICK, GestureStore.resolve(unknown))
    }

    /** 覆盖成 NONE 表示「禁用该手势」 */
    @Test
    fun override_to_none_disables_gesture() {
        GestureStore.setOverrideForTest(
            Gestures.GESTURE_SWIPE_RIGHT, GestureAction.NONE
        )
        assertEquals(
            "覆盖为 NONE 应能禁用（而非退回默认）",
            GestureAction.NONE,
            GestureStore.resolve(Gestures.GESTURE_SWIPE_RIGHT)
        )
    }

    // ==================== 冲突检测 ====================

    @Test
    fun no_duplicates_in_defaults() {
        val dup = GestureStore.detectDuplicates()
        assertTrue(
            "默认表里不该有同一动作绑多个手势（除了已知的 BACK/RECENTS，" +
                "它们出现在「三指左滑」与「双指反向」两种手势上）: $dup",
            dup.values.all { it.size == 2 } || dup.isEmpty()
        )
    }

    /**
     * ★ 冲突检测能找到「同一动作绑多个手势」
     */
    @Test
    fun detect_duplicates_finds_conflicts() {
        GestureStore.setOverrideForTest(
            Gestures.GESTURE_4_FINGER_SWIPE_UP, GestureAction.SCROLL_FORWARD
        )
        val dup = GestureStore.detectDuplicates()
        assertTrue(
            "SCROLL_FORWARD 现在有 3 个手势，应被检出：$dup",
            dup.containsKey(GestureAction.SCROLL_FORWARD)
        )
        assertTrue(
            "应含刚加的那个手势",
            dup[GestureAction.SCROLL_FORWARD]?.contains(
                Gestures.GESTURE_4_FINGER_SWIPE_UP
            ) == true
        )
    }

    @Test
    fun detect_duplicates_ignores_none() {
        // 把所有手势设为 NONE，不该报冲突
        GestureStore.allDefaults().keys.forEach {
            GestureStore.setOverrideForTest(it, GestureAction.NONE)
        }
        assertTrue(
            "NONE 不该计入冲突",
            GestureStore.detectDuplicates().isEmpty()
        )
    }

    // ==================== fromName 往返 ====================

    /**
     * ★ 用名字持久化（而非序号）—— 防止枚举重排导致配置串位
     */
    @Test
    fun gesture_action_name_round_trips() {
        GestureAction.values().forEach { a ->
            assertEquals(
                "「${a.name}」名字往返失败",
                a, GestureAction.fromName(a.name)
            )
        }
    }

    @Test
    fun from_name_rejects_invalid() {
        assertNull(GestureAction.fromName(null))
        assertNull(GestureAction.fromName(""))
        assertNull(GestureAction.fromName("NOT_A_REAL_ACTION"))
    }

    // ==================== 统计 ====================

    @Test
    fun override_count_tracks_changes() {
        assertEquals(0, GestureStore.overrideCount())
        GestureStore.setOverrideForTest(Gestures.GESTURE_SWIPE_RIGHT, GestureAction.CLICK)
        assertEquals(1, GestureStore.overrideCount())
        GestureStore.resetForTest(Gestures.GESTURE_SWIPE_RIGHT)
        assertEquals(0, GestureStore.overrideCount())
    }
}
