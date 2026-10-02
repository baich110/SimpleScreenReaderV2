package com.baicha.reader.core

/**
 * 导航标签的「原生 / 网页」二分 —— 对应天坦 `X6/z.b.f()`
 *
 * ══════════════════════════════════════════════════════════
 * 【天坦怎么做的（X6/z.java:409~421）】
 * ══════════════════════════════════════════════════════════
 *
 * ```java
 * public final int f(Context context, boolean useWeb) {
 *     int i9 = switch-map[ordinal()];
 *     if (i9 != 1 && i9 != 2 && i9 != 3) return this.f10744b;   // 非这三种 → 通用标签
 *     if (!useWeb) return this.f10744b;                          // 非网页 → 通用标签
 *     // 网页场景：把资源名后缀 _web 换掉，拿 granularity_*_web
 *     String name = resources.getResourceName(this.f10744b);
 *     return resources.getIdentifier(name + "_web", ...);
 * }
 * ```
 *
 * 只有 **链接 / 控件 / 标题** 三种导航有网页专用标签：
 *   granularity_native_link    ↔ granularity_web_link
 *   granularity_native_control ↔ granularity_web_control
 *   granularity_native_heading ↔ granularity_web_heading
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么这个区分重要（不是文字游戏）】
 * ══════════════════════════════════════════════════════════
 *
 * 网页里的「链接」和原生 App 里的「链接」**行为不同**：
 *
 *   - 原生链接：跳到下一个可点击的文本控件
 *   - 网页链接：走 ACTION_NEXT_HTML_ELEMENT，由 WebView 按 HTML <a> 语义找
 *
 * 视障用户听到「链接」时会预期某种行为。如果在网页里切到「链接」，
 * 实际走的是 HTML 语义跳转（能穿过一整个导航栏直接到正文链接），
 * 而念的还是「链接」这个词，用户无法判断当前是哪种模式。
 *
 * 天坦用「网页链接」「网页标题」这样的标签让用户**听出模式**。
 *
 * ══════════════════════════════════════════════════════════
 * 【TalkBack 的做法】
 * ══════════════════════════════════════════════════════════
 *
 * TalkBack 用 `CursorGranularity` 枚举区分，网页粒度是**独立枚举项**
 * （HEADING / LINK / CONTROL 与 WEB_LIST / WEB_LANDMARK / H1~H6 ...），
 * 在 `getSupportedGranularities()` 里按 `hasWebContent` 动态过滤，
 * 即「网页里优先暴露网页粒度，原生里暴露原生粒度」。
 *
 * 【抉择：选天坦】
 *   TalkBack 的做法要求把粒度表整个翻倍（原生一组、网页一组），
 *   且要用 `hasNavigableWebContent()` 动态计算可用集合 ——
 *   对白茶现有的单层 `GranularityNavigator` 是伤筋动骨的改造。
 *   天坦的做法只改**标签文案**，不动结构，收益（用户能听出模式）相同。
 */
object NavigationLabel {

    /**
     * 取导航标签。
     *
     * @param mode    导航模式
     * @param inWeb   当前是否在网页上下文
     */
    fun of(mode: NavigationMode, inWeb: Boolean): String {
        if (!inWeb) return mode.label
        return when (mode) {
            NavigationMode.LINKS -> "网页链接"
            NavigationMode.HEADINGS -> "网页标题"
            NavigationMode.CONTROLS -> "网页控件"
            // 其余导航无网页专用语义 —— 与天坦一致，直接返回通用标签
            else -> mode.label
        }
    }

    /**
     * 该导航是否有网页专用语义。
     *
     * 对应天坦 `f()` 里 `if (i9 != 1 && i9 != 2 && i9 != 3) return 通用标签`
     * 的那个判定 —— 只有这 3 种返回 true。
     */
    fun hasWebVariant(mode: NavigationMode): Boolean = when (mode) {
        NavigationMode.LINKS,
        NavigationMode.HEADINGS,
        NavigationMode.CONTROLS -> true
        else -> false
    }
}
