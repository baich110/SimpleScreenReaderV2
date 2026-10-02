package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 测试用节点替身 —— **可以真正在 JVM 单测里实例化**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要它（这是一个重要的测试基础设施突破）】
 * ══════════════════════════════════════════════════════════
 *
 * 本项目 `app/build.gradle.kts` 开了
 * `testOptions.unitTests.isReturnDefaultValues = true`，
 * 于是 `new AccessibilityNodeInfo()` 在 JVM 里**返回 null 而不是抛异常**。
 *
 * 后果：以前所有涉及节点的测试都只能写「形状测试」——
 * 传 null、断言不崩、断言 API 存在。**逻辑一行都没跑到**。
 * `FocusSearch` 的搜索语义就是这样一路绿灯活到今天的。
 *
 * 【实测结论（2026-10-02 验证）】
 * `AccessibilityNodeInfo` 是**非 final** 类，其无参构造器被调用后
 * 构造出来的对象虽是 null，但**子类可以正常实例化**：
 * ```
 *   class ProbeNode : AccessibilityNodeInfo()   // ← 实测 OK
 * ```
 * 且子类重写 `equals`/`hashCode` 后行为完全可控。
 *
 * 这让我们第一次能对遍历/搜索这类**纯控制流**逻辑写真测试。
 *
 * 【与真实节点的语义对齐要求】
 * 真实 `AccessibilityNodeInfo.equals()` 是**内容哈希**（同内容不同对象也相等），
 * 而 `FocusSearch.searchFocus` 的环检测用 `HashSet` 存节点 ——
 * 即 TalkBack 的 `seenNodes` 也是**内容相等**语义。
 * 因此 FakeNode 的 equals 必须**故意保留内容相等语义**（按 id 比），
 * 才能忠实复现真实行为；用 identity 反而会测出假结果。
 */
open class FakeNode(
    /** 逻辑身份 —— 同时充当内容标识（等价于真实节点的「同内容」） */
    val nodeId: Int,
    /** 供 filter 读取的可见性（默认可见） */
    var visible: Boolean = true
) : AccessibilityNodeInfo() {

    override fun hashCode(): Int = nodeId

    override fun equals(other: Any?): Boolean = other is FakeNode && other.nodeId == nodeId

    override fun toString(): String = "FakeNode($nodeId)"
}
