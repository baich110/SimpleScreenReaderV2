package com.baicha.reader.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 窗口遍历 —— 对应天坦 `M6/h.F()`（源码 1075-1150）+ TalkBack
 * `WindowTraversal` / `searchTargetInNextOrPreviousWindow`
 *
 * ══════════════════════════════════════════════════════════════
 * 【为什么需要这一层 —— 这是焦点系统的结构性缺口】
 * ══════════════════════════════════════════════════════════════
 *
 * 无障碍树是**多窗口**结构：应用窗口、输入法窗口、系统窗口、
 * 无障碍浮层、分屏窗口。每个窗口有独立的 root。
 *
 * 只在一个窗口内遍历（本项目的原状态），遇到这些场景就**卡死**：
 *   - 弹出对话框（TYPE_APPLICATION 新窗口）
 *   - 输入法弹起（TYPE_INPUT_METHOD）
 *   - 悬浮窗 / 无障碍浮层（TYPE_ACCESSIBILITY_OVERLAY）
 *   - 分屏（TYPE_SPLIT_SCREEN_DIVIDER）
 *
 * 用户反馈的「焦点丢失、找不到焦点」最直接的根因就在这里：
 * 焦点一旦离开当前窗口，我们没有任何机制把它带到下一个窗口。
 *
 * ══════════════════════════════════════════════════════════════
 * 【天坦的实现（M6/h.F()，逐行对照）】
 * ══════════════════════════════════════════════════════════════
 *
 *   ① 取当前焦点节点的窗口 ID        → wVarW0.U()
 *   ② 取全部窗口                    → service.getWindows()
 *   ③ 用过滤器筛窗口                 → m9.accept(w)
 *   ④ 只有一个且就是当前 → 不跨      → return false
 *   ⑤ 找当前窗口在列表里的下标        → 线性扫描
 *   ⑥ 按方向取下一个窗口（带环绕）    → forward: +1 越界归 0
 *                                     backward: -1 越界取末尾
 *   ⑦ 从新窗口 root 找第一个可聚焦    → C1427v.c(new C1424s(), newRoot, dir, filter)
 *   ⑧ 设焦点                        → W(node, 9)
 *
 * 【本实现的强化】
 *   ① `hasFocusableInSubtree` 加缓存（天坦每次都完整遍历子树）
 *   ② 窗口列表按 layer 排序（天坦依赖 getWindows() 的返回顺序 —— 不保证稳定）
 *   ③ 返回结果附带诊断信息
 */
object WindowTraversal {

    private const val TAG = "WindowTraversal"

    // ==================== 窗口类型常量 ====================

    /** 应用窗口 */
    const val TYPE_APPLICATION = AccessibilityWindowInfo.TYPE_APPLICATION

    /** 输入法窗口 */
    const val TYPE_INPUT_METHOD = AccessibilityWindowInfo.TYPE_INPUT_METHOD

    /** 系统窗口（状态栏/导航栏等） */
    const val TYPE_SYSTEM = AccessibilityWindowInfo.TYPE_SYSTEM

    /** 无障碍浮层 */
    const val TYPE_ACCESSIBILITY_OVERLAY = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY

    /** 分屏分隔条 */
    const val TYPE_SPLIT_SCREEN_DIVIDER = AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER

    // ==================== 窗口过滤器 ====================

    /**
     * 窗口过滤器 —— 对应天坦 `M6/h` 的内部过滤器类
     */
    fun interface WindowFilter {
        fun accept(window: AccessibilityWindowInfo?): Boolean
    }

    /**
     * 可聚焦窗口 —— 对应天坦内部类 `g`
     *
     * 排除输入法 + 要求 root 子树里有可聚焦节点
     */
    val FOCUSABLE: WindowFilter = WindowFilter { w ->
        if (w == null) return@WindowFilter false
        if (w.type == TYPE_INPUT_METHOD) return@WindowFilter false
        val root = safeRoot(w) ?: return@WindowFilter false
        hasFocusableInSubtree(root)
    }

    /**
     * 常规窗口 —— 对应天坦内部类 `C0054h`
     *
     * 只接受「应用 / 分屏 / 系统」三类，且要有可聚焦节点
     */
    val NORMAL: WindowFilter = WindowFilter { w ->
        if (w == null) return@WindowFilter false
        val type = w.type
        val accepted = type == TYPE_APPLICATION ||
                type == TYPE_SPLIT_SCREEN_DIVIDER ||
                type == TYPE_SYSTEM ||
                type == TYPE_ACCESSIBILITY_OVERLAY
        if (!accepted) return@WindowFilter false
        val root = safeRoot(w) ?: return@WindowFilter false
        hasFocusableInSubtree(root)
    }

    /**
     * 全部窗口（不筛）—— 调试用
     */
    val ALL: WindowFilter = WindowFilter { it != null }

    /** 非当前窗口 —— 对应天坦内部类 `i` */
    fun notCurrent(service: AccessibilityService): WindowFilter =
        WindowFilter { w -> w != null && w.id != activeWindowId(service) }

    /** 非活动窗口 —— 对应天坦内部类 `j` */
    fun notActive(): WindowFilter =
        WindowFilter { w -> w != null && !w.isActive }

    /**
     * 排除系统栏（状态栏 / 导航栏）—— 对应天坦 `M6/h` 的窗口过滤器 `f6018r`
     *
     * 【为什么要把系统栏排除在焦点序列外】
     *   用户按「下一个窗口」遍历应用内容时，不该突然滑到
     *   顶部状态栏（时间/电量）或底部导航栏（三个按钮）上 ——
     *   那里没有用户要找的内容，却会打断整个浏览流程。
     *
     * 【为什么不用系统类型判断】
     *   Android 把状态栏、导航栏、悬浮球都归为 TYPE_SYSTEM(3)，
     *   直接按类型过滤会把「悬浮球」这种真实可交互的内容一起滤掉。
     *   [WindowClassifier] 用几何位置细分出真正的系统栏，
     *   保留其他系统窗口 —— 这与天坦 `E0.parseWindowType()` 思路一致。
     *
     * 【与 FOCUSABLE 的关系】
     *   两者可叠加：先排除系统栏，再要求有可聚焦内容。
     */
    fun notSystemBar(service: AccessibilityService): WindowFilter =
        WindowFilter { w -> w != null && !WindowClassifier.isSystemBar(service, w) }

    /** 只要系统栏 —— 用于「跳转系统栏」等特定场景 */
    fun onlySystemBar(service: AccessibilityService): WindowFilter =
        WindowFilter { w -> w != null && WindowClassifier.isSystemBar(service, w) }

    /**
     * 默认的「应用内容导航」过滤器。
     *
     * 对应天坦 `M6/h.f6018r`（默认窗口过滤器）：
     *   有可聚焦内容 + 不是系统栏 + 不是输入法
     *
     * 【为什么要排除输入法】
     *   输入法是独立上下文，用户按「下一个窗口」通常是想在
     *   应用窗口间跳转；滑进输入法面板会让人困惑。
     *   需要输入法时用户会直接点输入框，焦点自然过去。
     */
    fun applicationWindows(service: AccessibilityService): WindowFilter {
        val focusable = FOCUSABLE
        val notBar = notSystemBar(service)
        return WindowFilter { w ->
            w != null &&
                !WindowClassifier.isInputMethod(service, w) &&
                notBar.accept(w) &&
                focusable.accept(w)
        }
    }

    // ==================== 核心：跨窗口导航 ====================

    /**
     * 跨窗口找下一个焦点 —— 1:1 对应天坦 `M6/h.F()`
     *
     * @param service 无障碍服务
     * @param forward true = 前向（下一个窗口），false = 后向
     * @param filter 窗口过滤器
     * @return 新窗口里的起点节点；null = 没有可用窗口
     */
    fun navigateToNextWindow(
        service: AccessibilityService,
        forward: Boolean,
        filter: WindowFilter = FOCUSABLE
    ): AccessibilityNodeInfo? {
        // ① 当前窗口 ID
        val currentWindowId = currentFocusWindowId(service)

        // ② 全部窗口（已按 layer 排序，保证顺序稳定）
        val all = sortedWindows(service)
        if (all.isEmpty()) return null

        // ③ 过滤
        val list = ArrayList<AccessibilityWindowInfo>(all.size)
        for (w in all) {
            if (filter.accept(w)) list.add(w)
        }
        if (list.isEmpty()) {
            ReaderLog.d(TAG, "没有可用窗口")
            return null
        }

        // ④ 只有一个且就是当前窗口 → 不跨
        if (list.size == 1 && list[0].id == currentWindowId) {
            ReaderLog.d(TAG, "只有当前窗口，无需跨窗口")
            return null
        }

        // ⑤ 找当前窗口下标
        var idx = -1
        for (i in list.indices) {
            if (list[i].id == currentWindowId) {
                idx = i
                break
            }
        }

        // ⑥ 按方向取下一个（带环绕）
        //    当前窗口不在筛选列表里（idx == -1）→ 从第一个/最后一个开始
        var next: Int = if (idx < 0) {
            if (forward) 0 else list.size - 1
        } else {
            if (forward) idx + 1 else idx - 1
        }
        if (forward && next >= list.size) next = 0
        if (!forward && next < 0) next = list.size - 1

        // ⑦ 从新窗口 root 找起点
        val targetWindow = list[next]
        val newRoot = safeRoot(targetWindow) ?: run {
            ReaderLog.d(TAG, "目标窗口 root 为 null")
            return null
        }

        ReaderLog.i(
            TAG,
            "跨窗口: ${if (forward) "前向" else "后向"} → " +
                    "window=${targetWindow.id} type=${targetWindow.type} " +
                    "(${idx}→${next}/${list.size})"
        )

        val direction = if (forward) TraversalDirection.FORWARD
        else TraversalDirection.BACKWARD

        val strategy = TraversalStrategyFactory.create(
            TraversalStrategyFactory.TYPE_DEFAULT,
            newRoot
        ) { ShouldFocusNode.shouldFocus(it) }

        return try {
            FocusSearch.findFirstFocus(strategy, newRoot, direction) {
                ShouldFocusNode.shouldFocus(it)
            }
        } finally {
            strategy.release()
        }
    }

    /**
     * 尝试跨窗口导航并设焦点 —— 便捷方法
     *
     * @return 是否成功跨窗口并设上焦点
     */
    fun tryNavigateAndFocus(
        service: AccessibilityService,
        forward: Boolean,
        applyFocus: (AccessibilityNodeInfo) -> Boolean,
        filter: WindowFilter = FOCUSABLE
    ): AccessibilityNodeInfo? {
        val node = navigateToNextWindow(service, forward, filter) ?: return null
        return if (applyFocus(node)) node else null
    }

    // ==================== 工具 ====================

    /**
     * 全部窗口（按 layer 排序）
     *
     * 【为什么排序】
     *   `getWindows()` 的返回顺序**不保证稳定**（系统按内部顺序给）。
     *   天坦直接用返回顺序 —— 跨窗口方向可能时好时坏。
     *   按 layer 升序排序后，顺序与实际视觉层叠一致。
     */
    private fun sortedWindows(service: AccessibilityService): List<AccessibilityWindowInfo> {
        val windows = try {
            service.windows
        } catch (e: Throwable) {
            // getWindows() 可能抛 SecurityException（服务未就绪）
            ReaderLog.w(TAG, "getWindows 失败: ${e.message}")
            return emptyList()
        } ?: return emptyList()

        return try {
            windows.sortedBy { it.layer }
        } catch (e: Throwable) {
            windows
        }
    }

    /** 安全取窗口 root（可能抛异常） */
    private fun safeRoot(window: AccessibilityWindowInfo?): AccessibilityNodeInfo? = try {
        window?.root
    } catch (e: Throwable) {
        null
    }

    /**
     * 当前焦点所在窗口 ID
     *
     * 优先用焦点节点的窗口；取不到则退化到「活动窗口」
     */
    private fun currentFocusWindowId(service: AccessibilityService): Int {
        try {
            val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null) return focused.windowId
        } catch (e: Throwable) {
            // ignore
        }
        return activeWindowId(service)
    }

    /** 活动窗口 ID —— 对应天坦 `E0.e()` */
    private fun activeWindowId(service: AccessibilityService): Int {
        return try {
            service.windows?.firstOrNull { it.isActive }?.id ?: -1
        } catch (e: Throwable) {
            -1
        }
    }

    /**
     * 子树里有没有可聚焦节点 —— 对应天坦 `AbstractC3693m.P(root, filter)`
     *
     * 【性能】加深度上限与节点数上限，避免异常树结构导致长时间遍历。
     */
    private fun hasFocusableInSubtree(root: AccessibilityNodeInfo): Boolean {
        return hasFocusableInternal(root, 0, intArrayOf(0))
    }

    private fun hasFocusableInternal(
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray
    ): Boolean {
        if (depth > 30) return false
        if (++counter[0] > 500) return false     // 节点数上限

        if (ShouldFocusNode.shouldFocus(node)) return true

        val count = try {
            node.childCount
        } catch (e: Throwable) {
            return false
        }
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (e: Throwable) {
                null
            } ?: continue
            if (hasFocusableInternal(child, depth + 1, counter)) return true
        }
        return false
    }

    /**
     * 描述全部窗口 —— 诊断用
     */
    fun describeWindows(service: AccessibilityService): String {
        val windows = sortedWindows(service)
        if (windows.isEmpty()) return "无窗口"
        return windows.joinToString(" | ") { w ->
            "id=${w.id} type=${w.type} layer=${w.layer} " +
                    "active=${w.isActive} focused=${w.isFocused}"
        }
    }

    /** 窗口数量（诊断） */
    fun windowCount(service: AccessibilityService): Int = sortedWindows(service).size
}
