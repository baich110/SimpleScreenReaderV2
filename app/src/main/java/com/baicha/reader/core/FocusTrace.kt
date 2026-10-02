package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 焦点决策追踪 —— 「这次滑动为什么没找到焦点」的一站式答案
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要（真机 P0 的直接教训）】
 * ══════════════════════════════════════════════════════════
 *
 * 排查「左右滑动找不到焦点」时，现有的日志**分散在十几个文件**里：
 *   · FocusManager 打「滚动中同向导航 → 吞掉」
 *   · FocusSearch 打「遍历超过 N 次迭代」
 *   · ShouldFocusNode 不打日志（判定全静默）
 *   · 服务打「边界震动」
 *
 * 结果是：**看日志只能看到「没找到焦点」这个结果，
 * 看不到「在哪一步被拒绝」**。而这正是排查需要的信息 ——
 * 焦点查找有十几道判定，任何一道返回 false 都会导致同一种表现。
 *
 * ══════════════════════════════════════════════════════════
 * 【设计：一次导航 = 一条追踪记录】
 * ══════════════════════════════════════════════════════════
 *
 * ```kotlin
 * val trace = FocusTrace.begin("下一焦点")
 * trace.step("滚动拦截", passed = !isScrolling)
 * trace.step("防抖", passed = !debounced)
 * if (target == null) {
 *     trace.reject("遍历无果", "遍历了 137 个节点，全部未通过可聚焦判定")
 * } else {
 *     trace.accept(target)
 * }
 * trace.end()   // 输出一行完整结论
 * ```
 *
 * 输出形如：
 * ```
 * [焦点] 下一焦点 ✅ 通过 → 「设置项：Wi-Fi」
 *         滚动拦截=通过 防抖=通过 粒度=跳过 搜索=命中(第3次)
 * [焦点] 下一焦点 ❌ 失败 ← 遍历无果
 *         滚动拦截=通过 防抖=通过 粒度=跳过 搜索=失败 遍历=137节点/0命中
 * ```
 *
 * ══════════════════════════════════════════════════════════
 * 【设计约束】
 * ══════════════════════════════════════════════════════════
 *
 * ① **零分配热路径**：`step()` 只在开启追踪时记录。
 *    默认关闭时是「判空 + 返回」两条指令。
 * ② **有界**：最多记 [MAX_STEPS] 步，超出丢弃并标记。
 *    导航是高频操作，绝不能无限累积。
 * ③ **不持有节点**：只记节点描述（文本摘要 + 身份哈希），
 *    不持有 AccessibilityNodeInfo 引用 —— 否则拖住节点不被回收。
 * ④ **单条输出**：一次导航结束时打**一行**日志，而不是 N 行。
 *    这是对「日志刷屏」的直接响应。
 */
open class FocusTrace internal constructor(
    /** 本次导航的意图，如「下一焦点」 */
    val intent: String
) {

    /** 一个决策步骤 */
    private class Step(
        val name: String,
        val passed: Boolean,
        val detail: String?
    )

    private val steps = ArrayList<Step>(8)
    private var overflow = 0
    private var result: String? = null
    private var resultOk = false

    /** 记一步判定 */
    open fun step(name: String, passed: Boolean, detail: String? = null) {
        if (steps.size >= MAX_STEPS) {
            overflow++
            return
        }
        steps.add(Step(name, passed, detail))
    }

    /** 记一个「跳过」的步骤（如「本次导航不涉及粒度」） */
    open fun skip(name: String) {
        step(name, passed = true, detail = "跳过")
    }

    /** 记录成功找到目标 */
    open fun accept(node: AccessibilityNodeInfo?) {
        resultOk = true
        result = "通过 → ${describe(node)}"
    }

    /** 记录失败 */
    open fun reject(reason: String, detail: String? = null) {
        resultOk = false
        result = if (detail == null) "失败 ← $reason" else "失败 ← $reason（$detail）"
    }

    /**
     * 结束并输出**单行**结论。
     *
     * 【为什么单行】这是对真机「焦点清除 x64」刷屏的直接修复：
     *   一次导航一条记录，而不是散布十几条。
     */
    open fun end() {
        if (!enabled) return
        val summary = steps.joinToString(" ") { s ->
            if (s.detail != null) "${s.name}=${s.detail}"
            else "${s.name}=${if (s.passed) "通过" else "否决"}"
        }
        val overflowNote = if (overflow > 0) " （另有 $overflow 步未记）" else ""
        val mark = if (resultOk) "✅" else "❌"
        ReaderLog.i(
            TAG,
            "$mark $intent | ${result ?: "未结束"} | $summary$overflowNote"
        )
    }

    companion object {
        private const val TAG = "焦点"

        /** 最多记录步数 */
        const val MAX_STEPS = 12

        /**
         * 是否开启追踪。
         *
         * 【默认开启】因为这是排查真机问题的唯一手段，
         * 而单条记录的开销极低（一次导航一行）。
         * 若将来发现日志量仍大，可通过此开关关闭。
         */
        @Volatile
        var enabled = true

        /** 追踪次数（诊断） */
        @Volatile
        var traceCount = 0L
            private set

        /**
         * 开始一次追踪。
         *
         * 【关闭时的开销】返回一个共享的空实现对象，
         * 避免每次导航都分配。
         */
        fun begin(intent: String): FocusTrace {
            if (!enabled) return DISABLED
            traceCount++
            return FocusTrace(intent)
        }

        /** 关闭时使用的空实现（零分配） */
        private val DISABLED = DisabledTrace()

        /**
         * 节点描述 —— **只取文本摘要，不持有节点**。
         *
         * 形如 `「Wi-Fi」(btn)` —— 文本 + 角色缩写，
         * 便于日志里一眼认出是哪个控件。
         */
        /**
         * 节点描述 —— **带类名**（真机排障必需）
         *
         * 【为什么要加类名】真机日志里全是 `「(无文本)」(?)` ——
         * 光看这个**无法判断**那些被拒的节点到底是什么：
         *   · 是 `FrameLayout` 之类的**容器**（本就不该聚焦，拒得对）
         *   · 还是 `TextView` 之类的**内容节点**（拒错，是 bug）
         * 两者的诊断方向完全相反。
         *
         * 加上短类名后，下次真机日志能**一眼分辨**，不用再猜一轮。
         */
        fun describe(node: AccessibilityNodeInfo?): String {
            if (node == null) return "(null)"
            return try {
                val text = node.text?.toString()?.take(24)
                    ?: node.contentDescription?.toString()?.take(24)
                    ?: "(无文本)"
                val role = when (NodeRoleHelper.roleOf(node)) {
                    NodeRoleHelper.ROLE_BUTTON -> "btn"
                    NodeRoleHelper.ROLE_EDIT_TEXT -> "edit"
                    NodeRoleHelper.ROLE_LIST -> "list"
                    NodeRoleHelper.ROLE_GRID -> "grid"
                    NodeRoleHelper.ROLE_CHECK_BOX -> "chk"
                    NodeRoleHelper.ROLE_SWITCH -> "sw"
                    NodeRoleHelper.ROLE_IMAGE -> "img"
                    NodeRoleHelper.ROLE_SCROLL_VIEW -> "scroll"
                    else -> "?"
                }
                // 短类名 —— 去掉包名前缀，只留类名（如 FrameLayout / TextView）
                val cls = try {
                    node.className?.toString()?.substringAfterLast('.')?.take(18).orEmpty()
                } catch (e: Throwable) {
                    ""
                }
                val clsPart = if (cls.isEmpty()) "" else "/$cls"
                "「$text」($role$clsPart)"
            } catch (e: Throwable) {
                "(描述失败)"
            }
        }
    }
}

/**
 * 追踪关闭时的空实现 —— 所有方法立即返回，零分配
 *
 * 【为什么用子类而不是可空对象】
 *   调用方写成 `trace?.step(...)` 会让每个调用点都要判空；
 *   空实现让调用点保持直线代码，且 JIT 能内联掉。
 */
private class DisabledTrace : FocusTrace("") {
    override fun step(name: String, passed: Boolean, detail: String?) = Unit
    override fun skip(name: String) = Unit
    override fun accept(node: AccessibilityNodeInfo?) = Unit
    override fun reject(reason: String, detail: String?) = Unit
    override fun end() = Unit
}
