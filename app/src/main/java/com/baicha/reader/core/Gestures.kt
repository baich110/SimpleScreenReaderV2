package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 手势常量 + 名称 —— 1:1 抄天坦 w7/AbstractC3684g.java
 *
 * 完整 46 个手势，不做减法。
 */
object Gestures {

    const val GESTURE_TOUCH_EXPLORATION = -2
    const val GESTURE_PASSTHROUGH = -1
    const val GESTURE_UNKNOWN = 0

    // 单指滑动
    const val GESTURE_SWIPE_UP = 1
    const val GESTURE_SWIPE_DOWN = 2
    const val GESTURE_SWIPE_LEFT = 3
    const val GESTURE_SWIPE_RIGHT = 4

    // 双指反向滑动
    const val GESTURE_SWIPE_LEFT_AND_RIGHT = 5
    const val GESTURE_SWIPE_RIGHT_AND_LEFT = 6
    const val GESTURE_SWIPE_UP_AND_DOWN = 7
    const val GESTURE_SWIPE_DOWN_AND_UP = 8

    // 直角滑动
    const val GESTURE_SWIPE_LEFT_AND_UP = 9
    const val GESTURE_SWIPE_LEFT_AND_DOWN = 10
    const val GESTURE_SWIPE_RIGHT_AND_UP = 11
    const val GESTURE_SWIPE_RIGHT_AND_DOWN = 12
    const val GESTURE_SWIPE_UP_AND_LEFT = 13
    const val GESTURE_SWIPE_UP_AND_RIGHT = 14
    const val GESTURE_SWIPE_DOWN_AND_LEFT = 15
    const val GESTURE_SWIPE_DOWN_AND_RIGHT = 16

    // 点击
    const val GESTURE_DOUBLE_TAP = 17
    const val GESTURE_DOUBLE_TAP_AND_HOLD = 18
    const val GESTURE_2_FINGER_SINGLE_TAP = 19
    const val GESTURE_2_FINGER_DOUBLE_TAP = 20
    const val GESTURE_2_FINGER_TRIPLE_TAP = 21
    const val GESTURE_3_FINGER_SINGLE_TAP = 22
    const val GESTURE_3_FINGER_DOUBLE_TAP = 23
    const val GESTURE_3_FINGER_TRIPLE_TAP = 24

    // 多指滑动
    const val GESTURE_2_FINGER_SWIPE_UP = 25
    const val GESTURE_2_FINGER_SWIPE_DOWN = 26
    const val GESTURE_2_FINGER_SWIPE_LEFT = 27
    const val GESTURE_2_FINGER_SWIPE_RIGHT = 28
    const val GESTURE_3_FINGER_SWIPE_UP = 29
    const val GESTURE_3_FINGER_SWIPE_DOWN = 30
    const val GESTURE_3_FINGER_SWIPE_LEFT = 31
    const val GESTURE_3_FINGER_SWIPE_RIGHT = 32
    const val GESTURE_4_FINGER_SWIPE_UP = 33
    const val GESTURE_4_FINGER_SWIPE_DOWN = 34
    const val GESTURE_4_FINGER_SWIPE_LEFT = 35
    const val GESTURE_4_FINGER_SWIPE_RIGHT = 36

    // 四指点击
    const val GESTURE_4_FINGER_SINGLE_TAP = 37
    const val GESTURE_4_FINGER_DOUBLE_TAP = 38
    const val GESTURE_4_FINGER_TRIPLE_TAP = 39

    // 多指长按
    const val GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD = 40
    const val GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD = 41
    const val GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD = 42
    const val GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD = 43
    const val GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD = 44
    const val GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD = 45

    /**
     * 手势名 —— 1:1 抄天坦 AbstractC3684g.a()
     */
    fun name(gestureId: Int): String = when (gestureId) {
        GESTURE_TOUCH_EXPLORATION -> "GESTURE_TOUCH_EXPLORATION"
        GESTURE_PASSTHROUGH -> "GESTURE_PASSTHROUGH"
        GESTURE_UNKNOWN -> "GESTURE_UNKNOWN"
        GESTURE_SWIPE_UP -> "GESTURE_SWIPE_UP"
        GESTURE_SWIPE_DOWN -> "GESTURE_SWIPE_DOWN"
        GESTURE_SWIPE_LEFT -> "GESTURE_SWIPE_LEFT"
        GESTURE_SWIPE_RIGHT -> "GESTURE_SWIPE_RIGHT"
        GESTURE_SWIPE_LEFT_AND_RIGHT -> "GESTURE_SWIPE_LEFT_AND_RIGHT"
        GESTURE_SWIPE_RIGHT_AND_LEFT -> "GESTURE_SWIPE_RIGHT_AND_LEFT"
        GESTURE_SWIPE_UP_AND_DOWN -> "GESTURE_SWIPE_UP_AND_DOWN"
        GESTURE_SWIPE_DOWN_AND_UP -> "GESTURE_SWIPE_DOWN_AND_UP"
        GESTURE_SWIPE_LEFT_AND_UP -> "GESTURE_SWIPE_LEFT_AND_UP"
        GESTURE_SWIPE_LEFT_AND_DOWN -> "GESTURE_SWIPE_LEFT_AND_DOWN"
        GESTURE_SWIPE_RIGHT_AND_UP -> "GESTURE_SWIPE_RIGHT_AND_UP"
        GESTURE_SWIPE_RIGHT_AND_DOWN -> "GESTURE_SWIPE_RIGHT_AND_DOWN"
        GESTURE_SWIPE_UP_AND_LEFT -> "GESTURE_SWIPE_UP_AND_LEFT"
        GESTURE_SWIPE_UP_AND_RIGHT -> "GESTURE_SWIPE_UP_AND_RIGHT"
        GESTURE_SWIPE_DOWN_AND_LEFT -> "GESTURE_SWIPE_DOWN_AND_LEFT"
        GESTURE_SWIPE_DOWN_AND_RIGHT -> "GESTURE_SWIPE_DOWN_AND_RIGHT"
        GESTURE_DOUBLE_TAP -> "GESTURE_DOUBLE_TAP"
        GESTURE_DOUBLE_TAP_AND_HOLD -> "GESTURE_DOUBLE_TAP_AND_HOLD"
        GESTURE_2_FINGER_SINGLE_TAP -> "GESTURE_2_FINGER_SINGLE_TAP"
        GESTURE_2_FINGER_DOUBLE_TAP -> "GESTURE_2_FINGER_DOUBLE_TAP"
        GESTURE_2_FINGER_TRIPLE_TAP -> "GESTURE_2_FINGER_TRIPLE_TAP"
        GESTURE_3_FINGER_SINGLE_TAP -> "GESTURE_3_FINGER_SINGLE_TAP"
        GESTURE_3_FINGER_DOUBLE_TAP -> "GESTURE_3_FINGER_DOUBLE_TAP"
        GESTURE_3_FINGER_TRIPLE_TAP -> "GESTURE_3_FINGER_TRIPLE_TAP"
        GESTURE_2_FINGER_SWIPE_UP -> "GESTURE_2_FINGER_SWIPE_UP"
        GESTURE_2_FINGER_SWIPE_DOWN -> "GESTURE_2_FINGER_SWIPE_DOWN"
        GESTURE_2_FINGER_SWIPE_LEFT -> "GESTURE_2_FINGER_SWIPE_LEFT"
        GESTURE_2_FINGER_SWIPE_RIGHT -> "GESTURE_2_FINGER_SWIPE_RIGHT"
        GESTURE_3_FINGER_SWIPE_UP -> "GESTURE_3_FINGER_SWIPE_UP"
        GESTURE_3_FINGER_SWIPE_DOWN -> "GESTURE_3_FINGER_SWIPE_DOWN"
        GESTURE_3_FINGER_SWIPE_LEFT -> "GESTURE_3_FINGER_SWIPE_LEFT"
        GESTURE_3_FINGER_SWIPE_RIGHT -> "GESTURE_3_FINGER_SWIPE_RIGHT"
        GESTURE_4_FINGER_SWIPE_UP -> "GESTURE_4_FINGER_SWIPE_UP"
        GESTURE_4_FINGER_SWIPE_DOWN -> "GESTURE_4_FINGER_SWIPE_DOWN"
        GESTURE_4_FINGER_SWIPE_LEFT -> "GESTURE_4_FINGER_SWIPE_LEFT"
        GESTURE_4_FINGER_SWIPE_RIGHT -> "GESTURE_4_FINGER_SWIPE_RIGHT"
        GESTURE_4_FINGER_SINGLE_TAP -> "GESTURE_4_FINGER_SINGLE_TAP"
        GESTURE_4_FINGER_DOUBLE_TAP -> "GESTURE_4_FINGER_DOUBLE_TAP"
        GESTURE_4_FINGER_TRIPLE_TAP -> "GESTURE_4_FINGER_TRIPLE_TAP"
        GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD -> "GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD"
        GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD -> "GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD"
        GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD -> "GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD"
        GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD -> "GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD"
        GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD -> "GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD"
        GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD -> "GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD"
        else -> Integer.toHexString(gestureId)
    }
}

/**
 * 手势动作类型
 */
enum class GestureAction {
    /** 无动作 */
    NONE,

    /** 焦点上一个 */
    FOCUS_PREVIOUS,

    /** 焦点下一个 */
    FOCUS_NEXT,

    /** 滚动上一屏 */
    SCROLL_BACKWARD,

    /** 滚动下一屏 */
    SCROLL_FORWARD,

    /** 点击 */
    CLICK,

    /** 长按 */
    LONG_CLICK,

    /** 返回 */
    BACK,

    /** 主屏 */
    HOME,

    /** 最近任务 */
    RECENTS,

    /** 通知栏 */
    NOTIFICATIONS,

    /** 全局菜单 */
    GLOBAL_MENU,

    /** 粒度：按字 */
    GRANULARITY_CHARACTER,

    /** 粒度：按词 */
    GRANULARITY_WORD,

    /** 粒度：按行 */
    GRANULARITY_LINE,

    /** 粒度：默认 */
    GRANULARITY_DEFAULT,

    /** 复制 */
    COPY,

    /** 粘贴 */
    PASTE,

    /** 剪切 */
    CUT,

    /** 全选 */
    SELECT_ALL,

    /** 上一首 */
    MEDIA_PREVIOUS,

    /** 下一首 */
    MEDIA_NEXT,

    /** 播放/暂停 */
    MEDIA_PLAY_PAUSE,

    /** 音量加 */
    VOLUME_UP,

    /** 音量减 */
    VOLUME_DOWN,

    /** 屏幕截图 */
    SCREENSHOT,

    /** 屏幕识别 */
    SCREEN_RECOGNIZE,

    /** 读屏开关 */
    TOGGLE_SPEAK,

    /** 停止播报 */
    STOP_SPEAK
}

/**
 * 手势映射表 —— 抄天坦的手势分发（SoundBackService.X1）
 *
 * 42 手势完整映射。
 */
object GestureMap {

    /**
     * 手势 → 动作
     *
     * 天坦默认映射（参考天坦手势设置）：
     *   单指左滑 → 上一个焦点
     *   单指右滑 → 下一个焦点
     *   单指上滑 → 上一个（纵向）
     *   单指下滑 → 下一个（纵向）
     *   双指上滑 → 滚动上一屏
     *   双指下滑 → 滚动下一屏
     *   双指左滑 → 上一页
     *   双指右滑 → 下一页
     *   双击 → 点击
     *   双击长按 → 长按
     *   三指上滑 → 主屏
     *   三指下滑 → 通知栏
     *   三指左滑 → 返回
     *   三指右滑 → 最近任务
     *   三指双击 → 全局菜单
     *   四指双击 → 粒度切换
     */
    fun actionOf(gestureId: Int): GestureAction = when (gestureId) {
        // 单指滑动 —— 焦点导航
        Gestures.GESTURE_SWIPE_LEFT -> GestureAction.FOCUS_PREVIOUS
        Gestures.GESTURE_SWIPE_RIGHT -> GestureAction.FOCUS_NEXT
        Gestures.GESTURE_SWIPE_UP -> GestureAction.FOCUS_PREVIOUS
        Gestures.GESTURE_SWIPE_DOWN -> GestureAction.FOCUS_NEXT

        // 双指滑动 —— 滚动
        Gestures.GESTURE_2_FINGER_SWIPE_UP -> GestureAction.SCROLL_BACKWARD
        Gestures.GESTURE_2_FINGER_SWIPE_DOWN -> GestureAction.SCROLL_FORWARD
        Gestures.GESTURE_2_FINGER_SWIPE_LEFT -> GestureAction.SCROLL_FORWARD
        Gestures.GESTURE_2_FINGER_SWIPE_RIGHT -> GestureAction.SCROLL_BACKWARD

        // 三指滑动 —— 系统
        Gestures.GESTURE_3_FINGER_SWIPE_UP -> GestureAction.HOME
        Gestures.GESTURE_3_FINGER_SWIPE_DOWN -> GestureAction.NOTIFICATIONS
        Gestures.GESTURE_3_FINGER_SWIPE_LEFT -> GestureAction.BACK
        Gestures.GESTURE_3_FINGER_SWIPE_RIGHT -> GestureAction.RECENTS

        // 四指滑动
        Gestures.GESTURE_4_FINGER_SWIPE_UP -> GestureAction.VOLUME_UP
        Gestures.GESTURE_4_FINGER_SWIPE_DOWN -> GestureAction.VOLUME_DOWN
        Gestures.GESTURE_4_FINGER_SWIPE_LEFT -> GestureAction.MEDIA_PREVIOUS
        Gestures.GESTURE_4_FINGER_SWIPE_RIGHT -> GestureAction.MEDIA_NEXT

        // 点击
        Gestures.GESTURE_DOUBLE_TAP -> GestureAction.CLICK
        Gestures.GESTURE_DOUBLE_TAP_AND_HOLD -> GestureAction.LONG_CLICK
        Gestures.GESTURE_2_FINGER_SINGLE_TAP -> GestureAction.STOP_SPEAK
        Gestures.GESTURE_2_FINGER_DOUBLE_TAP -> GestureAction.MEDIA_PLAY_PAUSE
        Gestures.GESTURE_2_FINGER_TRIPLE_TAP -> GestureAction.SCREENSHOT
        Gestures.GESTURE_3_FINGER_SINGLE_TAP -> GestureAction.GLOBAL_MENU
        Gestures.GESTURE_3_FINGER_DOUBLE_TAP -> GestureAction.GRANULARITY_DEFAULT
        Gestures.GESTURE_3_FINGER_TRIPLE_TAP -> GestureAction.SCREEN_RECOGNIZE
        Gestures.GESTURE_4_FINGER_SINGLE_TAP -> GestureAction.TOGGLE_SPEAK
        Gestures.GESTURE_4_FINGER_DOUBLE_TAP -> GestureAction.GRANULARITY_CHARACTER
        Gestures.GESTURE_4_FINGER_TRIPLE_TAP -> GestureAction.GRANULARITY_WORD

        // 多指长按
        Gestures.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD -> GestureAction.COPY
        Gestures.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD -> GestureAction.PASTE
        Gestures.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD -> GestureAction.CUT
        Gestures.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD -> GestureAction.SELECT_ALL
        Gestures.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD -> GestureAction.GRANULARITY_LINE
        Gestures.GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD -> GestureAction.SCREEN_RECOGNIZE

        // 双指反向滑动
        Gestures.GESTURE_SWIPE_LEFT_AND_RIGHT -> GestureAction.BACK
        Gestures.GESTURE_SWIPE_RIGHT_AND_LEFT -> GestureAction.RECENTS
        Gestures.GESTURE_SWIPE_UP_AND_DOWN -> GestureAction.HOME
        Gestures.GESTURE_SWIPE_DOWN_AND_UP -> GestureAction.NOTIFICATIONS

        // 直角滑动
        Gestures.GESTURE_SWIPE_LEFT_AND_UP -> GestureAction.BACK
        Gestures.GESTURE_SWIPE_LEFT_AND_DOWN -> GestureAction.BACK
        Gestures.GESTURE_SWIPE_RIGHT_AND_UP -> GestureAction.RECENTS
        Gestures.GESTURE_SWIPE_RIGHT_AND_DOWN -> GestureAction.RECENTS
        Gestures.GESTURE_SWIPE_UP_AND_LEFT -> GestureAction.HOME
        Gestures.GESTURE_SWIPE_UP_AND_RIGHT -> GestureAction.HOME
        Gestures.GESTURE_SWIPE_DOWN_AND_LEFT -> GestureAction.NOTIFICATIONS
        Gestures.GESTURE_SWIPE_DOWN_AND_RIGHT -> GestureAction.NOTIFICATIONS

        else -> GestureAction.NONE
    }
}