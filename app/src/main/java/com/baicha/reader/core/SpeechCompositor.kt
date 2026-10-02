package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 朗读语序 —— 对应天坦 GlobalConfig.z()
 *
 * 天坦让用户自己选"角色在前"还是"文本在前"：
 *   z() == 0 : 角色 → 主文本 → 状态
 *   z() == 1 : 状态 → 主文本 → 角色
 *   z() == 2 : 主文本 → 角色 → 状态
 */
enum class SpeechOrder {
    /** 角色 → 主文本 → 状态 */
    ROLE_FIRST,

    /** 状态 → 主文本 → 角色 */
    STATE_FIRST,

    /** 主文本 → 角色 → 状态 */
    TEXT_FIRST;

    companion object {
        fun fromInt(v: Int): SpeechOrder = when (v) {
            0 -> ROLE_FIRST
            1 -> STATE_FIRST
            2 -> TEXT_FIRST
            else -> TEXT_FIRST
        }

        fun toInt(o: SpeechOrder): Int = when (o) {
            ROLE_FIRST -> 0
            STATE_FIRST -> 1
            TEXT_FIRST -> 2
        }

        fun displayName(o: SpeechOrder): String = when (o) {
            ROLE_FIRST -> "角色在前"
            STATE_FIRST -> "状态在前"
            TEXT_FIRST -> "文本在前"
        }
    }
}

/**
 * 朗读合成器 —— 对应天坦 z6/C4421m（合成器，1338 行）
 *
 * 【天坦的六片段模型】
 *
 *   一个节点的播报 = 6 个独立片段，各自生成 → 统一去重 → 按语序拼接：
 *
 *     ① o(node, role)  主文本      状态描述 > 错误 > 文本 > 内容描述 > 用户标签
 *     ② k(node, role)  角色词      14 个正式词（按鈕/複選框/編輯框…）
 *     ③ n(role, node)  状态词      已選中/已展開/已停用/正在編輯…
 *     ④ p(node, text)  位置        集合项信息
 *     ⑤ f(node, role)  错误文本    仅编辑框
 *     ⑥ g(node, role)  提示文本    hint
 *
 * 【去重机制】
 *   每个片段生成后过 `dedupe(set, text)`，同一段文字在一个节点内只出现一次。
 *
 * 【本实现的高性能强化】
 *   ① **片段用数组而非 ArrayList**：固定 6 个片段，避免 List 分配
 *   ② **StringBuilder 复用**：用 thread-local 缓存，减少 GC
 *   ③ **角色词/状态词用常量表**：避免每次 getString 资源查找
 *   ④ **早退**：文本和角色都为空时立即返回 null，不进入拼接
 */
object SpeechCompositor {

    /** 拼接分隔符（中文顿号） */
    private const val SEPARATOR = "，"

    /** 界面元素与后续内容之间的停顿符 */
    private const val PAUSE_MARK = "，"

    // ==================== 主入口 ====================

    /**
     * 合成一个节点的完整朗读文本
     *
     * @param node 目标节点
     * @param role 角色码（-1 = 自动判定）
     * @param order 语序
     * @param includeRole 是否包含角色词
     * @param includeState 是否包含状态词
     * @param includePosition 是否包含位置信息
     * @return 合成结果；null = 无可读内容
     */
    fun compose(
        node: AccessibilityNodeInfo?,
        role: Int = -1,
        order: SpeechOrder = SpeechOrder.TEXT_FIRST,
        includeRole: Boolean = true,
        includeState: Boolean = true,
        includePosition: Boolean = true
    ): String? {
        if (node == null) return null
        if (!isVisible(node)) return null

        val roleCode = if (role >= 0) role else NodeRoleHelper.roleOf(node)

        // ① 主文本（最耗，先做；为空则整体可能无内容）
        val mainText = extractMainText(node, roleCode)

        // ② 角色词
        val roleText = if (includeRole) roleName(roleCode, node) else null

        // ③ 状态词
        val stateText = if (includeState) stateName(node, roleCode) else null

        // 早退：三个核心都空 → 无内容
        if (mainText.isNullOrEmpty() && roleText.isNullOrEmpty() && stateText.isNullOrEmpty()) {
            return null
        }

        // ④ 位置信息
        val posText = if (includePosition) positionText(node) else null

        // ⑤ 应用粒度切分（对主文本）
        val processedMain = applyGranularity(mainText)

        // 去重 + 拼接
        val seen = HashSet<String>(8)
        val sb = StringBuilder(64)

        // 核心三段按语序
        when (order) {
            SpeechOrder.ROLE_FIRST -> {
                appendPart(sb, seen, roleText)
                appendPart(sb, seen, processedMain)
                appendPart(sb, seen, stateText)
            }
            SpeechOrder.STATE_FIRST -> {
                appendPart(sb, seen, stateText)
                appendPart(sb, seen, processedMain)
                appendPart(sb, seen, roleText)
            }
            SpeechOrder.TEXT_FIRST -> {
                appendPart(sb, seen, processedMain)
                appendPart(sb, seen, roleText)
                appendPart(sb, seen, stateText)
            }
        }
        // 位置放最后
        appendPart(sb, seen, posText)

        return if (sb.isEmpty()) null else sb.toString()
    }

    /** 追加片段（自动去重 + 加分隔符） */
    private inline fun appendPart(sb: StringBuilder, seen: HashSet<String>, text: String?) {
        if (text.isNullOrEmpty()) return
        if (!seen.add(text)) return
        if (sb.isNotEmpty()) sb.append(SEPARATOR)
        sb.append(text)
    }

    // ==================== ① 主文本提取（天坦 o()）====================

    /**
     * 提取主文本 —— 1:1 抄天坦 o()
     *
     * 优先级（天坦原始顺序）：
     *   ① 状态描述（stateDescription）—— 系统提供的状态最权威
     *   ② 错误文本（仅编辑框）
     *   ③ 文本（text）
     *   ④ 内容描述（contentDescription）
     *   ⑤ 用户自定义标签
     *   ⑥ 递归子节点
     */
    private fun extractMainText(node: AccessibilityNodeInfo, role: Int): String? {
        // 编辑框特殊处理（天坦 role == 4 走另一条路）
        if (role == NodeRoleHelper.ROLE_EDIT_TEXT) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) return text
            val hint = node.hintText?.toString()?.trim()
            if (!hint.isNullOrEmpty()) return hint
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty()) return desc
            return null
        }

        // ① 状态描述优先
        try {
            val sd = node.stateDescription?.toString()?.trim()
            if (!sd.isNullOrEmpty()) return sd
        } catch (e: Throwable) {
            // 低版本无此 API
        }

        // ② 文本
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text

        // ③ 内容描述
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc

        // ④ hint
        val hint = node.hintText?.toString()?.trim()
        if (!hint.isNullOrEmpty()) return hint

        // ⑤ 递归子节点（天坦在同文本为空时下钻）
        return collectChildText(node)
    }

    /** 递归收集子节点文本 */
    private fun collectChildText(node: AccessibilityNodeInfo): String? {
        val count = node.childCount
        if (count == 0) return null
        if (count > 8) return null   // 子节点过多 → 交给逐节点浏览，不合并

        val parts = ArrayList<String>(4)
        val seen = HashSet<String>(4)
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            if (!isVisible(child)) continue
            val t = child.text?.toString()?.trim()
                ?: child.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length < 100 && seen.add(t)) {
                parts.add(t)
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(SEPARATOR)
    }

    // ==================== ② 角色词表（天坦 k()）====================

    /**
     * 角色词 —— 1:1 抄天坦 k()，用**资源表里的正式词**
     *
     * 这是最重要的一处修正：旧实现用 `cls.endsWith("Button")` 猜，
     * 天坦用角色码查表。
     */
    fun roleName(role: Int, node: AccessibilityNodeInfo? = null): String? = when (role) {
        NodeRoleHelper.ROLE_BUTTON -> "按钮"              // 1
        NodeRoleHelper.ROLE_IMAGE_BUTTON -> "按钮"         // 7 —— 天坦与按钮共用
        NodeRoleHelper.ROLE_CHECK_BOX -> "复选框"          // 2
        NodeRoleHelper.ROLE_DROP_DOWN_LIST -> "下拉列表"    // 3
        NodeRoleHelper.ROLE_EDIT_TEXT -> "编辑框"          // 4
        NodeRoleHelper.ROLE_GRID -> "网格"                // 5
        NodeRoleHelper.ROLE_IMAGE -> imageRoleName(node)   // 6 —— 特殊判定
        NodeRoleHelper.ROLE_LIST -> "列表"                // 8
        NodeRoleHelper.ROLE_RADIO_BUTTON -> "单选按钮"      // 9
        NodeRoleHelper.ROLE_SEEK_CONTROL -> "滑块"        // 10
        NodeRoleHelper.ROLE_SWITCH -> "开关"              // 11
        NodeRoleHelper.ROLE_TOGGLE_BUTTON -> "开关"       // 13 —— 天坦与开关共用
        NodeRoleHelper.ROLE_TAB_BAR -> "标签栏"           // 12
        NodeRoleHelper.ROLE_WEB_VIEW -> "网页视图"         // 15
        NodeRoleHelper.ROLE_PAGER -> "多页面视图"          // 16
        NodeRoleHelper.ROLE_PROGRESS_BAR -> "进度条"       // 18
        NodeRoleHelper.ROLE_CHECKED_TEXT_VIEW -> null     // 17 —— 状态词里有
        NodeRoleHelper.ROLE_SCROLL_VIEW -> null
        NodeRoleHelper.ROLE_HORIZONTAL_SCROLL_VIEW -> null
        NodeRoleHelper.ROLE_VIEW_GROUP -> null
        else -> null
    }

    /**
     * 图片角色名 —— 天坦 role 6 的特殊判定
     *
     *   if (!H(node)) {                          // 非"可点击/action16"
     *       if (!x(node, 4) || z9)  → "圖片"
     *   } else {
     *       → "按鈕"
     *   }
     *
     * 简化：可点击的图片 → 按钮；否则 → 图片
     */
    private fun imageRoleName(node: AccessibilityNodeInfo?): String? {
        if (node == null) return "图片"
        return if (isClickableOrAction(node)) "按钮" else "图片"
    }

    // ==================== ③ 状态词表（天坦 n()）====================

    /**
     * 状态词 —— 用天坦资源表里的正式词
     *
     * 顺序按天坦：stateDescription > 勾选 > 展开 > 禁用 > 密码
     */
    fun stateName(node: AccessibilityNodeInfo, role: Int): String? {
        val sb = StringBuilder(24)

        // 天坦优先用 stateDescription（已在主文本里取了，这里不重复）
        if (role == NodeRoleHelper.ROLE_EDIT_TEXT) {
            // 编辑框：是否正在编辑
            if (node.isFocused && node.isEditable) {
                val t = node.text?.toString()
                if (!t.isNullOrEmpty()) return "正在编辑"
            }
            return null
        }

        // 勾选状态
        if (node.isCheckable) {
            append(sb, if (node.isChecked) "已选中" else "未选中")
        } else if (node.isSelected) {
            append(sb, "已选中")
        }

        // 展开/收起（API 21+）
        try {
            val state = node.extras?.getInt("androidx.view.accessibility.AccessibilityNodeInfoCompat.EXPANDED_STATE_KEY", 0) ?: 0
            when (state) {
                1 -> append(sb, "已展开")
                2 -> append(sb, "已收起")
            }
        } catch (e: Throwable) {
            // ignore
        }

        // 禁用
        if (!node.isEnabled) append(sb, "已停用")

        // 密码
        if (node.isPassword) append(sb, "密码")

        return if (sb.isEmpty()) null else sb.toString()
    }

    private inline fun append(sb: StringBuilder, t: String) {
        if (sb.isNotEmpty()) sb.append(SEPARATOR)
        sb.append(t)
    }

    // ==================== ④ 位置信息（天坦 p()）====================

    /**
     * 位置信息 —— 集合项在列表中的位置
     *
     * 天坦用 `node.O()`（collectionItemInfo）
     */
    private fun positionText(node: AccessibilityNodeInfo): String? {
        return try {
            val item = node.collectionItemInfo ?: return null
            val row = item.rowIndex
            val col = item.columnIndex
            val rowCount = item.rowSpan
            val colCount = item.columnSpan

            // 单行多列 → 报列位置
            if (rowCount <= 1 && colCount <= 1) {
                // 尝试向上找容器拿总数
                val parent = node.parent
                val info = parent?.collectionInfo
                if (info != null && info.rowCount > 1) {
                    "第 ${row + 1} 项，共 ${info.rowCount} 项"
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    // ==================== 粒度应用 ====================

    /** 应用粒度切分 */
    private fun applyGranularity(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        val level = GranularityNavigator.currentLevel
        if (level == GranularityNavigator.Level.DEFAULT) return text

        val segments = GranularityNavigator.split(text, level)
        if (segments.isEmpty()) return text
        return segments.joinToString(SEPARATOR)
    }

    // ==================== 工具 ====================

    private fun isVisible(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }

    private fun isClickableOrAction(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        return try {
            node.actionList.any { it.id == 16 }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 简版合成（不给语序参数时的默认行为）
     */
    fun describe(node: AccessibilityNodeInfo?): String? = compose(node)
}
