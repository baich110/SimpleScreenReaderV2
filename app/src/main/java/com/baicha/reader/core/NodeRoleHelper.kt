package com.baicha.reader.core

import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.MultiAutoCompleteTextView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TabWidget
import android.widget.ToggleButton

/**
 * 节点角色判定 —— 1:1 抄天坦 w7/AbstractC3685g0.java
 *
 * 天坦定义了 34 种角色，完整照抄。
 */
object NodeRoleHelper {

    const val ROLE_NONE = 0
    const val ROLE_BUTTON = 1
    const val ROLE_CHECK_BOX = 2
    const val ROLE_DROP_DOWN_LIST = 3
    const val ROLE_EDIT_TEXT = 4
    const val ROLE_GRID = 5
    const val ROLE_IMAGE = 6
    const val ROLE_IMAGE_BUTTON = 7
    const val ROLE_LIST = 8
    const val ROLE_RADIO_BUTTON = 9
    const val ROLE_SEEK_CONTROL = 10
    const val ROLE_SWITCH = 11
    const val ROLE_TAB_BAR = 12
    const val ROLE_TOGGLE_BUTTON = 13
    const val ROLE_VIEW_GROUP = 14
    const val ROLE_WEB_VIEW = 15
    const val ROLE_PAGER = 16
    const val ROLE_CHECKED_TEXT_VIEW = 17
    const val ROLE_PROGRESS_BAR = 18
    const val ROLE_ACTION_BAR_TAB = 19
    const val ROLE_DRAWER_LAYOUT = 20
    const val ROLE_SLIDING_DRAWER = 21
    const val ROLE_ICON_MENU = 22
    const val ROLE_TOAST = 23
    const val ROLE_ALERT_DIALOG = 24
    const val ROLE_DATE_PICKER_DIALOG = 25
    const val ROLE_TIME_PICKER_DIALOG = 26
    const val ROLE_DATE_PICKER = 27
    const val ROLE_TIME_PICKER = 28
    const val ROLE_NUMBER_PICKER = 29
    const val ROLE_SCROLL_VIEW = 30
    const val ROLE_HORIZONTAL_SCROLL_VIEW = 31
    const val ROLE_KEYBOARD_KEY = 32
    const val ROLE_TALKBACK_EDIT_TEXT_OVERLAY = 33
    const val ROLE_TEXT_ENTRY_KEY = 34

    /** 类名 → 角色（天坦 f39037a，事件用） */
    private val EVENT_ROLE_MAP = mapOf(
        "android.widget.Toast\$TN" to ROLE_TOAST,
        "android.widget.Toast" to ROLE_TOAST,
        "android.app.ActionBar\$Tab" to ROLE_ACTION_BAR_TAB,
        "android.support.v4.widget.DrawerLayout" to ROLE_DRAWER_LAYOUT,
        "android.widget.SlidingDrawer" to ROLE_SLIDING_DRAWER,
        "com.android.internal.view.menu.IconMenuView" to ROLE_ICON_MENU,
        "android.widget.DatePicker" to ROLE_DATE_PICKER,
        "android.widget.TimePicker" to ROLE_TIME_PICKER,
        "android.widget.NumberPicker" to ROLE_NUMBER_PICKER,
        "android.app.DatePickerDialog" to ROLE_DATE_PICKER_DIALOG,
        "android.app.TimePickerDialog" to ROLE_TIME_PICKER_DIALOG,
        "android.app.AlertDialog" to ROLE_ALERT_DIALOG,
        "android.app.Dialog" to ROLE_ALERT_DIALOG,
        "androidx.appcompat.app.AlertDialog" to ROLE_ALERT_DIALOG,
        "miui.app.AlertDialog" to ROLE_ALERT_DIALOG
    )

    /** 类名 → 角色（天坦 f39038b，节点用） */
    private val NODE_ROLE_MAP = mapOf(
        Switch::class.java.name to ROLE_SWITCH,
        "com.tencent.widget.Switch" to ROLE_SWITCH,
        ToggleButton::class.java.name to ROLE_TOGGLE_BUTTON,
        RadioButton::class.java.name to ROLE_RADIO_BUTTON,
        CompoundButton::class.java.name to ROLE_CHECK_BOX,
        Button::class.java.name to ROLE_BUTTON,
        CheckedTextView::class.java.name to ROLE_CHECKED_TEXT_VIEW,
        EditText::class.java.name to ROLE_EDIT_TEXT,
        AutoCompleteTextView::class.java.name to ROLE_EDIT_TEXT,
        MultiAutoCompleteTextView::class.java.name to ROLE_EDIT_TEXT,
        "miuix.androidbasewidget.widget.ClearableEditText" to ROLE_EDIT_TEXT,
        "com.android.fileexplorer.view.SearchEditText" to ROLE_EDIT_TEXT,
        "miui.widget.ClearableEditText" to ROLE_EDIT_TEXT,
        "android.inputmethodservice.Keyboard\$Key" to ROLE_KEYBOARD_KEY,
        "android.webkit.WebView" to ROLE_WEB_VIEW,
        TabWidget::class.java.name to ROLE_TAB_BAR,
        ScrollView::class.java.name to ROLE_SCROLL_VIEW,
        "androidx.viewpager.widget.ViewPager" to ROLE_PAGER,
        "android.support.v4.view.ViewPager" to ROLE_PAGER,
        "androidx.core.view.ViewPager" to ROLE_PAGER,
        Spinner::class.java.name to ROLE_DROP_DOWN_LIST,
        GridView::class.java.name to ROLE_GRID,
        AbsListView::class.java.name to ROLE_LIST,
        ViewGroup::class.java.name to ROLE_VIEW_GROUP,
        FrameLayout::class.java.name to ROLE_VIEW_GROUP,
    )

    /**
     * 判定节点角色 —— 1:1 抄天坦 a(node)
     */
    fun roleOf(node: AccessibilityNodeInfo?): Int {
        if (node == null) return ROLE_NONE

        // ① 文本输入键
        if (isTextEntryKey(node)) return ROLE_TEXT_ENTRY_KEY

        val cls = node.className?.toString()

        // ② SeekBar
        if (classNameEquals(cls, SeekBar::class.java.name)) return ROLE_SEEK_CONTROL

        // ③ 有 setProgress 动作
        if (isActionable(node) && hasAction(node, android.R.id.accessibilityActionSetProgress)) {
            return ROLE_SEEK_CONTROL
        }

        // ④ 查表
        NODE_ROLE_MAP[cls]?.let { return it }

        // ⑤ CompoundButton 子类
        if (classNameExtends(cls, CompoundButton::class.java.name)) return ROLE_CHECK_BOX

        // ⑥ ImageView
        if (classNameExtends(cls, ImageView::class.java.name)) {
            return if (isClickable(node)) ROLE_IMAGE_BUTTON else ROLE_IMAGE
        }

        // ⑦ HorizontalScrollView（无 collectionInfo）
        if (classNameExtends(cls, HorizontalScrollView::class.java.name) && node.collectionInfo == null) {
            return ROLE_HORIZONTAL_SCROLL_VIEW
        }

        // ⑧ ProgressBar
        if (classNameExtends(cls, ProgressBar::class.java.name)) return ROLE_PROGRESS_BAR

        // ⑨ 可操作但无 setProgress
        if (isActionable(node) && !hasAction(node, android.R.id.accessibilityActionSetProgress)) {
            return ROLE_PROGRESS_BAR
        }

        // ⑩ Keyboard.Key
        if (classNameExtends(cls, "android.inputmethodservice.Keyboard\$Key")) return ROLE_KEYBOARD_KEY

        // ⑪ GridView / AbsListView
        if (classNameExtends(cls, GridView::class.java.name)) return ROLE_GRID
        if (classNameExtends(cls, AbsListView::class.java.name)) return ROLE_LIST

        // ⑫ Pager 相关动作
        if (hasScrollAction(node)) return ROLE_PAGER

        // ⑬ collectionInfo
        val collection = node.collectionInfo
        if (collection != null) {
            return if (collection.rowCount <= 1 || collection.columnCount <= 1) ROLE_LIST else ROLE_GRID
        }

        // ⑭ 空文本的 setText 节点 → EditText
        if (hasAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) &&
            node.text.isNullOrEmpty()
        ) {
            return ROLE_EDIT_TEXT
        }

        return ROLE_NONE
    }

    /**
     * 角色名的**调试表示** —— 仅供日志，**禁止用于播报**
     *
     * 【为什么改名】原名 `roleName()` 返回 "ROLE_BUTTON" 这类枚举名
     * 字符串，看起来像「角色名」，很容易被误用到播报路径上 ——
     * 读屏念出 "ROLE_BUTTON" 对用户毫无意义。
     *
     * 播报请用 `SpeechCompositor.roleName(role, node)`（返回中文）。
     *
     * 【本轮核查结论】所有播报调用点用的确实都是 SpeechCompositor
     * 的中文版本，本方法没有被误用。但它作为公开 API 是隐患，
     * 故改名并加此警示。
     */
    fun debugName(role: Int): String = when (role) {
        ROLE_NONE -> "ROLE_NONE"
        ROLE_BUTTON -> "ROLE_BUTTON"
        ROLE_CHECK_BOX -> "ROLE_CHECK_BOX"
        ROLE_DROP_DOWN_LIST -> "ROLE_DROP_DOWN_LIST"
        ROLE_EDIT_TEXT -> "ROLE_EDIT_TEXT"
        ROLE_GRID -> "ROLE_GRID"
        ROLE_IMAGE -> "ROLE_IMAGE"
        ROLE_IMAGE_BUTTON -> "ROLE_IMAGE_BUTTON"
        ROLE_LIST -> "ROLE_LIST"
        ROLE_RADIO_BUTTON -> "ROLE_RADIO_BUTTON"
        ROLE_SEEK_CONTROL -> "ROLE_SEEK_CONTROL"
        ROLE_SWITCH -> "ROLE_SWITCH"
        ROLE_TAB_BAR -> "ROLE_TAB_BAR"
        ROLE_TOGGLE_BUTTON -> "ROLE_TOGGLE_BUTTON"
        ROLE_VIEW_GROUP -> "ROLE_VIEW_GROUP"
        ROLE_WEB_VIEW -> "ROLE_WEB_VIEW"
        ROLE_PAGER -> "ROLE_PAGER"
        ROLE_CHECKED_TEXT_VIEW -> "ROLE_CHECKED_TEXT_VIEW"
        ROLE_PROGRESS_BAR -> "ROLE_PROGRESS_BAR"
        ROLE_ACTION_BAR_TAB -> "ROLE_ACTION_BAR_TAB"
        ROLE_DRAWER_LAYOUT -> "ROLE_DRAWER_LAYOUT"
        ROLE_SLIDING_DRAWER -> "ROLE_SLIDING_DRAWER"
        ROLE_ICON_MENU -> "ROLE_ICON_MENU"
        ROLE_TOAST -> "ROLE_TOAST"
        ROLE_ALERT_DIALOG -> "ROLE_ALERT_DIALOG"
        ROLE_DATE_PICKER_DIALOG -> "ROLE_DATE_PICKER_DIALOG"
        ROLE_TIME_PICKER_DIALOG -> "ROLE_TIME_PICKER_DIALOG"
        ROLE_DATE_PICKER -> "ROLE_DATE_PICKER"
        ROLE_TIME_PICKER -> "ROLE_TIME_PICKER"
        ROLE_NUMBER_PICKER -> "ROLE_NUMBER_PICKER"
        ROLE_SCROLL_VIEW -> "ROLE_SCROLL_VIEW"
        ROLE_HORIZONTAL_SCROLL_VIEW -> "ROLE_HORIZONTAL_SCROLL_VIEW"
        ROLE_KEYBOARD_KEY -> "ROLE_KEYBOARD_KEY"
        ROLE_TALKBACK_EDIT_TEXT_OVERLAY -> "ROLE_TALKBACK_EDIT_TEXT_OVERLAY"
        ROLE_TEXT_ENTRY_KEY -> "ROLE_TEXT_ENTRY_KEY"
        else -> "(unknown role $role)"
    }

    /**
     * 中文角色名（用于播报）
     */
    fun roleNameCn(role: Int): String? = when (role) {
        ROLE_BUTTON -> "按钮"
        ROLE_CHECK_BOX -> "复选框"
        ROLE_DROP_DOWN_LIST -> "下拉列表"
        ROLE_EDIT_TEXT -> "编辑框"
        ROLE_GRID -> "网格"
        ROLE_IMAGE -> "图片"
        ROLE_IMAGE_BUTTON -> "图片按钮"
        ROLE_LIST -> "列表"
        ROLE_RADIO_BUTTON -> "单选按钮"
        ROLE_SEEK_CONTROL -> "进度条"
        ROLE_SWITCH -> "开关"
        ROLE_TAB_BAR -> "标签栏"
        ROLE_TOGGLE_BUTTON -> "切换按钮"
        ROLE_VIEW_GROUP -> null
        ROLE_WEB_VIEW -> "网页"
        ROLE_PAGER -> "页面"
        ROLE_CHECKED_TEXT_VIEW -> "可勾选"
        ROLE_PROGRESS_BAR -> "进度条"
        ROLE_ACTION_BAR_TAB -> "标签"
        ROLE_DRAWER_LAYOUT -> "抽屉"
        ROLE_SLIDING_DRAWER -> "抽屉"
        ROLE_ICON_MENU -> "菜单"
        ROLE_TOAST -> "提示"
        ROLE_ALERT_DIALOG -> "对话框"
        ROLE_DATE_PICKER -> "日期选择"
        ROLE_TIME_PICKER -> "时间选择"
        ROLE_NUMBER_PICKER -> "数字选择"
        ROLE_SCROLL_VIEW -> null
        ROLE_HORIZONTAL_SCROLL_VIEW -> null
        ROLE_KEYBOARD_KEY -> "按键"
        ROLE_TEXT_ENTRY_KEY -> "按键"
        else -> null
    }

    // ==================== 内部工具 ====================

    private fun classNameEquals(cls: String?, target: String): Boolean = cls == target

    /**
     * 判断类是否继承自某个父类 —— 走 ClassLoadingCache
     *
     * 【性能修正】
     *
     * 原实现每次调用都执行 `Class.forName()` 两次 —— 而本方法是
     * 角色判定（`roleOf()`）的热路径，滑动浏览时**每个节点**都会走。
     *
     * `Class.forName` 会触发类加载（拿 ClassLoader 锁），
     * 在大列表快速滚动时是明确的卡顿来源。
     *
     * 现在改为：
     *   ① Class 对象缓存（对应 TalkBack ClassLoadingCache）
     *   ② **继承关系结果缓存**（比 TalkBack 更进一步：
     *      连 `isAssignableFrom` 的结果都缓存，避免重复计算）
     */
    private fun classNameExtends(cls: String?, parent: String): Boolean {
        return ClassLoadingCache.isInstanceOf(cls, parent)
    }

    private fun isActionable(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.isLongClickable || node.isFocusable
    }

    private fun isClickable(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable
    }

    private fun hasAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return try {
            node.actionList.any { it.id == actionId }
        } catch (e: Throwable) {
            false
        }
    }

    /** 天坦 AbstractC3693m.n0(node, actionId) 的语义 */
    private fun hasScrollAction(node: AccessibilityNodeInfo): Boolean {
        // ACTION_SCROLL_UP/DOWN 在 API 23+ 才有，用常量值兜底
        val scrollActions = intArrayOf(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            4096,   // ACTION_SCROLL_UP
            8192    // ACTION_SCROLL_DOWN
        )
        return try {
            node.actionList.any { it.id in scrollActions }
        } catch (e: Throwable) {
            false
        }
    }

    /** 天坦 AbstractC3693m.V(node) —— 文本输入键 */
    private fun isTextEntryKey(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: return false
        return cls.contains("Keyboard\$Key") && node.text != null
    }
}