package com.baicha.reader.core

import android.graphics.Rect
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 可聚焦判定 —— **1:1 移植 TalkBack `AccessibilityNodeInfoUtils.shouldFocusNode()`**
 *
 * ══════════════════════════════════════════════════════════════════
 * 【为什么推倒重来】
 * ══════════════════════════════════════════════════════════════════
 *
 * 【用户原话】（2026-10-03）
 * > 你把这些规则，那些你把这些算法，像 TalkBack 看齐行不行？一次性补全行不行
 *
 * 【旧实现（`ShouldFocusNode.kt`，2282 行）的问题】
 *   抄的是**天坦** `AccessibilityNodeInfoUtils.shouldFocusNodeInternal`。
 *   天坦的实现有大量**私有补丁**（`isInRecyclerView`、`isSamsungAdapterView`、
 *   `isType15`、`isSystemBar`、`NodeCache` 缓存层……），
 *   这些补丁是为**天坦自己的播报策略**服务的，不是通用语义。
 *
 *   真机表现：建树 107 个节点，但**只有 2 个可聚焦** ——
 *   焦点在 `EditText` ↔ `View` 之间无限横跳，走不到对话内容。
 *
 * 【TalkBack 的实现（本文件）】
 *   TalkBack 的判定链**干净、可解释**，只有 7 步：
 *
 *   ```
 *   shouldFocusNode(node, cache, checkChildren=true):
 *     ① node == null                          → false
 *     ② supportsWebActions(node)              → WebView 容器可见？
 *     ③ !isVisible(node)                      → false
 *     ④ isPictureInPicture(node)              → true
 *     ⑤ areBoundsIdenticalToWindow(node)
 *        && childCount > 0
 *        && !isFocusableOrClickable(node)     → false   （全屏容器且不可操作）
 *     ⑥ accessibilityFocusable =
 *          isFocusableOrClickable(node)
 *          || (isTopLevelScrollItem(node) && isSpeakingNode(node))
 *        · checkChildren == false → return accessibilityFocusable
 *        · accessibilityFocusable:
 *            childCount == 0                    → true    （叶子可聚焦）
 *            isSpeakingNode(node)               → true    （有话说）
 *            否则                                → false   （可聚焦但没话说）
 *     ⑦ !hasMatchingAncestor(node, shouldFocusNode(_, _, false))
 *        && (hasText(node) || hasStateDescription(node))
 *                                              → true    （有文本且无聚焦祖先）
 *        否则                                   → false
 *   ```
 *
 *   关键差异：TalkBack **没有**「RecyclerView 特殊处理」、
 *   **没有**「三星 AdapterView」、**没有**「系统栏」、
 *   **没有**缓存层。它靠 `isTopLevelScrollItem` + `isSpeakingNode`
 *   两个通用概念覆盖滚动列表场景。
 *
 * 【源码位置】
 *   · `utils/.../AccessibilityNodeInfoUtils.java:838~949`（shouldFocusNode）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:1001~1040`（isSpeakingNode）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:1046~1110`（hasNonActionableSpeakingChildren）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:1159~1180`（isActionableForAccessibility）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:1855~1869`（isScrollable）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:1879~1905`（hasText / hasStateDescription）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:1908~1918`（isFocusableOrClickable）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:1920~1965`（isTopLevelScrollItem）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:2364~2372`（isVisible）
 *   · `utils/.../AccessibilityNodeInfoUtils.java:2428~2442`（areBoundsIdenticalToWindow）
 *
 * ══════════════════════════════════════════════════════════════════
 * 【设计取舍】
 * ══════════════════════════════════════════════════════════════════
 *
 * ① **不用 `AccessibilityNodeInfoCompat`**：那是 AndroidX 的 wrapper。
 *    本项目直接用 framework 的 `AccessibilityNodeInfo`，
 *    所有 `compat.xxx()` 调用改成 framework 等价 API。
 *
 * ② **`Role` 判定用 className 近似**：
 *    TalkBack 的 `Role.getRole()` 会检查 `CollectionInfo`、
 *    `isScrollable`、className 等多个维度。本实现用 className + 动作
 *    近似，覆盖 List/Grid/ScrollView 三大类（真机验证足够）。
 *
 * ③ **`speakingNodesCache` 保留**：TalkBack 用它避免重复递归。
 *    本实现用 `HashMap<AccessibilityNodeInfo, Boolean>`，
 *    与 `nodeMap` 一致用内容比较。
 *
 * ④ **`visitedNodes` 防环**：TalkBack 用 `HashSet`，
 *    本实现用 `HashSet<AccessibilityNodeInfo>`（内容比较）。
 */
object TalkBackShouldFocus {

    private const val TAG = "TalkBackShouldFocus"

    /** 诊断开关 */
    @JvmStatic
    var diagnose: Boolean = false

    /** 递归深度上限（防畸形树栈溢出） */
    private const val MAX_DEPTH = 32

    // ==================== 缓存 ====================

    /**
     * 「有没有话说」缓存 —— 对应 TalkBack `speakingNodesCache`
     *
     * 【为什么需要】`isSpeakingNode` 会递归检查子节点，
     *   而 `shouldFocusNode` 里可能对同一节点问多次。
     *   无缓存时真机 107 节点会退化成 O(n²)。
     */
    private val speakingCache = HashMap<AccessibilityNodeInfo, Boolean>()

    /** 清空缓存 —— 每次导航前调用 */
    @JvmStatic
    fun clearCache() {
        speakingCache.clear()
    }

    // ==================== 主入口 ====================

    /**
     * 是否应该聚焦 —— 1:1 对应 TalkBack `shouldFocusNode(node, cache, true)`
     */
    @JvmStatic
    fun shouldFocus(node: AccessibilityNodeInfo?): Boolean {
        return shouldFocus(node, true, 0)
    }

    /**
     * 是否应该聚焦（带 `checkChildren`）—— 对应 TalkBack 的三参重载
     *
     * @param checkChildren `false` 时只检查「节点自身是否可聚焦」，
     *   不递归检查子节点。用于第 ⑦ 步的 `hasMatchingAncestor` 判定
     *   —— 避免向上查找时重复下钻。
     */
    @JvmStatic
    fun shouldFocus(
        node: AccessibilityNodeInfo?,
        checkChildren: Boolean,
        depth: Int = 0
    ): Boolean {
        if (depth > MAX_DEPTH) return false

        // ═══ ① node == null ═══
        if (node == null) return false

        // ═══ ② WebView 特殊路径 ═══
        // TalkBack 原文：
        //   if (WebInterfaceUtils.supportsWebActions(node)) {
        //     AccessibilityNodeInfoCompat webViewContainer =
        //         WebInterfaceUtils.ascendToWebViewContainer(node);
        //     return webViewContainer != null && webViewContainer.isVisibleToUser();
        //   }
        if (supportsWebActions(node)) {
            val container = ascendToWebViewContainer(node)
            return container != null && isVisibleToUserSafe(container)
        }

        // ═══ ③ 可见性 ═══
        if (!isVisible(node)) return false

        // ═══ ④ 画中画 ═══
        // TalkBack 原文：
        //   if (isPictureInPicture(node)) return true;
        if (isPictureInPicture(node)) return true

        // ═══ ⑤ 全屏容器且不可操作 ═══
        // TalkBack 原文：
        //   if (areBoundsIdenticalToWindow(node)
        //       && node.getChildCount() > 0
        //       && !isFocusableOrClickable(node)) return false;
        if (areBoundsIdenticalToWindow(node)
            && childCountSafe(node) > 0
            && !isFocusableOrClickable(node)
        ) {
            return false
        }

        // ═══ ⑥ 可聚焦性 ═══
        // TalkBack 原文：
        //   boolean accessibilityFocusable =
        //       isFocusableOrClickable(node)
        //           || (isTopLevelScrollItem(node) && isSpeakingNode(node, null, visitedNodes));
        val visited = HashSet<AccessibilityNodeInfo>()
        val accessibilityFocusable =
            isFocusableOrClickable(node)
                || (isTopLevelScrollItem(node) && isSpeakingNode(node, visited, 0))

        if (!checkChildren) {
            // TalkBack 原文：checkChildren=false 时直接返回，不递归
            return accessibilityFocusable
        }

        if (accessibilityFocusable) {
            visited.clear()
            // TalkBack 原文：
            //   if (node.getChildCount() == 0) return true;
            //   else if (isSpeakingNode(node, cache, visitedNodes)) return true;
            //   else return false;
            if (childCountSafe(node) == 0) return true
            if (isSpeakingNode(node, visited, 0)) return true
            return false
        }

        // ═══ ⑦ 有文本且无聚焦祖先 ═══
        // TalkBack 原文：
        //   Filter filter = node -> shouldFocusNode(node, cache, false);
        //   if (!hasMatchingAncestor(node, filter)
        //       && (hasText(node) || hasStateDescription(node))) return true;
        //   return false;
        if (!hasMatchingAncestor(node) { n ->
                shouldFocus(n, false, depth + 1)
            }
            && (hasText(node) || hasStateDescription(node))
        ) {
            return true
        }

        return false
    }

    // ==================== 子函数（1:1 移植）====================

    /**
     * 可见性 —— 对应 TalkBack `isVisible()`
     *
     * ```java
     * public static boolean isVisible(AccessibilityNodeInfoCompat node) {
     *   return node != null
     *       && (node.isVisibleToUser()
     *           || (WebInterfaceUtils.isWebContainer(node)
     *               && Role.getRole(node) != Role.ROLE_WEB_VIEW));
     * }
     * ```
     */
    private fun isVisible(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (isVisibleToUserSafe(node)) return true
        // WebView 容器内的子节点：即使不可见也允许聚焦
        // （WebView 会自己滚动到该节点）
        return isWebContainer(node) && !isWebViewClass(node)
    }

    /**
     * 可聚焦或可点击 —— 对应 TalkBack `isFocusableOrClickable()`
     *
     * ```java
     * private static boolean isFocusableOrClickable(AccessibilityNodeInfoCompat node) {
     *   return (node != null)
     *       && isVisible(node)
     *       && (node.isScreenReaderFocusable() || isActionableForAccessibility(node));
     * }
     * ```
     */
    private fun isFocusableOrClickable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (!isVisible(node)) return false
        return isScreenReaderFocusableSafe(node) || isActionableForAccessibility(node)
    }

    /**
     * 无障碍可操作性 —— 对应 TalkBack `isActionableForAccessibility()`
     *
     * ```java
     * public static boolean isActionableForAccessibility(AccessibilityNodeInfoCompat node) {
     *   if (node == null) return false;
     *   if (isClickable(node) || isLongClickable(node)) return true;
     *   if (node.isFocusable()) return true;
     *   if (WebInterfaceUtils.hasNativeWebContent(node)) {
     *     return supportsAnyAction(node, ACTION_FOCUS);
     *   }
     *   return supportsAnyAction(node,
     *       ACTION_FOCUS, ACTION_NEXT_HTML_ELEMENT, ACTION_PREVIOUS_HTML_ELEMENT);
     * }
     * ```
     */
    private fun isActionableForAccessibility(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        if (isClickableSafe(node) || isLongClickableSafe(node)) return true
        if (isFocusableSafe(node)) return true

        if (hasNativeWebContent(node)) {
            return hasAction(node, AccessibilityNodeInfo.ACTION_FOCUS)
        }
        return hasAction(
            node,
            AccessibilityNodeInfo.ACTION_FOCUS,
            ACTION_NEXT_HTML_ELEMENT,
            ACTION_PREVIOUS_HTML_ELEMENT
        )
    }

    /**
     * 是否顶层滚动项 —— 对应 TalkBack `isTopLevelScrollItem()`
     *
     * ```java
     * public static boolean isTopLevelScrollItem(AccessibilityNodeInfoCompat node) {
     *   if (node == null) return false;
     *   if (!isVisible(node)) return false;
     *   AccessibilityNodeInfoCompat parent = node.getParent();
     *   return isScrollItem(parent);
     * }
     * ```
     *
     * 【注意】这里会调 `getParent()` —— 但**只用于判定**，
     *   不影响遍历（遍历用 `WorkingTree` 的固化 parent）。
     *   Compose 上 `getParent()` 返回 null → `isScrollItem(null)` → false，
     *   是安全的降级。
     */
    private fun isTopLevelScrollItem(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (!isVisible(node)) return false
        val parent = try {
            node.parent
        } catch (e: Throwable) {
            null
        }
        return isScrollItem(parent)
    }

    /**
     * 是否是滚动容器 —— 对应 TalkBack `isScrollItem()`
     *
     * ```java
     * private static boolean isScrollItem(AccessibilityNodeInfoCompat node) {
     *   if (node == null) return false;
     *   if (Role.getRole(node) == Role.ROLE_DROP_DOWN_LIST) return false;
     *   if (isScrollable(node)) return true;
     *   @Role.RoleName int parentRole = Role.getRole(node);
     *   return parentRole == ROLE_LIST || parentRole == ROLE_GRID
     *       || parentRole == ROLE_SCROLL_VIEW || parentRole == ROLE_HORIZONTAL_SCROLL_VIEW
     *       || nodeMatchesAnyClassByType(node, CLASS_TOUCHWIZ_TWADAPTERVIEW);
     * }
     * ```
     *
     * 【本实现】用 className + 滚动动作近似 Role。
     */
    private fun isScrollItem(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // Spinner 不算（TalkBack 原文）
        if (isDropDownList(node)) return false

        // 有滚动动作 → 是滚动容器
        if (isScrollable(node)) return true

        // className 近似 Role
        val cls = classNameSafe(node)
        return cls.contains("ListView")
            || cls.contains("GridView")
            || cls.contains("RecyclerView")
            || cls.contains("ScrollView")
            || cls.contains("ViewPager")
            || cls.contains("HorizontalScrollView")
            || cls.contains("AdapterView")
    }

    /**
     * 是否有滚动动作 —— 对应 TalkBack `isScrollable()`
     *
     * ```java
     * public static boolean isScrollable(AccessibilityNodeInfoCompat node) {
     *   return supportsAnyAction(node,
     *       ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD,
     *       ACTION_SCROLL_DOWN, ACTION_SCROLL_UP,
     *       ACTION_SCROLL_RIGHT, ACTION_SCROLL_LEFT);
     * }
     * ```
     */
    private fun isScrollable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return hasAction(
            node,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            ACTION_SCROLL_UP,
            ACTION_SCROLL_DOWN,
            ACTION_SCROLL_LEFT,
            ACTION_SCROLL_RIGHT
        )
    }

    /**
     * 有没有话说 —— 对应 TalkBack `isSpeakingNode()`
     *
     * ```java
     * private static boolean isSpeakingNode(
     *     node, speakingNodesCache, visitedNodes) {
     *   if (speakingNodesCache != null && speakingNodesCache.containsKey(node)) {
     *     return speakingNodesCache.get(node);
     *   }
     *   boolean result = false;
     *   if (hasText(node)) result = true;
     *   else if (hasStateDescription(node)) result = true;
     *   else if (node.isCheckable()) result = true;
     *   else if (hasNonActionableSpeakingChildren(node, cache, visitedNodes)) result = true;
     *   if (speakingNodesCache != null) speakingNodesCache.put(node, result);
     *   return result;
     * }
     * ```
     */
    private fun isSpeakingNode(
        node: AccessibilityNodeInfo,
        visited: MutableSet<AccessibilityNodeInfo>,
        depth: Int
    ): Boolean {
        if (depth > MAX_DEPTH) return false

        speakingCache[node]?.let { return it }

        var result = false
        if (hasText(node)) {
            result = true
        } else if (hasStateDescription(node)) {
            result = true
        } else if (isCheckableSafe(node)) {
            result = true
        } else if (hasNonActionableSpeakingChildren(node, visited, depth)) {
            result = true
        }

        speakingCache[node] = result
        return result
    }

    /**
     * 有「不可操作但有话说」的子节点 —— 对应 TalkBack `hasNonActionableSpeakingChildren()`
     *
     * ```java
     * private static boolean hasNonActionableSpeakingChildren(
     *     node, speakingNodesCache, visitedNodes) {
     *   final int childCount = node.getChildCount();
     *   for (int i = 0; i < childCount; i++) {
     *     child = node.getChild(i);
     *     if (child == null) continue;
     *     if (!visitedNodes.add(child)) return false;      // ★ 防环
     *     if (!isVisible(child)) continue;                  // 跳过不可见
     *     if (isFocusableOrClickable(child)) continue;      // 跳过可聚焦
     *     if ((isTopLevelScrollItem(child) && isSpeakingNode(child, cache, visitedNodes))
     *         && !(isClickable(node) || isLongClickable(node))) continue;
     *     if (isSpeakingNode(child, cache, visitedNodes)) return true;
     *   }
     *   return hasInvisibleNonActionableSpeakingChildren(node, childCount);
     * }
     * ```
     */
    private fun hasNonActionableSpeakingChildren(
        node: AccessibilityNodeInfo,
        visited: MutableSet<AccessibilityNodeInfo>,
        depth: Int
    ): Boolean {
        if (depth > MAX_DEPTH) return false

        val count = childCountSafe(node)
        for (i in 0 until count) {
            val child = childSafe(node, i) ?: continue

            // ★ 防环：visited 里已有 → 直接返回 false（TalkBack 原文行为）
            if (!visited.add(child)) return false

            if (!isVisible(child)) continue
            if (isFocusableOrClickable(child)) continue

            // 顶层滚动项且有话说，且父节点不可点击 → 跳过
            if (isTopLevelScrollItem(child)
                && isSpeakingNode(child, visited, depth + 1)
                && !(isClickableSafe(node) || isLongClickableSafe(node))
            ) {
                continue
            }

            if (isSpeakingNode(child, visited, depth + 1)) return true
        }

        // 不可见的非操作子节点（TalkBack 的兜底）
        return hasInvisibleNonActionableSpeakingChildren(node, count)
    }

    /**
     * 不可见但可播报的子节点 —— 对应 TalkBack `hasInvisibleNonActionableSpeakingChildren()`
     *
     * 【TalkBack 原文要点】
     *   · 如果 node 本身是「自动滚动容器」（`FILTER_AUTO_SCROLL`）→ 直接 false
     *   · 遍历子节点，找「不可见 + 不可操作 + 有文本」的
     */
    private fun hasInvisibleNonActionableSpeakingChildren(
        node: AccessibilityNodeInfo,
        childCount: Int
    ): Boolean {
        // 自动滚动容器不因不可见子节点而获得焦点
        if (isAutoScrollContainer(node)) return false

        for (i in 0 until childCount) {
            val child = childSafe(node, i) ?: continue
            if (isVisible(child)) continue              // 只看不可见的
            if (isFocusableOrClickable(child)) continue // 跳过可操作
            if (hasText(child) || hasStateDescription(child)) return true
        }
        return false
    }

    /**
     * 是否有文本 —— 对应 TalkBack `hasText()`
     *
     * ```java
     * private static boolean hasText(AccessibilityNodeInfoCompat node) {
     *   return node != null
     *       && node.getCollectionInfo() == null
     *       && (!TextUtils.isEmpty(getText(node))
     *           || !TextUtils.isEmpty(node.getContentDescription())
     *           || !TextUtils.isEmpty(node.getHintText()));
     * }
     * ```
     */
    private fun hasText(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (hasCollectionInfo(node)) return false
        if (!TextUtils.isEmpty(textSafe(node))) return true
        if (!TextUtils.isEmpty(contentDescSafe(node))) return true
        if (!TextUtils.isEmpty(hintTextSafe(node))) return true
        return false
    }

    /**
     * 是否有状态描述 —— 对应 TalkBack `hasStateDescription()`
     *
     * ```java
     * private static boolean hasStateDescription(AccessibilityNodeInfoCompat node) {
     *   return node != null
     *       && (!TextUtils.isEmpty(node.getStateDescription())
     *           || node.isCheckable()
     *           || hasValidRangeInfo(node));
     * }
     * ```
     */
    private fun hasStateDescription(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (!TextUtils.isEmpty(stateDescSafe(node))) return true
        if (isCheckableSafe(node)) return true
        if (hasValidRangeInfo(node)) return true
        return false
    }

    /**
     * 是否有有效 RangeInfo —— 对应 TalkBack `hasValidRangeInfo()`
     */
    private fun hasValidRangeInfo(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            val ri = node.rangeInfo ?: return false
            val max = ri.max
            val min = ri.min
            val cur = ri.current
            max > min && cur in min..max
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 边界是否等于窗口 —— 对应 TalkBack `areBoundsIdenticalToWindow()`
     *
     * ```java
     * private static boolean areBoundsIdenticalToWindow(AccessibilityNodeInfoCompat node) {
     *   if (node == null) return false;
     *   AccessibilityWindowInfoCompat window = getWindow(node);
     *   if (window == null) return false;
     *   Rect windowBounds = new Rect();
     *   window.getBoundsInScreen(windowBounds);
     *   Rect nodeBounds = new Rect();
     *   node.getBoundsInScreen(nodeBounds);
     *   return windowBounds.equals(nodeBounds);
     * }
     * ```
     */
    private fun areBoundsIdenticalToWindow(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val window = try {
            node.window
        } catch (e: Throwable) {
            null
        } ?: return false

        val windowBounds = Rect()
        val nodeBounds = Rect()
        return try {
            window.getBoundsInScreen(windowBounds)
            node.getBoundsInScreen(nodeBounds)
            windowBounds == nodeBounds
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 是否有匹配的祖先 —— 对应 TalkBack `hasMatchingAncestor()`
     *
     * ```java
     * public static boolean hasMatchingAncestor(
     *     AccessibilityNodeInfoCompat node, Filter<AccessibilityNodeInfoCompat> filter) {
     *   AccessibilityNodeInfoCompat currentNode =
     *       getMatchingAncestor(node, filter);
     *   return currentNode != null;
     * }
     * ```
     *
     * 【注意】这里会调 `getParent()` —— 只用于判定，不影响遍历。
     */
    private fun hasMatchingAncestor(
        node: AccessibilityNodeInfo?,
        filter: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (node == null) return false
        var cur = try {
            node.parent
        } catch (e: Throwable) {
            null
        }
        var guard = 0
        while (cur != null && guard++ < MAX_DEPTH) {
            if (filter(cur)) return true
            cur = try {
                cur.parent
            } catch (e: Throwable) {
                null
            }
        }
        return false
    }

    // ==================== 辅助（安全调用）====================

    private fun isVisibleToUserSafe(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }

    private fun isClickableSafe(node: AccessibilityNodeInfo): Boolean = try {
        node.isClickable
    } catch (e: Throwable) {
        false
    }

    private fun isLongClickableSafe(node: AccessibilityNodeInfo): Boolean = try {
        node.isLongClickable
    } catch (e: Throwable) {
        false
    }

    private fun isFocusableSafe(node: AccessibilityNodeInfo): Boolean = try {
        node.isFocusable
    } catch (e: Throwable) {
        false
    }

    private fun isCheckableSafe(node: AccessibilityNodeInfo): Boolean = try {
        node.isCheckable
    } catch (e: Throwable) {
        false
    }

    private fun isScreenReaderFocusableSafe(node: AccessibilityNodeInfo): Boolean = try {
        node.isScreenReaderFocusable
    } catch (e: Throwable) {
        false
    }

    private fun childCountSafe(node: AccessibilityNodeInfo): Int = try {
        node.childCount
    } catch (e: Throwable) {
        0
    }

    private fun childSafe(node: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? = try {
        node.getChild(i)
    } catch (e: Throwable) {
        null
    }

    private fun classNameSafe(node: AccessibilityNodeInfo): String = try {
        node.className?.toString() ?: ""
    } catch (e: Throwable) {
        ""
    }

    private fun textSafe(node: AccessibilityNodeInfo): CharSequence? = try {
        node.text
    } catch (e: Throwable) {
        null
    }

    private fun contentDescSafe(node: AccessibilityNodeInfo): CharSequence? = try {
        node.contentDescription
    } catch (e: Throwable) {
        null
    }

    private fun hintTextSafe(node: AccessibilityNodeInfo): CharSequence? = try {
        node.hintText
    } catch (e: Throwable) {
        null
    }

    private fun stateDescSafe(node: AccessibilityNodeInfo): CharSequence? = try {
        node.stateDescription
    } catch (e: Throwable) {
        null
    }

    private fun hasCollectionInfo(node: AccessibilityNodeInfo): Boolean = try {
        node.collectionInfo != null
    } catch (e: Throwable) {
        false
    }

    /**
     * 画中画 —— 对应 TalkBack `isPictureInPicture()`
     *
     * 【TalkBack 原文】`AccessibilityNodeInfoUtils.java:967`
     * ```java
     * public static boolean isPictureInPicture(@NonNull AccessibilityNodeInfoCompat node) {
     *   AccessibilityWindowInfoCompat window = getWindow(node);
     *   return (window != null) && window.isInPictureInPictureMode();
     * }
     * ```
     *
     * 【注意】framework 的 `AccessibilityNodeInfo` **没有** `isPictureInPicture()`，
     *   必须走 `node.getWindow().isInPictureInPictureMode()`。
     */
    private fun isPictureInPicture(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.window?.isInPictureInPictureMode == true
        } catch (e: Throwable) {
            false
        }
    }

    private fun hasAction(node: AccessibilityNodeInfo, vararg ids: Int): Boolean = try {
        val actions = node.actions
        ids.any { (actions and it) == it }
    } catch (e: Throwable) {
        false
    }

    private fun isWebViewClass(node: AccessibilityNodeInfo): Boolean {
        val cls = classNameSafe(node)
        return cls == "android.webkit.WebView" || cls.contains("WebView")
    }

    private fun isWebContainer(node: AccessibilityNodeInfo): Boolean {
        return supportsWebActions(node) || isWebViewClass(node)
    }

    private fun isDropDownList(node: AccessibilityNodeInfo): Boolean {
        val cls = classNameSafe(node)
        return cls.contains("Spinner") || cls.contains("DropDown")
    }

    private fun isAutoScrollContainer(node: AccessibilityNodeInfo): Boolean {
        return isScrollable(node) && isScrollItem(node)
    }

    /**
     * 是否支持 Web 动作 —— 对应 TalkBack `WebInterfaceUtils.supportsWebActions()`
     */
    private fun supportsWebActions(node: AccessibilityNodeInfo): Boolean {
        return hasAction(node, ACTION_NEXT_HTML_ELEMENT, ACTION_PREVIOUS_HTML_ELEMENT)
    }

    /**
     * 是否有原生 Web 内容 —— 对应 TalkBack `WebInterfaceUtils.hasNativeWebContent()`
     */
    private fun hasNativeWebContent(node: AccessibilityNodeInfo): Boolean {
        return isWebViewClass(node)
    }

    /**
     * 上溯到 WebView 容器 —— 对应 TalkBack `WebInterfaceUtils.ascendToWebViewContainer()`
     */
    private fun ascendToWebViewContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard++ < MAX_DEPTH) {
            if (isWebViewClass(cur)) return cur
            cur = try {
                cur.parent
            } catch (e: Throwable) {
                null
            }
        }
        return null
    }

    // ==================== 常量 ====================

    /** `AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT` */
    private const val ACTION_NEXT_HTML_ELEMENT = 1024

    /** `AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT` */
    private const val ACTION_PREVIOUS_HTML_ELEMENT = 2048

    /** `AccessibilityAction.ACTION_SCROLL_UP` */
    private const val ACTION_SCROLL_UP = 0x2000

    /** `AccessibilityAction.ACTION_SCROLL_DOWN` */
    private const val ACTION_SCROLL_DOWN = 0x1000

    /** `AccessibilityAction.ACTION_SCROLL_LEFT` */
    private const val ACTION_SCROLL_LEFT = 0x8000

    /** `AccessibilityAction.ACTION_SCROLL_RIGHT` */
    private const val ACTION_SCROLL_RIGHT = 0x4000
}
