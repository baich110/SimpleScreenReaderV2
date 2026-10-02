package com.baicha.reader.core

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 粒度移动 —— 使用**系统原生 API** 移动光标
 *
 * 对应天坦 `X6/e.java:271` + TalkBack `CursorGranularityManager`
 *
 * ══════════════════════════════════════════════════════════
 * 【原实现的问题：假动作】
 * ══════════════════════════════════════════════════════════
 *
 * 原 `SpeechCompositor.applyGranularity()` 的做法是：
 *   val segments = GranularityNavigator.split(text, level)   // 切分
 *   return segments.joinToString(SEPARATOR)                  // ★ 又拼回去
 *
 * 「按字导航」的实际效果 = 把每个字用分隔符拼起来朗读一遍。
 * **这不是「移动到下一个字」，而是「重新读一遍整段，只是加了分隔符」。**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么必须用系统 API】
 * ══════════════════════════════════════════════════════════
 *
 *   ① **光标位置由 App 维护** —— 我们 split 字符串，App 里的光标根本没动。
 *      用户按「按字下一个」时 App 不知道要动哪里。
 *
 *   ② **App 可能自定义粒度** —— 代码编辑器按 token 移动、表格按单元格移动，
 *      这些我们 split 不出来。
 *
 *   ③ **朗读与光标必须同步** —— 系统发
 *      `TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY` 事件，
 *      我们从事件里读**新光标位置**的文本。
 *
 * ══════════════════════════════════════════════════════════
 * 【天坦的实现（X6/e.java:271）】
 * ══════════════════════════════════════════════════════════
 *
 *   bundleA.putInt("ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT", aVar.f9299c);
 *   if (AbstractC3675b0.c(wVar2, i9, bundleA)) {
 *       // 框架已处理粒度遍历
 *       return 1;
 *   }
 */
object GranularityMover {

    private const val TAG = "GranularityMover"

    /**
     * 项目粒度 → 系统粒度常量
     *
     * 对应 Android `AccessibilityNodeInfo.MOVEMENT_GRANULARITY_*`
     */
    fun toSystemGranularity(level: GranularityNavigator.Level): Int = when (level) {
        GranularityNavigator.Level.CHARACTER -> MOVEMENT_GRANULARITY_CHARACTER
        GranularityNavigator.Level.WORD -> MOVEMENT_GRANULARITY_WORD
        GranularityNavigator.Level.LINE -> MOVEMENT_GRANULARITY_LINE
        GranularityNavigator.Level.PARAGRAPH -> MOVEMENT_GRANULARITY_PARAGRAPH
        else -> 0
    }

    // 系统常量（部分在公开 SDK 中不可见，用数值）
    /** MOVEMENT_GRANULARITY_CHARACTER */
    const val MOVEMENT_GRANULARITY_CHARACTER = 1

    /** MOVEMENT_GRANULARITY_WORD */
    const val MOVEMENT_GRANULARITY_WORD = 2

    /** MOVEMENT_GRANULARITY_LINE */
    const val MOVEMENT_GRANULARITY_LINE = 4

    /** MOVEMENT_GRANULARITY_PARAGRAPH */
    const val MOVEMENT_GRANULARITY_PARAGRAPH = 8

    /**
     * 移动粒度 —— 对应天坦 `AbstractC3675b0.c(node, action, bundle)`
     *
     * @param node 当前焦点节点（应为可编辑）
     * @param level 粒度级别
     * @param forward true = 下一个，false = 上一个
     * @return true = 系统已处理（App 支持该粒度）
     */
    fun move(
        node: AccessibilityNodeInfo?,
        level: GranularityNavigator.Level,
        forward: Boolean
    ): Boolean {
        if (node == null) return false

        val granularity = toSystemGranularity(level)
        if (granularity == 0) return false

        val action = if (forward) ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        else ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY

        return try {
            val args = Bundle().apply {
                putInt(ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
                // 天坦：不扩展选区（移动而非选中）
                putBoolean(ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
            }
            val ok = node.performAction(action, args)
            if (!ok) {
                ReaderLog.d(TAG, "系统未处理粒度移动（granularity=$granularity）")
            }
            ok
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "粒度移动失败: ${e.message}")
            false
        }
    }

    /**
     * 节点是否支持粒度移动
     */
    fun isSupported(node: AccessibilityNodeInfo?, level: GranularityNavigator.Level): Boolean {
        if (node == null) return false
        if (toSystemGranularity(level) == 0) return false
        return try {
            node.actionList.any { it.id == ACTION_NEXT_AT_MOVEMENT_GRANULARITY }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 从「粒度遍历事件」里提取要朗读的文本 —— 对应
     * `TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY` 事件
     *
     * 系统在 App 移动光标后会发这个事件，
     * 我们从事件里读出**新光标位置**对应的文本片段。
     *
     * @param event 粒度遍历事件
     * @return 应朗读的文本；null = 无有效内容
     */
    fun extractTraversedText(event: android.view.accessibility.AccessibilityEvent): String? {
        // ══════════════════════════════════════════════════════════
        // 【修正】原来第一优先是「用 event.text」—— 那是错的。
        //
        // TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY 的 event.text
        // 携带的是**整个节点的文本**，不是当前粒度单元。
        // 直接用它，等于「用户按『下一个字』，读屏念整段」——
        // 这正是要修的 bug，用错误的第一优先分支会把它原样保留。
        //
        // 【两方一致的做法：只认 fromIndex/toIndex 切片】
        //   TalkBack `TextEventInterpreter:568` 与 `getSubsequenceWithSpans()`：
        //     只有 `from >= 0 && to <= textLength` 时才
        //     `text.subSequence(from, to)`，**从不采信 event.text 当单元**。
        //   天坦同样按索引切。
        //
        // 故这里改为**只走切片路径**，event.text 仅作最后的兜底
        // （且加了长度校验，避免把整段当单元）。
        // ══════════════════════════════════════════════════════════
        val node = event.source

        val fullText = try {
            node?.text?.toString()
        } catch (e: Throwable) {
            null
        }.orEmpty()

        val eventText = try {
            event.text?.joinToString("")
        } catch (e: Throwable) {
            null
        }

        return sliceUnit(
            fullText = fullText,
            from = event.fromIndex,
            to = event.toIndex,
            eventText = eventText
        )
    }

    /**
     * 粒度单元切片 —— **纯函数，无 Android 依赖**
     *
     * 【为什么抽出来】
     *   `AccessibilityEvent.obtain()` 在 JVM 单测里返回 null
     *   （Android SDK 是空壳），导致整个提取逻辑无法测试。
     *   抽成只吃 String/Int 的纯函数后，判定规则可被逐条锁定。
     *
     * 判定顺序（对齐 TalkBack `TextEventInterpreter:568`
     * 与 `getSubsequenceWithSpans()`）：
     *   ① 有完整文本 + 索引合法 → 按 [from, to) 切片
     *   ② event.text 明显短于全文 → 采信它（真机的退化路径）
     *   ③ 其余 → null（宁可不读，也不念整段）
     *
     * 【为什么不采信「event.text == 全文」】
     *   那就是本 bug 的成因。返回全文等于没修。
     *
     * @param fullText  节点完整文本（空串表示取不到）
     * @param from      选区起点（含）
     * @param to        选区终点（不含）
     * @param eventText 事件自带文本（真机上常等于全文）
     * @return 粒度单元；无有效单元时 null
     */
    fun sliceUnit(
        fullText: String,
        from: Int,
        to: Int,
        eventText: String?
    ): String? {
        // ① 索引切片 —— TalkBack 的边界校验：
        //    from < 0 || text.length < to || to < from → 放弃
        if (fullText.isNotEmpty() &&
            from >= 0 && to <= fullText.length && to > from
        ) {
            val sliced = fullText.substring(from, to).trim()
            if (sliced.isNotEmpty()) return sliced
        }

        // ② event.text 兜底：只有确实短于全文才可能是单元
        val et = eventText?.trim()
        if (et.isNullOrEmpty()) return null

        // 取不到全文时无法比较 → 保守采信（真机上比返回 null 更有用）
        if (fullText.isEmpty()) return et

        // 等于或超过全文长度 → 它是整段，不是单元 → 丢弃
        if (et.length >= fullText.length) return null

        return et
    }

    // ==================== 系统动作常量 ====================
    //
    // 这些常量在部分 SDK 版本中不可见，用数值定义并注明来源。

    /** ACTION_NEXT_AT_MOVEMENT_GRANULARITY */
    const val ACTION_NEXT_AT_MOVEMENT_GRANULARITY = 256

    /** ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY */
    const val ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY = 512

    /** 粒度参数 key */
    const val ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT =
        "ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT"

    /** 扩展选区参数 key */
    const val ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN =
        "ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN"
}
