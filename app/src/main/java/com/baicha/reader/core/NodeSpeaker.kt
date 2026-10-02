package com.baicha.reader.core

import android.graphics.Rect
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点朗读器 —— 按天坦 Compositor（z6/C4421m，1338 行）的规则重写
 *
 * 天坦播报一个节点的完整结构：
 *   ① 角色（按钮 / 编辑框 / 复选框 / 标题 / 列表项 ...）
 *   ② 文本（text > contentDescription > hintText，递归收集子节点）
 *   ③ 状态（已选中 / 未选中 / 已勾选 / 已展开 / 已禁用 ...）
 *   ④ 位置（列表第 N 项，共 M 项）
 *   ⑤ 操作提示（可双击激活 / 可长按 ...）
 *
 * 天坦的去重规则：
 *   - 父子节点文本重复 → 只读子节点
 *   - 相同文本 → 只读一次
 */
object NodeSpeaker {

    // ==================== 角色识别（抄天坦 AbstractC3685g0.a()）====================

    /**
     * 识别节点角色（天坦 AbstractC3685g0.a(w) 返回 int 角色码）
     * 这里用字符串，更可读
     */
    fun roleOf(node: AccessibilityNodeInfo): String? {
        val cls = node.className?.toString().orEmpty()

        // 优先级从高到低
        return when {
            node.isEditable -> "编辑框"
            cls.endsWith("Button") || cls.endsWith("ImageButton") || cls.endsWith("AppCompatButton")
                    || cls.endsWith("MaterialButton") -> "按钮"
            cls.endsWith("CheckBox") || cls.endsWith("AppCompatCheckBox") -> "复选框"
            cls.endsWith("RadioButton") || cls.endsWith("AppCompatRadioButton") -> "单选框"
            cls.endsWith("Switch") || cls.endsWith("SwitchCompat") -> "开关"
            cls.endsWith("ToggleButton") -> "切换按钮"
            cls.endsWith("SeekBar") -> "进度条"
            cls.endsWith("ProgressBar") -> "进度条"
            cls.endsWith("Spinner") -> "下拉列表"
            cls.endsWith("Tab") || cls.endsWith("TabWidget") -> "标签页"
            cls.endsWith("ImageView") && !node.contentDescription.isNullOrBlank() -> "图片"
            cls.endsWith("TextView") -> null          // TextView 不读角色（天坦规则）
            cls.endsWith("EditText") -> "编辑框"
            cls.endsWith("RecyclerView") || cls.endsWith("ListView")
                    || cls.endsWith("GridView") || cls.endsWith("ScrollView") -> "列表"
            cls.endsWith("WebView") -> "网页"
            cls.endsWith("MenuItem") -> "菜单项"
            else -> null
        }
    }

    // ==================== 主入口：生成完整朗读文本 ====================

    /**
     * 生成节点的完整朗读文本（天坦 Compositor.q() 的简化实现）
     *
     * @param node 目标节点
     * @param includeRole 是否包含角色（触摸探索时包含，焦点移动时不包含）
     * @param includeState 是否包含状态
     * @param includePosition 是否包含位置
     */
    fun describe(
        node: AccessibilityNodeInfo?,
        includeRole: Boolean = true,
        includeState: Boolean = true,
        includePosition: Boolean = true
    ): String? {
        if (node == null) return null
        if (!node.isVisibleToUser) return null

        val parts = ArrayList<String>(6)

        // ① 文本内容（核心）
        val text = extractText(node)
        if (!text.isNullOrBlank()) {
            // ★ 修正：应用粒度切分（原来 GranularityNavigator 是死功能）
            val granularity = GranularityNavigator.currentLevel
            if (granularity != GranularityNavigator.Level.DEFAULT) {
                val segments = GranularityNavigator.split(text, granularity)
                if (segments.isNotEmpty()) {
                    parts.add(segments.joinToString("，"))
                }
            } else {
                parts.add(text)
            }
        }

        // ② 角色
        if (includeRole) {
            roleOf(node)?.let { parts.add(it) }
        }

        // ③ 状态
        if (includeState) {
            stateOf(node)?.let { parts.add(it) }
        }

        // ④ 位置（列表第几项）
        if (includePosition) {
            positionOf(node)?.let { parts.add(it) }
        }

        if (parts.isEmpty()) return null
        return parts.joinToString("，")
    }

    /**
     * 简版：只取文本（给事件日志用）
     */
    fun speakableText(node: AccessibilityNodeInfo?): String? = describe(node)

    // ==================== 文本提取（抄天坦 Compositor.d()）====================

    /**
     * 提取节点文本，按天坦优先级：
     *   contentDescription > text > hintText
     * 并递归收集子节点文本（防止漏读容器里的内容）
     */
    private fun extractText(node: AccessibilityNodeInfo): String? {
        // 编辑框：hintText 优先（空输入框要读提示）
        if (node.isEditable) {
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) return t
            val h = node.hintText?.toString()?.trim()
            if (!h.isNullOrEmpty()) return h
            return null
        }

        // 普通节点：contentDescription 优先
        node.contentDescription?.toString()?.trim()?.let {
            if (it.isNotEmpty()) return it
        }

        node.text?.toString()?.trim()?.let {
            if (it.isNotEmpty()) return it
        }

        node.hintText?.toString()?.trim()?.let {
            if (it.isNotEmpty()) return it
        }

        // 兜底：递归收集子节点文本（去重）
        val childTexts = LinkedHashSet<String>()
        collectChildTexts(node, childTexts, 0)

        if (childTexts.isEmpty()) return null
        return childTexts.joinToString("，")
    }

    /** 递归收集子节点文本（深度限制 3 层，防炸） */
    private fun collectChildTexts(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 3) return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue

            val t = child.text?.toString()?.trim()
            val c = child.contentDescription?.toString()?.trim()
            val v = when {
                !c.isNullOrEmpty() -> c
                !t.isNullOrEmpty() -> t
                else -> null
            }
            if (!v.isNullOrEmpty() && v.length < 200) {
                out.add(v)
            }
            collectChildTexts(child, out, depth + 1)
        }
    }

    // ==================== 状态描述（抄天坦状态规则）====================

    private fun stateOf(node: AccessibilityNodeInfo): String? {
        val states = ArrayList<String>(4)

        // 天坦优先用 stateDescription（系统提供的状态）
        val sd = node.stateDescription?.toString()?.trim()
        if (!sd.isNullOrEmpty()) {
            return sd
        }

        if (node.isCheckable) {
            states.add(if (node.isChecked) "已选中" else "未选中")
        }
        if (node.isSelected) {
            states.add("已选中")
        }
        if (node.isEnabled.not()) {
            states.add("已禁用")
        }
        if (node.isPassword) {
            states.add("密码")
        }

        return if (states.isEmpty()) null else states.joinToString("，")
    }

    // ==================== 位置描述（抄天坦列表项规则）====================

    private fun positionOf(node: AccessibilityNodeInfo): String? {
        // 向上找可滚动祖先（列表）
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isScrollable) {
                val index = indexOfChild(parent, node)
                if (index >= 0) {
                    val total = parent.childCount
                    return "第 ${index + 1} 项，共 $total 项"
                }
                return null
            }
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun indexOfChild(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        for (i in 0 until parent.childCount) {
            val c = parent.getChild(i) ?: continue
            if (c == child || c.hashCode() == child.hashCode()) return i
        }
        return -1
    }

    // ==================== 操作提示 ====================

    /** 该节点是否可激活（天坦：可点击/可长按/可勾选/可编辑） */
    fun isActionable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return node.isClickable || node.isLongClickable || node.isCheckable || node.isEditable
    }

    /** 向上找最近的可点击节点（天坦点击逻辑） */
    fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        var depth = 0
        while (n != null && depth < 10) {
            if (n.isClickable && n.isEnabled) return n
            n = n.parent
            depth++
        }
        return null
    }

    // ==================== 事件类型名 ====================

    fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "点击"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "长按"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "选中"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "焦点"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "文本变化"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "窗口变化"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "内容变化"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "滚动"
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "触摸"
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "离开"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "无障碍焦点"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "焦点清除"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "触摸探索开始"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "触摸探索结束"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "播报"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "选择变化"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "窗口集合变化"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "通知"
        else -> "TYPE_$type"
    }
}