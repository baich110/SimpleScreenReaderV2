package com.baicha.reader.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.baicha.reader.R
import com.baicha.reader.core.GestureAction
import com.baicha.reader.core.GestureStore
import com.baicha.reader.core.Gestures

/**
 * 手势配置页（A7 / D2）—— 对应天坦 `TouchControlPreferencesActivity`
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么 A7 必须配一个界面】
 * ══════════════════════════════════════════════════════════
 *
 * 只有 `GestureStore` 而没有界面，用户依然改不了手势 ——
 * 等于「做完了但落不了地」。所以 A7 的完成定义包含最小界面。
 *
 * ══════════════════════════════════════════════════════════
 * 【无障碍设计要点（本页由视障用户用读屏操作）】
 * ══════════════════════════════════════════════════════════
 *
 * ① **整行可点 + 可聚焦**：读屏用左右滑浏览元素、
 *    上滑浏览操作、双击执行。行本身聚焦比「行内藏个小按钮」好操作。
 * ② **contentDescription 含状态**：每行播报「单指向右滑，下一个焦点」，
 *    用户不用进二级页面就知道当前配置。
 * ③ **ListView 而非自定义**：系统自带集合语义
 *    （「第 3 项，共 34 项」），手写 RecyclerView 要自己补。
 * ④ **触控目标 ≥ 48dp**：见布局。
 * ⑤ **确认对话框**：改动是不可逆的（虽然能恢复默认），
 *    且用户是盲操作 —— 给出明确确认项。
 */
class GestureSettingsActivity : AppCompatActivity() {

    private lateinit var listGestures: ListView
    /** 按 ListView 顺序排列的手势 ID */
    private var gestureIds: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture_settings)

        // 加载用户配置
        GestureStore.load(this)

        listGestures = findViewById(R.id.listGestures)

        // ══════════════════════════════════════════════════════════
        // 【★ 本轮修复：补无障碍动作】
        // ══════════════════════════════════════════════════════════
        // 项目铁律：所有可交互元素都要有 addAccessibilityAction ——
        // 读屏用户单指上下滑浏览操作项。这两个按钮此前**一个动作都没有**，
        // 读屏只能念出按钮文字，用户不知道点了会发生什么。
        //
        // 对「恢复默认」尤其重要：它是**破坏性操作**，
        // 用户必须先听到「会把所有手势改回出厂设置」才敢点。
        val btnResetAll = findViewById<Button>(R.id.btnResetAll)
        btnResetAll.setOnClickListener { confirmResetAll() }
        ViewCompat.addAccessibilityAction(
            btnResetAll, getString(R.string.a11y_reset_all_gestures)
        ) { _, _ ->
            confirmResetAll()
            true
        }

        val btnShowConflicts = findViewById<Button>(R.id.btnShowConflicts)
        btnShowConflicts.setOnClickListener { showConflicts() }
        ViewCompat.addAccessibilityAction(
            btnShowConflicts, getString(R.string.a11y_show_conflicts)
        ) { _, _ ->
            showConflicts()
            true
        }

        gestureIds = GestureMapOrdering.displayOrder()
        listGestures.adapter = GestureAdapter(gestureIds)
        listGestures.setOnItemClickListener { _, _, position, _ ->
            val gestureId = gestureIds.getOrNull(position) ?: return@setOnItemClickListener
            showActionPicker(gestureId)
        }
    }

    // ==================== 选择动作 ====================

    private fun showActionPicker(gestureId: Int) {
        val current = GestureStore.resolve(gestureId)
        val actions = GestureMapOrdering.selectableActions()
        val labels = actions.map { GestureMapOrdering.actionLabel(this, it) }
        val currentIndex = actions.indexOf(current)

        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.gesture_pick_action_title,
                    GestureMapOrdering.gestureLabel(this, gestureId)
                )
            )
            .setSingleChoiceItems(
                labels.toTypedArray(), currentIndex
            ) { dialog, which ->
                val picked = actions.getOrNull(which)
                if (picked != null) {
                    GestureStore.set(this, gestureId, picked)
                    refreshList()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.gesture_cancel, null)
            .show()
    }

    // ==================== 恢复默认 ====================

    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.gesture_reset_all)
            .setMessage(R.string.gesture_reset_all_confirm)
            .setPositiveButton(R.string.gesture_confirm) { _, _ ->
                GestureStore.resetAll(this)
                refreshList()
                Toast.makeText(this, R.string.gesture_reset_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.gesture_cancel, null)
            .show()
    }

    // ==================== 冲突检测 ====================

    /**
     * 显示「同一动作被绑到多个手势」的提示。
     *
     * 【为什么要有这个】两方都没有，但用户可能把两个手势都设成滚动，
     * 结果以为「设了没生效」—— 其实是另一个手势抢了。
     */
    private fun showConflicts() {
        val dup = GestureStore.detectDuplicates()
        if (dup.isEmpty()) {
            Toast.makeText(this, R.string.gesture_no_conflicts, Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        for ((action, ids) in dup) {
            sb.append(GestureMapOrdering.actionLabel(this, action)).append("：")
            sb.append(ids.joinToString("、") { GestureMapOrdering.gestureLabel(this, it) })
            sb.append('\n')
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.gesture_conflicts_title)
            .setMessage(sb.toString().trim())
            .setPositiveButton(R.string.gesture_confirm, null)
            .show()
    }

    private fun refreshList() {
        (listGestures.adapter as? GestureAdapter)?.notifyDataSetChanged()
    }

    // ==================== 适配器 ====================

    /**
     * 每行显示「手势名 / 当前动作」。
     *
     * 【无障碍要点】整行设 `contentDescription` ——
     * 读屏聚焦到行时会一次念完整信息，而不是分三次念
     * 「单指向右滑」「下一个焦点」「第 3 项」。
     */
    private inner class GestureAdapter(
        private val ids: List<Int>
    ) : ArrayAdapter<Int>(this@GestureSettingsActivity, 0, ids) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_gesture, parent, false)

            val gestureId = ids[position]
            val name = GestureMapOrdering.gestureLabel(this@GestureSettingsActivity, gestureId)
            val action = GestureStore.resolve(gestureId)
            val actionLabel = GestureMapOrdering.actionLabel(this@GestureSettingsActivity, action)
            val isOverridden = GestureStore.isOverridden(gestureId)

            view.findViewById<TextView>(R.id.txtGestureName).text = name
            view.findViewById<TextView>(R.id.txtGestureAction).text =
                if (isOverridden) {
                    getString(R.string.gesture_action_overridden, actionLabel)
                } else {
                    actionLabel
                }

            // 一次念完：手势名 + 当前动作 +（是否自定义）
            view.contentDescription = buildString {
                append(name).append('，').append(actionLabel)
                if (isOverridden) append('，').append(getString(R.string.gesture_is_custom))
            }
            return view
        }
    }
}

/**
 * 手势显示顺序与标签 —— 从 UI 里抽出的纯逻辑
 *
 * 【为什么抽出来】顺序与标签不需要 Context 以外的东西，
 * 但混在 Activity 里就无法测试。抽出来后可验证
 * 「显示的每个手势都有映射」这一性质。
 */
object GestureMapOrdering {

    /**
     * 显示顺序 —— 按手指数递增，用户容易定位。
     *
     * 单指 → 双指 → 三指 → 四指 → 组合
     */
    fun displayOrder(): List<Int> = listOf(
        // 单指滑动
        Gestures.GESTURE_SWIPE_LEFT, Gestures.GESTURE_SWIPE_RIGHT,
        Gestures.GESTURE_SWIPE_UP, Gestures.GESTURE_SWIPE_DOWN,
        // 单指点击
        Gestures.GESTURE_DOUBLE_TAP, Gestures.GESTURE_DOUBLE_TAP_AND_HOLD,
        // 双指
        Gestures.GESTURE_2_FINGER_SWIPE_UP, Gestures.GESTURE_2_FINGER_SWIPE_DOWN,
        Gestures.GESTURE_2_FINGER_SWIPE_LEFT, Gestures.GESTURE_2_FINGER_SWIPE_RIGHT,
        Gestures.GESTURE_2_FINGER_SINGLE_TAP, Gestures.GESTURE_2_FINGER_DOUBLE_TAP,
        Gestures.GESTURE_2_FINGER_TRIPLE_TAP,
        Gestures.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD,
        Gestures.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD,
        // 三指
        Gestures.GESTURE_3_FINGER_SWIPE_UP, Gestures.GESTURE_3_FINGER_SWIPE_DOWN,
        Gestures.GESTURE_3_FINGER_SWIPE_LEFT, Gestures.GESTURE_3_FINGER_SWIPE_RIGHT,
        Gestures.GESTURE_3_FINGER_SINGLE_TAP, Gestures.GESTURE_3_FINGER_DOUBLE_TAP,
        Gestures.GESTURE_3_FINGER_TRIPLE_TAP,
        Gestures.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD,
        Gestures.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD,
        // 四指
        Gestures.GESTURE_4_FINGER_SWIPE_UP, Gestures.GESTURE_4_FINGER_SWIPE_DOWN,
        Gestures.GESTURE_4_FINGER_SWIPE_LEFT, Gestures.GESTURE_4_FINGER_SWIPE_RIGHT,
        Gestures.GESTURE_4_FINGER_SINGLE_TAP, Gestures.GESTURE_4_FINGER_DOUBLE_TAP,
        Gestures.GESTURE_4_FINGER_TRIPLE_TAP,
        Gestures.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD,
        // 组合
        Gestures.GESTURE_SWIPE_LEFT_AND_RIGHT, Gestures.GESTURE_SWIPE_RIGHT_AND_LEFT
    )

    /**
     * 可选择的动作（不含 NONE 之外的内部项）。
     *
     * 【为什么把 NONE 放在列表首位】它用来「禁用该手势」——
     * 用户可能把某个手势关掉（如与输入法冲突）。
     */
    fun selectableActions(): List<GestureAction> = listOf(
        GestureAction.NONE,
        GestureAction.FOCUS_NEXT, GestureAction.FOCUS_PREVIOUS,
        GestureAction.SCROLL_FORWARD, GestureAction.SCROLL_BACKWARD,
        GestureAction.CLICK, GestureAction.LONG_CLICK,
        GestureAction.BACK, GestureAction.HOME, GestureAction.RECENTS,
        GestureAction.NOTIFICATIONS, GestureAction.GLOBAL_MENU,
        GestureAction.GRANULARITY_DEFAULT, GestureAction.GRANULARITY_CHARACTER,
        GestureAction.GRANULARITY_WORD, GestureAction.GRANULARITY_LINE,
        GestureAction.CONTINUOUS_READ,
        GestureAction.COPY, GestureAction.PASTE, GestureAction.CUT,
        GestureAction.SELECT_ALL,
        GestureAction.MEDIA_PREVIOUS, GestureAction.MEDIA_NEXT,
        GestureAction.MEDIA_PLAY_PAUSE,
        GestureAction.VOLUME_UP, GestureAction.VOLUME_DOWN,
        GestureAction.SCREENSHOT,
        GestureAction.TOGGLE_SPEAK, GestureAction.STOP_SPEAK
    )

    /** 手势的中文名 */
    fun gestureLabel(context: android.content.Context, gestureId: Int): String =
        context.getString(gestureNameRes(gestureId))

    private fun gestureNameRes(gestureId: Int): Int = when (gestureId) {
        Gestures.GESTURE_SWIPE_LEFT -> R.string.gesture_swipe_left
        Gestures.GESTURE_SWIPE_RIGHT -> R.string.gesture_swipe_right
        Gestures.GESTURE_SWIPE_UP -> R.string.gesture_swipe_up
        Gestures.GESTURE_SWIPE_DOWN -> R.string.gesture_swipe_down
        Gestures.GESTURE_DOUBLE_TAP -> R.string.gesture_double_tap
        Gestures.GESTURE_DOUBLE_TAP_AND_HOLD -> R.string.gesture_double_tap_hold
        Gestures.GESTURE_2_FINGER_SWIPE_UP -> R.string.gesture_2_swipe_up
        Gestures.GESTURE_2_FINGER_SWIPE_DOWN -> R.string.gesture_2_swipe_down
        Gestures.GESTURE_2_FINGER_SWIPE_LEFT -> R.string.gesture_2_swipe_left
        Gestures.GESTURE_2_FINGER_SWIPE_RIGHT -> R.string.gesture_2_swipe_right
        Gestures.GESTURE_2_FINGER_SINGLE_TAP -> R.string.gesture_2_single_tap
        Gestures.GESTURE_2_FINGER_DOUBLE_TAP -> R.string.gesture_2_double_tap
        Gestures.GESTURE_2_FINGER_TRIPLE_TAP -> R.string.gesture_2_triple_tap
        Gestures.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD -> R.string.gesture_2_double_tap_hold
        Gestures.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD -> R.string.gesture_2_triple_tap_hold
        Gestures.GESTURE_3_FINGER_SWIPE_UP -> R.string.gesture_3_swipe_up
        Gestures.GESTURE_3_FINGER_SWIPE_DOWN -> R.string.gesture_3_swipe_down
        Gestures.GESTURE_3_FINGER_SWIPE_LEFT -> R.string.gesture_3_swipe_left
        Gestures.GESTURE_3_FINGER_SWIPE_RIGHT -> R.string.gesture_3_swipe_right
        Gestures.GESTURE_3_FINGER_SINGLE_TAP -> R.string.gesture_3_single_tap
        Gestures.GESTURE_3_FINGER_DOUBLE_TAP -> R.string.gesture_3_double_tap
        Gestures.GESTURE_3_FINGER_TRIPLE_TAP -> R.string.gesture_3_triple_tap
        Gestures.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD -> R.string.gesture_3_single_tap_hold
        Gestures.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD -> R.string.gesture_3_double_tap_hold
        Gestures.GESTURE_4_FINGER_SWIPE_UP -> R.string.gesture_4_swipe_up
        Gestures.GESTURE_4_FINGER_SWIPE_DOWN -> R.string.gesture_4_swipe_down
        Gestures.GESTURE_4_FINGER_SWIPE_LEFT -> R.string.gesture_4_swipe_left
        Gestures.GESTURE_4_FINGER_SWIPE_RIGHT -> R.string.gesture_4_swipe_right
        Gestures.GESTURE_4_FINGER_SINGLE_TAP -> R.string.gesture_4_single_tap
        Gestures.GESTURE_4_FINGER_DOUBLE_TAP -> R.string.gesture_4_double_tap
        Gestures.GESTURE_4_FINGER_TRIPLE_TAP -> R.string.gesture_4_triple_tap
        Gestures.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD -> R.string.gesture_4_double_tap_hold
        Gestures.GESTURE_SWIPE_LEFT_AND_RIGHT -> R.string.gesture_swipe_lr
        Gestures.GESTURE_SWIPE_RIGHT_AND_LEFT -> R.string.gesture_swipe_rl
        else -> R.string.gesture_unknown
    }

    /** 动作的中文名（复用 GestureAction 的名字，这里给中文） */
    fun actionLabel(context: android.content.Context, action: GestureAction): String =
        context.getString(actionNameRes(action))

    private fun actionNameRes(action: GestureAction): Int = when (action) {
        GestureAction.NONE -> R.string.action_none
        GestureAction.FOCUS_NEXT -> R.string.action_focus_next
        GestureAction.FOCUS_PREVIOUS -> R.string.action_focus_previous
        GestureAction.SCROLL_FORWARD -> R.string.action_scroll_forward
        GestureAction.SCROLL_BACKWARD -> R.string.action_scroll_backward
        GestureAction.CLICK -> R.string.action_click
        GestureAction.LONG_CLICK -> R.string.action_long_click
        GestureAction.BACK -> R.string.action_back
        GestureAction.HOME -> R.string.action_home
        GestureAction.RECENTS -> R.string.action_recents
        GestureAction.NOTIFICATIONS -> R.string.action_notifications
        GestureAction.GLOBAL_MENU -> R.string.action_global_menu
        GestureAction.GRANULARITY_DEFAULT -> R.string.action_granularity_default
        GestureAction.GRANULARITY_CHARACTER -> R.string.action_granularity_character
        GestureAction.GRANULARITY_WORD -> R.string.action_granularity_word
        GestureAction.GRANULARITY_LINE -> R.string.action_granularity_line
        GestureAction.CONTINUOUS_READ -> R.string.action_continuous_read
        GestureAction.COPY -> R.string.action_copy
        GestureAction.PASTE -> R.string.action_paste
        GestureAction.CUT -> R.string.action_cut
        GestureAction.SELECT_ALL -> R.string.action_select_all
        GestureAction.MEDIA_PREVIOUS -> R.string.action_media_previous
        GestureAction.MEDIA_NEXT -> R.string.action_media_next
        GestureAction.MEDIA_PLAY_PAUSE -> R.string.action_media_play_pause
        GestureAction.VOLUME_UP -> R.string.action_volume_up
        GestureAction.VOLUME_DOWN -> R.string.action_volume_down
        GestureAction.SCREENSHOT -> R.string.action_screenshot
        GestureAction.TOGGLE_SPEAK -> R.string.action_toggle_speak
        GestureAction.STOP_SPEAK -> R.string.action_stop_speak
        else -> R.string.action_unknown
    }
}
