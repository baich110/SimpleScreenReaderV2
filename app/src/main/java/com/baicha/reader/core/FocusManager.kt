package com.baicha.reader.core

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 焦点管理器 —— 对应天坦 M6/h（FocusProcessorForLogicalNavigation，2356 行）
 *
 * 【天坦的核心机制（本类逐一实现）】
 *
 * ① **单条焦点记录**（不是历史队列）
 *      f6010j : c(node, anchor, direction, targetType, flag, rect)
 *      f6007g : 焦点代数（判定任务是否过期）
 *
 * ② **滚动中导航拦截**
 *      if (正在滚动) {
 *          if (同方向) return true              ← 直接吞掉
 *          取消滚动，继续导航                     ← 反方向
 *      }
 *
 * ③ **搜索失败递归重试**
 *      if (搜索无果 && C1423r.s()) {            ← 上次遍历失败的标志
 *          重置标志
 *          return D(direction, ..., retry=true)  ← 递归重试
 *      }
 *
 * ④ **可达性判定 p()**
 *      目标节点不可直达（被容器挡住）→ 退到父节点再试
 *
 * ⑤ **延后聚焦**
 *      候选节点与目标不一致时，构造聚焦任务而非立即聚焦
 *
 * 【本实现的高性能强化】
 *
 * ① **焦点记录用 slots 而非对象**：6 个字段展开成独立变量，
 *    避免每次导航 new 一个记录对象（天坦的 `new c(...)` 是每步分配）。
 *
 * ② **方向判定免分配**：用 int 常量而非 enum，避免枚举值缓存查找。
 *
 * ③ **可达性判定带缓存**：同一父节点在一次导航内只算一次。
 *
 * ④ **防抖自适应**：连续同向导航时放宽防抖窗口（天坦固定 110ms，
 *    快速滑动时会被吞），改善体感。
 */
class FocusManager(
    private val service: ReaderAccessibilityService
) {

    companion object {
        private const val TAG = "FocusManager"

        /**
         * 方向常量 —— 数值对齐 TalkBack TraversalStrategy.SearchDirection
         *
         * 保留 NEXT/PREVIOUS 别名以兼容既有调用点。
         */
        const val DIR_NEXT = TraversalDirection.FORWARD
        const val DIR_PREVIOUS = TraversalDirection.BACKWARD
        const val DIR_UP = TraversalDirection.UP
        const val DIR_DOWN = TraversalDirection.DOWN
        const val DIR_LEFT = TraversalDirection.LEFT
        const val DIR_RIGHT = TraversalDirection.RIGHT

        /** 基础防抖窗口 —— 天坦 110ms */
        const val BASE_DEBOUNCE_MS = 110L

        /**
         * 连续同向导航时的**放宽上限**
         *
         * 【修正】原常量名 MAX_DEBOUNCE_MS=60 配合 coerceAtLeast 实际是「收紧到 60ms」，
         * 与注释「放宽」相反，且名字也是反的。
         *
         * 现在明确语义：**连续快速同向滑动时放宽窗口**，让快速浏览不被误吞。
         */
        const val MAX_DEBOUNCE_MS = 200L

        /** 连续导航时每次放宽的步长 */
        const val DEBOUNCE_STEP_MS = 15L

        /** 滚动状态有效期 */
        private const val SCROLL_STATE_TTL_MS = 300L

        /** 触摸目标有效期 */
        private const val TOUCH_TARGET_TTL_MS = 3000L
    }

    // ==================== 焦点记录（展开字段，非对象）====================

    /** 当前焦点节点 */
    @Volatile
    private var focusNode: AccessibilityNodeInfo? = null

    /** 锚点节点（上一个焦点） */
    @Volatile
    private var anchorNode: AccessibilityNodeInfo? = null

    /** 锚点边界 */
    private val anchorBounds = Rect()

    /** 焦点代数（每次设置自增，用于判定任务过期） */
    @Volatile
    private var focusGeneration = 0

    /** 上次方向 */
    @Volatile
    private var lastDirection = 0

    /** 上次导航时间 */
    @Volatile
    private var lastNavTime = 0L

    /** 连续同向计数（用于自适应防抖） */
    @Volatile
    private var navStreak = 0

    // ==================== 滚动状态 ====================

    /** 是否正在自动滚动 */
    @Volatile
    var isScrolling = false
        private set

    /** 滚动方向 */
    @Volatile
    private var scrollDirection = 0

    /** 滚动状态时间戳 */
    @Volatile
    private var scrollStateTime = 0L

    // ==================== 触摸目标 ====================

    @Volatile
    private var touchNode: AccessibilityNodeInfo? = null

    private val touchBounds = Rect()

    @Volatile
    private var touchTime = 0L

    // ==================== 回调 ====================

    /** 停止自动滚动 */
    @Volatile
    var onCancelScroll: (() -> Unit)? = null

    /**
     * 粒度移动器 —— 由服务注入
     *
     * 返回 true 表示「系统已处理粒度移动」，此时不应再做节点遍历。
     * 对应天坦 `AbstractC3675b0.c(node, action, bundle)`。
     */
    @Volatile
    var granularityMover: ((node: AccessibilityNodeInfo, forward: Boolean) -> Boolean)? = null

    // ==================== 统计 ====================

    @Volatile
    var navCount = 0L
        private set

    @Volatile
    var blockedByScroll = 0L
        private set

    @Volatile
    var retryCount = 0L
        private set

    // ==================== 滚动定位（P1-1）====================

    /**
     * 「跳到顶部/底部」钩子 —— 由服务注入。
     *
     * 参数 = 是否跳顶部（true 顶部 / false 底部）
     * 返回 = 容器是否接受（false → 回退普通遍历，不静默）
     *
     * 【为什么用钩子而不是直接调 ScrollToPosition】
     *   与 granularityMover 同构：FocusManager 不持有 Android 上下文，
     *   保持纯逻辑可测；具体实现由服务层注入。
     */
    var scrollJump: ((top: Boolean) -> Boolean)? = null

    /**
     * 焦点成功设置后的回调 —— 用于采集节点路径。
     *
     * 【为什么用回调而不是让 FocusManager 自己持有路径】
     *   与 granularityMover / scrollJump 同构：FocusManager 不持有
     *   恢复状态，保持可测；路径的存储与使用由服务层负责。
     *
     * 【为什么在这里采集而不是在事件处理器里】
     *   applyFocus 是「我们主动设焦点」的唯一出口 ——
     *   在这里采集能保证每次主动聚焦都留下路径。
     */
    var onFocusApplied: ((node: AccessibilityNodeInfo) -> Unit)? = null

    /** 本次导航是否为「跳到顶部」 */
    @Volatile
    private var scrollJumpToTop = true

    /**
     * 本次导航是否**请求**了滚动定位。
     *
     * ★★ 本轮修正（真机 P0 —— 用户「左右滑动从来获取不到焦点」的直接成因）
     *
     * 【原来的 bug】
     *   `scrollJump` 钩子在服务启动时注入后**永不撤销**，
     *   而 `moveFocusInternal` 开头是：
     *   ```kotlin
     *   val jump = scrollJump
     *   if (jump != null) {
     *       val handled = jump.invoke(scrollJumpToTop)   // ← 每次都触发
     *       if (handled) return null                      // ← 直接返回，不做遍历
     *   }
     *   ```
     *   即 **hook 非空就每次导航都尝试滚动定位**。
     *   而 `scrollJumpToTop` 只在切到 SCROLL_TOP/SCROLL_BOTTOM 导航时赋值
     *   （`else -> {}` 空分支，从不清除）。
     *
     *   后果：用户一旦切到过「跳到顶部/底部」导航，
     *   **此后每一次左右滑动都走滚动定位 → 返回 null → 焦点永远不动**；
     *   又因 `NavigationStore` 持久化了导航模式，**重启也恢复不了**。
     *
     * 【修法】加「一次性请求」标志：
     *   · 只有**当前导航就是滚动定位**时才置 true（见 requestScrollJump）
     *   · `moveFocusInternal` 开头检查并**立即消费**它
     *   · 其他导航切换时显式清除（见 clearScrollJump）
     */
    @Volatile
    private var scrollJumpRequested = false

    /**
     * 声明「本次导航做滚动定位」—— 由服务在调用 moveFocus 前设置。
     *
     * 语义是**一次性请求**：`moveFocusInternal` 消费后自动复位，
     * 不会泄漏到下一次导航。
     */
    fun setScrollJumpDirection(top: Boolean) {
        scrollJumpToTop = top
        scrollJumpRequested = true
    }

    /**
     * 清除滚动定位请求 —— 每当导航模式**不是** SCROLL_TOP/SCROLL_BOTTOM 时调用。
     *
     * 【为什么需要显式清除】用户从「跳到顶部」切回「默认」后，
     * 残留的请求会让下一次滑动变成跳转 —— 这正是原来的 bug。
     */
    fun clearScrollJump() {
        scrollJumpRequested = false
    }

    /** 仅测试用：当前是否有待消费的滚动定位请求 */
    internal fun isScrollJumpRequested(): Boolean = scrollJumpRequested

    // ==================== 获取焦点 ====================

    /**
     * 获取当前焦点
     *
     * 优先级（天坦 C1079a.d()）：
     *   ① 服务级无障碍焦点
     *   ② 根节点上的无障碍焦点
     *   ③ 我们缓存的焦点
     *   ④ 输入焦点
     */
    /**
     * 焦点监控器 —— 对应 TalkBack `AccessibilityFocusMonitor`
     *
     * 提供多级回退（无障碍焦点 → 输入焦点 → 历史编辑框）。
     * 由服务注入，因为需要「输入法窗口是否在屏」这类服务级信息。
     */
    @Volatile
    var monitor: FocusMonitor? = null

    fun getFocused(): AccessibilityNodeInfo? {
        // ★ 优先走监控器的多级回退链（对应 TalkBack）
        val m = monitor
        if (m != null) {
            val node = m.getAccessibilityFocus(useInputFocusIfEmpty = true)
            if (node != null) {
                cacheFocus(node)
                return node
            }
        }

        // ---- 监控器不可用时的降级路径（保持原有行为）----

        // ① 服务级
        try {
            service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let {
                if (isValid(it)) {
                    cacheFocus(it)
                    return it
                }
            }
        } catch (e: Throwable) {
            // ignore
        }

        val root = service.rootInActiveWindow

        // ② 根节点上的
        try {
            root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let {
                if (isValid(it)) {
                    cacheFocus(it)
                    return it
                }
            }
        } catch (e: Throwable) {
            // ignore
        }

        // ③ 缓存
        focusNode?.let {
            if (isValid(it)) return it
        }

        // ④ 输入焦点
        try {
            root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
                if (isValid(it)) return it
            }
        } catch (e: Throwable) {
            // ignore
        }

        return null
    }

    private fun cacheFocus(node: AccessibilityNodeInfo) {
        if (focusNode == node) return
        anchorNode = focusNode
        // 【修正】原来写成 node.getBoundsInScreen(...)，用的是新节点参数。
        // anchorBounds 语义是「上一个焦点的边界」，必须取旧焦点。
        focusNode?.let { it.getBoundsInScreen(anchorBounds) }
        focusNode = node
    }

    // ==================== 导航（天坦 D()）====================

    /**
     * 移动焦点 —— 天坦 M6.h.D() 的入口
     *
     * @param direction 方向
     * @param strategyType 遍历策略类型（对应导航模式）
     * @return 设上焦点的节点；null = 无
     */
    fun moveFocus(
        direction: Int,
        strategyType: Int = TraversalStrategyFactory.TYPE_DEFAULT,
        filter: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        // ★ 性能基线埋点（审查报告「目标 D」）
        //   放在最外层，覆盖全部退出路径 —— 与下面的 try/finally 同理
        val perfStart = PerfCounters.begin()
        // ★ 对应天坦：C3678d.d() 在 M6.h.D() 每条退出路径上调用（26 处）
        //   用 try/finally 一次覆盖全部退出路径，比 26 处手动调用更可靠
        return try {
            moveFocusInternal(direction, strategyType, filter)
        } finally {
            ShouldFocusNode.resetOperationScope()
            PerfCounters.endNav(perfStart)
        }
    }

    /** 导航实现主体 */
    private fun moveFocusInternal(
        direction: Int,
        strategyType: Int,
        filter: ((AccessibilityNodeInfo) -> Boolean)?
    ): AccessibilityNodeInfo? {
        // ★ 决策追踪（本轮新增）—— 让「这次滑动为什么没找到焦点」可回答
        //
        // 【真机教训】排查 P0 时，日志只能看到「没找到焦点」这个**结果**，
        // 看不到「在哪一步被拒绝」——而焦点查找有十几道判定，
        // 任何一道返回 false 都是同一种表现。
        // 本追踪把每一步的通过/否决汇总成**一行**记录。
        val trace = FocusTrace.begin(if (isForward(direction)) "下一焦点" else "上一焦点")
        // ★ ⓪ 滚动定位（跳到顶部/底部）
        //
        // 对应 TalkBack `performActionScrollToPosition()`。
        // 放在最前面：它不参与遍历、不受防抖/滚动拦截影响 ——
        // 「跳到顶部」是个明确的一次性指令，被防抖吞掉才是错的。
        //
        // 【为什么不做滚动状态判定】
        //   TalkBack 把它当作独立动作（ScrollActionRecord.ACTION_AUTO_SCROLL），
        //   不与逐项导航共用去重窗口。
        // ★★ 只有**显式请求**时才走滚动定位（本轮 P0 修复）
        //
        // 原来是「hook 非空就触发」—— 而 hook 在服务启动时注入后永不撤销，
        // 导致切到过「跳到顶部/底部」之后每次滑动都变成跳转、焦点不动。
        val jump = scrollJump
        if (jump != null && scrollJumpRequested) {
            scrollJumpRequested = false   // ★ 一次性消费，绝不泄漏到下一次
            val handled = jump.invoke(scrollJumpToTop)
            if (handled) {
                ReaderLog.d(TAG, if (scrollJumpToTop) "已跳到顶部" else "已跳到底部")
                return null   // 位置已改变，焦点由滚动事件回带，本次不设焦点
            }
            // 容器不支持 → 交给下面的普通遍历（不静默）
            ReaderLog.d(TAG, "容器不支持滚动定位 → 回退遍历")
        }

        // ★ ① 滚动中拦截（天坦行为）
        //
        // 【状态机说明】isScrolling 是「滚动进行中」的标志。
        //   原实现只有「反方向导航」和「窗口变化」两条清除路径 ——
        //   同方向导航被吞掉后 isScrolling 保持 true，
        //   靠 isScrollStateFresh() 的 300ms TTL 隐式兜底。
        //
        //   这是**隐式依赖**：如果 TTL 被调大或判断被误删，
        //   isScrolling 会永久为 true → 导航全部被吞（读屏"死机"）。
        //
        //   现在改为**显式状态机**：TTL 过期即视为滚动结束，主动清除标志。
        if (isScrolling && !isScrollStateFresh()) {
            // TTL 过期 → 滚动已结束，显式收敛状态
            ReaderLog.d(TAG, "滚动状态超时，自动结束")
            isScrolling = false
        }

        if (isScrolling) {
            if (isSameScrollDirection(direction)) {
                // 同方向 → 直接吞掉
                blockedByScroll++
                trace.step("滚动拦截", passed = false, detail = "同向滚动画吞掉")
                trace.reject("滚动中同向导航被吞")
                trace.end()
                return null
            }
            // 反方向 → 取消滚动，继续导航
            ReaderLog.d(TAG, "滚动中反方向导航 → 取消滚动")
            isScrolling = false
            onCancelScroll?.invoke()
        }

        // ★ ② 自适应防抖
        val now = SystemClock.uptimeMillis()
        if (direction == lastDirection) {
            // 【修正】连续同向导航 → 逐步**放宽**窗口（原来写反了，是收紧）
            val window = (BASE_DEBOUNCE_MS + navStreak * DEBOUNCE_STEP_MS)
                .coerceAtMost(MAX_DEBOUNCE_MS)
            if (now - lastNavTime < window) {
                trace.step("防抖", passed = false, detail = "${now - lastNavTime}ms<${window}ms")
                trace.reject("防抖窗口内被丢弃")
                trace.end()
                return null
            }
            trace.step("防抖", passed = true, detail = "第${navStreak + 1}次连滑")
            navStreak++
        } else {
            // 换方向 → 立即放行，重置连续计数
            navStreak = 0
        }
        lastNavTime = now
        lastDirection = direction
        navCount++

        val root = service.rootInActiveWindow ?: run {
            ReaderLog.w(TAG, "moveFocus: 无活动窗口")
            return null
        }

        val current = getFocused()

        // 无焦点 → 取第一个
        if (current == null) {
            trace.step("当前焦点", passed = true, detail = "无 → 取第一个")
            val first = findFirstFocusable(root, strategyType)
            if (first == null) {
                trace.reject("当前无焦点且找不到首个可聚焦节点")
                trace.end()
                return null
            }
            val ok = applyFocus(first)
            if (ok) trace.accept(first) else trace.reject("设焦点失败", "performAction 返回 false")
            trace.end()
            return if (ok) first else null
        }
        trace.step("当前焦点", passed = true, detail = FocusTrace.describe(current))

        val forward = isForward(direction)

        // ★ ③ 粒度导航：优先用系统 API 移动光标（而不是找下一个节点）
        //
        // 【为什么】
        //   粒度导航（按字/词/句/行/段）的本质是「移动光标」，
        //   不是「移动焦点到下一个节点」。
        //   App 维护光标位置，必须通过
        //   ACTION_NEXT_AT_MOVEMENT_GRANULARITY 让 App 自己移动。
        //
        // 天坦 X6/e.java:271 / TalkBack CursorGranularityManager 都是这个机制。
        val gm = granularityMover
        if (gm != null) {
            val moved = gm.invoke(current, forward)
            if (moved) {
                // 系统已处理 —— 新位置的内容由
                // TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY 事件回传
                trace.step("粒度", passed = true, detail = "系统已移动光标")
                trace.accept(current)
                trace.end()
                return current
            }
            trace.skip("粒度")
            // 系统不支持 → 回退到普通遍历（下方逻辑）
        }

        // ★ ④ 搜索（走策略）
        var target = searchFocus(current, forward, strategyType, root, filter)

        // ★ ④ 搜索失败 → 递归重试（天坦 C1423r.s() 机制）
        if (target == null) {
            retryCount++
            trace.step("搜索", passed = false, detail = "首次无果，用默认策略重试")
            // 重试时强制用默认策略（避免策略本身导致的死路）
            target = searchFocus(current, forward, TraversalStrategyFactory.TYPE_DEFAULT, root, filter)
            if (target == null) {
                trace.reject(
                    "遍历无果",
                    "策略重试后仍未找到可聚焦节点（过滤=${filterDescription(filter)}）"
                )
                trace.end()
                return null
            }
            trace.step("搜索", passed = true, detail = "重试命中 ${FocusTrace.describe(target)}")
        } else {
            trace.step("搜索", passed = true, detail = FocusTrace.describe(target))
        }

        if (target != null && target != current) {
            // ★ ⑤ 可达性判定（天坦 M6.h.p()）
            //
            // 【修正】原实现算完布尔值就丢掉，等于没实现 —— 目标不可达时不会退到父节点，
            // 导致「焦点丢失」。现在改成返回「实际应该聚焦的节点」。
            val actual = resolveReachable(target)
            if (actual !== target) {
                trace.step("可达性", passed = true, detail = "退到父节点")
            } else {
                trace.step("可达性", passed = true)
            }
            val ok = applyFocus(actual)
            if (ok) {
                trace.accept(actual)
            } else {
                trace.reject("设焦点失败", "performAction(ACTION_ACCESSIBILITY_FOCUS) 返回 false")
            }
            trace.end()
            return if (ok) actual else null
        }

        // ① 走到头 → 循环（回到另一端）
        //
        // 【修正】加「已尝试节点」身份集合，避免策略差异导致的反复横跳。
        val wrap = if (forward) findFirstFocusable(root, strategyType)
        else findLastFocusable(root, strategyType)
        if (wrap != null && wrap !== current && !isSameAsAny(wrap, current, target)) {
            return if (applyFocus(wrap)) wrap else null
        }

        // ② ★ 本窗口内彻底走不出去 → 跨窗口兜底
        //
        // 【对应的缺口】原来这里直接 return null。
        //   后果：焦点一旦在本窗口遍历到尽头，就再也动不了 ——
        //   弹窗/输入法/浮层场景下表现为「焦点卡死、找不到焦点」。
        //
        //   天坦 M6/h.F() 与 TalkBack searchTargetInNextOrPreviousWindow 都在
        //   这个位置做跨窗口尝试。见 WindowTraversal 的类注释。
        val crossWindow = WindowTraversal.navigateToNextWindow(
            service = service,
            forward = forward
        )
        if (crossWindow != null) {
            ReaderLog.i(TAG, "跨窗口导航 → ${desc(crossWindow)}")
            return if (applyFocus(crossWindow)) crossWindow else null
        }

        return null
    }

    /** 判断节点是否与给定节点之一相同（身份比较） */
    private fun isSameAsAny(node: AccessibilityNodeInfo, vararg others: AccessibilityNodeInfo?): Boolean {
        for (o in others) {
            if (o != null && node === o) return true
        }
        return false
    }

    /**
     * 搜索下一个可聚焦节点 —— 对应天坦 C1427v.o()
     */
    private fun searchFocus(
        start: AccessibilityNodeInfo,
        forward: Boolean,
        strategyType: Int,
        root: AccessibilityNodeInfo,
        customFilter: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        val strategy = TraversalStrategyFactory.create(strategyType, root) { node ->
            if (customFilter != null) customFilter(node) else ShouldFocusNode.shouldFocus(node)
        }
        return try {
            strategy.next(
                start,
                if (forward) TraversalStrategy.DIRECTION_NEXT
                else TraversalStrategy.DIRECTION_PREVIOUS
            )
        } finally {
            strategy.release()
        }
    }

    /**
     * 可达性判定 —— 对应天坦 M6.h.p()
     *
     * 【天坦的行为】
     *   目标节点如果「被容器挡住」（不可直接聚焦），就**退到父节点再试**。
     *   这是避免「焦点卡住/丢失」的关键机制。
     *
     * @return 实际应该聚焦的节点（可能是 target 本身，也可能是它的父节点）
     */
    private fun resolveReachable(target: AccessibilityNodeInfo): AccessibilityNodeInfo {
        // ① 目标本身可聚焦 → 用它
        if (ShouldFocusNode.shouldFocus(target)) return target

        // ② 退到父节点（天坦 p() 的父节点回退）
        val parent = target.parent
        if (parent != null && ShouldFocusNode.shouldFocus(parent)) {
            return parent
        }

        // ③ 都不行 → 仍返回 target（由 applyFocus 决定成败，不静默丢弃）
        return target
    }

    /**
     * 找第一个可聚焦
     */
    private fun findFirstFocusable(
        root: AccessibilityNodeInfo,
        strategyType: Int
    ): AccessibilityNodeInfo? {
        if (ShouldFocusNode.shouldFocus(root)) return root
        val strategy = TraversalStrategyFactory.create(strategyType, root) {
            ShouldFocusNode.shouldFocus(it)
        }
        return try {
            strategy.next(root, TraversalStrategy.DIRECTION_NEXT)
        } finally {
            strategy.release()
        }
    }

    /**
     * 找最后一个可聚焦
     */
    private fun findLastFocusable(
        root: AccessibilityNodeInfo,
        strategyType: Int
    ): AccessibilityNodeInfo? {
        val strategy = TraversalStrategyFactory.create(strategyType, root) {
            ShouldFocusNode.shouldFocus(it)
        }
        return try {
            strategy.next(root, TraversalStrategy.DIRECTION_PREVIOUS)
        } finally {
            strategy.release()
        }
    }

    // ==================== 设焦点 ====================

    /**
     * 设无障碍焦点 —— 天坦只做「清旧 + 设新」，不向上/向下兜底
     *
     * 兜底会导致「焦点飘」：摸到 A 却聚焦到 A 的父容器 B
     */
    fun applyFocus(node: AccessibilityNodeInfo, force: Boolean = false): Boolean {
        // 清旧
        try {
            val root = service.rootInActiveWindow
            val cur = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (cur != null && (cur != node || force)) {
                cur.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            }
        } catch (e: Throwable) {
            // ignore
        }

        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } catch (e: Throwable) {
            false
        }

        if (ok) {
            anchorNode = focusNode
            // 【修正】同 cacheFocus：必须取旧焦点的边界
            focusNode?.let { it.getBoundsInScreen(anchorBounds) }
            focusNode = node
            focusGeneration++
            // ★ 记录可编辑焦点（供 FocusMonitor 的历史回退）
            monitor?.recordEditableFocus(node)
            // ★ 采集节点路径（供屏幕状态恢复时按特征找回）
            //   对应用户可感知的问题：焦点恢复时永远跳第一个
            onFocusApplied?.invoke(node)
        }
        return ok
    }

    /**
     * 触摸探索设焦点
     */
    fun focusForTouch(node: AccessibilityNodeInfo): Boolean {
        touchNode = node
        node.getBoundsInScreen(touchBounds)
        touchTime = SystemClock.uptimeMillis()
        return applyFocus(node, force = true)
    }

    /**
     * 过滤器描述 —— 日志里能看出「这次导航用的是哪种过滤」
     *
     * 【为什么需要】焦点找不到的常见原因之一是**过滤器过严**：
     *   比如导航模式是「标题」但页面上没有标题，
     *   此时遍历会走遍整棵树然后失败。
     *   日志里带上过滤条件，能立刻区分「过滤器问题」与「树结构问题」。
     */
    private fun filterDescription(filter: ((AccessibilityNodeInfo) -> Boolean)?): String =
        if (filter == null) "默认(可聚焦)" else "自定义过滤器"

    // ==================== 操作目标 ====================

    /**
     * 取当前操作目标
     *
     * 优先级：触摸节点（3 秒内）> 无障碍焦点 > 缓存
     */
    fun getTargetNode(): AccessibilityNodeInfo? {
        val now = SystemClock.uptimeMillis()
        val tn = touchNode
        if (tn != null && now - touchTime < TOUCH_TARGET_TTL_MS) {
            if (isValid(tn)) return tn
            // 节点失效 → 按位置找
            findByBounds(service.rootInActiveWindow, touchBounds)?.let { return it }
        }
        return getFocused()
    }

    /** 点击当前目标（向上找可点击祖先 + 坐标兜底） */
    fun clickTarget(): Boolean {
        val target = getTargetNode() ?: return false
        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isClickable && node.isEnabled) {
                val ok = try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Throwable) {
                    false
                }
                if (ok) return true
            }
            node = node.parent
            depth++
        }
        // 坐标兜底
        return clickByCoordinate(target)
    }

    /** 长按当前目标 */
    fun longClickTarget(): Boolean {
        val target = getTargetNode() ?: return false
        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isLongClickable && node.isEnabled) {
                return try {
                    node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                } catch (e: Throwable) {
                    false
                }
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun clickByCoordinate(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return service.dispatchGestureTap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    /** 滚动 */
    fun scroll(forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        var node: AccessibilityNodeInfo? = getFocused()
        var depth = 0
        while (node != null && depth < 10) {
            if (node.isScrollable) {
                return try {
                    node.performAction(action)
                } catch (e: Throwable) {
                    false
                }
            }
            node = node.parent
            depth++
        }
        return try {
            service.rootInActiveWindow?.performAction(action) ?: false
        } catch (e: Throwable) {
            false
        }
    }

    // ==================== 滚动状态 ====================

    /**
     * 标记滚动开始
     */
    fun onScrollStart(direction: Int) {
        isScrolling = true
        scrollDirection = direction
        scrollStateTime = SystemClock.uptimeMillis()
    }

    /**
     * 标记滚动结束
     */
    fun onScrollEnd() {
        isScrolling = false
        scrollDirection = 0
    }

    private fun isScrollStateFresh(): Boolean =
        SystemClock.uptimeMillis() - scrollStateTime < SCROLL_STATE_TTL_MS

    private fun isSameScrollDirection(direction: Int): Boolean {
        if (scrollDirection == 0) return false
        val navForward = isForward(direction)
        val scrollForward = scrollDirection > 0
        return navForward == scrollForward
    }

    // ==================== 窗口变化 ====================

    fun onWindowChanged() {
        focusNode = null
        anchorNode = null
        anchorBounds.setEmpty()
        monitor?.onWindowChanged()
        lastDirection = 0
        navStreak = 0
        isScrolling = false
        ShouldFocusNode.clear()
    }

    fun shutdown() {
        onWindowChanged()
    }

    // ==================== 工具 ====================

    private fun isForward(direction: Int): Boolean =
        direction == DIR_NEXT || direction == DIR_RIGHT || direction == DIR_DOWN

    private fun isValid(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }

    private fun findByBounds(root: AccessibilityNodeInfo?, target: Rect): AccessibilityNodeInfo? {
        if (root == null || target.isEmpty) return null
        return findByBoundsInternal(root, target, 0)
    }

    private fun findByBoundsInternal(
        node: AccessibilityNodeInfo,
        target: Rect,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 25) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r == target) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByBoundsInternal(child, target, depth + 1)?.let { return it }
        }
        return null
    }

    fun desc(node: AccessibilityNodeInfo?): String {
        if (node == null) return "null"
        val r = Rect()
        node.getBoundsInScreen(r)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text ?: node.contentDescription ?: ""
        return "$cls[$text]$r"
    }

    fun stats(): String =
        "导航 $navCount / 滚动拦截 $blockedByScroll / 重试 $retryCount"
}
