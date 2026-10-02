package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 测试用节点替身 —— **可以真正在 JVM 单测里实例化，并支持构造树**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要它（本项目一次重要的测试基础设施突破）】
 * ══════════════════════════════════════════════════════════
 *
 * 本项目 `app/build.gradle.kts` 开了
 * `testOptions.unitTests.isReturnDefaultValues = true`，
 * 于是 `new AccessibilityNodeInfo()` 在 JVM 里**返回 null 而不是抛异常**。
 *
 * 后果：以前所有涉及节点的测试都只能写「形状测试」——
 * 传 null、断言不崩、断言 API 存在。**逻辑一行都没跑到**。
 * `FocusSearch.findFirstFocus` 的错误就是这样一路绿灯活到第八轮。
 *
 * 【实测结论（2026-10-02 验证）】
 * `AccessibilityNodeInfo` 是**非 final** 类，其无参构造器在 JVM 里
 * 虽返回 null，但**子类可以正常实例化**：
 * ```
 *   class ProbeNode : AccessibilityNodeInfo()   // ← 实测 OK
 * ```
 * 且子类重写 `equals`/`hashCode`/`getChild`/`getChildCount` 后行为完全可控。
 *
 * ══════════════════════════════════════════════════════════
 * 【语义对齐要求 —— 不能乱写】
 * ══════════════════════════════════════════════════════════
 *
 * ① `equals`/`hashCode` 必须保持**内容相等**语义（按 [nodeId] 比）。
 *    真实 `AccessibilityNodeInfo.equals()` 是内容哈希，而
 *    `FocusSearch.searchFocus` 的环检测用 `HashSet` 存节点 ——
 *    即它依赖**内容相等**。若这里改成 identity 语义，环检测会
 *    测出与真机不同的结果（假通过/假失败）。
 *
 * ② `childCount` / `getChild` 必须忠实返回构造时给的结构。
 *    `TraversalStrategy` / `TatansTraversal` / `NodeOrderComparator`
 *    全部依赖父子关系，这是它们的输入。
 *
 * 【使用方式】
 * ```
 *   val root = FakeNode(0).apply {
 *       add(FakeNode(1, text = "按钮 A"))
 *       add(FakeNode(2, text = "按钮 B"))
 *   }
 * ```
 */
open class FakeNode(
    /** 逻辑身份 —— 同时充当内容标识（等价于真实节点的「同内容」） */
    val nodeId: Int,

    /** 供 filter 读取的文本 */
    var textValue: CharSequence? = null,

    /** 供 filter 读取的可见性（默认可见） */
    var visible: Boolean = true,

    /** 是否可点击（影响 ShouldFocusNode 等判定） */
    var fakeClickable: Boolean = false,

    /** 是否可滚动 */
    var fakeScrollable: Boolean = false,

    /** 包名 —— `needsSort` 按包名分支（vivo 桌面 / 原生桌面） */
    var fakePackageName: CharSequence? = null,

    /**
     * 类名 —— `NodeRoleHelper.roleOf()` 的**主输入**
     *
     * 它是角色判定的第一依据（查 NODE_ROLE_MAP + 继承链检查），
     * 不提供就无法测角色/排序相关逻辑。
     */
    var fakeClassName: CharSequence? = null,

    /** 是否持有**输入**焦点 —— `FocusMonitor` 回退链第②级的判定输入 */
    var fakeInputFocused: Boolean = false,

    /** 是否可编辑 —— `FocusMonitor` 的 `requireEditable` 校验输入 */
    var fakeEditable: Boolean = false,

    /** 所属窗口 ID —— 屏幕状态恢复要求「焦点节点属于当前窗口」 */
    var fakeWindowId: Int = -1,

    /**
     * 无障碍内容描述 —— `getContentDescription()`
     *
     * 【为什么需要】文本提取链（`SpeechCompositor.extractMainText` /
     * `collectChildText`）的第二数据源就是它：
     * ```kotlin
     *   child.text?.toString()?.trim()
     *       ?: child.contentDescription?.toString()?.trim()
     * ```
     * 没有它就无法测「节点只有 contentDescription 时能否读出内容」。
     */
    var fakeContentDescription: CharSequence? = null,

    /**
     * 是否**可获得**焦点 —— `isFocusable()`
     *
     * 【为什么必须有这个字段】`ShouldFocusNode.n(node, true)` 的第一项
     * 就是它：
     * ```kotlin
     *   return isScreenReaderFocusable(node) || node.isFocusable ||
     *          node.isClickable || node.isLongClickable || hasAction(node, 1, 16, 32)
     * ```
     * 而 `isReturnDefaultValues` 让未重写的 `isFocusable()` 恒返回 **false** ——
     * 没有这个字段，`shouldFocus()` 在单测里**永远返回 false**，
     * 等于整个焦点判定层测不了。
     */
    var fakeFocusable: Boolean = false,

    /**
     * 是否「读屏应聚焦」—— `isScreenReaderFocusable()`
     *
     * API 28+ 才有；`n()` 的第一项用它。
     */
    var fakeScreenReaderFocusable: Boolean = false
) : AccessibilityNodeInfo() {

    private val childList = ArrayList<FakeNode>(4)

    private var parentRef: FakeNode? = null

    /** 构造树：把 [child] 挂到本节点下（同时维护 parent 反向指针） */
    fun add(child: FakeNode): FakeNode {
        childList.add(child)
        child.parentRef = this
        return this
    }

    /** 本文节点的子节点个数 —— 对齐 `AccessibilityNodeInfo.getChildCount()` */
    override fun getChildCount(): Int = childList.size

    /** 第 [index] 个子节点 —— 对齐 `AccessibilityNodeInfo.getChild(int)`，越界返回 null */
    override fun getChild(index: Int): AccessibilityNodeInfo? = childList.getOrNull(index)

    /** 父节点 —— 对齐 `AccessibilityNodeInfo.getParent()` */
    override fun getParent(): AccessibilityNodeInfo? = parentRef

    // ==================== Android 节点 API 的忠实替身 ====================
    //
    // 【为什么必须重写这些】`isReturnDefaultValues = true` 让未实现的
    // Android 方法统统返回「默认值」：
    //   isVisibleToUser() → false
    //   getText()         → null
    //   isClickable()     → false
    // 于是任何依赖这些判定的被测代码（如 TatansTraversal.isNodeFocusable
    // 先查 isVisibleToUser）在测试里**永远走 false 分支**，
    // 遍历结果恒为空 —— 看起来是「产品有 bug」，其实是被测输入是空壳。
    //
    // 这正是本项目 `docs/测试基础设施限制-2026-10-02.md` 记录的同一个坑
    // （android.graphics.Rect 是空壳），只是换到了 AccessibilityNodeInfo 上。
    //
    // 所以这里必须**逐字段忠实映射**，不能依赖默认值。

    override fun isVisibleToUser(): Boolean = visible

    override fun getText(): CharSequence? = textValue

    override fun isClickable(): Boolean = fakeClickable

    override fun isScrollable(): Boolean = fakeScrollable

    override fun getPackageName(): CharSequence? = fakePackageName

    override fun getClassName(): CharSequence? = fakeClassName

    override fun getContentDescription(): CharSequence? = fakeContentDescription

    override fun isFocusable(): Boolean = fakeFocusable

    override fun isScreenReaderFocusable(): Boolean = fakeScreenReaderFocusable

    /**
     * 动作位掩码 —— `getActions()`
     *
     * 【为什么必须有】多条判定走**位掩码路径**：
     *   · `isInRecyclerView` → `hasAction(node, 1024, 2048)`
     *     （`ShouldFocusNode.isVisible` 的 RecyclerView 兜底依赖它）
     *   · `hasAction` / `hasActionMask` 的全部位掩码分支
     * 未重写时恒返回 0 ⇒ 位掩码路径在单测里**永远不生效**。
     */
    var fakeActions: Int = 0

    /**
     * 状态描述 —— `getStateDescription()`（API 30+）
     *
     * 天坦 `hasTextOrContent = hasText || hasStateDescription`，
     * 少了这个字段就测不了「只有状态描述的节点是否可聚焦」。
     */
    var fakeStateDescription: CharSequence? = null

    override fun getActions(): Int = fakeActions

    override fun getStateDescription(): CharSequence? = fakeStateDescription

    override fun isFocused(): Boolean = fakeInputFocused

    override fun isEditable(): Boolean = fakeEditable

    override fun getWindowId(): Int = fakeWindowId

    override fun hashCode(): Int = nodeId

    override fun equals(other: Any?): Boolean = other is FakeNode && other.nodeId == nodeId

    override fun toString(): String =
        "FakeNode($nodeId${textValue?.let { ", \"$it\"" } ?: ""})"
}

/**
 * 构树 DSL —— 让测试里的树结构一眼可读
 *
 * ```
 *   val root = tree(0) {
 *       node(1, "列表") {
 *           node(2, "项目 A")
 *           node(3, "项目 B")
 *       }
 *   }
 * ```
 */
fun tree(rootId: Int, text: CharSequence? = null, block: FakeNode.() -> Unit): FakeNode =
    FakeNode(rootId, text).apply(block)

/** 建一个子节点并挂到当前节点上 */
fun FakeNode.node(id: Int, text: CharSequence? = null, block: FakeNode.() -> Unit = {}): FakeNode =
    FakeNode(id, text).apply(block).also { add(it) }
