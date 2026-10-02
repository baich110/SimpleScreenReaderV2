package com.baicha.reader.core

/**
 * 手势 Actor 接口 —— 对应天坦的「责任链」参与者
 *
 * 【天坦的手势分发（SoundBackService.X1）不是查表，而是责任链】
 *
 *   X1(gestureId, displayId):
 *       ① 内测认证检查（硬编码）
 *       ② gestureId == 17 (双击)      → NodeActionPerformer.S()      ★ 硬编码优先
 *       ③ gestureId == 18 (双击长按)  → NodeActionPerformer.b0()     ★ 硬编码优先
 *       ④ mediaControlActor.j(id)     媒体控制
 *       ⑤ fullScreenCopyActor         全屏复制
 *       ⑥ phoneCallActor.E(id)        电话
 *       ⑦ magnificationActor.j(id)    放大镜
 *       ⑧ 默认导航 l1().d(id)          ← 兜底
 *       ⑨ videoBrowseActor            视频浏览（单指上下滑被接管）
 *
 * 【为什么要责任链而不是查表】
 *
 * 查表：手势 → 固定动作。无法表达「当前在看视频，单指上下滑改成调音量」。
 * 责任链：每个 Actor 自己判断「当前场景我该不该处理」，
 *         抢先处理并返回 true，后面的不再执行。
 *
 * 【本实现的高性能强化】
 *
 * ① **数组遍历而非 List**：Actor 数量固定，用数组 + 局部变量，
 *    避免 List 迭代器的对象分配。
 * ② **Actor 按命中率排序**：最常命中的放前面（媒体/电话在多数场景返回 false）。
 * ③ **early-return 短路**：Actor 返回 true 立即停止，
 *    天坦也是这么做的，但要保证顺序最优让平均链长最短。
 * ④ **双击/长按仍硬编码优先**：与天坦一致 —— 这两个是最高频操作，
 *    走链会有额外判断开销。
 */
interface GestureActor {
    /**
     * 尝试处理手势
     *
     * @param gestureId 手势 ID
     * @param displayId 显示器 ID
     * @return true = 已消费，链停止
     */
    fun onGesture(gestureId: Int, displayId: Int): Boolean

    /** Actor 名（诊断用） */
    val name: String

    /** 是否激活（未激活的 Actor 直接跳过，省一次方法调用） */
    val isActive: Boolean
        get() = true
}

/**
 * 手势责任链 —— 对应天坦 SoundBackService.X1
 */
class GestureChain {

    companion object {
        private const val TAG = "GestureChain"

        /** 单指上滑 */
        const val GESTURE_SWIPE_UP = 1

        /** 单指下滑 */
        const val GESTURE_SWIPE_DOWN = 2

        /** 双击 —— 天坦硬编码优先 */
        const val GESTURE_DOUBLE_TAP = 17

        /** 双击长按 —— 天坦硬编码优先 */
        const val GESTURE_DOUBLE_TAP_AND_HOLD = 18

        /** 先向下再向上滑 —— 天坦：切换到下一个导航 */
        const val GESTURE_SWIPE_DOWN_AND_UP = 8

        /** 先向上再向下滑 —— 天坦：切换到上一个导航 */
        const val GESTURE_SWIPE_UP_AND_DOWN = 7

        /** 特殊值：交给当前显示器的处理器 */
        const val GESTURE_SPECIAL = -3
    }

    /** Actor 数组（顺序 = 优先级） */
    private val actors = ArrayList<GestureActor>(8)

    /**
     * 双击处理器 —— 硬编码优先，对应天坦 `M1().M()` / `C2120a0.S()`
     *
     * 返回 true = 已处理
     */
    @Volatile
    var onDoubleTap: ((displayId: Int) -> Boolean)? = null

    /**
     * 双击长按处理器 —— 硬编码优先
     */
    @Volatile
    var onDoubleTapHold: ((displayId: Int) -> Boolean)? = null

    /**
     * 导航切换处理器 —— 硬编码优先
     *
     * 天坦行为：先下再上滑 = 下一个导航，先上再下滑 = 上一个导航。
     * 这两个手势专用于导航切换，不参与通用手势映射。
     */
    @Volatile
    var onNavigationSwitch: ((gestureId: Int) -> Boolean)? = null

    /** 诊断计数 */
    @Volatile
    var handledCount = 0L
        private set

    @Volatile
    var unhandledCount = 0L
        private set

    /** 最后一次命中的 Actor 名（诊断） */
    @Volatile
    var lastHandledBy: String? = null
        private set

    /**
     * 注册 Actor —— 按注册顺序决定优先级
     */
    fun register(actor: GestureActor) {
        actors.add(actor)
    }

    /**
     * 处理手势 —— 1:1 抄天坦 X1()
     *
     * @return true = 已消费
     */
    fun handle(gestureId: Int, displayId: Int = 0): Boolean {
        // ★ ① 双击/双击长按：硬编码优先（天坦行为）
        if (gestureId == GESTURE_DOUBLE_TAP) {
            val handler = onDoubleTap
            if (handler != null && handler.invoke(displayId)) {
                handledCount++
                lastHandledBy = "DoubleTap"
                return true
            }
            // 处理器没消费 → 继续走链（天坦也是这个行为）
        } else if (gestureId == GESTURE_DOUBLE_TAP_AND_HOLD) {
            val handler = onDoubleTapHold
            if (handler != null && handler.invoke(displayId)) {
                handledCount++
                lastHandledBy = "DoubleTapHold"
                return true
            }
        }

        // ★ ② 导航切换手势（硬编码优先）
        val navSwitch = onNavigationSwitch
        if (navSwitch != null &&
            (gestureId == GESTURE_SWIPE_DOWN_AND_UP || gestureId == GESTURE_SWIPE_UP_AND_DOWN)
        ) {
            if (navSwitch.invoke(gestureId)) {
                handledCount++
                lastHandledBy = "NavigationSwitch"
                return true
            }
        }

        // ★ ③ 责任链（跳过未激活的 Actor）
        val n = actors.size
        for (i in 0 until n) {
            val actor = actors[i]
            if (!actor.isActive) continue
            try {
                if (actor.onGesture(gestureId, displayId)) {
                    handledCount++
                    lastHandledBy = actor.name
                    ReaderLog.d(TAG, "${actor.name} handle gesture ${Gestures.name(gestureId)}")
                    return true
                }
            } catch (e: Throwable) {
                // ★ 单个 Actor 抛异常不能阻断整条链
                ReaderLog.e(TAG, "${actor.name} 处理手势异常: ${e.message}")
            }
        }

        // ③ 无人消费 → 交给默认导航（由服务注册为最后一个 Actor）
        unhandledCount++
        return false
    }

    fun clear() {
        actors.clear()
    }

    fun stats(): String = "已处理 $handledCount / 未处理 $unhandledCount / 最后 $lastHandledBy"
}

/**
 * 默认导航 Actor —— 兜底处理器（对应天坦 l1()）
 *
 * 负责：
 *   - 单指上/下滑 → 焦点移动
 *   - 双指滑动   → 滚动
 *   - 其他手势   → 按映射表执行
 */
class DefaultNavigationActor(
    private val onFocusMove: (forward: Boolean, displayId: Int) -> Boolean,
    private val onScroll: (forward: Boolean, displayId: Int) -> Boolean,
    private val onMappedAction: (gestureId: Int, displayId: Int) -> Boolean
) : GestureActor {

    override val name: String = "DefaultNavigationActor"

    override fun onGesture(gestureId: Int, displayId: Int): Boolean {
        return when (gestureId) {
            Gestures.GESTURE_SWIPE_LEFT,
            Gestures.GESTURE_SWIPE_UP -> onFocusMove(false, displayId)

            Gestures.GESTURE_SWIPE_RIGHT,
            Gestures.GESTURE_SWIPE_DOWN -> onFocusMove(true, displayId)

            Gestures.GESTURE_2_FINGER_SWIPE_UP -> onScroll(false, displayId)
            Gestures.GESTURE_2_FINGER_SWIPE_DOWN -> onScroll(true, displayId)

            else -> onMappedAction(gestureId, displayId)
        }
    }
}

/**
 * 媒体控制 Actor —— 对应天坦 mediaControlActor（f30577L）
 *
 * 场景：设备正在播放音乐/视频时，特定手势改为媒体控制
 */
class MediaControlActor(
    private val isMediaPlaying: () -> Boolean,
    private val onMediaControl: (gestureId: Int, displayId: Int) -> Boolean
) : GestureActor {

    override val name: String = "MediaControlActor"

    /** 只在媒体播放时激活 —— 省掉空闲时的链上开销 */
    override val isActive: Boolean
        get() = isMediaPlaying()

    override fun onGesture(gestureId: Int, displayId: Int): Boolean {
        if (!isMediaPlaying()) return false
        return onMediaControl(gestureId, displayId)
    }
}

/**
 * 视频浏览 Actor —— 对应天坦 videoBrowseActor / M1()
 *
 * 场景：视频播放中，单指上/下滑改为调节进度或音量
 * 天坦行为：`if ((id != 1 && id != 2) || (!t0 && !u0))` → 交给默认导航
 */
class VideoBrowseActor(
    private val isVideoMode: () -> Boolean,
    private val onVideoGesture: (gestureId: Int, displayId: Int) -> Boolean
) : GestureActor {

    override val name: String = "VideoBrowseActor"

    override val isActive: Boolean
        get() = isVideoMode()

    override fun onGesture(gestureId: Int, displayId: Int): Boolean {
        // 只接管单指上/下滑
        if (gestureId != GestureChain.GESTURE_SWIPE_UP &&
            gestureId != GestureChain.GESTURE_SWIPE_DOWN
        ) {
            return false
        }
        return onVideoGesture(gestureId, displayId)
    }
}

/**
 * 全屏朗读/复制 Actor —— 对应天坦 fullScreenCopyActor（j1()）
 *
 * 场景：全屏朗读模式下，任意手势先中断
 */
class FullScreenModeActor(
    private val isActiveMode: () -> Boolean,
    private val onAnyGesture: (gestureId: Int, displayId: Int) -> Boolean
) : GestureActor {

    override val name: String = "FullScreenModeActor"

    override val isActive: Boolean
        get() = isActiveMode()

    override fun onGesture(gestureId: Int, displayId: Int): Boolean {
        return onAnyGesture(gestureId, displayId)
    }
}

/**
 * 边缘菜单 Actor —— 对应天坦 SlidingMenuActor（m6/d1）
 *
 * 场景：边缘滑动打开菜单
 */
class SlidingMenuActor(
    private val isMenuEnabled: () -> Boolean,
    private val onEdgeGesture: (gestureId: Int, displayId: Int) -> Boolean
) : GestureActor {

    override val name: String = "SlidingMenuActor"

    override val isActive: Boolean
        get() = isMenuEnabled()

    override fun onGesture(gestureId: Int, displayId: Int): Boolean {
        return onEdgeGesture(gestureId, displayId)
    }
}
