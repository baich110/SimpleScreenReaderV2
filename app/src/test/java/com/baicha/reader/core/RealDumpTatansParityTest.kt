package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 真机 dump × 天坦语义 一致性护栏
 *
 * ══════════════════════════════════════════════════════════════
 * 【这个测试保护什么】
 * `shouldFocus` 的每一处判定都对应天坦源码的一个分支，但**抄错一处
 * 不会让编译失败、也不会让其他单测失败** —— 真机上表现为
 * 「焦点少了几个」或「焦点多了几个」，用户只能感觉「不好用」。
 *
 * 本测试用**真机 dump**（117 节点）跑一遍完整判定，把结果**冻结**成
 * 基线：任何改动只要让某个节点的判定翻转，这里立刻失败。
 * ══════════════════════════════════════════════════════════════
 *
 * 【基线是怎么来的】
 * 不是「跑一遍看结果就记下来」—— 那样只是把错误固化。
 * 基线来自**独立移植的天坦参考实现**（Java，直接照抄
 * `w7/AccessibilityNodeInfoUtils.shouldFocusNodeInternal` 等函数），
 * 在同一个 dump 上跑出的逐节点判定。两者 117/117 吻合后才冻结。
 *
 * 【已知的设计分歧（不是 bug）】
 * 同为「默认滑动浏览」，TalkBack 与天坦在**兜底分支**上不同：
 *   · TalkBack：`!hasMatchingAncestor(...)` —— 无可聚焦**祖先**
 *   · 天坦：    `!hasMatchingDescendant(...)` —— 无可聚焦**后代**
 * 在同一 dump 上 TalkBack 接受 36 个、天坦接受 56 个。
 * 本项目按用户既定原则**采用天坦**，故基线是 56。
 */
class RealDumpTatansParityTest {

    private fun decisions(): Pair<Int, Int> {
        val parsed = UiDumpParser.parse("src/test/resources/ui-dump-real.xml")
        var accept = 0
        var reject = 0
        for (n in UiDumpParser.flatten(parsed.root)) {
            val ok = try { ShouldFocusNode.shouldFocus(n) } catch (e: Throwable) { false }
            if (ok) accept++ else reject++
        }
        return accept to reject
    }

    /**
     * ★★★ 与天坦参考实现逐节点一致的基线：接受 56 / 拒绝 61（共 117）
     *
     * 【如果这个测试失败】
     *   说明某处判定发生了翻转。**不要直接把数字改成新值** ——
     *   先回答：新结果与天坦参考实现还一致吗？若不一致，是白茶错还是
     *   参考实现错？只有确证参考实现抄错时，才更新基线。
     */
    @Test
    fun matchesTatansReferenceOnRealTree() {
        val (accept, reject) = decisions()
        assertEquals("真机 dump 节点总数应为 117", 117, accept + reject)
        assertEquals(
            "与天坦参考实现的判定不一致（应为 接受=56 / 拒绝=61）。" +
                "判定翻转会让真机焦点数量变化，务必先核对天坦原文。",
            56 to 61, accept to reject
        )
    }

    /**
     * ★★ 关键子集护栏：这 5 个节点**必须被拒绝**
     *
     * 形态：`android.view.View`、无文本、`clickable=false`、`focusable=false`、
     *       有 2~6 个可见子节点、父节点是 `View`（非滚动容器）、祖父才是 `ScrollView`。
     *
     * 【为什么必须拒绝】尽管它们的子孙里有文本，但天坦的 `z11` 第二项
     *   `isTopLevelScrollItem(node) && hasSomethingToSpeak(node)` 中，
     *   **`isTopLevelScrollItem` 只看直接父节点**（`w7:534~558`）：
     *   父节点 42 是 `android.view.View`、无滚动 action ⇒ false ⇒ `z11=false`。
     *   落到兜底分支后，自身又无文本/状态描述 ⇒ **拒绝**。
     *
     *   ⇒ 这 5 个节点的文本由**更内层的子节点**负责朗读，
     *     容器本身不该再抢一个焦点（否则同一段文字被读两遍）。
     *
     * 【我在这条上犯过的错】一度给参考实现的 `isScrollItem` 多加了一层
     *   `node.parent.scrollable`（即查祖父），于是误判这 5 个该被接受，
     *   还把错误期望写进了本测试。**天坦原文没有那一层**。
     *   教训：参考实现必须逐行直译，不能"顺手补全"。
     */
    @Test
    fun scrollWrappersWithoutOwnContentAreRejected() {
        val parsed = UiDumpParser.parse("src/test/resources/ui-dump-real.xml")
        val all = UiDumpParser.flatten(parsed.root)
        val ids = intArrayOf(33, 43, 49, 66, 72)
        val wrong = mutableListOf<Int>()
        for (id in ids) {
            val n = all.firstOrNull { it.nodeId == id } ?: continue
            val ok = try { ShouldFocusNode.shouldFocus(n) } catch (e: Throwable) { false }
            if (ok) wrong.add(id)
        }
        assertEquals(
            "这些「自身无内容、父节点非滚动容器」的包装节点被错误接受" +
                "（会导致同一段文字被读两遍）：$wrong",
            0, wrong.size
        )
    }

    /**
     * ★★ 反向护栏：真机上必须能拿到焦点的**交互元素**一个都不能少
     *
     * 只追求「拒绝得准」会把界面读空 —— 接受侧同样要有底线：
     * 所有带文本的可点击节点（按钮、列表项）都必须可聚焦。
     */
    @Test
    fun allInteractiveNodesRemainFocusable() {
        val parsed = UiDumpParser.parse("src/test/resources/ui-dump-real.xml")
        val all = UiDumpParser.flatten(parsed.root)
        val missing = mutableListOf<String>()
        for (n in all) {
            val a = parsed.attrs[n.nodeId] ?: continue
            val txt = a["text"].orEmpty().ifEmpty { a["content-desc"].orEmpty() }
            if (txt.isEmpty()) continue
            if (a["clickable"] != "true" || a["focusable"] != "true") continue
            val ok = try { ShouldFocusNode.shouldFocus(n) } catch (e: Throwable) { false }
            if (!ok) missing.add("${a["class"]?.substringAfterLast('.')}: $txt")
        }
        assertEquals("带文本的可点击可聚焦节点被拒绝了：$missing", 0, missing.size)
    }
}
