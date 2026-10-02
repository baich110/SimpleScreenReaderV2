package com.baicha.reader.core

import android.content.Context

/**
 * 手势配置存储 —— 对标 TalkBack `GestureShortcutMapping`(1264 行)
 * 与天坦 `GestureListPreference` + `GlobalConfig`
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * 白茶的手势映射是 `GestureMap.actionOf()` 里**写死的 when 分支**
 * （46 条）。后果：
 *   · 用户习惯了 TalkBack 的手势 → 改不了，只能重新适应
 *   · 某个手势与输入法/系统冲突 → 换不了
 *   · 换手机后配置带不过去
 *
 * ══════════════════════════════════════════════════════════
 * 【两方对标（已读源码）】
 * ══════════════════════════════════════════════════════════
 *
 * **TalkBack** `GestureShortcutMapping.java`：
 *   · `:87`  `NUMBER_OF_GESTURE_SET = 2` —— **两套手势集**，可切换
 *   · `:748~760` 查表：`gestureIdToActionKey.get(setIndex).get(gestureId)`，
 *               查不到返回 `actionUnassigned`
 *   · `:669~691` `switchGestureSet()` —— 手势集持久化到 SharedPreferences
 *   · `:795~815` 反向查表 `getGestureFromActionKey()` —— 给界面显示用
 *
 * **天坦** `GestureListPreference`：
 *   · `:206` 读：`prefs.getString(key, defaultValue)`
 *   · `:232~234` 写：`prefs.edit().putString(key, value).apply()`
 *   · **每个手势一个 key**，值为动作标识字符串
 *   · 无「手势集」概念
 *
 * ══════════════════════════════════════════════════════════
 * 【A/B 取舍：选天坦】
 * ══════════════════════════════════════════════════════════
 *
 * 差异在「手势集」：
 *   TalkBack 两套手势集，用户可以整体切换（应对不同 App 习惯）
 *   天坦无此概念，只有「一个手势配一个动作」
 *
 * 【为什么选天坦】
 *   ① 手势集对**视障用户**是额外负担 —— 用户要记住「当前在哪一套」，
 *      切换后所有手势语义全变，容易造成误操作。天坦的「一对一」
 *      心智模型更简单。
 *   ② 实现复杂度差一个数量级（1264 行 vs 存储层几十行），
 *      而收益不明确。
 *   ③ 用户的目标明确规定「A/B 取舍选天坦」。
 *
 * ══════════════════════════════════════════════════════════
 * 【相对两方的改进：三级解析 + 冲突检测 + 可测】
 * ══════════════════════════════════════════════════════════
 *
 * ① **三级解析**（用户覆盖 → 默认 → NONE），比天坦的
 *    `getString(key, default)` 多一层显式默认表 ——
 *    这样「恢复默认」只需清覆盖，不必重写全部值。
 * ② **冲突检测** —— 两方都没有。同一动作被绑到多个手势时
 *    在配置页给出提示（不是禁止，用户可能需要）。
 * ③ **纯逻辑可测** —— 解析/冲突检测与 SharedPreferences 分离。
 */
object GestureStore {

    private const val PREFS_NAME = "baicha_gestures"
    private const val KEY_PREFIX = "gesture_action_"

    /**
     * 默认映射 —— 与 `GestureMap.actionOf()` 的硬编码一致。
     *
     * 【为什么把默认表放在这里而不是继续用 when】
     *   解析要能「逐条覆盖」，必须有可枚举的默认值来源。
     *   `when` 无法枚举。
     */
    private val defaults: Map<Int, GestureAction> by lazy { buildDefaults() }

    /** 用户覆盖缓存 —— 避免每次手势都读 SharedPreferences */
    @Volatile
    private var overrides: Map<Int, GestureAction> = emptyMap()

    // ==================== 解析 ====================

    /**
     * 解析手势对应的动作 —— **三级降级**。
     *
     * @return 用户覆盖 → 默认 → [GestureAction.NONE]
     */
    fun resolve(gestureId: Int): GestureAction =
        overrides[gestureId] ?: defaults[gestureId] ?: GestureAction.NONE

    /**
     * 解析手势对应的动作；**无任何映射时返回 null**。
     *
     * 【与 resolve 的区别】
     *   resolve 总会返回一个动作（兜底 NONE），
     *   而本方法在「既无覆盖也无默认」时返回 null ——
     *   让调用方能区分「明确映射到 NONE」与「本表里没有这项」。
     *
     * 这是为了让 `GestureMap.actionOf()` 能在 store 未覆盖某项时
     * 安全退回它自己的硬编码默认。
     */
    fun resolveOrNull(gestureId: Int): GestureAction? =
        overrides[gestureId] ?: defaults[gestureId]

    /** 默认动作（用于「恢复默认」与界面显示） */
    fun defaultOf(gestureId: Int): GestureAction =
        defaults[gestureId] ?: GestureAction.NONE

    /** 该手势是否被用户改过 */
    fun isOverridden(gestureId: Int): Boolean = overrides.containsKey(gestureId)

    /** 全部默认映射（供配置页列出） */
    fun allDefaults(): Map<Int, GestureAction> = defaults

    /** 当前全部生效映射 */
    fun allEffective(): Map<Int, GestureAction> =
        defaults.keys.associateWith { resolve(it) }

    // ==================== 修改 ====================

    /**
     * 设置手势动作。
     *
     * @return true = 值确实变了
     */
    fun set(context: Context, gestureId: Int, action: GestureAction): Boolean {
        if (resolve(gestureId) == action && isOverridden(gestureId)) return false

        overrides = overrides.toMutableMap().apply { put(gestureId, action) }
        save(context)
        return true
    }

    /** 恢复单个手势的默认 */
    fun reset(context: Context, gestureId: Int): Boolean {
        if (!isOverridden(gestureId)) return false
        overrides = overrides.toMutableMap().apply { remove(gestureId) }
        save(context)
        return true
    }

    /** 恢复全部默认 */
    fun resetAll(context: Context) {
        overrides = emptyMap()
        try {
            prefs(context).edit().clear().apply()
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "手势配置重置失败：${e.message}")
        }
    }

    // ==================== 持久化 ====================

    /**
     * 从存储加载。
     *
     * 【容错】存的动作名若无法解析（版本降级、枚举改名），
     * **跳过该项**而不是崩溃或回退到错误动作。
     * 读屏常驻，任何路径都不允许抛异常。
     */
    fun load(context: Context) {
        val loaded = HashMap<Int, GestureAction>()
        try {
            val all = prefs(context).all
            for ((key, value) in all) {
                if (!key.startsWith(KEY_PREFIX)) continue
                val gestureId = key.removePrefix(KEY_PREFIX).toIntOrNull() ?: continue
                val actionName = value as? String ?: continue
                val action = GestureAction.fromName(actionName) ?: continue
                loaded[gestureId] = action
            }
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "手势配置加载失败：${e.message}")
        }
        overrides = loaded
        ReaderLog.i(TAG, "手势配置：已加载 ${loaded.size} 项覆盖")
    }

    private fun save(context: Context) {
        try {
            val editor = prefs(context).edit()
            editor.clear()
            for ((id, action) in overrides) {
                editor.putString(KEY_PREFIX + id, action.name)
            }
            editor.apply()
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "手势配置保存失败：${e.message}")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ==================== 冲突检测 ====================

    /**
     * 找出绑定到同一动作的多个手势。
     *
     * 【为什么两方都没有而白茶加】
     *   用户可能把「双指上滑」和「三指上滑」都设成滚动 ——
     *   这本身没错，但配置页应当**提示**，
     *   避免用户以为「设了但没生效」（其实是被另一个手势抢了）。
     *
     * @return 动作 → 手势列表（只含手指数 > 1 的项）
     */
    fun detectDuplicates(): Map<GestureAction, List<Int>> =
        allEffective()
            .entries
            .filter { it.value != GestureAction.NONE }
            .groupBy({ it.value }, { it.key })
            .filter { it.value.size > 1 }

    /** 供测试与诊断：当前覆盖条数 */
    fun overrideCount(): Int = overrides.size

    // ==================== 测试钩子 ====================
    //
    // 【为什么需要】SharedPreferences 需要真实 Context，
    // JVM 单测拿不到。这三个方法让「解析/覆盖/冲突」逻辑
    // 能在不触盘的情况下被完整测试。
    //
    // 【命名带 ForTest】避免生产代码误用 —— 它们跳过持久化，
    // 在生产路径调用会导致重启后配置丢失。

    /** 仅测试用：直接设置覆盖（不落盘） */
    internal fun setOverrideForTest(gestureId: Int, action: GestureAction) {
        overrides = overrides.toMutableMap().apply { put(gestureId, action) }
    }

    /** 仅测试用：清除单个覆盖（不落盘） */
    internal fun resetForTest(gestureId: Int) {
        overrides = overrides.toMutableMap().apply { remove(gestureId) }
    }

    /** 仅测试用：清空内存态 */
    internal fun resetAllForTest() {
        overrides = emptyMap()
    }

    private const val TAG = "GestureStore"

    /**
     * 默认映射表 —— 与 `GestureMap.actionOf()` 逐条对齐。
     *
     * 【注意】这里的映射必须与 `GestureMap.actionOf()` 保持一致，
     * 否则会出现「Store 说 A、Map 说 B」的双真相。
     * 测试里有专门一条断言两者一致（见 GestureStoreTest）。
     */
    private fun buildDefaults(): Map<Int, GestureAction> = mapOf(
        // 单指滑动 —— 焦点导航
        Gestures.GESTURE_SWIPE_LEFT to GestureAction.FOCUS_PREVIOUS,
        Gestures.GESTURE_SWIPE_RIGHT to GestureAction.FOCUS_NEXT,
        Gestures.GESTURE_SWIPE_UP to GestureAction.FOCUS_PREVIOUS,
        Gestures.GESTURE_SWIPE_DOWN to GestureAction.FOCUS_NEXT,

        // 双指滑动 —— 滚动
        Gestures.GESTURE_2_FINGER_SWIPE_UP to GestureAction.SCROLL_BACKWARD,
        Gestures.GESTURE_2_FINGER_SWIPE_DOWN to GestureAction.SCROLL_FORWARD,
        Gestures.GESTURE_2_FINGER_SWIPE_LEFT to GestureAction.SCROLL_FORWARD,
        Gestures.GESTURE_2_FINGER_SWIPE_RIGHT to GestureAction.SCROLL_BACKWARD,

        // 三指滑动 —— 系统
        Gestures.GESTURE_3_FINGER_SWIPE_UP to GestureAction.HOME,
        Gestures.GESTURE_3_FINGER_SWIPE_DOWN to GestureAction.NOTIFICATIONS,
        Gestures.GESTURE_3_FINGER_SWIPE_LEFT to GestureAction.BACK,
        Gestures.GESTURE_3_FINGER_SWIPE_RIGHT to GestureAction.RECENTS,

        // 四指滑动
        Gestures.GESTURE_4_FINGER_SWIPE_UP to GestureAction.VOLUME_UP,
        Gestures.GESTURE_4_FINGER_SWIPE_DOWN to GestureAction.VOLUME_DOWN,
        Gestures.GESTURE_4_FINGER_SWIPE_LEFT to GestureAction.MEDIA_PREVIOUS,
        Gestures.GESTURE_4_FINGER_SWIPE_RIGHT to GestureAction.MEDIA_NEXT,

        // 点击
        Gestures.GESTURE_DOUBLE_TAP to GestureAction.CLICK,
        Gestures.GESTURE_DOUBLE_TAP_AND_HOLD to GestureAction.LONG_CLICK,
        Gestures.GESTURE_2_FINGER_SINGLE_TAP to GestureAction.STOP_SPEAK,
        Gestures.GESTURE_2_FINGER_DOUBLE_TAP to GestureAction.MEDIA_PLAY_PAUSE,
        Gestures.GESTURE_2_FINGER_TRIPLE_TAP to GestureAction.SCREENSHOT,
        Gestures.GESTURE_3_FINGER_SINGLE_TAP to GestureAction.GLOBAL_MENU,
        Gestures.GESTURE_3_FINGER_DOUBLE_TAP to GestureAction.GRANULARITY_DEFAULT,
        // A4 变更：原 SCREEN_RECOGNIZE（未实现），改绑连续朗读
        Gestures.GESTURE_3_FINGER_TRIPLE_TAP to GestureAction.CONTINUOUS_READ,
        Gestures.GESTURE_4_FINGER_SINGLE_TAP to GestureAction.TOGGLE_SPEAK,
        Gestures.GESTURE_4_FINGER_DOUBLE_TAP to GestureAction.GRANULARITY_CHARACTER,
        Gestures.GESTURE_4_FINGER_TRIPLE_TAP to GestureAction.GRANULARITY_WORD,

        // 多指长按
        Gestures.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD to GestureAction.COPY,
        Gestures.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD to GestureAction.PASTE,
        Gestures.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD to GestureAction.CUT,
        Gestures.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD to GestureAction.SELECT_ALL,
        Gestures.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD to GestureAction.GRANULARITY_LINE,

        // 双指反向滑动
        Gestures.GESTURE_SWIPE_LEFT_AND_RIGHT to GestureAction.BACK,
        Gestures.GESTURE_SWIPE_RIGHT_AND_LEFT to GestureAction.RECENTS,

        // 手势 7/8（先上再下 / 先下再上）**不在本表内** ——
        // 它们在服务里被单独处理为「切换导航」（见 Gestures.kt:313~314
        // 返回 NONE，实际由 ReaderAccessibilityService 的
        // GESTURE_SWIPE_UP_AND_DOWN / GESTURE_SWIPE_DOWN_AND_UP 分支处理）。
        // 若在这里也映射，会出现「两处都管」的双真相。
        Gestures.GESTURE_SWIPE_UP_AND_DOWN to GestureAction.NONE,
        Gestures.GESTURE_SWIPE_DOWN_AND_UP to GestureAction.NONE
    )
}
