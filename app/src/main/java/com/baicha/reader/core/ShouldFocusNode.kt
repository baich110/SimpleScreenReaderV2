package com.baicha.reader.core

import android.graphics.Rect
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 可聚焦判定 —— 1:1 精确抄天坦 w7/C3678d.r(node, z9)
 *
 * 【关键：滑动浏览走的是 r(node, false)，不是 j()】
 *
 * 天坦 M6/h.java:435 E() → D(i9, z9, z10, z11, i10)，i10 默认 0
 *   → t(0) = C3678d.g() = f39014i = 内部类 a = r(node, false)
 *
 * t(i9) 的遍历模式表：
 *   0(默认) → g() = a = r(node, false)     ★滑动浏览用这个★
 *   1       → f39053m = n  (列表遍历)
 *   3       → f39054n = o  (拆分遍历)
 *   4       → g().and(f39055o)
 *   5       → g().and(h(f39050j))
 *   6       → g().and(f39051k)
 *
 * 而 C3678d.h() = C0496d = t() = AbstractC3682f.j() 只在 WebView(role 15) 场景用
 * （M6/h.java:751/908/1994 都是 `roleOf == 15 && B(...)`）
 *
 * 【天坦原版 r(node, z9)】
 *   ① node == null → false
 *   ② isWebView(node) → A0.b(node) 是否可见
 *   ③ !node.isVisibleToUser → false
 *   ④ if (f39007b == null) { 初始化 f39008c = node 边界 }
 *   ⑤ if (c(node) && node.childCount > 0) {
 *         微信：text == "图像" || "浮窗" → true；!k(node) → false
 *         其他：!k(node) || !i(node) → false
 *      }
 *   ⑥ role = roleOf(node)
 *      if (role == 10 && hasAction(4096, 8192, SET_PROGRESS)) → true
 *      if (role != 8 && role != 16) {
 *          z11 = n(node, false) || (W(node) && p(node))
 *          if (!z9) return z11          ← z9=false 直接返回
 *          if (z11) { ... }
 *          if (i(node) && !isSystemBar(node)) return true
 *      }
 *   ⑦ false
 *
 * 【辅助】
 *   n(node, false) = n0 || a0 || l0 || hasAction(16, 32)
 *       n0 = isScreenReaderFocusable()
 *       a0 = isClickable()
 *       l0 = isLongClickable()
 *   k(node) = (isFocusable || hasAction(16)) || (isSelected || hasAction(32))
 *   i(node) = isClickable || isLongClickable
 *   p(node) = hasText(node) && hasChildContent(node)
 *   W(node) = 父节点是 列表(8)/网格(5)/滚动视图(30)/横向滚动(31)
 */
object ShouldFocusNode {

    private const val WECHAT = "com.tencent.mm"

    /** 递归深度上限 */
    private const val MAX_DEPTH = 12

    private val tempRect = Rect()
    private val tempRect2 = Rect()

    /**
     * 清空调用内状态 —— 窗口切换时由 FocusController 调用
     *
     * 【修正】去掉了跨调用的 cache（内容 hash 做 key 且无失效机制 = 读到过期结果）。
     * 现在只保留一个「本次判定的递归深度护栏」，无需外部清理。
     */
    fun clear() {
        tempRect.setEmpty()
        tempRect2.setEmpty()
    }

    /**
     * 可聚焦判定 —— 1:1 抄天坦 C3678d.r(node, false)
     *
     * 【修正】原实现的 cache 用 AccessibilityNodeInfo 当 key（内容 hash），
     * 但节点内容会变（文本更新/位置移动），且没有任何失效机制 →
     * 跨页面读到过期结果，判定错误。
     *
     * 天坦的 f39007b/f39008c 只是「首次初始化边界」的哨兵，
     * 不是跨调用缓存。这里改为**不做持久缓存**，只在单次调用内递归用。
     */
    fun shouldFocus(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return r(node, false, 0)
    }

    /**
     * 修复路径5需要的「本次调用边界基准」——
     * 天坦用静态 f39008c 存「上一次处理的节点边界」，
     * 语义是「同一个节点被重复处理」。这里用调用栈内的 prevBounds 表达。
     */
    private fun r(node: AccessibilityNodeInfo, z9: Boolean, depth: Int, prevBounds: Rect? = null): Boolean {
        if (depth > MAX_DEPTH) return false

        // ② WebView 检查
        if (isWebView(node)) {
            val webView = findWebViewRoot(node)
            return webView?.isVisibleToUser == true
        }

        // ③ 可见性
        if (!node.isVisibleToUser) return false

        // ④ （原「初始化边界缓存」已移除，见 clear() 注释）

        // ⑤ 同一节点重复处理 且 有子节点
        if (prevBounds != null && isSameBounds(node, prevBounds) && node.childCount > 0) {
            if (WECHAT == node.packageName?.toString()) {
                val text = node.text?.toString()
                if (text == "图像" || text == "浮窗") return true
                if (!k(node)) return false
            } else {
                if (!k(node) || !i(node)) return false
            }
        }

        // ⑥ 角色判定
        val role = NodeRoleHelper.roleOf(node)

        if (role == NodeRoleHelper.ROLE_SEEK_CONTROL &&
            hasAction(node, 4096, 8192, SET_PROGRESS_ACTION)
        ) {
            return true
        }

        if (role != NodeRoleHelper.ROLE_LIST && role != NodeRoleHelper.ROLE_PAGER) {
            val z11 = n(node, false) || (W(node) && p(node))

            if (!z9) return z11

            if (z11) {
                if (node.childCount == 0 || p(node)) return true
                return k(node) && !k(node)
            }

            if (i(node) && !isSystemBar(node)) return true
        }

        return false
    }

    // ==================== 辅助（1:1 抄天坦） ====================

    /**
     * n(node, z9) —— 天坦 C3678d.n()
     *
     *   z9=false: n0 || a0 || l0 || hasAction(16, 32)
     *   z9=true:  n0 || g0 || a0 || l0 || hasAction(1, 16, 32)
     */
    private fun n(node: AccessibilityNodeInfo, z9: Boolean): Boolean {
        if (z9) {
            return isScreenReaderFocusable(node) || node.isFocusable ||
                    node.isClickable || node.isLongClickable ||
                    hasAction(node, 1, 16, 32)
        }
        return isScreenReaderFocusable(node) || node.isClickable ||
                node.isLongClickable || hasAction(node, 16, 32)
    }

    /**
     * k(node) = H(node) || N(node) —— 天坦 AbstractC3688i.k()
     */
    private fun k(node: AccessibilityNodeInfo): Boolean {
        val h = node.isFocusable || hasAction(node, 16)
        val nn = node.isSelected || hasAction(node, 32)
        return h || nn
    }

    /**
     * i(node) = isClickable || isLongClickable —— 天坦 AbstractC3688i.i()
     */
    private fun i(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.isLongClickable
    }

    /**
     * p(node) —— 天坦 C3678d.p()
     */
    private fun p(node: AccessibilityNodeInfo): Boolean {
        return hasText(node) && hasChildContent(node)
    }

    /**
     * W(node) —— 天坦 AbstractC3693m.W()
     *   父节点是 列表(8)/网格(5)/滚动视图(30)/横向滚动(31)
     */
    private fun W(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val parent = node.parent ?: return false
        return try {
            val role = NodeRoleHelper.roleOf(parent)
            role == NodeRoleHelper.ROLE_LIST ||
                    role == NodeRoleHelper.ROLE_GRID ||
                    role == NodeRoleHelper.ROLE_SCROLL_VIEW ||
                    role == NodeRoleHelper.ROLE_HORIZONTAL_SCROLL_VIEW
        } finally {
            runCatching { parent.recycle() }
        }
    }

    /**
     * 是否有文本 —— 天坦 AbstractC3693m.C()
     */
    private fun hasText(node: AccessibilityNodeInfo): Boolean {
        if (node.collectionInfo != null) return false
        return !node.text.isNullOrEmpty() ||
                !node.contentDescription.isNullOrEmpty() ||
                !node.hintText.isNullOrEmpty()
    }

    /**
     * 有子节点内容 —— 天坦 C3678d.k()
     */
    private fun hasChildContent(node: AccessibilityNodeInfo): Boolean {
        val count = node.childCount
        if (count > 3) return true

        node.getBoundsInScreen(tempRect)
        // 【修正】天坦用 HashSet<o0.w>（identity）。这里也必须 identity，
        // 否则内容相同的兄弟节点会被误判为重复 → 返回 false（漏判）。
        val seen = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        val parentBounds = Rect(tempRect)
        for (idx in 0 until count) {
            val child = node.getChild(idx) ?: continue
            if (seen.containsKey(child)) return false
            seen[child] = true
            if (child.isVisibleToUser) {
                if (child.childCount > 0) return true
                child.getBoundsInScreen(tempRect2)
                if (tempRect2 == tempRect) return true
                // ★ 传入父节点边界作为「重复处理」基准
                if (r(child, false, 0, parentBounds)) return true
            }
        }
        return false
    }

    /**
     * 是否系统栏 —— 天坦 AbstractC3693m.y(node, f39020o)
     */
    private fun isSystemBar(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: return false
        return id == "com.android.systemui:id/back" ||
                id == "com.android.systemui:id/home" ||
                id == "com.android.systemui:id/recent_apps"
    }

    /**
     * isScreenReaderFocusable —— 天坦 o0.w.n0()
     */
    private fun isScreenReaderFocusable(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                node.isScreenReaderFocusable
            } else {
                val extras = node.extras
                extras != null && extras.getBoolean(
                    "androidx.view.accessibility.AccessibilityNodeInfoCompat.SCREEN_READER_FOCUSABLE_KEY",
                    false
                )
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 是否 WebView —— 天坦 AbstractC3688i.v()
     */
    private fun isWebView(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: return false
        return cls.contains("WebView") || cls.contains("webkit")
    }

    /**
     * 找 WebView 根 —— 天坦 A0.b()
     */
    private fun findWebViewRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 10) {
            if (NodeRoleHelper.roleOf(cur) == NodeRoleHelper.ROLE_WEB_VIEW) return cur
            cur = cur.parent
            depth++
        }
        return node
    }

    /**
     * 边界是否和指定基准一致 —— 天坦 C3678d.c()
     */
    private fun isSameBounds(node: AccessibilityNodeInfo, base: Rect): Boolean {
        node.getBoundsInScreen(tempRect)
        return tempRect == base
    }

    private fun hasAction(node: AccessibilityNodeInfo, vararg ids: Int): Boolean {
        return try {
            node.actionList.any { it.id in ids }
        } catch (e: Throwable) {
            false
        }
    }

    /** ACTION_SET_PROGRESS */
    private const val SET_PROGRESS_ACTION = 16908349
}