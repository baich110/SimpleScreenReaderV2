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
 * 两个关键点：
 *   ① **换节点立即放行** —— 用户焦点移走了，新节点的变化立即有意义
 *   ② 同节点按间隔限流 —— 默认 [CONTENT_CHANGE_MINIMUM_CYCLE]
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
    private val minIntervalMs: Long = CONTENT_CHANGE_MINIMUM_CYCLE
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
    fun shouldAnnounce(nodeKey: Int, now: Long = SystemClock.uptimeMillis()): Boolean {
        // 无节点身份 → 无法限流，宁可放行（不静默丢失）
        if (nodeKey == NO_NODE) return true

        // ① 换节点 → 立即放行（对齐 TalkBack）
        if (nodeKey != lastNodeKey) {
            lastNodeKey = nodeKey
            lastTime = now
            return true
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
         * 【为什么是 500ms】TalkBack 的 `CONTENT_CHANGE_MINIMUM_CYCLE`
         * 取值为 500。低于此值会刷屏（如进度条每帧更新），
         * 高于此值会漏掉较快的变化。
         *
         * 【为什么不照搬 TalkBack 的 App 特判表】
         *   TalkBack 对特定 App 用 SHORT(200)/LONG(1000) 两档，
         *   但那张表随 App 更新会失效，维护成本 > 收益。
         */
        const val CONTENT_CHANGE_MINIMUM_CYCLE = 500L
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

    /** 文本变了 —— 最常需要播报（计数器、进度、状态） */
    const val TEXT = 1

    /** 内容描述变了 —— 按钮状态、图标语义 */
    const val CONTENT_DESCRIPTION = 2

    /** 状态描述变了 —— 开关/复选状态 */
    const val STATE_DESCRIPTION = 3

    /** 结构变化（增删子节点）—— 通常由别的机制播报 */
    const val SUBTREE = 4

    /**
     * 分类内容变化类型。
     *
     * 对齐 TalkBack 的位掩码判定顺序 ——
     * 一项事件可能同时带多个位，取**最有信息量**的那个。
     *
     * @param changeTypes `event.contentChangeTypes`
     */
    fun classify(changeTypes: Int): Int = when {
        // 文本优先 —— 最有信息量
        changeTypes and CONTENT_CHANGE_TYPE_TEXT != 0 -> TEXT
        changeTypes and CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0 -> STATE_DESCRIPTION
        changeTypes and CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0 -> CONTENT_DESCRIPTION
        changeTypes and CONTENT_CHANGE_TYPE_SUBTREE != 0 -> SUBTREE
        else -> NONE
    }

    /**
     * 这类变化是否值得播报。
     *
     * 【为什么不播 SUBTREE】
     *   子树增删通常伴随焦点事件或窗口事件 ——
     *   那些已有专门处理，这里再播会重复。
     */
    fun isWorthAnnouncing(kind: Int): Boolean = when (kind) {
        TEXT, STATE_DESCRIPTION, CONTENT_DESCRIPTION -> true
        else -> false
    }

    // ==================== 位掩码常量 ====================
    // 与 AccessibilityEvent.CONTENT_CHANGE_TYPE_* 一致

    const val CONTENT_CHANGE_TYPE_SUBTREE = 0x00000001
    const val CONTENT_CHANGE_TYPE_TEXT = 0x00000002
    const val CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION = 0x00000004
    const val CONTENT_CHANGE_TYPE_STATE_DESCRIPTION = 0x00000040
}
