package com.baicha.reader.core

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍焦点监控 —— 对应 TalkBack `AccessibilityFocusMonitor`（234 行）
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这一层】
 * ══════════════════════════════════════════════════════════
 *
 * `FocusManager.getFocused()` 只做「取当前焦点」。
 * 但 TalkBack 把这件事做成了一个**多级回退的决策**：
 *
 *   ① 已设的无障碍焦点（且可见）→ 用它
 *   ② 没有 → 退回**输入焦点**（编辑框场景）
 *      - 但要校验：必须是可编辑的（`requireEditable`）
 *   ③ 还没有 → 退回**焦点历史里的最后一个编辑框**
 *      - 但只在**输入法窗口在屏上**时才算有效
 *        （对应 WebView 里 input focus 是虚拟视图的场景）
 *   ④ 都没有 → null
 *
 * 【第 ③ 步的价值】
 *   WebView / 自绘编辑框的输入焦点可能是**虚拟视图**，
 *   `findFocus(FOCUS_INPUT)` 取不到东西。
 *   此时用「历史里最后一个编辑框」+「输入法在屏」双条件兜底，
 *   能恢复出正确的编辑框焦点 —— 否则用户在网页里输入时焦点会丢。
 *
 * ══════════════════════════════════════════════════════════
 * 【本实现的边界处理】
 *   本项目用的是原生 `AccessibilityNodeInfo`（不是 Compat），
 *   所以「可编辑」判定用 `isEditable || roleOf == ROLE_EDIT_TEXT`。
 */
class FocusMonitor(
    /** 取服务级焦点 —— 注入以便测试 */
    private val findAccessibilityFocus: () -> AccessibilityNodeInfo?,

    /** 取输入焦点 */
    private val findInputFocus: () -> AccessibilityNodeInfo?,

    /** 输入法窗口是否在屏 */
    private val isImeWindowOnScreen: () -> Boolean
) {

    companion object {
        private const val TAG = "FocusMonitor"
    }

    /**
     * 焦点历史里的「最后一个可编辑焦点」—— 对应 TalkBack
     * `history.getLastEditableFocusActionRecord()`
     *
     * 由外部设置（FocusManager 在设焦点时记录）
     */
    @Volatile
    var lastEditableFocus: AccessibilityNodeInfo? = null

    /** 统计（诊断） */
    @Volatile
    var a11yFocusHit = 0L
        private set

    @Volatile
    var inputFocusFallback = 0L
        private set

    @Volatile
    var historyFallback = 0L
        private set

    @Volatile
    var missCount = 0L
        private set

    // ==================== 主接口 ====================

    /**
     * 取无障碍焦点（默认要求可编辑回退）—— 对应 TalkBack
     * `getAccessibilityFocus(useInputFocusIfEmpty)`
     */
    fun getAccessibilityFocus(useInputFocusIfEmpty: Boolean = true): AccessibilityNodeInfo? =
        getAccessibilityFocus(useInputFocusIfEmpty, requireEditable = true)

    /**
     * 是否已有无障碍焦点 —— 对应 TalkBack `hasAccessibilityFocus()`
     */
    fun hasAccessibilityFocus(useInputFocusIfEmpty: Boolean = true): Boolean =
        getAccessibilityFocus(useInputFocusIfEmpty) != null

    /**
     * 取无障碍焦点（多级回退）—— 1:1 对应 TalkBack
     * `getAccessibilityFocus(useInputFocusIfEmpty, requireEditable)`
     *
     * @param useInputFocusIfEmpty 无障碍焦点为空时是否退回输入焦点
     * @param requireEditable 回退到输入焦点时是否要求可编辑
     * @return 焦点节点；null = 找不到
     */
    fun getAccessibilityFocus(
        useInputFocusIfEmpty: Boolean,
        requireEditable: Boolean
    ): AccessibilityNodeInfo? {
        // ① 已设的无障碍焦点（且可见）
        val a11yFocus = findAccessibilityFocus()
        if (a11yFocus != null && isVisible(a11yFocus)) {
            a11yFocusHit++
            return a11yFocus
        }

        if (!useInputFocusIfEmpty) {
            missCount++
            return null
        }

        // ② 退回输入焦点
        val inputFocus = findInputFocus()
        if (inputFocus != null) {
            val editable = isEditable(inputFocus)
            if (isFocused(inputFocus) && (!requireEditable || editable)) {
                inputFocusFallback++
                return inputFocus
            }
        } else {
            ReaderLog.d(TAG, "无输入焦点，尝试历史回退")
        }

        // ③ 退回「历史里最后一个编辑框」，但要求输入法在屏
        //
        // TalkBack 原注释：
        //   "If we can't find the focused node but the keyboard is showing,
        //    return the last editable. This will occur if the input-focused
        //    view is actually a virtual view (e.g. in WebViews)."
        val lastEditable = lastEditableFocus
        if (lastEditable != null && refresh(lastEditable)) {
            if (isImeWindowOnScreen()) {
                historyFallback++
                return lastEditable
            } else {
                ReaderLog.d(TAG, "历史回退失败：输入法不在屏上")
            }
        }

        missCount++
        ReaderLog.d(TAG, "无法确定焦点")
        return null
    }

    // ==================== 焦点状态记录 ====================

    /**
     * 记录一次可编辑焦点 —— 供历史回退使用
     *
     * 在 `FocusManager.applyFocus()` 成功后调用。
     */
    fun recordEditableFocus(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (isEditable(node)) {
            lastEditableFocus = node
        }
    }

    /**
     * 窗口变化时清理历史
     *
     * 跨窗口保留旧的编辑框节点会导致回退到**别的窗口**的编辑框。
     */
    fun onWindowChanged() {
        lastEditableFocus = null
    }

    // ==================== 工具 ====================

    /**
     * 是否可编辑 —— 对应 TalkBack 的
     * `node.isEditable() || Role.getRole(node) == ROLE_EDIT_TEXT`
     *
     * 两个条件任一成立即可（不同 App 暴露的信息不同）。
     */
    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.isEditable || NodeRoleHelper.roleOf(node) == NodeRoleHelper.ROLE_EDIT_TEXT
        } catch (e: Throwable) {
            false
        }
    }

    private fun isFocused(node: AccessibilityNodeInfo): Boolean = try {
        node.isFocused
    } catch (e: Throwable) {
        false
    }

    private fun isVisible(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }

    /**
     * 刷新节点 —— 对应 TalkBack `node.refresh()`
     *
     * 用途：验证历史里的节点是否还「活着」。
     * 已失效的节点不能作为回退目标。
     */
    private fun refresh(node: AccessibilityNodeInfo): Boolean = try {
        node.refresh()
    } catch (e: Throwable) {
        false
    }

    fun stats(): String =
        "无障碍焦点 $a11yFocusHit / 输入回退 $inputFocusFallback / " +
                "历史回退 $historyFallback / 未命中 $missCount"

    fun reset() {
        lastEditableFocus = null
        a11yFocusHit = 0
        inputFocusFallback = 0
        historyFallback = 0
        missCount = 0
    }
}

/**
 * 输入法窗口检测 —— 对应 TalkBack
 * `AccessibilityServiceCompatUtils.isInputWindowOnScreen()`
 */
object ImeWindowDetector {

    /**
     * 输入法窗口是否在屏上
     *
     * @param windows 当前全部窗口
     */
    fun isInputWindowOnScreen(
        windows: List<android.view.accessibility.AccessibilityWindowInfo>?
    ): Boolean {
        if (windows.isNullOrEmpty()) return false
        return windows.any {
            try {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } catch (e: Throwable) {
                false
            }
        }
    }
}
