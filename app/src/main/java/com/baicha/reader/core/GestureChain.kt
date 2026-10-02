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
        // ★ ① 双击/双击长按：**硬编码优先且必须终止**（天坦行为）
        //
        // ══════════════════════════════════════════════════════════
        // 【★ 本轮修复：原来允许「处理器没消费就继续走链」，这是错的】
        // ══════════════════════════════════════════════════════════
        //
        // 天坦 `Q6/TouchInteractionMonitor.java:860~872`（原样摘录）：
        // ```java
        //   if (gestureId == 17) {
        //       if (this.f7610o) {
        //           F(accessibilityGestureEvent);       // 触摸探索模式：转交手势链
        //           return;                             // ← 无论如何都 return
        //       } else {
        //           this.f7600e.performClick();         // 否则：直接点击当前焦点
        //           return;                             // ← 无论如何都 return
        //       }
        //   }
        //   if (gestureId == 18) {
        //       this.f7600e.performLongClickAndStartDrag();
        //       return;                                 // ← 无条件 return
        //   }
        // ```
        //
        // **两个分支都 `return`** —— 双击（17）在任何情况下都不会
        // 落到后面的通用手势链上；双击长按（18）同理。
        //
        // 【我原来写的注释「处理器没消费 → 继续走链（天坦也是这个行为）」
        //   是我自己臆断的，源码里没有这个行为】——
        // 这正是目标里点名的错误模式②「凭印象写注释，比代码更危险」。
        //
        // 【真机后果】原先当 `clickTarget()` 失败（当前节点不可点击/已被
        // 回收）时会**继续走链**，于是同一个双击被
        //   MediaControlActor / FullScreenModeActor / VideoBrowseActor /
        //   SlidingMenuActor / DefaultNavigationActor
        // 依次尝试 —— 用户双击一个不可点节点，可能莫名其妙触发
        // 「停止播放」「退出全屏」「打开侧边菜单」之类的**无关动作**，
        // 或者被 DefaultNavigationActor 当成普通手势再走一遍导航。
        // 而按天坦语义，双击就是这个双击，不该变成别的东西。
        //
        // 【修复】改为「调用处理器，无论成功与否都终止」。
        // handler 为 null（未接线）时也不再继续走链 ——
        // 与天坦一致（天坦这里的两个分支都不依赖外部是否注册）。
        if (gestureId == GESTURE_DOUBLE_TAP) {
            val handler = onDoubleTap
            val consumed = handler != null && handler.invoke(displayId)
            if (consumed) {
                handledCount++
            } else {
                // 未消费也终止 —— 但要记账，便于真机排障时区分
                // 「双击没生效」到底是「没处理」还是「处理失败」
                unhandledCount++
            }
            lastHandledBy = "DoubleTap"
            return consumed
        }

        if (gestureId == GESTURE_DOUBLE_TAP_AND_HOLD) {
            val handler = onDoubleTapHold
            val consumed = handler != null && handler.invoke(displayId)
            if (consumed) {
                handledCount++
            } else {
                unhandledCount++
            }
            lastHandledBy = "DoubleTapHold"
            return consumed
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
        //
        // 【调试开关】进入责任链时记一条 DEBUG，便于真机判断
        // 「手势到底进没进链」——与上面的「无人消费」配对使用。
        if (ReaderLog.minLevel.priority <= LogLevel.DEBUG.priority) {
            val names = actors.joinToString(",") { "${it.name}${if (it.isActive) "" else "(跳过)"}" }
            ReaderLog.d(TAG, "责任链: ${Gestures.name(gestureId)} → [$names]")
        }
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

        // ③ 无人消费
        //
        // ══════════════════════════════════════════════════════════
        // 【★ 本轮补日志：这条路原来是**完全静默**的】
        // ══════════════════════════════════════════════════════════
        //
        // 【真机教训】用户实装后的日志里，连续 9 次手势
        // （`onGesture(Event): 3/4`）**一条后续都没有**：
        // ```
        //   05:28:21.204  onGesture(Event): 4
        //   05:28:22.263  onGesture(Event): 3
        //   05:28:36.751  onGesture(Event): 4
        //   ...
        // ```
        // 排查者无法判断手势**去哪了** —— 是没进链？被 Actor 拒绝？
        // 还是进了默认导航但导航失败？属目标点名的模式⑨「静默失败」。
        //
        // 【本行补上】把「哪些 Actor 被跳过、最后无人消费」说清楚。
        // 正常情况下 `DefaultNavigationActor` 是兜底，**必然**消费
        // 单指上/下/左/右滑 —— 所以这里出现日志本身就是异常信号。
        val activeNames = actors.filter { it.isActive }.joinToString(",") { it.name }
        ReaderLog.w(
            TAG,
            "手势 ${Gestures.name(gestureId)} 无人消费（激活 Actor: $activeNames）"
        )
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
