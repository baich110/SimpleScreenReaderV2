package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件分发 —— **常量正确性与重复分支排查**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么要测这个】
 * ══════════════════════════════════════════════════════════
 *
 * `AccessibilityEventHandler.handleEvent()` 是一个 21 分支的 `when`，
 * 里面既有**常量名**（`AccessibilityEvent.TYPE_VIEW_CLICKED`）
 * 也有**硬编码数字**（`131072`）。
 *
 * 两类风险：
 * ① **数字抄错** —— 编译通过、测试全绿，但事件被静默路由到错误分支；
 * ② **重复分支** —— Kotlin 的 `when` 只取**第一个匹配**，
 *    后面的同值分支是**死代码**且编译器**不报错**。
 *
 * 而且注释里的「16: 焦点」「4096: 滚动」这类数字说明也可能与
 * 真实的常量值不符 —— 后来人依赖注释理解代码，注释错了更危险。
 *
 * ══════════════════════════════════════════════════════════
 * 【本轮实测发现】
 * ══════════════════════════════════════════════════════════
 *
 * 第 404 行：`TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY`（= **131072**）
 * 第 424 行：`131072 -> { ... log("拖拽: ...") }` —— **同一个值**！
 *
 * 官方取值表（AOSP `AccessibilityEvent`）：
 * | 值 | 常量 |
 * |---|---|
 * | 131072 | `TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY` |
 * | 262144 | `TYPE_GESTURE_DETECTION_START` |
 * | 524288 | `TYPE_GESTURE_DETECTION_END` |
 *
 * ⇒ 「拖拽」分支**永远不可达**（被前面 404 行截获）。
 * 天坦对 131072 的处理也是**粒度遍历**
 * （`w7/AbstractC3659a.java:54`：`getEventType() == 131072
 *   && getMovementGranularity() == 1`），**没有拖拽语义**。
 *
 * 而真正的「拖拽」相关能力在 Android 里是
 * `AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_*`
 * （动作，不是事件）。
 */
class EventDispatchConstantTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val handler by lazy {
        codeOf("src/main/java/com/baicha/reader/core/AccessibilityEventHandler.kt")
    }

    // ==================== ① 所有 case 值必须唯一 ====================

    /**
     * ★★ `when` 分支里**不得出现重复的事件值**
     *
     * 这是本轮发现的那类缺陷：Kotlin 只取第一个匹配，
     * 后面的同值分支是死代码且**编译器不报错**。
     *
     * 本测试解析所有 `case` 标签（常量名 + 硬编码数字），
     * 换算成数值后检查唯一性。
     */
    @Test
    fun whenBranches_haveNoDuplicateEventValues() {
        // 官方常量 → 值（AOSP AccessibilityEvent）
        val known = mapOf(
            "TYPE_VIEW_CLICKED" to 1,
            "TYPE_VIEW_LONG_CLICKED" to 2,
            "TYPE_VIEW_SELECTED" to 4,
            "TYPE_VIEW_FOCUSED" to 8,
            "TYPE_VIEW_TEXT_CHANGED" to 16,
            "TYPE_WINDOW_STATE_CHANGED" to 32,
            "TYPE_NOTIFICATION_STATE_CHANGED" to 64,
            "TYPE_VIEW_HOVER_ENTER" to 128,
            "TYPE_VIEW_HOVER_EXIT" to 256,
            "TYPE_TOUCH_EXPLORATION_GESTURE_START" to 512,
            "TYPE_TOUCH_EXPLORATION_GESTURE_END" to 1024,
            "TYPE_WINDOW_CONTENT_CHANGED" to 2048,
            "TYPE_VIEW_SCROLLED" to 4096,
            "TYPE_VIEW_TEXT_SELECTION_CHANGED" to 8192,
            "TYPE_ANNOUNCEMENT" to 16384,
            "TYPE_VIEW_ACCESSIBILITY_FOCUSED" to 32768,
            "TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED" to 65536,
            "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY" to 131072,
            "TYPE_GESTURE_DETECTION_START" to 262144,
            "TYPE_GESTURE_DETECTION_END" to 524288,
            "TYPE_TOUCH_INTERACTION_START" to 1048576,
            "TYPE_TOUCH_INTERACTION_END" to 2097152,
            "TYPE_WINDOWS_CHANGED" to 4194304,
            "TYPE_VIEW_CONTEXT_CLICKED" to 8388608
        )

        // 取 handleEvent 的 when 体
        val start = handler.indexOf("fun handleEvent(event:")
        assertTrue("必须存在 handleEvent", start >= 0)
        val body = handler.substring(start, minOf(handler.length, start + 6000))

        val seen = mutableMapOf<Int, MutableList<String>>()
        // 常量名形式：AccessibilityEvent.TYPE_XXX ->
        Regex("AccessibilityEvent\\.(TYPE_\\w+)\\s*->").findAll(body).forEach {
            val name = it.groupValues[1]
            val v = known[name]
            assertTrue("遇到未知常量 $name，请先补充到测试的取值表", v != null)
            seen.getOrPut(v!!) { mutableListOf() }.add(name)
        }
        // 硬编码数字形式： 131072 -> 
        Regex("(?m)^\\s{12}(\\d{3,})\\s*->").findAll(body).forEach {
            val v = it.groupValues[1].toInt()
            seen.getOrPut(v) { mutableListOf() }.add("硬编码 $v")
        }

        val dup = seen.filterValues { it.size > 1 }
        assertTrue(
            "★ when 分支出现重复事件值 —— Kotlin 只取第一个匹配，" +
                    "后面的分支是**死代码**且编译器不报错：\n" +
                    dup.map { (v, names) -> "  值 $v 被用于: ${names.joinToString(", ")}" }
                        .joinToString("\n"),
            dup.isEmpty()
        )
    }

    // ==================== ② 硬编码数字必须与官方一致 ====================

    /**
     * 不得用**硬编码数字**做事件分派 —— 必须用常量名
     *
     * 【为什么】数字看不出语义，抄错也不报错；
     * 而常量名一旦写错就**编译失败**。
     * 本轮发现的「131072 拖拽」正是硬编码数字掩盖了语义错误。
     */
    @Test
    fun eventCases_useConstantsNotLiterals() {
        val start = handler.indexOf("fun handleEvent(event:")
        val body = handler.substring(start, minOf(handler.length, start + 6000))

        val literals = Regex("(?m)^\\s{12}(\\d{3,})\\s*->").findAll(body)
            .map { it.groupValues[1] }.toList()

        assertTrue(
            "★ 事件分派不得用硬编码数字（写错不报错，且看不出语义）—— " +
                    "实际发现: $literals。请改用 AccessibilityEvent.TYPE_* 常量。",
            literals.isEmpty()
        )
    }

    // ==================== ③ 关键分支必须存在且用对常量 ====================

    @Test
    fun granularityTraversal_usesCorrectConstant() {
        assertTrue(
            "粒度遍历必须用 TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY（=131072）",
            handler.contains("AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY")
        )
    }

    @Test
    fun contextClicked_usesCorrectConstant() {
        assertTrue(
            "上下文点击必须用 TYPE_VIEW_CONTEXT_CLICKED（=8388608）—— " +
                    "官方表里没有单独的「拖拽事件」，" +
                    "拖拽能力是 AccessibilityAction.ACTION_DRAG_*（动作而非事件）",
            handler.contains("AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED")
        )
    }

    /**
     * ★ 不得再有「拖拽」事件分支
     *
     * 天坦对 131072 的处理（`w7/AbstractC3659a.java:54`）：
     * `getEventType() == 131072 && getMovementGranularity() == 1`
     * —— 即**粒度遍历**，没有拖拽语义。
     */
    @Test
    fun noBogusDragEventBranch() {
        assertTrue(
            "不得再出现把 131072 当「拖拽」的分支（它是粒度遍历事件，" +
                    "天坦也只按粒度遍历处理）",
            !handler.contains("拖拽")
        )
    }

    // ==================== ④ 注释里的数字必须与官方一致 ====================

    /**
     * ★ 分支注释里的数字说明必须与官方取值一致
     *
     * 后来人靠注释理解代码；注释里的数字错了，
     * 排障时会被带到完全错误的方向（目标纪律③）。
     */
    @Test
    fun branchComments_haveCorrectNumbers() {
        val raw = java.io.File(
            "src/main/java/com/baicha/reader/core/AccessibilityEventHandler.kt"
        ).readText()

        // 注释里形如 "// ---------- 32768: 无障碍焦点 ----------"
        val official = mapOf(
            "1" to "点击",
            "2" to "长按",
            "4" to "选中",
            "8" to "焦点",
            "16" to "文本变化",
            "32" to "窗口状态变化",
            "128" to "悬停进入",
            "2048" to "内容变化",
            "4096" to "滚动",
            "8192" to "选择变化",
            "16384" to "公告",
            "32768" to "无障碍焦点",
            "65536" to "焦点清除",
            "131072" to "粒度遍历",
            "4194304" to "窗口集合变化",
            "8388608" to "上下文点击"
        )

        val wrong = mutableListOf<String>()
        raw.lines().forEach { line ->
            // 只检查**真正的分支标签**：形如 `            // ---------- N: 说明 ----------`
            //
            // 【为什么要排除「被引用的历史注释」】我在删除那个错误分支时，
            // 把**原注释**作为引文写在说明里（让人看到"原来错在哪"）。
            // 那类引文带了额外的 `//` 前缀或缩进更深，**不是**活的分支标签，
            // 不该被当成错误注释来报。
            // 判据：必须是 12 空格缩进 + `// ---------- ` 开头，
            // 且整行只有一层注释。
            val m = Regex("^\\s{12}// -+ (\\d+)[^:]*: (--+|[^-\\n]+?) -+$")
                .find(line) ?: return@forEach
            val num = m.groupValues[1]
            val said = m.groupValues[2].trim()
            val expect = official[num]
            if (expect != null && said != expect) {
                wrong += "  $num 官方是「$expect」，注释写「$said」（行: ${line.trim()}）"
            }
        }
        assertTrue(
            "分支注释里的数字与官方取值不符（会把人带到错误方向）：\n" +
                    wrong.joinToString("\n"),
            wrong.isEmpty()
        )
    }
}
