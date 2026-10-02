package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点过滤器 —— 对应 TalkBack `Filter<T>` + 天坦 `w7/M`
 *
 * 【为什么要抽象成类而不是用 lambda】
 *
 * 原来全项目用 `(AccessibilityNodeInfo) -> Boolean` 裸函数类型，问题：
 *
 * ① **不能组合**。天坦/TalkBack 都支持 `filterA.and(filterB)`、
 *    `filterA.or(filterB)`。用 lambda 只能手写成 `{ a(it) && b(it) }`，
 *    每多一层组合就多一层闭包分配。
 *
 * ② **不能缓存**。同一个过滤条件的组合结果每次重新计算。
 *    本节提供 `cached()` 让组合后的过滤器可以挂到 NodeCache。
 *
 * ③ **不可辨识**。调试时打印一个 lambda 只能看到 `Function1`，
 *    类可以带 name。
 *
 * 【天坦的用法（w7/AbstractC3693m 的 11 个预置过滤器）】
 *
 *   f39046f = new g()                     可滚动容器
 *   f39048h = new i()                     可点击或列表/网格
 *   f39049i = new j()                     可见且非输入法
 *   f39016k = new b().and(cVar)           排除 WebView + 可见
 *   ...
 *   调用时 mT.and(c3678d.i())             动态叠加
 *
 * 【本实现】
 *   提供 `and` / `or` / `not` 三种组合 + `cached` 缓存包装，
 *   并提供与天坦 11 个预置过滤器对应的工厂方法。
 */
abstract class NodeFilter {

    /** 判定 —— 对应 TalkBack `Filter.accept()` */
    abstract fun accept(node: AccessibilityNodeInfo?): Boolean

    /** 过滤器名（诊断用） */
    open val name: String
        get() = this::class.java.simpleName

    /** 与 —— 对应 TalkBack `Filter.and()` */
    fun and(other: NodeFilter?): NodeFilter {
        if (other == null) return this
        return AndFilter(this, other)
    }

    /** 或 —— 对应 TalkBack `Filter.or()` */
    fun or(other: NodeFilter?): NodeFilter {
        if (other == null) return this
        return OrFilter(this, other)
    }

    /** 非 —— 对应 TalkBack `Filter.not()` */
    fun not(): NodeFilter = NotFilter(this)

    /**
     * 加缓存 —— 本项目独有
     *
     * 组合过滤器的代价是每层一次方法调用 + 闭包。对高频调用的
     * 复合过滤器（如「可见 且 可聚焦 且 非 WebView」），
     * 缓存能显著降低开销。
     *
     * 缓存走 NodeCache，随窗口/内容变化自动失效。
     */
    fun cached(): NodeFilter = CachedFilter(this)

    // ==================== 组合实现 ====================

    private class AndFilter(val a: NodeFilter, val b: NodeFilter) : NodeFilter() {
        override fun accept(node: AccessibilityNodeInfo?): Boolean =
            a.accept(node) && b.accept(node)

        override val name: String get() = "(${a.name} 且 ${b.name})"
    }

    private class OrFilter(val a: NodeFilter, val b: NodeFilter) : NodeFilter() {
        override fun accept(node: AccessibilityNodeInfo?): Boolean =
            a.accept(node) || b.accept(node)

        override val name: String get() = "(${a.name} 或 ${b.name})"
    }

    private class NotFilter(val inner: NodeFilter) : NodeFilter() {
        override fun accept(node: AccessibilityNodeInfo?): Boolean =
            !inner.accept(node)

        override val name: String get() = "非(${inner.name})"
    }

    private class CachedFilter(val inner: NodeFilter) : NodeFilter() {
        override fun accept(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val c = NodeOrderComparator.cache ?: return inner.accept(node)
            return c.shouldFocus(node) { inner.accept(node) }
        }

        override val name: String get() = "缓存(${inner.name})"
    }

    // ==================== 预置过滤器（对应天坦 11 个）====================

    companion object {

        /** 接受任何非空节点 —— 对应天坦 `C1427v.a` */
        val ANY: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean = node != null
            override val name: String get() = "任意"
        }

        /** 可见 —— 对应天坦基础判定 */
        val VISIBLE: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                return try {
                    node.isVisibleToUser
                } catch (e: Throwable) {
                    false
                }
            }
            override val name: String get() = "可见"
        }

        /** 默认可聚焦 —— 走 ShouldFocusNode */
        val FOCUSABLE: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean =
                ShouldFocusNode.shouldFocus(node)
            override val name: String get() = "可聚焦"
        }

        /** 可点击 —— 对应天坦 `AbstractC3693m.H()` */
        val CLICKABLE: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isClickable) return true
                return try {
                    node.actionList.any { it.id == 16 }
                } catch (e: Throwable) {
                    false
                }
            }
            override val name: String get() = "可点击"
        }

        /** 可滚动 —— 对应天坦 `f39046f`（可滚动容器） */
        val SCROLLABLE: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isScrollable) return true
                return try {
                    node.actionList.any {
                        it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                                it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
                } catch (e: Throwable) {
                    false
                }
            }
            override val name: String get() = "可滚动"
        }

        /** 网格容器 —— 对应天坦 `f39047g`（rowCount>1 且 columnCount>1） */
        val GRID: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                return try {
                    val info = node.collectionInfo
                    info != null && info.rowCount > 1 && info.columnCount > 1
                } catch (e: Throwable) {
                    false
                }
            }
            override val name: String get() = "网格"
        }

        /** 输入法 —— 对应天坦 `F()`（排除输入法节点） */
        val IME: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                val pkg = node.packageName?.toString().orEmpty()
                return pkg.contains("inputmethod") || pkg.contains("ime")
            }
            override val name: String get() = "输入法"
        }

        /** 交互控件 —— 对应天坦 `f39050j`（9 种 role） */
        val CONTROL: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                return when (NodeRoleHelper.roleOf(node)) {
                    NodeRoleHelper.ROLE_BUTTON,
                    NodeRoleHelper.ROLE_IMAGE_BUTTON,
                    NodeRoleHelper.ROLE_EDIT_TEXT,
                    NodeRoleHelper.ROLE_CHECK_BOX,
                    NodeRoleHelper.ROLE_RADIO_BUTTON,
                    NodeRoleHelper.ROLE_TOGGLE_BUTTON,
                    NodeRoleHelper.ROLE_SWITCH,
                    NodeRoleHelper.ROLE_DROP_DOWN_LIST,
                    NodeRoleHelper.ROLE_SEEK_CONTROL -> true
                    else -> false
                }
            }
            override val name: String get() = "交互控件"
        }

        /** 有文本 —— 对应天坦 `S()` */
        val HAS_TEXT: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                return !node.text.isNullOrEmpty() ||
                        !node.contentDescription.isNullOrEmpty()
            }
            override val name: String get() = "有文本"
        }

        /** 标题 */
        val HEADING: NodeFilter = object : NodeFilter() {
            override fun accept(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                return try {
                    node.isHeading
                } catch (e: Throwable) {
                    false
                }
            }
            override val name: String get() = "标题"
        }

        /**
         * 从 lambda 构造 —— 兼容既有代码
         */
        fun of(block: (AccessibilityNodeInfo?) -> Boolean): NodeFilter =
            object : NodeFilter() {
                override fun accept(node: AccessibilityNodeInfo?): Boolean = block(node)
                override val name: String get() = "自定义"
            }
    }
}

/**
 * 转换辅助：让既有接受 lambda 的 API 也能接受 NodeFilter
 */
fun NodeFilter.asPredicate(): (AccessibilityNodeInfo) -> Boolean = { node ->
    accept(node)
}

/**
 * 反向转换：lambda 包装成 NodeFilter
 */
fun ((AccessibilityNodeInfo) -> Boolean).asFilter(): NodeFilter =
    NodeFilter.of { node -> node != null && this(node) }
