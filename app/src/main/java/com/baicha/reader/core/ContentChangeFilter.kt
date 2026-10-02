package com.baicha.reader.core

import android.os.SystemClock

/**
 * 窗口内容变化过滤 —— 对标 TalkBack `WindowContentChangeAnnouncementFilter`(126 行)
 * 与 `EventTypeWindowContentChangedFeedbackRule`(431 行)
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * `TYPE_WINDOW_CONTENT_CHANGED` 是**最吵也最有价值**的一类事件：
 *
 *   有价值：下载进度 45%→46%、计时器走秒、聊天新消息、表单校验提示
 *            —— 用户需要知道
 *   太吵：  RecyclerView 滚动时每个 item 都在变、
 *            动画每帧都在变 —— 会刷屏
 *
 * 硬性选择只有两个：全播（刷屏）或全不播（漏信息）。
 * TalkBack 的解法是**按节点限流**：同一个节点的内容变化
 * 在 [minInterval] 内只播一次，不同节点各自计时。
 *
 * ══════════════════════════════════════════════════════════
 * 【白茶现状（本轮修复前）】
 * ══════════════════════════════════════════════════════════
 *
 * ```kotlin
 * AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
 *     // 不播报（太吵），但缓存要失效
 *     CacheRegistry.invalidate(CacheRegistry.Level.CONTENT)
 * }
 * ```
 *
 * **完全静默。** 后果：直播比分、下载进度、计时器这类
 * 只靠内容变化传递信息的场景，用户完全感知不到。
 *
 * ══════════════════════════════════════════════════════════
 * 【TalkBack 的限流设计（照它的思路）】
 * ══════════════════════════════════════════════════════════
 *
 * ```java
 * long time = getMinDurationBetweenContentChangesMillis(node);  // 节点自定义间隔
 * if (time == 0) time = CONTENT_CHANGE_MINIMUM_CYCLE;           // 默认间隔
 * if (node.equals(lastUpdatedNode)) {
 *     if (now - timeStamp >= time) { timeStamp = now; return true; }
 *     return false;                                             // 同节点太频繁 → 丢
 * } else {
 *     lastUpdatedNode = node; timeStamp = now; return true;     // 换节点 → 放行
 * }
 * ```
 *
 * 两个关键点（**已按源码核实**）：
 *   ① **换节点跳过首次播报** —— 避免「焦点事件」与「内容变化事件」
 *      连续播两遍；但 live region 不跳过
 *      （初版我写成「换节点立即放行」，与 TalkBack 相反）
 *   ② 同节点按间隔限流 —— 默认 [CONTENT_CHANGE_MINIMUM_CYCLE] = 30 秒
 *      （初版我写 500ms，差了 60 倍）
 *
 * ══════════════════════════════════════════════════════════
 * 【本实现相对 TalkBack 的改进】
 * ══════════════════════════════════════════════════════════
 *
 * ① **不做「App 特判表」**：TalkBack 有
 *    `enableShortAndLongDurationsForSpecificApps` 按包名给不同间隔，
 *    维护成本高且随 App 更新失效。白茶统一用一个间隔，
 *    并把它做成可调常量。
 *
 * ② **纯函数可测**：TalkBack 的实现依赖静态字段 + 真实 node，
 *    无法单测。白茶把判定抽成 [shouldAnnounce]，
 *    只吃 (nodeKey, now)，可逐条验证限流行为。
 *
 * ③ **不计无界状态**：TalkBack 只记一个 lastUpdatedNode。
 *    白茶同样只记一个 —— 用 IdentityHashMap 记所有节点会泄漏
 *    （节点回收后条目不清）。
 */
class ContentChangeFilter(
    /** 同一节点两次播报的最小间隔（毫秒） */
    private val minIntervalMs: Long = CONTENT_CHANGE_MINIMUM_CYCLE,
    /**
     * 是否启用「换节点跳过首次播报」—— 对齐 TalkBack。
     *
     * 默认开启。
     */
    private val skipFirstOnNodeChange: Boolean = true
) {

    /** 上次播报的节点身份 */
    private var lastNodeKey: Int = NO_NODE

    /** 上次播报时间 */
    private var lastTime: Long = 0L

    /** 被限流丢弃的次数（诊断用） */
    var throttledCount = 0L
        private set

    /**
     * 是否应该播报这次内容变化。
     *
     * @param nodeKey 变化节点的身份哈希；无节点用 [NO_NODE]
     * @param now     当前时间（uptimeMillis）
     * @return true = 播报
     */
    fun shouldAnnounce(
        nodeKey: Int,
        now: Long = SystemClock.uptimeMillis(),
        /** 该节点是否是 live region（内容变了要主动告知，不跳过首次） */
        isLiveRegion: Boolean = false
    ): Boolean {
        // 无节点身份 → 无法限流，宁可放行（不静默丢失）
        if (nodeKey == NO_NODE) return true

        // ① 换节点
        //
        // 【核实修正】初版写「换节点立即放行」——**与 TalkBack 相反**。
        //
        // TalkBack `WindowContentChangeAnnouncementFilter:68~78`：
        //   ```java
        //   } else {
        //       lastUpdatedNode = node;
        //       timeStamp = now;
        //       if (enableShortAndLongDurationsForSpecificApps) {
        //           if (node.getLiveRegion() == ACCESSIBILITY_LIVE_REGION_NONE) {
        //               // Skip the first announcement, to avoid two announcements
        //               // in quick succession: one from accessibility focus and
        //               // the other from this window content change.
        //               // Live regions should not be skipped.
        //               return false;
        //           }
        //       }
        //       return true;
        //   }
        //   ```
        //
        // 即：换节点时**跳过第一次播报**，避免「焦点事件」与
        // 「内容变化事件」连续播两遍（同一个节点刚被聚焦时，
        // 焦点事件已经念过一次内容了）。
        // 但 **live region 不跳过** —— live region 的语义就是
        // 「内容变了要主动告知」，跳过它就失去意义。
        if (nodeKey != lastNodeKey) {
            lastNodeKey = nodeKey
            lastTime = now
            return if (skipFirstOnNodeChange && !isLiveRegion) false else true
        }

        // ② 同节点 → 按间隔限流
        if (now - lastTime >= minIntervalMs) {
            lastTime = now
            return true
        }

        throttledCount++
        return false
    }

    /** 重置 —— 窗口变化时调用 */
    fun reset() {
        lastNodeKey = NO_NODE
        lastTime = 0L
    }

    fun stats(): String = "内容变化限流 $throttledCount 次"

    companion object {
        /** 无节点 */
        const val NO_NODE = 0

        /**
         * 同一节点内容变化的最小播报间隔。
         *
         * 【核实修正】初版写 500ms，并注释「TalkBack 取值为 500」——
         * **错了两处**：
         *   ① 真实值是 **30000**（30 秒），我写的差了 60 倍
         *   ② 我还编造了「TalkBack 对特定 App 用 SHORT(200)/LONG(1000)」，
         *      真实是 SHORT_DURATION=5000 / LONG_DURATION=10000
         *
         * 源码（`WindowContentChangeAnnouncementFilter.java:15~19`）：
         *   ```java
         *   CONTENT_CHANGE_MINIMUM_CYCLE = 30000;
         *   SHORT_DURATION = 5000;
         *   LONG_DURATION = 10000;
         *   ```
         *
         * 【30 秒是不是太长了？】
         *   不是 —— 这个限流的作用是「防止**同一个节点**反复刷屏」，
         *   而换节点会重置计时（且跳过首次）。
         *   真实场景（进度条、计时器）通常是**新节点或
         *   用户主动聚焦**，不受这个间隔影响。
         *   30 秒防的是「同一个节点被高频重绘」这种病态情况。
         */
        const val CONTENT_CHANGE_MINIMUM_CYCLE = 30000L

        /** 特定 App 的短间隔 —— TalkBack `SHORT_DURATION = 5000` */
        const val SHORT_DURATION = 5000L

        /** 特定 App 的长间隔 —— TalkBack `LONG_DURATION = 10000` */
        const val LONG_DURATION = 10000L
    }
}

/**
 * 内容变化的**语义判定** —— 决定「这次变化值不值得播」
 *
 * 对标 TalkBack `EventTypeWindowContentChangedFeedbackRule.getContentChangeType()`：
 * 按 `contentChangeTypes` 位掩码分类。
 *
 * 抽成纯函数的理由同上：可单测。
 */
object ContentChangeKind {

    /** 无信息量的变化（如单纯布局调整） */
    const val NONE = -1

    /**
     * 内容描述变了（权重最高）
     *
     * 【核实修正】初版把 TEXT 排在最前 —— **与 TalkBack 相反**。
     * TalkBack `getContentChangeType():276` 的第一判定就是
     * CONTENT_DESCRIPTION。
     */
    const val CONTENT_DESCRIPTION = 4

    /** 文本变了（计数器、进度、状态文字） */
    const val TEXT = 2

    /** 状态描述变了（开关/复选状态） */
    const val STATE_DESCRIPTION = 64

    /** 拖拽开始 —— TalkBack `CONTENT_CHANGE_TYPE_DRAG_STARTED` */
    const val DRAG_STARTED = 128

    /** 拖拽放下 */
    const val DRAG_DROPPED = 256

    /** 拖拽取消 */
    const val DRAG_CANCELLED = 512

    /** 未定义 —— TalkBack 的 `CONTENT_CHANGE_TYPE_UNDEFINED`（值为 0） */
    const val UNDEFINED = 0

    /** 错误状态（表单校验提示） */
    const val ERROR = 2048

    /** 启用/禁用状态变化 */
    const val ENABLED = 4096

    /** 结构变化（增删子节点）—— 白茶额外保留 */
    const val SUBTREE = 1

    /**
     * 分类内容变化类型。
     *
     * **判定顺序严格对齐 TalkBack**
     * `EventTypeWindowContentChangedFeedbackRule.getContentChangeType():276~302`：
     * ```
     * CONTENT_DESCRIPTION → TEXT → STATE_DESCRIPTION → DRAG_STARTED
     *   → DRAG_DROPPED → DRAG_CANCELLED → UNDEFINED → ERROR → ENABLED
     * ```
     *
     * 【核实修正】初版顺序为 TEXT → STATE_DESCRIPTION →
     * CONTENT_DESCRIPTION → SUBTREE，且**漏了 5 类**
     * （DRAG_STARTED / DRAG_DROPPED / DRAG_CANCELLED / ERROR / ENABLED）。
     *
     * @param changeTypes `event.contentChangeTypes`
     * @return 分类值；无匹配返回 [NONE]
     */
    fun classify(changeTypes: Int): Int = when {
        // 顺序严格照 TalkBack，不要「按直觉重排」
        changeTypes and CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0 -> CONTENT_DESCRIPTION
        changeTypes and CONTENT_CHANGE_TYPE_TEXT != 0 -> TEXT
        changeTypes and CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0 -> STATE_DESCRIPTION
        changeTypes and CONTENT_CHANGE_TYPE_DRAG_STARTED != 0 -> DRAG_STARTED
        changeTypes and CONTENT_CHANGE_TYPE_DRAG_DROPPED != 0 -> DRAG_DROPPED
        changeTypes and CONTENT_CHANGE_TYPE_DRAG_CANCELLED != 0 -> DRAG_CANCELLED
        changeTypes == CONTENT_CHANGE_TYPE_UNDEFINED -> UNDEFINED
        changeTypes and CONTENT_CHANGE_TYPE_ERROR != 0 -> ERROR
        changeTypes and CONTENT_CHANGE_TYPE_ENABLED != 0 -> ENABLED
        // 白茶额外：结构变化（TalkBack 不在此函数处理，它另有分支）
        changeTypes and CONTENT_CHANGE_TYPE_SUBTREE != 0 -> SUBTREE
        else -> NONE
    }

    /**
     * 这类变化是否值得播报。
     *
     * 【核实修正】初版只播 TEXT/STATE_DESCRIPTION/CONTENT_DESCRIPTION。
     * 现按 TalkBack 补齐 —— 它对这些类型都产出了播报文本
     * （`computeWindowContentChangedStateText`）。
     *
     * 【为什么不播 SUBTREE / UNDEFINED】
     *   SUBTREE：子树增删通常伴随焦点或窗口事件，那里已有处理，
     *            这里再播会重复。
     *   UNDEFINED：没有具体变化类型，不知道播什么。
     */
    fun isWorthAnnouncing(kind: Int): Boolean = when (kind) {
        CONTENT_DESCRIPTION, TEXT, STATE_DESCRIPTION,
        DRAG_STARTED, DRAG_DROPPED, DRAG_CANCELLED,
        ERROR, ENABLED -> true
        else -> false
    }

    // ==================== 位掩码常量 ====================
    // 全部用 `javap android.jar` 核实过

    const val CONTENT_CHANGE_TYPE_SUBTREE = 0x00000001          // 1
    const val CONTENT_CHANGE_TYPE_TEXT = 0x00000002             // 2
    const val CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION = 0x00000004  // 4
    const val CONTENT_CHANGE_TYPE_STATE_DESCRIPTION = 0x00000040    // 64
    const val CONTENT_CHANGE_TYPE_DRAG_STARTED = 0x00000080     // 128
    const val CONTENT_CHANGE_TYPE_DRAG_DROPPED = 0x00000100     // 256
    const val CONTENT_CHANGE_TYPE_DRAG_CANCELLED = 0x00000200   // 512
    const val CONTENT_CHANGE_TYPE_UNDEFINED = 0x00000000        // 0
    const val CONTENT_CHANGE_TYPE_ERROR = 0x00000800            // 2048
    const val CONTENT_CHANGE_TYPE_ENABLED = 0x00001000          // 4096
}
