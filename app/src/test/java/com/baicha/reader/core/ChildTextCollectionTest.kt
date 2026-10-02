package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子节点文本收集 —— **真机 `(无标签)` 刷屏的根因**
 *
 * ══════════════════════════════════════════════════════════
 * 【真机现象】（用户实装后的日志）
 * ══════════════════════════════════════════════════════════
 *
 * ```
 * 05:28:01.438  下一焦点: (无标签) → 兜底播报
 * 05:28:01.454  无障碍焦点: (无内容)
 * 05:28:03.691  上一焦点: (无标签) → 兜底播报
 * ...
 * 05:27:48.592  触摸: (无标签) → 兜底播报
 * ```
 *
 * 用户原话：「左右滑动还是失效，它只能获取到一个两个焦点，
 * 而且很多焦点它都（获取不到）」。
 *
 * **能工作的只有**：`全屏输入` / `编辑框` / `菜单`
 * —— 这些都是**自身直接带 text 或 contentDescription** 的节点。
 *
 * ══════════════════════════════════════════════════════════
 * 【根因】`collectChildText()` 里有一行天坦**没有**的可见性过滤
 * ══════════════════════════════════════════════════════════
 *
 * 白茶（`SpeechCompositor.collectChildText`）：
 * ```kotlin
 *   for (i in 0 until limit) {
 *       val child = node.getChild(i) ?: continue
 *       if (!isVisible(child)) continue          // ← ★ 天坦没有这一行
 *       val t = child.text?.toString()?.trim()
 *           ?: child.contentDescription?.toString()?.trim()
 *       ...
 *   }
 * ```
 * 而 `isVisible(node)` 直接返回 `node.isVisibleToUser`。
 *
 * 天坦 `w7/NodeUtils.java:69~87` 的 `getChildTextRecursive()`：
 * ```java
 *   public static final CharSequence getChildTextRecursive(NodeInfo nodeInfo) {
 *       if (nodeInfo == null) { return null; }
 *       final F5.E e9 = new F5.E();
 *       int childCount = nodeInfo.getChildCount();
 *       for (int i9 = 0; i9 < childCount; i9++) {
 *           NodeInfo child = nodeInfo.getChild(i9);
 *           if (child != null) {
 *               withNodeRecycled(child, ... lambdaCollectText ...);
 *           }
 *       }
 *       return (CharSequence) e9.f3763a;
 *   }
 * ```
 * **没有 `isVisible` 判断** —— 只要 child 非 null 就收集它的文本。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么这会造成大面积失败】
 * ══════════════════════════════════════════════════════════
 *
 * `isVisibleToUser` 在真机上有大量**假阴性**：
 *   · RecyclerView 列表项的容器节点常返回 false
 *   · 滚动中/动画中/自绘 UI 返回 false
 *   · 部分国产 ROM 对未完全进入视口的子节点返回 false
 *   · 被父容器裁剪的文本节点返回 false
 *
 * 而这些节点**文本是有的**。过滤掉它们 ⇒ 文本收集结果为空 ⇒
 *   · `compose()` 走到「三个核心都空 → return null」
 *   · `speakNode` 落到 `(无标签) → 兜底播报`
 *   · 用户听到的是一连串没有意义的提示
 *
 * 【副作用：与 `ShouldFocusNode` 的判定冲突】
 * `ShouldFocusNode` 已经把「不可见但属于 RecyclerView」的节点
 * 视为可聚焦（`w7/AccessibilityNodeInfoUtils.java:570~577` 的
 * `isVisible` 有 RecyclerView 兜底）——
 * 于是出现「**判定可聚焦、但读不出内容**」的荒诞组合：
 * 焦点设上去了，却念「未加标签」。
 */
class ChildTextCollectionTest {

    /** 复刻 `SpeechCompositor.collectChildText` 的收集逻辑（含被修的可见性过滤） */
    private fun collect(node: FakeNode, skipInvisible: Boolean): String? {
        val count = node.childCount
        if (count == 0) return null
        val limit = minOf(count, 8)
        val parts = ArrayList<String>(4)
        val seen = HashSet<String>(4)
        for (i in 0 until limit) {
            val child = node.getChild(i) as? FakeNode ?: continue
            if (skipInvisible && !child.visible) continue      // ← 待验证的那一行
            val t = child.textValue?.toString()?.trim()
                ?: child.fakeContentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length < 100 && seen.add(t)) {
                parts.add(t)
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("，")
    }

    // ==================== ★ 核心：不可见子节点必须仍被收集 ====================

    /**
     * ★★ 列表项容器（自身无文本）+ 子节点不可见 —— 天坦能读出，白茶原来读不出
     *
     * 这是真机 `(无标签)` 的最小复现。
     */
    @Test
    fun invisibleChildren_withText_mustStillBeCollected() {
        // 典型列表项：容器无文本，子节点有文本，但 isVisibleToUser = false
        val item = FakeNode(1).apply {
            add(FakeNode(2, textValue = "标题").apply { visible = false })
            add(FakeNode(3, textValue = "摘要").apply { visible = false })
        }

        val withFilter = collect(item, skipInvisible = true)
        val withoutFilter = collect(item, skipInvisible = false)

        assertNull(
            "复现真机现象：加了可见性过滤后，列表项**读不出任何内容**",
            withFilter
        )
        assertEquals(
            "★ 天坦的做法（不过滤可见性）能正确读出「标题，摘要」—— " +
                    "`getChildTextRecursive` 里**没有** isVisible 判断",
            "标题，摘要", withoutFilter
        )
    }

    /** 部分子节点不可见时，可见的那些仍应读到（过滤不该「全丢」） */
    @Test
    fun mixedVisibility_stillCollectsVisibleOnes() {
        val item = FakeNode(1).apply {
            add(FakeNode(2, textValue = "可见项").apply { visible = true })
            add(FakeNode(3, textValue = "不可见项").apply { visible = false })
        }
        val noFilter = collect(item, skipInvisible = false)
        assertEquals("两者都应被收集（天坦语义）", "可见项，不可见项", noFilter)
    }

    /** 全部可见时两种做法等价（不得为了修 bug 而改变正常路径） */
    @Test
    fun allVisible_behaviourUnchanged() {
        val item = FakeNode(1).apply {
            add(FakeNode(2, textValue = "A"))
            add(FakeNode(3, textValue = "B"))
        }
        assertEquals(collect(item, true), collect(item, false))
        assertEquals("A，B", collect(item, true))
    }

    /** 无文本的子节点仍应跳过（不能把空串也收进来） */
    @Test
    fun childWithoutText_isSkipped() {
        val item = FakeNode(1).apply {
            add(FakeNode(2))                       // 无 text / 无 desc
            add(FakeNode(3, textValue = "有文本"))
        }
        assertEquals("只有带文本的子节点被收集", "有文本", collect(item, false))
    }

    /** 重复文本去重 */
    @Test
    fun duplicateChildText_isDeduped() {
        val item = FakeNode(1).apply {
            add(FakeNode(2, textValue = "重复"))
            add(FakeNode(3, textValue = "重复"))
        }
        assertEquals("重复文本只收一次", "重复", collect(item, false))
    }

    /** 超长子文本应被跳过（防噪音） */
    @Test
    fun overlongChildText_isSkipped() {
        val item = FakeNode(1).apply {
            add(FakeNode(2, textValue = "x".repeat(150)))
            add(FakeNode(3, textValue = "正常"))
        }
        assertEquals("超长文本跳过，保留正常的", "正常", collect(item, false))
    }

    // ==================== 接线守卫 ====================

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * ★★ 源码里**不得**再有「子节点可见性过滤」
     */
    @Test
    fun collectChildText_hasNoVisibilityFilter() {
        val src = codeOf("src/main/java/com/baicha/reader/core/SpeechCompositor.kt")
        val idx = src.indexOf("private fun collectChildText(")
        assertTrue("必须存在 collectChildText", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 1400))
        assertTrue(
            "★ collectChildText 里**不得**跳过不可见子节点 —— " +
                    "天坦 `NodeUtils.getChildTextRecursive()` 没有 isVisible 判断；" +
                    "而 isVisibleToUser 在真机上有大量假阴性（列表项容器/" +
                    "滚动中/自绘 UI），过滤掉会导致**读不出任何内容** → " +
                    "真机表现为 `(无标签) → 兜底播报` 刷屏。\n$body",
            !body.contains("!isVisible(child)") && !body.contains("isVisibleToUser")
        )
    }
}

/**
 * 导航路径日志 —— **静默失败必须可见**
 *
 * ══════════════════════════════════════════════════════════
 * 【用户的原话】（真机第一次跑起来后的反馈）
 * ══════════════════════════════════════════════════════════
 *
 * > **现在最大的问题是：手势进来了，但日志什么都不说。**
 * > 这正是"静默失败"。
 * > **请在导航路径上加日志**（找到几个候选节点、过滤掉几个、
 * > 为什么全部被过滤），让下次真机验证能直接看出原因。
 *
 * ══════════════════════════════════════════════════════════
 * 【具体的三个静默点】（本轮已补）
 * ══════════════════════════════════════════════════════════
 *
 * ① `GestureChain.handle()` 全部 Actor 拒绝 → 只 `unhandledCount++`
 *    **不打日志**。真机上表现是「手势事件有、后续全无」。
 * ② `ReaderAccessibilityService.moveFocusInternal()`
 *    `focus.moveFocus(...) ?: run { haptic.boundary(); return false }`
 *    —— `haptic.boundary()` **只震动不写日志**（见
 *    `HapticFeedback.boundary()`：`vibratePattern(...)`）。
 *    表现是「手机震一下，然后什么也没有」。
 * ③ 导航入口本身无日志 ⇒ 无法区分「没走到」与「走到但失败」。
 */
class NavigationLoggingTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * ★ `GestureChain` 的「无人消费」路径必须打日志
     */
    @Test
    fun gestureChain_logsWhenNobodyConsumes() {
        val src = codeOf("src/main/java/com/baicha/reader/core/GestureChain.kt")
        // ★ 注意 `unhandledCount++` 出现多次（双击未消费也 +1）。
        //   要看的是「责任链全部走完仍无人消费」那一处 ——
        //   特征是与 `return false` 相邻。我第一版取 indexOf 的**第一个**，
        //   拿错了位置（那是双击分支的）。
        val idx = src.indexOf("unhandledCount++\n        return false")
        assertTrue(
            "必须存在「责任链全部走完仍无人消费」的 unhandledCount++ / return false",
            idx >= 0
        )
        val around = src.substring(maxOf(0, idx - 700), minOf(src.length, idx + 60))
        assertTrue(
            "★ `unhandledCount++`（无人消费）附近必须有日志 —— " +
                    "真机上出现过「连续 9 次手势零输出」，排查者无法判断" +
                    "手势去哪了。属模式⑨静默失败。\n$around",
            around.contains("ReaderLog.w") || around.contains("ReaderLog.e") ||
                    around.contains("ReaderLog.d")
        )
    }

    /**
     * ★ 导航失败路径必须打日志（不能只震动）
     */
    @Test
    fun navigationFailure_logsBeforeBoundaryHaptic() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
        val idx = src.indexOf("haptic.boundary()\n            return false")
        assertTrue("必须存在「震动 + 返回 false」的边界路径", idx >= 0)
        val around = src.substring(maxOf(0, idx - 700), idx + 60)
        assertTrue(
            "★ `haptic.boundary(); return false` 之前必须有日志 —— " +
                    "`HapticFeedback.boundary()` 只震动不写日志，" +
                    "表现是「手机震一下然后什么都没有」，用户和排查者都无法定位。\n$around",
            around.contains("log(") || around.contains("ReaderLog")
        )
    }

    /** ★ 导航入口必须有日志（区分「没走到」与「走到但失败」） */
    @Test
    fun navigationEntry_logsModeAndDirection() {
        val src = codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
        val idx = src.indexOf("private fun moveFocusInternal(forward: Boolean): Boolean {")
        assertTrue("必须存在 moveFocusInternal", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 900))
        assertTrue(
            "★ 导航入口必须打日志（含方向与导航模式）—— " +
                    "否则真机上无法二分「手势链没派发」与「导航内部失败」。\n$body",
            body.contains("导航入口")
        )
    }

    /** `HapticFeedback.boundary()` 本身不得被改成"打日志"来替代调用方日志 */
    @Test
    fun boundaryHaptic_stillOnlyVibrates() {
        val src = codeOf("src/main/java/com/baicha/reader/core/HapticFeedback.kt")
        val idx = src.indexOf("fun boundary()")
        assertTrue("必须存在 boundary()", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 200))
        assertTrue(
            "boundary() 应保持「只震动」—— 日志由调用方补，" +
                    "这样调用方才能带上自己的上下文（模式/方向）",
            body.contains("vibrate")
        )
    }
}
