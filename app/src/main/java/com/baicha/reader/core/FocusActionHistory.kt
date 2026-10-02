package com.baicha.reader.core

/**
 * 焦点动作记录 —— 对标 TalkBack
 * `focusmanagement/record/FocusActionRecord.java`(262 行)
 * 与 `FocusActionInfo.java`(293 行)
 *
 * 一条记录 = 「谁、把焦点移到了哪个节点、什么时候、什么类型」。
 *
 * 【为什么记 NodePath 而不是节点引用】
 *   节点会被 RecyclerView 回收，引用失效后指向别的 item。
 *   [NodePath] 是特征描述，可在新树里重新定位 ——
 *   这正是 A0（路径记忆）产出的东西，也是 A6 依赖 A0 的原因。
 */
data class FocusActionRecord(
    /** 目标节点的路径特征 —— 抗节点回收 */
    val nodePath: NodePath?,
    /** 窗口 ID —— 用于「这个窗口上次焦点在哪」 */
    val windowId: Int,
    /** 动作来源 */
    val source: Int,
    /** 动作发生时间（`SystemClock.uptimeMillis()`） */
    val actionTime: Long,
    /** 节点角色 */
    val role: Int,
    /** 是否可编辑节点 —— 供 `lastEditable()` 用 */
    val isEditable: Boolean,
    /**
     * 文本指纹 —— 用于「事件与动作是否指同一节点」的快速比对。
     *
     * 【为什么不用节点对象比对】记录要长期持有（可能在几百毫秒后
     * 才用于匹配），存节点引用会拖住它不被回收。
     */
    val textFingerprint: Int
) {
    /** 是否由用户主动操作触发（导航/触摸探索） */
    val isUserInitiated: Boolean
        get() = source == FOCUS_SOURCE_LOGICAL_NAVIGATION ||
            source == FOCUS_SOURCE_TOUCH_EXPLORATION

    companion object {
        /** 逻辑导航（滑动手势） */
        const val FOCUS_SOURCE_LOGICAL_NAVIGATION = 1

        /** 触摸探索 */
        const val FOCUS_SOURCE_TOUCH_EXPLORATION = 2

        /** 恢复焦点 |
         * 对应 `ScreenStateRecovery.FOCUS_SOURCE_*` 的语义 */
        const val FOCUS_SOURCE_RESTORE = 3

        /** 其他/未知 */
        const val FOCUS_SOURCE_UNKNOWN = 0
    }
}

/**
 * 焦点动作历史 —— 对标 TalkBack `AccessibilityFocusActionHistory`(445 行)
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * 白茶原来只有一个 `lastFocusActionTime` 字段。后果：
 *
 *   ① 焦点乱跳时**无法追溯**「是谁移的」——用户操作？App？系统？
 *   ② 想「回到上一个焦点」→ 做不到（没有上一个）
 *   ③ 多窗口下「这个窗口上次焦点在哪」→ 不知道
 *   ④ 竞态判定只能比时间戳，无法确认「是不是同一个节点」
 *
 * ══════════════════════════════════════════════════════════
 * 【两方对标（已读源码）】
 * ══════════════════════════════════════════════════════════
 *
 * **TalkBack** `AccessibilityFocusActionHistory.java:115~160`：
 *   ```java
 *   static final int MAXIMUM_WINDOW_MAP_SIZE = 10;   // 每窗口记录的窗口数上限
 *   static final int MAXIMUM_RECORD_QUEUE_SIZE = 5;  // 最近记录条数
 *   static final int TIMEOUT_TOLERANCE_MS = 300;     // 时间匹配窗口
 *   static final int WEB_TIMEOUT_TOLERANCE_MS = 500; // 网页放宽
 *   static final int TV_TIMEOUT_TOLERANCE_MS = 400;  // TV 放宽
 *   ```
 *   两个结构：
 *     · `Deque<FocusActionRecord>` —— 最近 N 条（默认 5）
 *     · `LruCache<WindowIdentifier, FocusActionRecord>` —— 每窗口最后一条（默认 10 个窗口）
 *   匹配逻辑（`:294~330`）：**节点相等 + 时间在容差内**
 *
 * **天坦** ❌ 无对应类 —— 它的竞态判定靠单个时间戳
 *   （与白茶原实现同级）。
 *
 * 【A/B 取舍】天坦无对应实现，直接对标 TalkBack。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么队列只有 5 条】
 * ══════════════════════════════════════════════════════════
 *
 * 它服务的场景是「刚刚这次焦点变化是不是我造成的」——
 * 只需覆盖最近几次。存更多没有用途，且会让节点引用长期滞留。
 *
 * ══════════════════════════════════════════════════════════
 * 【已验证 / 未验证】
 * ══════════════════════════════════════════════════════════
 *   ✅ 已验证：入队/淘汰、窗口映射、三类查询、时间容差匹配
 *   ❌ 测不到：真实竞态下的匹配率、多窗口真机行为
 */
class FocusActionHistory(
    /** 最近记录条数上限 —— 对齐 TalkBack `MAXIMUM_RECORD_QUEUE_SIZE = 5` */
    private val maxRecords: Int = MAXIMUM_RECORD_QUEUE_SIZE,
    /** 每窗口映射上限 —— 对齐 TalkBack `MAXIMUM_WINDOW_MAP_SIZE = 10` */
    private val maxWindows: Int = MAXIMUM_WINDOW_MAP_SIZE
) {

    /** 最近的记录，**最新在前** */
    private val records = ArrayDeque<FocusActionRecord>(maxRecords)

    /**
     * 每个窗口的最后一条记录。
     *
     * 【为什么用 map 而不是遍历 records 找】
     *   records 只有 5 条，若用户在 A/B/C 三个窗口各动过一次，
     *   A 的记录早被挤出去了 —— 但「A 窗口上次焦点在哪」仍应能回答。
     *   这正是 TalkBack 单独维护 LruCache 的原因。
     */
    private val windowRecords = LinkedHashMap<Int, FocusActionRecord>()

    /** 最近一次可编辑焦点的记录 */
    private var lastEditable: FocusActionRecord? = null

    /** 总记录数（诊断用） */
    var totalRecorded = 0L
        private set

    /**
     * 记录一次焦点动作。
     *
     * 对应 TalkBack `onAccessibilityFocusAction()`（`:197`）。
     */
    fun record(record: FocusActionRecord) {
        totalRecorded++

        // ① 队列：最新在前，超限丢最旧
        records.addFirst(record)
        while (records.size > maxRecords) {
            records.removeLast()
        }

        // ② 按窗口：覆盖该窗口的旧记录；超限丢最久未访问的
        windowRecords.remove(record.windowId)   // 重新插入以更新 LRU 顺序
        windowRecords[record.windowId] = record
        while (windowRecords.size > maxWindows) {
            val eldest = windowRecords.keys.firstOrNull() ?: break
            windowRecords.remove(eldest)
        }

        // ③ 可编辑焦点单独记
        if (record.isEditable) {
            lastEditable = record
        } else if (lastEditable?.windowId == record.windowId) {
            // 同窗口内焦点离开可编辑节点 → 清掉旧记录，
            // 否则「回到可编辑框」会跳到一个已经不再编辑的位置
            lastEditable = null
        }
    }

    /**
     * 给最近一条记录补填路径 —— 供延迟采集使用。
     *
     * 【为什么需要】`NodePath.of()` 会遍历父链做数百次跨进程
     * `getChild()`，不能在触摸热路径调用（真机 P0 的根因）。
     * 故记录时 nodePath 为 null，采集完成后补填。
     *
     * 【为什么只填最近一条】采集有防抖 —— 补填时「最近一条」
     * 就是发起采集时的那条（期间若有新记录，防抖会重新计时）。
     */
    fun attachPathToLatest(path: NodePath) {
        val latest = records.firstOrNull() ?: return
        records.removeFirst()
        records.addFirst(latest.copy(nodePath = path))
    }

    /** 最近一次焦点动作 —— 对标 `getLastFocusActionRecord()`（`:268`） */
    fun last(): FocusActionRecord? = records.firstOrNull()

    /**
     * 指定窗口内最近一次 —— 对标 `getLastFocusActionRecordInWindow(int)`（`:253`）
     *
     * 用途：`ScreenStateRecovery` 恢复时优先查本窗口的历史。
     */
    fun lastInWindow(windowId: Int): FocusActionRecord? = windowRecords[windowId]

    /** 最近一次可编辑焦点 —— 对标 `getLastEditableFocusActionRecord()`（`:278`） */
    fun lastEditable(): FocusActionRecord? = lastEditable

    /** 队列快照（供测试与诊断） */
    fun records(): List<FocusActionRecord> = records.toList()

    fun size(): Int = records.size

    fun windowCount(): Int = windowRecords.size

    /**
     * 判断「某个焦点事件是否由我们发起」——
     * 对标 TalkBack `matchFocusActionRecordFromEvent()`（`:294~330`）
     *
     * 【匹配条件】节点指纹相同 **且** 时间在容差内。
     *
     * 【为什么要「节点相同」这一条】
     *   只比时间会误判：用户滑到 A 节点（记录时间 T），
     *   100ms 后 App 自己把焦点移到 B —— 时间在容差内，
     *   但节点不同，不该认作「我们的动作」。
     *   原来的实现只比时间，这正是本项要修的问题。
     *
     * @param textFingerprint 事件节点的文本指纹
     * @param eventTime       事件时间
     * @param toleranceMs     时间容差（默认 [TIMEOUT_TOLERANCE_MS]）
     * @return 匹配到的记录；null = 不是我们的动作
     */
    fun match(
        textFingerprint: Int,
        eventTime: Long,
        toleranceMs: Int = TIMEOUT_TOLERANCE_MS
    ): FocusActionRecord? {
        // 从最新往旧找 —— 对齐 TalkBack 的 descendingIterator
        for (record in records) {
            val timeDiff = eventTime - record.actionTime
            val timeMatches = timeDiff >= 0 && timeDiff < toleranceMs
            if (record.textFingerprint == textFingerprint && timeMatches) {
                return record
            }
        }
        return null
    }

    /** 是否由我们发起 —— `match()` 的布尔包装 */
    fun isOurs(
        textFingerprint: Int,
        eventTime: Long,
        toleranceMs: Int = TIMEOUT_TOLERANCE_MS
    ): Boolean = match(textFingerprint, eventTime, toleranceMs) != null

    fun reset() {
        records.clear()
        windowRecords.clear()
        lastEditable = null
        totalRecorded = 0
    }

    fun stats(): String =
        "历史 ${records.size} 条 / 窗口 ${windowRecords.size} 个 / 累计 $totalRecorded"

    companion object {
        /** 最近记录条数上限 —— TalkBack `AccessibilityFocusActionHistory:120` */
        const val MAXIMUM_RECORD_QUEUE_SIZE = 5

        /** 窗口映射上限 —— TalkBack `AccessibilityFocusActionHistory:117` */
        const val MAXIMUM_WINDOW_MAP_SIZE = 10

        /** 时间匹配容差 —— TalkBack `AccessibilityFocusActionHistory:127` */
        const val TIMEOUT_TOLERANCE_MS = 300

        /**
         * 网页场景的容差 —— TalkBack `:134`
         *
         * TalkBack 原注释：
         *   "It needs more time for webview to focus on the new element."
         * WebView 的元素聚焦链路更长，用普通容差会匹配不上。
         */
        const val WEB_TIMEOUT_TOLERANCE_MS = 500

        /** TV 场景容差 —— TalkBack `:143`（Sony ATV 的焦点动画较慢） */
        const val TV_TIMEOUT_TOLERANCE_MS = 400
    }
}
