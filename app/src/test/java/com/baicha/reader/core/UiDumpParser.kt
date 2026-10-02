package com.baicha.reader.core

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 真机 uiautomator dump 解析器 —— **把真实节点树喂给 shouldFocus**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要它】
 * ══════════════════════════════════════════════════════════
 *
 * 前几轮我用「手工假树」探针定位问题，结论对了一半，但**树的形状是猜的**。
 * 真机 dump（`uiautomator dump`）给出了**真实结构**，其中最关键的事实：
 *
 * > **38 个有文本节点里，只有 3 个 `focusable=true`**（全是 Button）。
 * > 其余 35 个是有文本但 `focusable=false` 的 TextView / View。
 *
 * 而 Operit 是 Compose 应用，树形是
 * ```
 *   ComposeView → android.view.View × N（层层嵌套容器）→ TextView（叶子）
 * ```
 * 与假树差异极大。
 *
 * 【本解析器做什么】把 dump 的 XML 还原成 [FakeNode] 树，
 * 并用 **dump 里真实的属性**（focusable / clickable / text / content-desc /
 * bounds）填充，从而可以在 JVM 里跑**真实的 shouldFocus**。
 *
 * 【属性映射】
 * | XML 属性 | FakeNode 字段 |
 * |---|---|
 * | `class` | `fakeClassName` |
 * | `text` | `textValue` |
 * | `content-desc` | `fakeContentDescription` |
 * | `focusable` | `fakeFocusable` |
 * | `clickable` | `fakeClickable` |
 * | `scrollable` | `fakeScrollable` |
 * | `long-clickable` | 影响 hasAction（转成 action 位） |
 * | `package` | `fakePackageName` |
 *
 * 【bounds 的处理】`FakeNode` 用的是 `android.graphics.Rect`，
 * 在 JVM 单测里是**空壳**（`set()` 无效、读出来恒 0）。
 * 所以这里解析出的 bounds **不写入节点**，只保存到侧表供诊断用 ——
 * 因为 `shouldFocus` 里的 `getBoundsInScreen` 在单测里本来就恒 0。
 */
object UiDumpParser {

    /** 解析结果：根节点 + 每个节点的原始属性（供诊断） */
    class Parsed(val root: FakeNode, val attrs: Map<Int, Map<String, String>>)

    fun parse(path: String): Parsed {
        val f = File(path)
        require(f.exists()) { "找不到 $path" }

        val db = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }.newDocumentBuilder()
        val doc = db.parse(f)
        val hierarchy = doc.documentElement

        val attrs = HashMap<Int, Map<String, String>>()
        var nextId = 1

        fun build(el: Element): FakeNode {
            val id = nextId++
            val a = HashMap<String, String>()
            for (k in listOf(
                "class", "text", "content-desc", "resource-id",
                "focusable", "clickable", "scrollable", "long-clickable",
                "checkable", "checked", "selected", "enabled", "password",
                "bounds", "package"
            )) {
                a[k] = el.getAttribute(k).orEmpty()
            }
            attrs[id] = a

            val node = FakeNode(
                id,
                textValue = a["text"]?.takeIf { it.isNotEmpty() }
            ).apply {
                fakeClassName = a["class"]
                fakeContentDescription = a["content-desc"]?.takeIf { it.isNotEmpty() }
                visible = true                 // dump 的节点都是在屏的
                fakeFocusable = a["focusable"] == "true"
                fakeClickable = a["clickable"] == "true"
                fakeScrollable = a["scrollable"] == "true"
                fakeScreenReaderFocusable = false   // dump 不提供，默认 false（与真机常见一致）
                fakePackageName = a["package"]
                // ★ 真实边界：dump 的 bounds 形如 "[0,0][1280,2772]"
                //   此前刻意不写，导致 FakeNode 边界恒为 0，
                //   使 shouldFocus 的 ⑤/⑤-b（同窗口边界）分支在测试里失效。
                parseBounds(a["bounds"] ?: "")?.let { bb ->
                    boundsLeft = bb[0]; boundsTop = bb[1]
                    boundsRight = bb[2]; boundsBottom = bb[3]
                }
                // 把 dump 的能力属性映射成 **action 位**。
                //
                // ⚠️ 这里必须完整 —— 之前漏了 `scrollable`，后果是
                //   `hasScrollAction(node)` 恒为 false ⇒ `W()`
                //   （`isTopLevelScrollItem`）对滚动容器内的节点恒为 false
                //   ⇒ `z11` 为 false ⇒ 有可朗读子孙的容器被错误拒绝。
                //   真机上 `scrollable=true` 的节点必然带滚动 action，
                //   保持与真机一致。
                var acts = 0
                if (a["clickable"] == "true") acts = acts or 16          // ACTION_CLICK
                if (a["long-clickable"] == "true") acts = acts or 32     // ACTION_LONG_CLICK
                if (a["scrollable"] == "true") {
                    // ACTION_SCROLL_FORWARD(4096) | ACTION_SCROLL_BACKWARD(8192)
                    acts = acts or 4096 or 8192
                }
                if (a["checkable"] == "true") acts = acts or 1048576      // ACTION_CHECK/UNCHECK 近似
                fakeActions = acts
            }

            val children = el.getElementsByTagName("node")
            // 只取**直接子节点**（getElementsByTagName 是递归的）
            for (i in 0 until el.childNodes.length) {
                val c = el.childNodes.item(i)
                if (c is Element && c.tagName == "node") {
                    node.add(build(c))
                }
            }
            @Suppress("UNUSED_EXPRESSION") children
            return node
        }

        // hierarchy 下的第一个 node 是根
        var rootEl: Element? = null
        for (i in 0 until hierarchy.childNodes.length) {
            val c = hierarchy.childNodes.item(i)
            if (c is Element && c.tagName == "node") { rootEl = c; break }
        }
        requireNotNull(rootEl) { "dump 里没有根 node" }

        return Parsed(build(rootEl), attrs)
    }

    /** 把树展平成「按遍历序」的列表（诊断用） */
    fun flatten(root: FakeNode): List<FakeNode> {
        val out = ArrayList<FakeNode>(128)
        fun walk(n: FakeNode) {
            out.add(n)
            for (i in 0 until n.childCount) {
                (n.getChild(i) as? FakeNode)?.let { walk(it) }
            }
        }
        walk(root)
        return out
    }
}

/** 解析 uiautomator 的 bounds 属性：`[l,t][r,b]` */
private fun parseBounds(s: String): IntArray? {
    val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(s) ?: return null
    return intArrayOf(
        m.groupValues[1].toInt(), m.groupValues[2].toInt(),
        m.groupValues[3].toInt(), m.groupValues[4].toInt()
    )
}
