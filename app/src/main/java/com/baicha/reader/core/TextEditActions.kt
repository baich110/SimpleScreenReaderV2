package com.baicha.reader.core

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 文本编辑操作 —— 复制 / 粘贴 / 剪切 / 全选
 *
 * 抄天坦的文本编辑动作（C2120a0 的文本编辑部分）
 */
object TextEditActions {

    /**
     * 复制选中文本到剪贴板
     *
     * 【修正】原实现往 Bundle 里塞东西然后调 performAction(id)，Bundle 根本没传进去，
     * 是死代码。正确做法：读节点的选中区间文本 → 写系统剪贴板。
     */
    fun copy(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            val text = node.text?.toString().orEmpty()
            if (text.isEmpty()) {
                return node.performAction(android.R.id.copy)
            }

            // 优先取选中区间，取不到就整段
            val selected = try {
                val start = node.textSelectionStart
                val end = node.textSelectionEnd
                if (start >= 0 && end > start && end <= text.length) {
                    text.substring(start, end)
                } else {
                    text
                }
            } catch (e: Throwable) {
                text
            }

            val ctx = ReaderAccessibilityService.instance
            val cm = ctx?.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                ?: return node.performAction(android.R.id.copy)

            cm.setPrimaryClip(android.content.ClipData.newPlainText("白茶读屏", selected))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 粘贴
     */
    fun paste(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            node.performAction(android.R.id.paste)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 剪切
     */
    fun cut(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            node.performAction(android.R.id.cut)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 全选
     */
    fun selectAll(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            node.performAction(android.R.id.selectAll)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 是否可编辑
     */
    fun isEditable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return NodeRoleHelper.roleOf(node) == NodeRoleHelper.ROLE_EDIT_TEXT
    }
}