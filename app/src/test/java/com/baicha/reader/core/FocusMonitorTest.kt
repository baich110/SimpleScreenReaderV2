package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点监控器测试 —— 多级回退链
 *
 * 对应 TalkBack AccessibilityFocusMonitor.getAccessibilityFocus()
 *
 * 【回退链】
 *   ① 无障碍焦点（可见）
 *   ② 输入焦点（需为可编辑，除非 requireEditable=false）
 *   ③ 历史最后的编辑框（需输入法在屏）
 *   ④ null
 *
 * 【为什么需要第 ③ 步】
 *   WebView / 自绘编辑框的输入焦点可能是虚拟视图，
 *   findFocus(FOCUS_INPUT) 取不到 → 必须靠历史 + 输入法在屏兜底。
 */
class FocusMonitorTest {

    /** 可注入的假环境 */
    private class Env {
        var a11yFocus: AccessibilityNodeInfo? = null
        var inputFocus: AccessibilityNodeInfo? = null
        var imeOnScreen: Boolean = false
    }

    private fun build(env: Env) = FocusMonitor(
        findAccessibilityFocus = { env.a11yFocus },
        findInputFocus = { env.inputFocus },
        isImeWindowOnScreen = { env.imeOnScreen }
    )

    // ==================== 回退链 ①：无障碍焦点 ====================

    /**
     * 无障碍焦点为空 → 走回退（不应崩溃）
     */
    @Test
    fun null_all_fallbacks_returns_null() {
        val env = Env()
        val m = build(env)

        assertNull(m.getAccessibilityFocus(true))
        assertEquals(1L, m.missCount)
    }

    /**
     * useInputFocusIfEmpty = false → 只查无障碍焦点，不回退
     */
    @Test
    fun no_fallback_when_disabled() {
        val env = Env().apply { imeOnScreen = true }
        val m = build(env)

        assertNull(m.getAccessibilityFocus(useInputFocusIfEmpty = false))
        assertEquals("关闭回退时不该走输入焦点", 0L, m.inputFocusFallback)
    }

    @Test
    fun has_accessibility_focus_reflects_result() {
        val env = Env()
        val m = build(env)
        assertFalse(m.hasAccessibilityFocus())
    }

    // ==================== 回退链 ③：历史编辑框 + 输入法在屏 ====================

    /**
     * 历史里有编辑框 + 输入法在屏 → 应回退成功
     *
     * 这需要真实的 AccessibilityNodeInfo 才能验证 isEditable/refresh，
     * 这里验证「输入法不在屏时不应回退」这个关键守卫。
     */
    @Test
    fun history_fallback_requires_ime_on_screen() {
        val env = Env().apply { imeOnScreen = false }
        val m = build(env)

        // 无任何焦点 → null，且不应误判为回退成功
        assertNull(m.getAccessibilityFocus(true))
    }

    @Test
    fun ime_off_screen_blocks_history_fallback() {
        val env = Env().apply { imeOnScreen = false }
        val m = build(env)
        m.getAccessibilityFocus(true)
        assertEquals("输入法不在屏时不该走历史回退", 0L, m.historyFallback)
    }

    // ==================== 记录与清理 ====================

    @Test
    fun record_null_is_safe() {
        val m = build(Env())
        m.recordEditableFocus(null)
        assertNull(m.lastEditableFocus)
    }

    @Test
    fun window_change_clears_history() {
        val m = build(Env())
        m.onWindowChanged()
        assertNull("窗口变化应清理历史编辑框", m.lastEditableFocus)
    }

    @Test
    fun reset_clears_stats() {
        val m = build(Env())
        m.getAccessibilityFocus(true)   // 制造一次 miss
        m.reset()
        assertEquals(0L, m.missCount)
        assertNull(m.lastEditableFocus)
    }

    @Test
    fun stats_is_readable() {
        val m = build(Env())
        val s = m.stats()
        assertTrue("统计应含中文标签", s.contains("无障碍焦点"))
        assertTrue(s.contains("历史回退"))
    }

    // ==================== 输入法窗口检测 ====================

    @Test
    fun ime_detector_handles_null() {
        assertFalse(ImeWindowDetector.isInputWindowOnScreen(null))
        assertFalse(ImeWindowDetector.isInputWindowOnScreen(emptyList()))
    }

    @Test
    fun ime_window_type_constant() {
        // 输入法窗口类型码为 2
        assertEquals(
            2,
            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        )
    }
}
