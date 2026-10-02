package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍动作 ID 常量 —— 与 Android AccessibilityNodeInfo.ACTION_* 对应
 *
 * 单独放一个对象：枚举常量初始化时不能引用自身 companion 的成员。
 */
object NodeActions {
    /** ACTION_SET_SELECTION */
    const val SET_SELECTION = 131072

    /** ACTION_PASTE */
    const val PASTE = 32768

    /** ACTION_COPY */
    const val COPY = 16384

    /** ACTION_CUT */
    const val CUT = 65536

    /** ACTION_SET_TEXT */
    const val SET_TEXT = 2097152
}

/**
 * 编辑动作 —— 对应天坦 X6/k 的内部类 a（8 个动作）
 *
 * 【天坦的原始定义】
 *   a(String name, int ordinal, int shortRes, int descRes, int actionId)
 *
 * 【关键设计：动作列表是「动态构建」的】
 *
 * 天坦不会给编辑框显示固定 8 个动作，而是**查节点实际支持哪些 ACTION**：
 *
 *   if (x(node, 131072))   → 加「光标跳转」「选择模式」「全选」
 *   if (x(node, 32768))    → 加「粘贴」
 *   if (G.c() && GlobalConfig.w()) → 加「云粘贴」
 *   if (x(node, 16384))    → 加「复制」
 *   if (x(node, 65536))    → 加「剪切」
 *   if (有文本 && x(node, 2097152)) → 加「清空文本」
 *
 * 好处：用户滑动浏览动作时，**不会看到执行必然失败的动作**
 *      （旧实现固定 4 个动作，在不支持的节点上会播报"复制失败"）。
 */
enum class EditAction(
    /** 显示名（朗读用） */
    val label: String,

    /**
     * 需要的节点 ACTION 位 —— 0 表示无前置条件
     *
     * 对应 Android 的 AccessibilityNodeInfo.ACTION_* 常量值。
     */
    val requiredAction: Int,

    /** 是否需要节点有文本 */
    val needsText: Boolean = false
) {
    /** 光标跳转行首行尾 —— 需要 ACTION_SET_SELECTION(131072) */
    CURSOR_JUMP("光标跳转行首行尾", NodeActions.SET_SELECTION, needsText = true),

    /** 选择模式 —— 同上 */
    SELECT_MODE("选择模式", NodeActions.SET_SELECTION, needsText = true),

    /** 全选 —— 同上 */
    SELECT_ALL("全选文本", NodeActions.SET_SELECTION, needsText = true),

    /** 清空文本 —— 需要 ACTION_SET_TEXT(2097152) */
    CLEAR_TEXT("清空文本", NodeActions.SET_TEXT, needsText = true),

    /** 粘贴 —— 需要 ACTION_PASTE(32768) */
    PASTE("粘贴", NodeActions.PASTE),

    /** 云粘贴 —— 天坦是会员功能，靠配置开关（0 = 无 ACTION 前置） */
    CROSS_DEVICE_PASTE("云粘贴", 0),

    /** 复制 —— 需要 ACTION_COPY(16384) */
    COPY("复制", NodeActions.COPY),

    /** 剪切 —— 需要 ACTION_CUT(65536) */
    CUT("剪切", NodeActions.CUT)
}

/**
 * 文本编辑动作执行器 —— 对应天坦 X6/k（499 行）
 *
 * 【职责】
 *   ① 判定节点是否可编辑（role == 4）
 *   ② 动态构建可用动作列表
 *   ③ 执行动作
 *   ④ 维护「选择模式」状态
 */
object EditActionExecutor {

    private const val TAG = "EditAction"

    /**
     * 选择模式下操作的位置（0 = 行首，其它 = 全部）
     */
    @Volatile
    private var selectMode = false

    /** 跨设备粘贴是否可用（由功能层注入） */
    @Volatile
    var crossDevicePasteAvailable: (() -> Boolean)? = null

    /** 云粘贴执行器（由功能层注入） */
    @Volatile
    var crossDevicePasteHandler: ((AccessibilityNodeInfo) -> Boolean)? = null

    /**
     * 构建可用动作列表 —— 1:1 抄天坦 k(node)
     *
     * @return 该节点当前支持的动作；空列表 = 不是编辑框或无可用动作
     */
    fun availableActions(node: AccessibilityNodeInfo?): List<EditAction> {
        if (node == null) return emptyList()

        // ① 必须是编辑框
        if (NodeRoleHelper.roleOf(node) != NodeRoleHelper.ROLE_EDIT_TEXT) return emptyList()

        val text = node.text?.toString()
        val hasText = !text.isNullOrEmpty()
        val actions = node.actionList
        val actionIds = HashSet<Int>(actions.size)
        for (a in actions) actionIds.add(a.id)

        val result = ArrayList<EditAction>(8)
        val hintOnly = hasText && text == node.hintText?.toString()

        // ② 选择相关（天坦：有文本 且 支持 SET_SELECTION）
        if (hasText && !hintOnly && actionIds.contains(NodeActions.SET_SELECTION)) {
            result.add(EditAction.CURSOR_JUMP)
            result.add(EditAction.SELECT_MODE)
            result.add(EditAction.SELECT_ALL)
        }

        // ③ 粘贴
        if (actionIds.contains(NodeActions.PASTE)) {
            result.add(EditAction.PASTE)
        }

        // ④ 云粘贴（功能层开关）
        val cdAvailable = crossDevicePasteAvailable?.invoke() ?: false
        if (cdAvailable) {
            result.add(EditAction.CROSS_DEVICE_PASTE)
        }

        // ⑤ 复制 / 剪切
        if (actionIds.contains(NodeActions.COPY)) result.add(EditAction.COPY)
        if (actionIds.contains(NodeActions.CUT)) result.add(EditAction.CUT)

        // ⑥ 清空文本（有文本 + 支持 SET_TEXT）
        if (hasText && actionIds.contains(NodeActions.SET_TEXT)) {
            result.add(EditAction.CLEAR_TEXT)
        }

        return result
    }

    /**
     * 取动作的显示名 —— 选择模式按钮会根据状态变化（天坦行为）
     */
    fun labelOf(action: EditAction, node: AccessibilityNodeInfo?): String {
        if (action == EditAction.SELECT_MODE) {
            return if (isSelectMode(node)) "退出选择模式" else "进入选择模式"
        }
        return action.label
    }

    /**
     * 执行动作
     *
     * @return true = 执行成功
     */
    fun execute(action: EditAction, node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        return when (action) {
            EditAction.COPY -> TextEditActions.copy(node)
            EditAction.PASTE -> TextEditActions.paste(node)
            EditAction.CUT -> TextEditActions.cut(node)
            EditAction.SELECT_ALL -> TextEditActions.selectAll(node)

            EditAction.CLEAR_TEXT -> clearText(node)

            EditAction.CURSOR_JUMP -> cursorJump(node)

            EditAction.SELECT_MODE -> toggleSelectMode(node)

            EditAction.CROSS_DEVICE_PASTE -> {
                crossDevicePasteHandler?.invoke(node) ?: false
            }
        }
    }

    // ==================== 具体动作实现 ====================

    /**
     * 清空文本 —— 天坦用 ACTION_SET_TEXT 传空串
     */
    private fun clearText(node: AccessibilityNodeInfo): Boolean {
        return try {
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                ""
            )
            node.performAction(NodeActions.SET_TEXT, args)
        } catch (e: Throwable) {
            ReaderLog.e(TAG, "清空文本失败: ${e.message}")
            false
        }
    }

    /**
     * 光标跳转 —— 天坦行为：跳到行首或行尾
     *
     * 用 ACTION_SET_SELECTION 把选区设到 0 或文本末尾。
     */
    private fun cursorJump(node: AccessibilityNodeInfo): Boolean {
        return try {
            val text = node.text?.toString().orEmpty()
            val hasSelection = isSelectMode(node)

            val (start, end) = if (hasSelection) {
                // 已选中 → 跳到行尾
                text.length to text.length
            } else {
                // 未选中 → 跳到行首
                0 to 0
            }

            val args = android.os.Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            node.performAction(NodeActions.SET_SELECTION, args)
        } catch (e: Throwable) {
            ReaderLog.e(TAG, "光标跳转失败: ${e.message}")
            false
        }
    }

    /**
     * 切换选择模式 —— 天坦行为：进入时全选，退出时清选区
     */
    private fun toggleSelectMode(node: AccessibilityNodeInfo): Boolean {
        return try {
            val text = node.text?.toString().orEmpty()
            val entering = !isSelectMode(node)

            val (start, end) = if (entering) {
                0 to text.length          // 进入 → 全选
            } else {
                text.length to text.length // 退出 → 清选区（光标移到末尾）
            }

            val args = android.os.Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)

            val ok = node.performAction(NodeActions.SET_SELECTION, args)
            if (ok) {
                selectMode = entering
                ReaderLog.i(TAG, "选择模式 → ${if (entering) "进入" else "退出"}")
            }
            ok
        } catch (e: Throwable) {
            ReaderLog.e(TAG, "切换选择模式失败: ${e.message}")
            false
        }
    }

    /**
     * 是否处于选择模式
     *
     * 判定：节点有选区（selectionStart != selectionEnd）
     */
    fun isSelectMode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return selectMode
        return try {
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            start >= 0 && end > start
        } catch (e: Throwable) {
            selectMode
        }
    }

    /** 重置状态（窗口变化时） */
    fun reset() {
        selectMode = false
    }
}
