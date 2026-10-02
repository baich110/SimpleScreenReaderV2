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
        // ① 优先用事件自带的文本
        val textList = event.text
        if (!textList.isNullOrEmpty()) {
            val joined = textList.joinToString("").trim()
            if (joined.isNotEmpty()) return joined
        }

        // ② 退化：用 fromIndex/toIndex 从节点文本里切片
        val node = event.source ?: return null
        val fullText = node.text?.toString() ?: return null
        val from = event.fromIndex
        val to = event.toIndex
        if (from < 0 || to < from || to > fullText.length) return null
        return fullText.substring(from, to).trim().takeIf { it.isNotEmpty() }
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
