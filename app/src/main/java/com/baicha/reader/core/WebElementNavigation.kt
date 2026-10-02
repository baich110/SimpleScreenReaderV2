package com.baicha.reader.core

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 网页元素导航 —— 对应 TalkBack `WebInterfaceUtils` + `CursorGranularityManager.navigateWeb()`
 *
 * 【解决什么问题】
 *   在浏览器 / WebView / 内嵌网页里，
 *   「跳到下一个链接」「跳到下一个标题」如果走普通树遍历，
 *   会停在大量无语义的容器节点上，视障用户要点几十次才能过一段文字。
 *
 *   Android 为此提供了专门的语义化跳转动作：
 *     ACTION_NEXT_HTML_ELEMENT     = 1024
 *     ACTION_PREVIOUS_HTML_ELEMENT = 2048
 *   带上「要跳什么元素」的参数，由 WebView 自己按 HTML 语义找下一个。
 *
 * 【为什么必须用系统动作，不能自己在树上找】
 *   1. WebView 的无障碍树是**惰性展开**的 —— 远处的节点根本不在树里，
 *      自己遍历找不到；
 *   2. HTML 语义（h1~h6、landmark、表格行列）在无障碍树里已被压平，
 *      靠 node 属性重建会错；
 *   3. 系统动作会让 WebView 同步滚动+渲染，自建遍历做不到。
 *
 * 【天坦做法】X6/z 同样是委托系统动作，不自建 HTML 解析。
 */
object WebElementNavigation {

    /** 跳到下一个 HTML 元素 */
    const val ACTION_NEXT_HTML_ELEMENT = 1024

    /** 跳到上一个 HTML 元素 */
    const val ACTION_PREVIOUS_HTML_ELEMENT = 2048

    /**
     * 元素类型参数键 —— 公开 SDK 不可见，按字面量给出。
     * 对应 `AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING`
     */
    const val ARGUMENT_HTML_ELEMENT = "ACTION_ARGUMENT_HTML_ELEMENT_STRING"

    // ==================== 元素类型常量 ====================
    // 取值对齐 TalkBack WebInterfaceUtils（必须是这些大写字面量，
    // WebView 按字符串匹配，写错会静默无动作）

    const val ELEMENT_HEADING = "HEADING"
    const val ELEMENT_LINK = "LINK"
    const val ELEMENT_LIST = "LIST"
    const val ELEMENT_LIST_ITEM = "LIST_ITEM"
    const val ELEMENT_CONTROL = "CONTROL"
    const val ELEMENT_BUTTON = "BUTTON"
    const val ELEMENT_CHECKBOX = "CHECKBOX"
    const val ELEMENT_RADIO = "RADIO"
    const val ELEMENT_TEXT_FIELD = "TEXT_FIELD"
    const val ELEMENT_COMBOBOX = "COMBOBOX"
    const val ELEMENT_GRAPHIC = "GRAPHIC"
    const val ELEMENT_TABLE = "TABLE"
    const val ELEMENT_LANDMARK = "LANDMARK"
    const val ELEMENT_FOCUSABLE = "FOCUSABLE"
    const val ELEMENT_VISITED_LINK = "VISITED_LINK"
    const val ELEMENT_UNVISITED_LINK = "UNVISITED_LINK"

    /**
     * 该节点是否支持 HTML 元素导航。
     *
     * WebView 会同时暴露 NEXT/PREVIOUS 两个动作；
     * 只查一个就够（避免每次遍历整个 actionList 两遍）。
     */
    fun isSupported(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            node.actionList.any { it.id == ACTION_NEXT_HTML_ELEMENT }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 跳到下一个/上一个指定类型的元素。
     *
     * @param node      起始节点
     * @param element   元素类型常量（见上方）
     * @param forward   true 下一个 / false 上一个
     * @return 系统是否接受
     */
    fun navigate(
        node: AccessibilityNodeInfo?,
        element: String,
        forward: Boolean
    ): Boolean {
        if (node == null) return false
        return try {
            val args = Bundle(1)
            args.putString(ARGUMENT_HTML_ELEMENT, element)
            node.performAction(
                if (forward) ACTION_NEXT_HTML_ELEMENT else ACTION_PREVIOUS_HTML_ELEMENT,
                args
            )
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 导航模式 → HTML 元素类型的映射。
     *
     * 对应 TalkBack `CursorGranularityManager.navigateWeb()` 的 switch。
     * 只映射我们支持的导航（未列出的返回 null → 回退普通遍历）。
     *
     * 【与 TalkBack 的差异说明】
     *   TalkBack 支持 h1~h6 分级别跳转（共 6 个粒度），
     *   本读屏把标题做成一档（HEADING），由 WebView 自行按层级推进 ——
     *   这是天坦的做法，减少手势切换次数。
     */
    fun elementFor(mode: NavigationMode): String? = when (mode) {
        NavigationMode.LINKS -> ELEMENT_LINK
        NavigationMode.HEADINGS -> ELEMENT_HEADING
        NavigationMode.CONTROLS -> ELEMENT_CONTROL
        else -> null
    }
}
