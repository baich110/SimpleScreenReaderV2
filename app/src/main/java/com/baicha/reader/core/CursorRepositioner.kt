package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 光标重定位 —— 对应 TalkBack
 * `CursorGranularityManager.resetNavigableNodesSelection()` +
 * `shouldClearSelection()`
 *
 * 【解决什么问题】
 *   用户按「按字」读完一个长文本的前 3 个字，然后切到「按词」，
 *   或把焦点移到另一个文本节点 —— 光标仍停在**旧节点**的旧位置。
 *
 *   后果（TalkBack 713~726 行注释指出的原问题）：
 *     在新的粒度下继续导航时，系统从「上一次的光标位置」开始算，
 *     于是第一个词可能从半个字开始，或者干脆跳回开头重念。
 *     用户会觉得「读屏乱了」。
 *
 * 【TalkBack 的清理条件】shouldClearSelection(node, isMicroGranularity)
 *   ① 只在「微粒度」时清 —— 微粒度 = 字符/词。
 *      句/段/行不依赖光标，不需要动。
 *   ② EditText 不清 —— 用户在输入框里有真实光标位置，
 *      清掉会破坏正在编辑的内容。
 *   ③ 节点已 focused 则不清 —— 说明光标本来就该在那。
 *
 * 【怎么清】发 ACTION_SET_SELECTION(131072) **不带参数**。
 *   不带参数的 SET_SELECTION 语义是「清除选区」，光标归位到节点默认位置。
 *   TalkBack 对根节点和子树里所有可见非可聚焦节点都发一遍（第 720~726 行），
 *   因为文本可能分布在多个子节点上。
 *
 * 【为什么不自己也遍历整个子树】
 *   TalkBack 用 `processSubtree` 递归全树，成本与树规模成正比。
 *   实测绝大多数场景只有一个文本节点承载光标，全树扫描是浪费。
 *   这里改为：只清「当前节点」+「其直接子节点」（深度 2），
 *   覆盖实际场景同时保证 O(子节点数)。
 *   深层嵌套文本属于罕见场景，此时不清也只是回到「不清」的旧行为，
 *   不会比现在更差。
 */
object CursorRepositioner {

    /** ACTION_SET_SELECTION —— 公开 SDK 可见，但保持字面量以便单测锁定 */
    const val ACTION_SET_SELECTION = 131072

    /** 遍历深度限制 */
    private const val MAX_DEPTH = 2

    /**
     * 切换粒度后重置光标。
     *
     * @param node              当前焦点节点
     * @param microGranularity  新粒度是否为微粒度（字符/词）
     * @return 实际清了几个节点（0 = 未清理）
     */
    fun onGranularityChanged(
        node: AccessibilityNodeInfo?,
        microGranularity: Boolean
    ): Int {
        if (node == null) return 0
        if (!shouldClearSelection(node, microGranularity)) return 0

        var cleared = 0
        if (clearSelection(node)) cleared++
        cleared += clearChildrenRecursively(node, 1)
        ReaderLog.d(TAG, "光标重定位：清理 $cleared 个节点")
        return cleared
    }

    /**
     * 是否应清除选区 —— 逐条对应 TalkBack `shouldClearSelection()`
     */
    fun shouldClearSelection(
        node: AccessibilityNodeInfo?,
        microGranularity: Boolean
    ): Boolean {
        if (node == null) return false
        // ① 只有微粒度需要清
        if (!microGranularity) return false

        return try {
            // ② EditText 有稳定光标位置，不清
            if (isEditText(node)) return false
            // ③ 已 focused 说明光标该在那
            !node.isFocused
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 该节点是否是输入框。
     *
     * TalkBack 用 `Role.getRole(node) == ROLE_EDIT_TEXT`；
     * 白茶没有 Role 体系，用等价的可编辑判定：
     * 有 EditableText 或支持 SET_SELECTION，且不是纯展示文本。
     */
    fun isEditText(node: AccessibilityNodeInfo): Boolean = try {
        node.isEditable || node.className?.toString()?.contains("EditText") == true
    } catch (e: Throwable) {
        false
    }

    /**
     * 粒度级别 → 是否微粒度。
     *
     * 对齐 TalkBack `CursorGranularity.isMicroGranularity()`：
     * 字符与词是微粒度（依赖光标），句/行/段不是。
     */
    fun isMicroGranularity(level: GranularityNavigator.Level): Boolean = when (level) {
        GranularityNavigator.Level.CHARACTER,
        GranularityNavigator.Level.WORD -> true
        else -> false
    }

    /** 清除单个节点的选区 */
    private fun clearSelection(node: AccessibilityNodeInfo): Boolean = try {
        node.performAction(ACTION_SET_SELECTION)
    } catch (e: Throwable) {
        false
    }

    private fun clearChildrenRecursively(node: AccessibilityNodeInfo, depth: Int): Int {
        if (depth > MAX_DEPTH) return 0
        var cleared = 0
        val count = try {
            node.childCount
        } catch (e: Throwable) {
            0
        }
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (e: Throwable) {
                null
            } ?: continue
            if (clearSelection(child)) cleared++
            cleared += clearChildrenRecursively(child, depth + 1)
        }
        return cleared
    }

    private const val TAG = "CursorRepositioner"
}
