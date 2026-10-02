package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机 dump 端到端护栏（数据来源：`src/test/resources/ui-dump-real.xml`，
 * 用户在 25060RK16C / Android 16 上 `uiautomator dump` 导出，117 节点）。
 *
 * 【为什么要有这个测试】
 * 真机 P0 的根因是「无文本的空容器被放行」，而单元测试里的合成树
 * 从未覆盖过 Compose 生成的那类节点形态。用真实 dump 做护栏，
 * 下次再动 `ShouldFocusNode` 时能立刻发现回归。
 */
class RealDumpFocusTest {

    private fun load() = UiDumpParser.parse("src/test/resources/ui-dump-real.xml")

    /** 该节点的 text + content-desc */
    private fun textOf(attrs: Map<String, String>): String =
        attrs["text"].orEmpty().ifEmpty { attrs["content-desc"].orEmpty() }

    /**
     * ★★★ 真机 P0 核心断言：
     * 无文本、有可见子节点、自身无内容可朗读的容器 —— **一个都不能放行**。
     *
     * 修复前实测放行 20 个，修复后为 0。
     */
    @Test
    fun blankContainersWithVisibleChildren_mustNotFocus() {
        val parsed = load()
        val offenders = mutableListOf<String>()

        for (n in UiDumpParser.flatten(parsed.root)) {
            val a = parsed.attrs[n.nodeId] ?: continue
            if (textOf(a).isNotEmpty()) continue
            if (n.childCount == 0) continue
            val visibleChildren =
                (0 until n.childCount).count { i -> (n.getChild(i) as? FakeNode)?.visible == true }
            if (visibleChildren == 0) continue

            val ok = try { ShouldFocusNode.shouldFocus(n) } catch (e: Throwable) { false }
            if (ok) {
                offenders.add(
                    "${a["class"]?.substringAfterLast('.')} " +
                        "focus=${a["focusable"]} click=${a["clickable"]} child=${n.childCount}"
                )
            }
        }

        assertEquals(
            "无文本空容器被放行（真机表现：焦点停在容器上，滑动只能走两个焦点）：$offenders",
            0, offenders.size
        )
    }

    /**
     * 反向护栏：真实界面里的可交互元素**必须**能拿到焦点。
     * 只拒绝不通过 = 什么都不聚焦，同样是坏的。
     */
    @Test
    fun realInteractiveNodes_stillFocusable() {
        val parsed = load()
        val buttons = UiDumpParser.flatten(parsed.root).filter { n ->
            val a = parsed.attrs[n.nodeId] ?: return@filter false
            a["class"]?.endsWith("Button") == true && textOf(a).isNotEmpty()
        }
        assertTrue("dump 里应存在带文本的 Button", buttons.isNotEmpty())
        val passed = buttons.count {
            try { ShouldFocusNode.shouldFocus(it) } catch (e: Throwable) { false }
        }
        assertTrue("带文本的 Button 必须全部可聚焦（$passed/${buttons.size}）", passed == buttons.size)
    }

    /**
     * 有文本但含可聚焦后代的节点应被拒绝（天坦行为）：
     * 让焦点落到后代，避免读屏重复朗读。
     */
    @Test
    fun textNodeWithFocusableDescendant_isRejected() {
        val parsed = load()
        val all = UiDumpParser.flatten(parsed.root)

        fun isDescendant(anc: FakeNode, n: FakeNode): Boolean {
            for (i in 0 until anc.childCount) {
                val c = anc.getChild(i) as? FakeNode ?: continue
                if (c === n || isDescendant(c, n)) return true
            }
            return false
        }

        var checked = 0
        for (n in all) {
            val a = parsed.attrs[n.nodeId] ?: continue
            if (textOf(a).isEmpty()) continue
            val hasFocusableDesc = all.any { c ->
                c !== n && isDescendant(n, c) &&
                    try { ShouldFocusNode.shouldFocus(c) } catch (e: Throwable) { false }
            }
            if (!hasFocusableDesc) continue
            checked++
            val ok = try { ShouldFocusNode.shouldFocus(n) } catch (e: Throwable) { false }
            assertTrue("「${textOf(a)}」有可聚焦后代，应拒绝（让焦点落到后代）", !ok)
        }
        assertTrue("dump 里应存在这类节点用于验证", checked > 0)
    }
}
