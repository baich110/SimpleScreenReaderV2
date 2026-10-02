package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 导航模式 —— 对应天坦 X6/z 的内部类 b（26 种导航）
 *
 * 【为什么导航是「框架层」而不是「功能层」】
 *
 * 天坦的导航不是一个"设置项"，而是**遍历分发的核心抽象**：
 *
 *   用户单指上/下滑 → 切换导航模式
 *        ↓
 *   当前导航模式 → 决定用哪种遍历策略 + 哪种过滤器
 *        ↓
 *   M6.h.D(direction, ..., strategyType)   ← strategyType 来自导航的类型码
 *        ↓
 *   C1427v.h(strategyType, ...)             ← 工厂按类型码建遍历器
 *
 * 也就是说：**导航模式 = 遍历策略 + 节点过滤 + 播报格式 的三元组**。
 * 没有这层，遍历策略就无从选择；所以它属于框架层。
 *
 * 【天坦的 26 种（每个带 3 个 int：ordinal / labelRes / typeCode）】
 *
 *   0  GRANULARITY_DEFAULT          默认        类型码 0
 *   1  GRANULARITY_CHARACTERS       字符        类型码 1
 *   2  GRANULARITY_WORDS            字词        类型码 2
 *   3  GRANULARITY_SENTENCE         逐句        类型码 3
 *   4  GRANULARITY_PARAGRAPHS       段落        类型码 4
 *   5  GRANULARITY_LINES            逐行        类型码 5
 *   6  GRANULARITY_LINKS            链接        类型码 6
 *   7  GRANULARITY_HEADINGS         标题        类型码 7
 *   8  GRANULARITY_CONTROLS         控件        类型码 8
 *   9  SCROLLABLE_WIDGET            可滚动控件   类型码 12
 *   10 GUIDEPOST                     路标        类型码 13
 *   11 SELECTOR_ACTIONS              操作选择器   类型码 30
 *   12 SELECTOR_EDIT                 编辑        类型码 32
 *   13 SELECTOR_COPY                 复制        类型码 10
 *   14 SELECTOR_OCR                  OCR        类型码 14
 *   15 LIST_TRAVERSAL                列表遍历     类型码 16
 *   16 ROW_AND_COLUM_TRAVERSAL       行列遍历     类型码 31
 *   17 VERTICAL_TRAVERSAL            垂直遍历     类型码 17
 *   18 SPLIT_TRAVERSAL               拆分遍历     类型码 18
 *   19 CONTINUES_READING             连续朗读     类型码 11
 *   20 SELECTOR_MAGNIFICATION        放大镜       类型码 19
 *   21 SELECTOR_TRANSLATE            翻译        类型码 15
 *   22 WINDOWS_NAVIGATION            窗口导航     类型码 9
 *   23 SPEECH_RATE                   语速        类型码 20
 *   24 SPEECH_TIMES                  语速倍数     类型码 21
 *   25 VOLUME_CONTROL                音量        类型码 22
 *   26 VIDEO_BROWSE                  视频浏览     类型码 23
 *
 * 【本实现的范围】
 *   框架层建全 26 种的定义 + 分发 + 切换机制。
 *   其中「节点移动型」导航已接真实遍历策略；
 *   「工具型」导航（OCR/翻译/放大镜等）依赖功能层，此处标记为待实现，
 *   但**框架接口已经预留**，功能层直接填实现即可。
 */
enum class NavigationMode(
    /** 中文名（对齐天坦资源表） */
    val label: String,

    /**
     * 导航标识码 —— 天坦 `X6/z.b` 的导航码
     *
     * ⚠️ **这不是遍历器类型码！**
     *   天坦 `M6/h.D(i9, ..., i10)` 里：
     *     i9  = 导航码（本字段）
     *     i10 = 遍历模式（见 [traversalType]）
     *   两者是**独立的参数**。
     *
     *   把导航码当遍历器码用会导致 3 个导航行为完全错误：
     *     WORDS(2)    → 撞上 TYPE_CACHED(2)   → 用了缓存遍历器
     *     CONTROLS(8) → 撞上 TYPE_GRID(8)     → 用了网格遍历
     *     VERTICAL(17)→ 无对应 → 落到 default
     */
    val typeCode: Int,

    /**
     * 遍历器类型码 —— 传给 [TraversalStrategyFactory]，对应天坦 `C1427v.h()` 的 `i10`
     *
     * 取值语义（天坦）：
     *   0 → 默认遍历（滑动浏览用）
     *   2 → 带锚点的遍历
     *   8 → 网格遍历
     *
     * 本项目的扩展：另有 TYPE_LIST / TYPE_ROW_COLUMN / TYPE_VERTICAL / TYPE_SPATIAL
     */
    val traversalType: Int,

    /** 导航类别 */
    val kind: NavigationKind,

    /** 是否已实现（未实现的框架会跳过，不影响其他导航） */
    val implemented: Boolean = true
) {

    // ==================== 粒度类（不改变遍历，只改变文本切分）====================
    DEFAULT("默认", 0, 0, NavigationKind.GRANULARITY),
    CHARACTERS("字符", 1, 0, NavigationKind.GRANULARITY),
    WORDS("字词", 2, 0, NavigationKind.GRANULARITY),
    SENTENCE("逐句", 3, 0, NavigationKind.GRANULARITY),
    PARAGRAPHS("段落", 4, 0, NavigationKind.GRANULARITY),
    LINES("逐行", 5, 0, NavigationKind.GRANULARITY),

    // ==================== 过滤类（只停在特定节点）====================
    LINKS("链接", 6, 0, NavigationKind.FILTER),
    HEADINGS("标题", 7, 0, NavigationKind.FILTER),
    CONTROLS("控件", 8, 0, NavigationKind.FILTER),
    SCROLLABLE_WIDGET("可滚动控件", 12, 0, NavigationKind.FILTER),

    // ==================== 特殊标记 ====================
    GUIDEPOST("路标", 13, 0, NavigationKind.MARKER, implemented = false),

    // ==================== 选择器类（对当前节点做操作）====================
    SELECTOR_ACTIONS("操作选择器", 30, 0, NavigationKind.SELECTOR, implemented = false),
    SELECTOR_EDIT("编辑", 32, 0, NavigationKind.SELECTOR, implemented = false),
    SELECTOR_COPY("复制选择器", 10, 0, NavigationKind.SELECTOR, implemented = false),
    SELECTOR_OCR("OCR 识别", 14, 0, NavigationKind.SELECTOR, implemented = false),
    SELECTOR_MAGNIFICATION("放大镜", 19, 0, NavigationKind.SELECTOR, implemented = false),
    SELECTOR_TRANSLATE("翻译", 15, 0, NavigationKind.SELECTOR, implemented = false),

    // ==================== 遍历结构类（★ 真正改变遍历方式）====================
    //
    // ⚠️ 注意 traversalType 与 typeCode **不同**：
    //   天坦把它们作为独立参数传给 M6/h.D(i9, ..., i10)
    //   若混用会导致：WORDS(2) 撞 TYPE_CACHED(2)、CONTROLS(8) 撞 TYPE_GRID(8)
    LIST_TRAVERSAL("列表遍历", 16, 16, NavigationKind.TRAVERSAL),
    ROW_AND_COLUMN_TRAVERSAL("行列遍历", 31, 31, NavigationKind.TRAVERSAL),
    VERTICAL_TRAVERSAL("垂直遍历", 17, 17, NavigationKind.TRAVERSAL),
    SPLIT_TRAVERSAL("拆分遍历", 18, 0, NavigationKind.TRAVERSAL, implemented = false),
    WINDOWS_NAVIGATION("窗口导航", 9, 0, NavigationKind.TRAVERSAL),

    // ==================== 滚动定位（P1-1）====================
    //
    // 对应 TalkBack `FocusProcessorForLogicalNavigation
    //   .performActionScrollToPosition()`。
    //
    // 与「遍历」的区别：不逐项移动焦点，而是让容器一步跳到目标位置。
    // 长列表里遍历到第 1000 项要点 1000 次，而定位只需一次。
    SCROLL_TOP("跳到顶部", 33, 0, NavigationKind.SCROLL_JUMP),
    SCROLL_BOTTOM("跳到底部", 34, 0, NavigationKind.SCROLL_JUMP),

    // ==================== 自动类 ====================
    CONTINUES_READING("连续朗读", 11, 0, NavigationKind.AUTO, implemented = false),

    // ==================== 工具类（调节设置）====================
    SPEECH_RATE("语速", 20, 0, NavigationKind.TOOL, implemented = false),
    SPEECH_TIMES("语速倍数", 21, 0, NavigationKind.TOOL, implemented = false),
    VOLUME_CONTROL("音量", 22, 0, NavigationKind.TOOL, implemented = false),
    VIDEO_BROWSE("视频浏览", 23, 0, NavigationKind.TOOL, implemented = false);

    /** 当前导航是否使用遍历结构（用于区分"换遍历器"与"只换过滤器"）*/
    val usesTraversal: Boolean
        get() = traversalType != 0

    companion object {
        /**
         * 按类型码查导航 —— 天坦 `b.b()` 的等价
         *
         * 用途：从持久化配置恢复导航选择时，
         * 存的是 typeCode（稳定），恢复时映射回枚举。
         *
         * 当前未被调用 —— 因为导航选择的持久化尚未做（属功能层）。
         * 保留此接口是为了让持久化实现时不必再改本类。
         */
        fun fromTypeCode(code: Int): NavigationMode? =
            values().firstOrNull { it.typeCode == code }

        /** 可切换的导航（已实现的） */
        val switchable: List<NavigationMode> by lazy { values().filter { it.implemented } }

        /**
         * 循环切换到下一个导航 —— 天坦的单指上/下滑切换
         *
         * 只在「已实现」的导航间循环，避免切到空实现导致"卡住"。
         */
        fun next(current: NavigationMode): NavigationMode {
            val list = switchable
            if (list.isEmpty()) return DEFAULT
            val idx = list.indexOf(current)
            return if (idx < 0) list.first()
            else list[(idx + 1) % list.size]
        }

        fun previous(current: NavigationMode): NavigationMode {
            val list = switchable
            if (list.isEmpty()) return DEFAULT
            val idx = list.indexOf(current)
            return if (idx < 0) list.first()
            else list[(idx - 1 + list.size) % list.size]
        }
    }
}

/**
 * 导航类别 —— 决定这个导航"怎么工作"
 */
enum class NavigationKind {
    /** 粒度：对当前节点的文本细分后朗读 */
    GRANULARITY,

    /** 过滤：遍历时只停在满足条件的节点 */
    FILTER,

    /** 遍历结构：换一种树遍历方式 */
    TRAVERSAL,

    /** 选择器：对当前节点执行某种操作 */
    SELECTOR,

    /** 自动：自动连续朗读 */
    AUTO,

    /** 工具：调节语音/音量等设置 */
    TOOL,

    /** 标记：用户自定义的路标 */
    MARKER,

    /**
     * 滚动定位：让可滚动容器一步跳到顶部/底部
     *
     * 对应 TalkBack ACTION_SCROLL_TO_POSITION 路径。
     * 与 TRAVERSAL 的区别：不移动焦点到相邻节点，
     * 而是改变「容器看哪里」，焦点随后由滚动事件回带。
     */
    SCROLL_JUMP
}

/**
 * 导航管理器 —— 对应天坦 X6/z（2133 行）的框架部分
 *
 * 【职责】
 *   ① 持有当前导航模式
 *   ② 切换导航（手势触发）
 *   ③ 为当前导航提供「遍历过滤器」
 *   ④ 为当前导航提供「文本处理方式」
 *
 * 【高性能设计】
 *   ① **过滤器缓存**：每种导航的过滤器只建一次，切换时复用
 *   ② **无锁读**：current 是 @Volatile，读路径无锁
 *   ③ **切换零分配**：枚举 + 缓存查表
 */
class NavigationManager {

    /** 当前导航模式 */
    @Volatile
    var current: NavigationMode = NavigationMode.DEFAULT
        private set

    /** 切换回调（用于播报导航名） */
    @Volatile
    var onModeChanged: ((NavigationMode) -> Unit)? = null

    /**
     * 未实现导航的切换尝试回调
     *
     * 【为什么需要】
     *   原先 `setMode()` 对未实现的导航**静默拒绝**（只写日志），
     *   用户做了手势却什么都没发生 —— 以为读屏坏了。
     *
     *   现在通过这个回调让服务播报「XXX 尚未实现」，给出明确反馈。
     */
    @Volatile
    var onUnimplemented: ((NavigationMode) -> Unit)? = null

    /** 各导航的过滤器缓存（惰性构建，切换时复用） */
    private val filterCache = HashMap<NavigationMode, (AccessibilityNodeInfo) -> Boolean>(8)

    /**
     * 恢复导航（P1-5，不触发 [onModeChanged]）
     *
     * 【与 setMode 的区别】
     *   setMode 是「用户主动切换」——要播报、要震动、要持久化。
     *   restore 是「服务启动时读回配置」——不应有任何用户可感知反馈，
     *   否则每次开机都会莫名念一遍导航名。
     *
     * 【为什么不做 implemented 校验】
     *   存进去的一定是当次实现过的导航；若版本降级导致某导航消失，
     *   `NavigationStore.load()` 已回退 DEFAULT，这里拿到的一定合法。
     *   再校验一次反而会在「新增导航后又降级」时把用户配置吞掉。
     */
    fun restore(mode: NavigationMode) {
        if (mode == current) return
        if (!mode.implemented) return   // 保险：不恢复不可用导航
        current = mode
        ReaderLog.i("NavigationMode", "恢复导航：${mode.label}（类型码 ${mode.typeCode}）")
    }

    /**
     * 切换导航
     */
    fun setMode(mode: NavigationMode): Boolean {
        if (mode == current) return false
        if (!mode.implemented) {
            ReaderLog.d("NavigationMode", "${mode.label} 尚未实现，拒绝切换")
            // ★ 给用户反馈，而不是静默拒绝
            onUnimplemented?.invoke(mode)
            return false
        }
        current = mode
        onModeChanged?.invoke(mode)
        ReaderLog.i("NavigationMode", "切换到：${mode.label}（类型码 ${mode.typeCode}）")
        return true
    }

    /** 下一个导航 */
    fun next(): NavigationMode {
        val m = NavigationMode.next(current)
        setMode(m)
        return current
    }

    /** 上一个导航 */
    fun previous(): NavigationMode {
        val m = NavigationMode.previous(current)
        setMode(m)
        return current
    }

    /**
     * 取当前导航对应的遍历策略类型码
     *
     * 这个码会传给 TraversalStrategyFactory 决定用哪种遍历器。
     */
    fun strategyTypeCode(): Int = current.typeCode

    /**
     * 取当前导航的遍历过滤器
     *
     * 不同导航只停在不同的节点上：
     *   - 链接导航 → 只停在含链接的节点
     *   - 标题导航 → 只停在标题节点
     *   - 控件导航 → 只停在交互控件
     *   - 默认     → 全部可聚焦节点
     */
    fun filter(): (AccessibilityNodeInfo) -> Boolean {
        return filterCache.getOrPut(current) { buildFilter(current) }
    }

    /**
     * 构建某导航的过滤器 —— 用可组合的 [NodeFilter]（对应天坦 `w7/M`）
     *
     * 【为什么改用 NodeFilter 而不是裸 lambda】
     *   ① 组合语义清晰：`NodeFilter.HEADING.and(NodeFilter.VISIBLE)`
     *   ② 可加缓存：`.cached()` 把结果挂到 NodeCache
     *   ③ 可诊断：每个过滤器有 name，日志可读
     */
    private fun buildFilter(mode: NavigationMode): (AccessibilityNodeInfo) -> Boolean {
        val filter: NodeFilter = when (mode) {
            NavigationMode.LINKS -> LINK
            NavigationMode.HEADINGS -> NodeFilter.HEADING
            NavigationMode.CONTROLS -> NodeFilter.CONTROL
            NavigationMode.SCROLLABLE_WIDGET -> NodeFilter.SCROLLABLE
            else -> NodeFilter.FOCUSABLE
        }
        return filter.asPredicate()
    }

    /** 链接过滤器 —— 天坦 A0.d()：含 2048 动作或 WebView 角色 */
    private val LINK: NodeFilter = object : NodeFilter() {
        override fun accept(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (NodeRoleHelper.roleOf(node) == NodeRoleHelper.ROLE_WEB_VIEW) return true
            return try {
                node.actionList.any { it.id == 2048 }
            } catch (e: Throwable) {
                false
            }
        }

        override val name: String get() = "链接"
    }

    // ==================== 节点条件判定 ====================
    //
    // 【重构说明】原先这里有 4 个手写判定方法：
    //   hasLinkAction / isHeading / isControl / isScrollable
    // 现已统一收敛到 `NodeFilter` 的预置过滤器（HEADING / CONTROL / SCROLLABLE
    // 与本节自定义的 LINK），好处：
    //   ① 可组合（and/or/not），不再每处手写 `&&` 拼接
    //   ② 可加缓存（`.cached()`）
    //   ③ 有 name，日志可读
    // 删除这 4 个方法是为了避免「两套判定并存」导致的行为漂移。

    /**
     * 当前导航是否为「粒度类」
     *
     * 粒度类导航不改变遍历，只改变文本朗读方式。
     */
    /**
     * 当前导航是否为「粒度类」
     *
     * 粒度类导航不改变遍历，只改变文本朗读方式。
     *
     * 当前未被调用 —— `granularityLevel()` 已覆盖实际需求
     * （服务直接用级别值，不需要先问"是不是粒度类"）。
     * 保留是为了语义完整性，供将来做"导航类切换时的差异化处理"。
     */
    fun isGranularityMode(): Boolean = current.kind == NavigationKind.GRANULARITY

    /**
     * 取当前导航对应的粒度级别（用于 SpeechCompositor）
     */
    fun granularityLevel(): GranularityNavigator.Level = when (current) {
        NavigationMode.CHARACTERS -> GranularityNavigator.Level.CHARACTER
        NavigationMode.WORDS -> GranularityNavigator.Level.WORD
        NavigationMode.LINES -> GranularityNavigator.Level.LINE
        else -> GranularityNavigator.Level.DEFAULT
    }

    fun stats(): String =
        "当前 ${current.label}（类型码 ${current.typeCode}，${current.kind}）"
}
