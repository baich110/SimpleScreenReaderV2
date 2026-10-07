package com.baicha.reader.core

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 遍历引擎 —— 1:1 抄天坦 d7/C1423r.java（1307 行）
 *
 * 【这是天坦真正的遍历核心】C1410e 是废弃路径，天坦实际走 C1423r。
 *
 * 【天坦的三层结构（关键）】
 *   a(node) = X(node) && filter.accept(node)
 *      ├─ X(node)        基础可聚焦：可见 || (WebView 相关 && 角色 != 15)
 *      └─ filter.accept  M 接口，默认 b 实现 = AbstractC3693m.X(node)
 *
 *   遍历（v/y/r/u/D/C/o/p/q/w/x/A/B/G）与过滤是**分开的两层**。
 *   遍历内部只用 X() 判可见性，filter 由外部注入。
 *
 * 【找下一个 v(node)】
 *   v(node):
 *       visited.clear()                    ← 每次调用清空
 *       result = r(node)                   ← ① 向下找
 *       return result ?: G(node)           ← ② 找不到 → 上溯
 *
 *   r(node):                              向下找（注意 I() 双分支！）
 *       if (I(node)) {
 *           list = q(node, false)          ← 排序后的子节点列表
 *           for (child in list) { D(child) }
 *       } else {
 *           for (i in 0 until childCount) { D(node.getChild(i)) }
 *       }
 *
 *   D(node):                              递归下降
 *       if (!visited.add(node)) return null
 *       return if (a(node)) b0(node) else r(node)
 *
 *   G(node):                              上溯找下一个兄弟
 *       parent = o(node, node.parent)
 *       loop:
 *           if (target != null && prev == target) return null
 *           if (!seen.add(parent)) return null
 *           result = w(parent, prev)
 *           if (result != null) return result
 *           prev = parent
 *           parent = o(parent, parent.parent)
 *
 * 【找上一个 y(node)】
 *   y(node):
 *       visited.clear()
 *       result = z(node)                   ← ① 前一个兄弟
 *       if (result != null) return result
 *       parent = node.parent
 *       if (parent == null) return null
 *       if ((target == null || parent != target) && a(parent)) return parent
 *       loop:                              ← ② 上溯
 *           if (target != null && prev == target) return null
 *           if (!seen.add(parent)) return null
 *           result = A(parent, prev)
 *           if (result != null) return result
 *           if ((target == null || parent != target) && a(parent)) return b0(parent)
 *           prev = parent
 *           parent = o(parent, parent.parent)
 *
 * 【关键辅助（全部带 I() 双分支）】
 *   w(parent, node)  后一个可聚焦兄弟     → 见 findNextSibling
 *   x(parent, node)  同上（非回溯版）     → 见 findNextSiblingNoTrace
 *   A(parent, node)  前一个可聚焦兄弟     → 见 findPrevSibling
 *   B(parent, node)  同上（非回溯版）     → 见 findPrevSiblingNoTrace
 *   C(node)          最后一个可聚焦后代
 *   D(node)          递归下降
 *   u(node)          从后往前找
 *   r(node)          向下找
 *   p(parent, child) 找 child 的索引（带缓存 + 双向搜索）
 *   q(list, node)    找哪个直接子节点包含 node
 *   o(node, parent)  取父节点
 *   a(node)          = X(node) && filter.accept(node)
 *
 * 【修正记录 2026-10-02】
 *   ① visited 必须用 **identity** 语义。天坦 HashSet<o0.w> 没重写 hashCode/equals
 *      → 默认 identity。之前用 HashSet<AccessibilityNodeInfo>（内容 hash）是错的：
 *      内容相同的不同节点会被误判为已访问 → 遍历提前返回 null → 焦点卡住。
 *   ② r()/u() 里的 I() 双分支必须补全，且 indexOf 必须用**同一顺序空间**。
 *      之前 findDown 用排序列表、indexOf 用 getChild(i) 原始顺序 → 索引错位。
 *   ③ filter 必须与 FocusController.searchFocus 里用的判定保持一致。
 */
class TatansTraversal(private val root: AccessibilityNodeInfo?) {

    companion object {
        /** 窗口根上溯深度上限（防畸形树死循环） */
        private const val MAX_WINDOW_ROOT_DEPTH = 64

        /**
         * 全树兜底扫描的节点数上限
         *
         * 【为什么需要上限】全树 DFS 是 O(n)，超大页面（长列表 / 网页）
         *   可能上万节点。无障碍回调有超时限制（超时会被系统熔断），
         *   宁可放弃兜底（返回 null）也不能卡住。
         *
         * 【取值依据】真机 Operit 界面 117 节点、微信聊天页约 300~500 节点，
         *   2000 足够覆盖绝大多数场景，DFS 耗时 < 5ms。
         */
        private const val FULL_TREE_SCAN_LIMIT = 2000

        /**
         * 全树兜底的时间预算（毫秒）
         *
         * 【为什么需要】`collectFocusableInOrder` 里每个节点都调
         *   `shouldFocus`，而后者内部递归遍历子树 ⇒ 整体 O(n²)。
         *   真机实测 117 节点页面卡死 12 秒（见 `findNextInFullTree` 注释）。
         *
         * 【取值】80ms —— 无障碍回调超时阈值在 100~200ms 量级，留余量。
         *   正常页面能在预算内跑完；超大页面中途放弃，退回「边界」行为。
         */
        private const val FULL_TREE_SCAN_BUDGET_MS = 80L

        /**
         * 遍历诊断开关 —— true 时把 `next()` / `previous()` 的每一步打日志
         *
         * 【为什么需要】真机出现「`访问 0`（遍历一步都没走出去）」时，
         *   光看结果无法判断是「起点没有 parent」还是「上溯链全 null」
         *   还是「全树兜底也没结果」。
         *   打开后能看到 `findDown` / `findNextSiblingUp` 各自的返回值。
         *
         * 【注意】诊断日志量大（每次导航十几行），只在排查时开。
         */
        @JvmStatic
        var diagnose: Boolean = true

        /** `ACTION_NEXT_HTML_ELEMENT` —— 可读版 AccessibilityNodeInfoUtils:657 标注 */
        private const val ACTION_NEXT_HTML_ELEMENT = 1024

        /** `ACTION_PREVIOUS_HTML_ELEMENT` —— 同上 :659 */
        private const val ACTION_PREVIOUS_HTML_ELEMENT = 2048

        private const val TAG = "TatansTraversal"

        /**
         * 节点副本工厂 —— **仅供测试注入**
         *
         * `AccessibilityNodeInfo.obtain(node)` 是静态方法，在 JVM 单测里
         * 被 `isReturnDefaultValues` 替换成返回 null，导致遍历一走到
         * 「返回结果」就 NPE，整个遍历层无法测试。
         *
         * 生产环境恒为 null（走原始静态调用），行为零变化。
         */
        @JvmStatic
        internal var obtainOverride: ((AccessibilityNodeInfo) -> AccessibilityNodeInfo?)? = null

        /** 索引缓存（天坦 f23911m/f23912n/f23913o） */
        private var cacheParentHash = -1
        private var cacheChildIndex = -1
        private var cacheChildCount = -1

        /**
         * 缓存代数 —— 窗口/内容变化时自增，强制缓存失效
         *
         * 天坦只靠内容哈希自动失效；本项目额外加一代数校验，
         * 覆盖「内容哈希恰好相同但结构已变」的边界情况。
         */
        @Volatile
        private var cacheGeneration = 0

        /**
         * 使索引缓存失效 —— 窗口/内容变化时由服务调用
         */
        fun invalidateIndexCache() {
            cacheGeneration++
            cacheParentHash = -1
            cacheChildIndex = -1
            cacheChildCount = -1
        }

        /** 天坦 f23906h —— q() 找不到时置 true，s() 读 */
        @Volatile
        var lastSearchFailed = false
            private set

        /**
         * identity 包装 —— 天坦 HashSet<o0.w> 是 identity 语义
         *
         * AccessibilityNodeInfo 重写了 equals/hashCode（基于内容），
         * 直接用会误判。这里用 IdentityHashMap 做真正的 identity 集合。
         */
        private class IdentitySet {
            private val map = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()

            fun add(node: AccessibilityNodeInfo): Boolean {
                if (map.containsKey(node)) return false
                map[node] = true
                return true
            }

            fun clear() = map.clear()
        }
    }

    /** 已访问集合（天坦 f23914a）—— identity 语义防环 */
    private val visited = IdentitySet()

    /** 本实例创建时的缓存代数快照 */
    private val generation: Int = cacheGeneration

    /** 目标节点（天坦 f23915b）—— 遇到就停 */
    private var target: AccessibilityNodeInfo? = null

    /** 可聚焦过滤器（天坦 f23916c，默认 b = AbstractC3693m.X） */
    private var focusFilter: (AccessibilityNodeInfo) -> Boolean = { isNodeFocusable(it) }

    init {
        // 天坦默认过滤器：AbstractC3693m.X(node)
        focusFilter = { node -> isNodeFocusable(node) }
    }

    /** 设置目标节点 —— 天坦 F(w) */
    fun setTarget(node: AccessibilityNodeInfo?) {
        target = node
    }

    /** 设置过滤器 —— 天坦 E(m) */
    fun setFilter(filter: (AccessibilityNodeInfo) -> Boolean) {
        focusFilter = filter
    }

    // ==================== 入口：v() / y() ====================

    /**
     * 找下一个 —— 1:1 抄天坦 v(node)
     */
    fun next(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        visited.clear()
        // ★ 2026-10-03 诊断：为什么 next() 返回 null
        val dbg = TatansTraversal.diagnose
        if (dbg) {
            ReaderLog.d(TAG, "▶ next(${nodeDesc(node)})")
        }
        val down = findDown(node)
        if (dbg) ReaderLog.d(TAG, "  findDown = ${down?.let { nodeDesc(it) } ?: "null"}")
        if (down != null) return down
        val up = findNextSiblingUp(node)
        if (dbg) ReaderLog.d(TAG, "  findNextSiblingUp = ${up?.let { nodeDesc(it) } ?: "null"}")
        return up
    }

    /** 节点简述（诊断用） */
    private fun nodeDesc(n: AccessibilityNodeInfo): String {
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        val t = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
        val cnt = try { n.childCount } catch (e: Throwable) { -1 }
        val vis = try { n.isVisibleToUser } catch (e: Throwable) { false }
        return "「${t.ifEmpty { "(无文本)" }}」($cls, ch=$cnt, vis=$vis)"
    }

    /**
     * 找上一个 —— 1:1 抄天坦 y(node)
     *
     * ★ 与 `next()` 对称：上溯链断掉时同样走「全树兜底」。
     *   理由见 `findNextSiblingUp` 的注释（Compose 节点 parent 为 null）。
     */
    fun previous(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        visited.clear()

        // ① z(node) —— 前一个兄弟
        val prevSibling = findPrevSiblingOfSelf(node)
        if (prevSibling != null) return prevSibling

        // ② 父节点
        var parent: AccessibilityNodeInfo? = node.parent
        if (parent != null) {
            val p0: AccessibilityNodeInfo = parent ?: return findNextInFullTree(node, forward = false)
            if ((target == null || p0 != target) && isFocusable(p0)) {
                return obtain(p0)
            }

            // ③ 上溯
            var prev = p0
            val seen = IdentitySet()
            while (true) {
                val cur: AccessibilityNodeInfo = parent ?: break
                if (target != null && prev == target) break
                if (!seen.add(cur)) break

                val result = findPrevSibling(cur, prev)
                if (result != null) return result

                // 天坦：a(parent) 时返回 b0(parent)，不是 parent 本身
                if ((target == null || cur != target) && isFocusable(cur)) {
                    return obtain(cur)
                }

                prev = cur
                parent = getParent(cur)
            }
        }

        // ★ 全树兜底 —— 上溯链断掉时的语义保证（与 next() 对称）
        return findNextInFullTree(node, forward = false)
    }

    // ==================== r() 向下找（带 I() 双分支）====================

    /**
     * 向下找第一个可聚焦 —— 1:1 抄天坦 r(node)
     *
     * 天坦关键：I(node) 为真时用排序列表 q(node, false)，
     * 否则用 getChild(i) 原始顺序。两条路径必须分别处理。
     */
    private fun findDown(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val count = node.childCount
        if (count <= 0) return null

        val sort = needsSort(node)

        if (sort) {
            // 排序路径
            val children = sortedChildren(node)
            for (child in children) {
                val result = descend(child)
                if (result != null) return result
            }
        } else {
            // 原始顺序路径
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                val result = descend(child)
                if (result != null) return result
            }
        }
        return null
    }

    // ==================== D() 递归下降 ====================

    /**
     * 递归下降 —— 1:1 抄天坦 D(node)
     */
    private fun descend(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!visited.add(node)) return null
        return if (isFocusable(node)) obtain(node) else findDown(node)
    }

    // ==================== G() 上溯找下一个兄弟 ====================

    /**
     * 上溯找下一个 —— 1:1 抄天坦 G(node)
     *
     * ══════════════════════════════════════════════════════════════
     * 【★★★ 真机 P0 根因（第四轮）：上溯链断掉时缺少「全树兜底」】
     * ══════════════════════════════════════════════════════════════
     *
     * 【真机日志铁证】（v6 APK）
     * ```
     * ❌ 下一焦点 | 失败 ← 遍历无果；诊断=访问 0（上限 200） / 不可见 0 / 未过可聚焦 0
     *    当前焦点=「菜单」(?/View)
     * ```
     * `访问 0` = `next(菜单)` 第一次调用就返回 null。
     *
     * 【失败链】
     * ```
     * next(菜单)
     *   → findDown(菜单) = null（叶子，childCount = 0）
     *   → findNextSiblingUp(菜单)
     *       → getParent(菜单)
     *           → 菜单.parent = null（Compose 虚拟节点，parent 链不完整）
     *           → getWindowRoot(菜单) → window.root = 真树根
     *           → 树根 !== 菜单 → 返回树根
     *       → findNextSibling(树根, 菜单)
     *           → indexOf(树根, 菜单) = -1（菜单不是树根的直接子节点）
     *           → 走 idx == -1 分支
     *               → findChildIndex(树根的每个子节点, 菜单) 只查**一层**
     *               → 全部 -1 → 返回 null          ← ❌ 深层节点定位不到
     *       → 上溯：parent = getParent(树根) = null → return null
     *   → 返回 null  ⇒ 访问 0
     * ```
     *
     * 【为什么天坦这里也是 null，但它能用】
     *   天坦的 `G()` 同样在 `idx == -1` 分支只下钻一层
     *   （`C1417r.java:1177~1201` 的 `s(child2, nodeInfo2)` 只查直接子节点，
     *     `x()` 内部 `p()` 同样只查直接子节点）。
     *   但天坦在上层 `FocusProcessorForLogicalNavigation` 有
     *   **`retryAutoScroll` / 跨窗口 / 空间查找**等多条兜底路径
     *   （见 `:1382 retryAutoScroll`、`:1242 Skip onAutoScrolled`），
     *   单条路径返回 null 不会让导航彻底失败。
     *   白茶只有这一条路径 ⇒ 一旦返回 null 就是「卡死」。
     *
     * ══════════════════════════════════════════════════════════════
     * 【修复：加「全树兜底」】
     * ══════════════════════════════════════════════════════════════
     *
     * 【语义】`next(node)` 的**正确语义**是
     *   「遍历序里排在 node **之后**的下一个可聚焦节点」。
     *   上溯找兄弟只是实现这个语义的**优化路径**；
     *   当上溯链断掉（parent 为 null / 节点不在祖先链的直接子层）时，
     *   必须能**退化为全树遍历**来保证语义正确。
     *
     * 【实现】上溯到顶仍无结果时：
     *   ① 取窗口根（`getWindowRoot`，优先走 window.root）
     *   ② 从窗口根做一次 DFS，收集遍历序上的可聚焦节点
     *   ③ 找到 `node` 在序列里的位置，返回它的下一个
     *
     * 【为什么用「遍历序」而不是「重新找第一个」】
     *   重新找第一个会导致焦点**跳回开头**（用户感知：滑一下回到顶部）。
     *   按遍历序取「当前节点之后的下一个」才符合「继续往下走」的直觉。
     *
     * 【性能】只在**上溯失败**时触发（正常路径零开销）；
     *   全树 DFS 有节点数上限（`FULL_TREE_SCAN_LIMIT`），防止超大页面卡顿。
     *   实测真机 Operit 界面 117 节点，DFS 开销可忽略。
     */
    private fun findNextSiblingUp(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var prev = node
        var parent: AccessibilityNodeInfo? = getParent(node)
        if (diagnose) {
            ReaderLog.d(TAG, "    getParent(${nodeDesc(node)}) = ${parent?.let { nodeDesc(it) } ?: "null"}")
        }
        if (parent != null) {
            val seen = IdentitySet()
            var hop = 0

            while (true) {
                val cur: AccessibilityNodeInfo = parent ?: break
                if (target != null && prev == target) return null
                if (!seen.add(cur)) break

                val result = findNextSibling(cur, prev)
                if (diagnose) {
                    ReaderLog.d(TAG, "    上溯#$hop 在 ${nodeDesc(cur)} 找 ${nodeDesc(prev)} 的下一个 = ${result?.let { nodeDesc(it) } ?: "null"}")
                }
                if (result != null) return result

                prev = cur
                parent = getParent(cur)
                hop++
                if (diagnose && hop > 12) {
                    ReaderLog.d(TAG, "    上溯超过 12 层，放弃")
                    break
                }
            }
        }

        // ★ 全树兜底 —— 上溯链断掉时的语义保证
        val fb = findNextInFullTree(node, forward = true)
        if (diagnose) {
            ReaderLog.d(TAG, "    全树兜底 = ${fb?.let { nodeDesc(it) } ?: "null"}")
        }
        return fb
    }

    /**
     * 全树兜底：在「遍历序」里找 node 之后（前）的下一个可聚焦节点
     *
     * 【为什么需要它】`findNextSiblingUp` / `findPrevSiblingUp` 依赖
     *   parent 链完整。Compose / 跨窗口 / 动态生成的节点上
     *   `getParent()` 可能返回 null，导致上溯链在第一步就断掉。
     *   此时必须能退化为全树遍历，否则导航彻底失效。
     *
     * 【顺序定义】与 `findDown` 一致：深度优先、按子节点顺序。
     *   这保证「兜底结果」与「正常上溯结果」在顺序上一致 ——
     *   用户滑动的方向感不会因为走了哪条路径而改变。
     *
     * ══════════════════════════════════════════════════════════════
     * 【★★★ 真机 P0 修复（v10）：全树兜底卡死 12 秒】
     * ══════════════════════════════════════════════════════════════
     *
     * 【真机日志铁证】（v10，`/sdcard/Android/data/.../baicha-reader.log`）
     * ```
     * 07:45:36.265 导航入口: 下一个 / 模式=跳到底部
     * 07:45:36.265 容器不支持滚动定位 → 回退遍历
     * ...（12 秒空白，无任何日志）...
     * 07:45:48.787 ❌ 下一焦点 | 失败 ← 设焦点失败
     * ```
     * **12 秒卡死** —— 用户感知为「滑动没反应、音效震动全没了」。
     *
     * 【根因：O(n²) 复杂度】
     *   `collectFocusableInOrder` 对**每个节点**都调 `isFocusable(node)`，
     *   而 `isFocusable` → `focusFilter.invoke` → `ShouldFocusNode.shouldFocus`
     *   → 内部 `hasMatchingFocusableDescendant` **递归遍历整棵子树**。
     *   ⇒ 树有 n 个节点时总代价是 **O(n²)**，
     *     再叠加 `NodeCache` 冷启动，实际更慢。
     *
     *   真机 Operit 界面 117 节点 ⇒ 117 × 子树遍历 ≈ 上万次节点访问，
     *   每次都是跨进程 IPC ⇒ 十几秒。
     *
     * 【修复：加时间预算 —— 超时立即放弃兜底】
     *   兜底是「尽力而为」的优化，不是正确性保证。
     *   宁可返回 null（用户听到边界震动），也不能卡死 12 秒。
     *
     * 【预算取值】80ms —— 无障碍回调的常见超时阈值在 100~200ms 量级，
     *   留出余量。正常页面（< 200 节点）在预算内能跑完；
     *   超大页面会中途放弃，退回「边界」行为（可接受）。
     */
    private fun findNextInFullTree(node: AccessibilityNodeInfo, forward: Boolean): AccessibilityNodeInfo? {
        val root = getWindowRoot(node) ?: return null
        if (root === node) return null

        val deadline = SystemClock.uptimeMillis() + FULL_TREE_SCAN_BUDGET_MS
        val order = ArrayList<AccessibilityNodeInfo>(64)
        val seen = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(128)
        collectFocusableInOrder(root, order, seen, 0, deadline)
        if (diagnose) {
            ReaderLog.d(TAG, "      兜底: root=${nodeDesc(root)} 收集到 ${order.size} 个可聚焦节点")
        }
        if (order.isEmpty()) return null

        // 定位当前节点在遍历序里的位置
        val idx = order.indexOfFirst { it === node || isSameNode(it, node) }

        return if (idx >= 0) {
            // 命中：取它之后（前）的下一个
            val target = if (forward) idx + 1 else idx - 1
            order.getOrNull(target)
        } else {
            // 当前节点不在序列里（可能是容器 / 被 filter 拒的节点）：
            // 用**几何位置**找「排在它之后」的第一个 —— 更符合直觉
            findNearestAfterInOrder(order, node, forward)
        }
    }

    /**
     * DFS 收集遍历序上的可聚焦节点 —— 顺序与 `findDown` 完全一致
     *
     * 【为什么不用 `descend`】`descend` 会在第一个命中时提前返回，
     *   而这里需要**完整序列**。所以单独实现，但保持同样的
     *   「先判自身、再下钻子节点」的顺序。
     */
    private fun collectFocusableInOrder(
        node: AccessibilityNodeInfo,
        out: ArrayList<AccessibilityNodeInfo>,
        seen: java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>,
        depth: Int,
        deadline: Long
    ) {
        if (depth > MAX_WINDOW_ROOT_DEPTH || out.size >= FULL_TREE_SCAN_LIMIT) return
        // ★ 时间预算：超时立即放弃（防 O(n²) 卡死，见 findNextInFullTree 注释）
        if (SystemClock.uptimeMillis() > deadline) return
        if (seen.put(node, true) != null) return

        if (isFocusable(node)) out.add(node)

        val count = try {
            node.childCount
        } catch (e: Throwable) {
            0
        }
        for (i in 0 until count) {
            if (out.size >= FULL_TREE_SCAN_LIMIT) return
            if (SystemClock.uptimeMillis() > deadline) return
            val child = try {
                node.getChild(i)
            } catch (e: Throwable) {
                null
            } ?: continue
            collectFocusableInOrder(child, out, seen, depth + 1, deadline)
        }
    }

    /**
     * 当前节点不在遍历序里时，用几何位置找「排在它之后」的第一个
     *
     * 【场景】当前节点是容器 / 被 filter 拒的节点（不在 `order` 里），
     *   但用户从它出发滑动 —— 应该走到「几何位置上排在它之后」的节点。
     *
     * 【判据】用 bounds 的 top 优先、left 次之（与遍历序的
     *   「上→下、左→右」直觉一致）。
     */
    private fun findNearestAfterInOrder(
        order: List<AccessibilityNodeInfo>,
        node: AccessibilityNodeInfo,
        forward: Boolean
    ): AccessibilityNodeInfo? {
        val myBounds = android.graphics.Rect()
        try {
            node.getBoundsInScreen(myBounds)
        } catch (e: Throwable) {
            return if (forward) order.firstOrNull() else order.lastOrNull()
        }
        if (myBounds.isEmpty) {
            return if (forward) order.firstOrNull() else order.lastOrNull()
        }

        val b = android.graphics.Rect()
        var best: AccessibilityNodeInfo? = null
        var bestKey = 0

        for (candidate in order) {
            try {
                candidate.getBoundsInScreen(b)
            } catch (e: Throwable) {
                continue
            }
            if (b.isEmpty) continue

            val after = if (forward) {
                b.top > myBounds.top || (b.top == myBounds.top && b.left > myBounds.left)
            } else {
                b.top < myBounds.top || (b.top == myBounds.top && b.left < myBounds.left)
            }
            if (!after) continue

            // 取「最近」的：forward 取最小 key，backward 取最大 key
            val key = b.top * 10000 + b.left
            if (best == null) {
                best = candidate
                bestKey = key
            } else if (forward && key < bestKey) {
                best = candidate
                bestKey = key
            } else if (!forward && key > bestKey) {
                best = candidate
                bestKey = key
            }
        }
        return best
    }

    /** 节点身份判定 —— 优先 identity，退化到内容相等 */
    private fun isSameNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        if (a === b) return true
        return try {
            a == b
        } catch (e: Throwable) {
            false
        }
    }

    // ==================== w() 找后一个兄弟（双分支）====================

    /**
     * 找 node 在 parent 里的后一个可聚焦兄弟 —— 1:1 抄天坦 w(parent, node)
     */
    private fun findNextSibling(
        parent: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val count = parent.childCount
        if (count <= 0) return null

        return if (needsSort(parent)) {
            val children = sortedChildren(parent)
            val idx = indexOfList(children, node)

            if (idx != -1 && idx < children.size - 1) {
                // 从 idx+1 往后找
                for (i in idx + 1 until children.size) {
                    val result = descend(children[i])
                    if (result != null) return result
                }
                null
            } else if (idx == -1) {
                // 不在直接子节点里 → 递归找
                val childIdx = findChildContaining(children, node)
                if (childIdx != -1) {
                    val child = children[childIdx]
                    val result = findNextSibling(child, node)
                    if (result == null && (target == null || child != target)) {
                        if (isFocusable(child)) return obtain(child)
                        for (i in childIdx + 1 until children.size) {
                            val r = descend(children[i])
                            if (r != null) return r
                        }
                    }
                    result
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            val idx = indexOf(parent, node)

            if (idx != -1 && idx < count - 1) {
                for (i in idx + 1 until count) {
                    val child = parent.getChild(i) ?: continue
                    val result = descend(child)
                    if (result != null) return result
                }
                null
            } else if (idx == -1 && count > 0) {
                // 天坦 w() 的 idx == -1 分支：顺序扫描，找到包含 node 的子节点
                var found = false
                for (i in 0 until count) {
                    val child = parent.getChild(i) ?: continue
                    if (found) {
                        val r = descend(child)
                        if (r != null) return r
                    } else {
                        if (findChildIndex(child, node) == -1) {
                            // 该子节点不含 node，跳过
                            continue
                        }
                        val r = findNextSiblingWithoutTrace(child, node)
                        val targetReached = target == null || child != target
                        if (r != null) return r
                        found = targetReached
                    }
                }
                null
            } else {
                null
            }
        }
    }

    /** 天坦 x(parent, node) —— w() 用的无回溯版本 */
    private fun findNextSiblingWithoutTrace(
        parent: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val count = parent.childCount
        if (count <= 0) return null

        val idx = indexOf(parent, node)
        if (idx != -1 && idx < count - 1) {
            for (i in idx + 1 until count) {
                val child = parent.getChild(i) ?: continue
                val result = descend(child)
                if (result != null) return result
            }
        }
        return null
    }

    /** 天坦 s(parent, child) —— 找 child 在 parent 里的直接索引（不缓存） */
    private fun findChildIndex(parent: AccessibilityNodeInfo, node: AccessibilityNodeInfo): Int {
        val count = parent.childCount
        for (i in 0 until count) {
            val child = parent.getChild(i) ?: continue
            if (child === node) return i
        }
        return -1
    }

    // ==================== A() 找前一个兄弟（双分支）====================

    /**
     * 找 node 在 parent 里的前一个可聚焦兄弟 —— 1:1 抄天坦 A(parent, node)
     */
    private fun findPrevSibling(
        parent: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val count = parent.childCount
        if (count <= 0) return null

        return if (needsSort(parent)) {
            val children = sortedChildren(parent)
            val idx = indexOfList(children, node)

            if (idx > 0) {
                for (i in idx - 1 downTo 0) {
                    val result = findLastDescendant(children[i])
                    if (result != null) return result
                }
                null
            } else if (idx == -1) {
                val childIdx = findChildContaining(children, node)
                if (childIdx != -1) {
                    val child = children[childIdx]
                    val result = findPrevSibling(child, node)
                    if (result == null && (target == null || child != target)) {
                        if (isFocusable(child)) return obtain(child)
                        for (i in childIdx - 1 downTo 0) {
                            val r = findLastDescendant(children[i])
                            if (r != null) return r
                        }
                    }
                    result
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            val idx = indexOf(parent, node)

            if (idx > 0) {
                for (i in idx - 1 downTo 0) {
                    val child = parent.getChild(i) ?: continue
                    val result = findLastDescendant(child)
                    if (result != null) return result
                }
                null
            } else if (idx == -1 && count > 0) {
                // 天坦 A() 的 idx == -1 分支
                var foundIdx = -1
                var targetReached = target == null
                for (i in 0 until count) {
                    val child = parent.getChild(i) ?: continue
                    val sub = findChildIndex(child, node)
                    if (sub != -1) {
                        val r = findPrevSiblingWithoutTrace(child, node)
                        targetReached = target == null || child != target
                        if (r != null) return r
                        foundIdx = i
                        if (!targetReached) break
                    }
                }
                if (foundIdx > 0) {
                    for (i in foundIdx - 1 downTo 0) {
                        val child = parent.getChild(i) ?: continue
                        val r = findLastDescendant(child)
                        if (r != null) return r
                    }
                }
                null
            } else {
                null
            }
        }
    }

    /** 天坦 B(parent, node) —— A() 用的无回溯版本 */
    private fun findPrevSiblingWithoutTrace(
        parent: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val count = parent.childCount
        if (count <= 0) return null
        val idx = indexOf(parent, node)
        if (idx > 0) {
            for (i in idx - 1 downTo 0) {
                val child = parent.getChild(i) ?: continue
                val result = findLastDescendant(child)
                if (result != null) return result
            }
        }
        return null
    }

    // ==================== z() 找当前节点的前一个兄弟 ====================

    /**
     * 找当前节点的前一个兄弟 —— 1:1 抄天坦 z(node)
     *
     *   z(node):
     *       parent = o(node, node.parent)
     *       if (parent == null) return null
     *       result = A(parent, node)
     *       parent.x0()                    ← 释放
     *       return result
     */
    private fun findPrevSiblingOfSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = getParent(node) ?: return null
        return findPrevSibling(parent, node)
    }

    // ==================== C() 找最后一个可聚焦后代 ====================

    /**
     * 找最后一个可聚焦后代 —— 1:1 抄天坦 C(node)（带 I() 双分支）
     */
    private fun findLastDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!visited.add(node)) return null

        val count = node.childCount
        if (count <= 0) {
            return if (isFocusable(node)) obtain(node) else null
        }

        // u(node)：从后往前
        if (needsSort(node)) {
            val children = sortedChildren(node)
            for (i in children.indices.reversed()) {
                val result = findLastDescendant(children[i])
                if (result != null) return result
            }
        } else {
            for (i in count - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                val result = findLastDescendant(child)
                if (result != null) return result
            }
        }

        return if (isFocusable(node)) obtain(node) else null
    }

    // ==================== p() 找索引（缓存 + 双向搜索）====================

    /**
     * 找 child 在 parent 里的索引 —— 1:1 抄天坦 p(parent, child)
     *
     * 天坦：命中缓存后，从上次索引位置**向两侧扩展**搜索。
     * 这是列表滚动的性能关键。
     *
     * 【缓存键的选择 —— 这是个陷阱】
     *
     * 天坦用 `wVar.hashCode()`（**内容**哈希）做缓存键，这带来了一个关键性质：
     * **内容变化会自动让缓存失效**（hash 变了 → 不匹配 → 走完整线性搜索）。
     *
     * 如果改用 `System.identityHashCode`（对象身份），身份在对象存活期间恒定，
     * 子节点增删后**缓存依然命中** → 用旧的中心索引做双向扩展搜索
     * → 搜不到就漏掉，导致索引错位、焦点跳错位置。
     *
     * 所以这里**故意用内容哈希**（与天坦一致）：
     *   ① 内容哈希变化 → 自动失效（安全性）
     *   ② 加 generation 校验 → 窗口/内容变化时强制失效（双保险）
     */
    private fun indexOf(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        val count = parent.childCount
        var result = -1

        try {
            val hash = parent.hashCode()
            // ★ 双保险：内容哈希匹配 + 代数匹配（窗口/内容变化时代数自增）
            if (hash != -1 && hash == cacheParentHash &&
                count == cacheChildCount && Companion.cacheGeneration == generation
            ) {
                val center = cacheChildIndex
                var offset = 0
                while (true) {
                    val lo = center - offset
                    val hi = center + offset
                    if (lo < 0 && hi >= count) break

                    if (lo >= 0) {
                        val c = parent.getChild(lo)
                        if (c === child) {
                            result = lo
                            cacheChildIndex = lo
                            return lo
                        }
                    }
                    if (offset != 0 && hi < count) {
                        val c = parent.getChild(hi)
                        if (c === child) {
                            result = hi
                            cacheChildIndex = hi
                            return hi
                        }
                    }
                    offset++
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        for (i in 0 until count) {
            val c = parent.getChild(i) ?: continue
            // 【修正】身份比较，对齐天坦 o0.w 的语义
            if (c === child) {
                try {
                    cacheParentHash = parent.hashCode()
                } catch (e: Exception) {
                    cacheParentHash = -1
                }
                cacheChildIndex = i
                cacheChildCount = count
                // ★★ 本轮修正（P1）：**删除** `Companion.cacheGeneration = generation`
                //
                // 【为什么必须删】`generation` 是本实例创建时抓取的快照
                // （`:145 private val generation: Int = cacheGeneration`）。
                // 把它写回静态字段的后果：
                //   · `invalidateIndexCache()` 递增 `cacheGeneration` 之后，
                //     只要再调一次本函数，就把静态字段**还原成旧值**；
                //   · 于是 `:562` 的 `Companion.cacheGeneration == generation`
                //     变成**永远成立** —— 代数校验彻底失效，
                //     `invalidateIndexCache()`（窗口/内容变化时调用）形同虚设。
                //   · 结果：索引缓存读到过期结果 → 焦点跳错位置。
                //
                // 【正确的语义】静态 `cacheGeneration` 是**单向递增的版本号**，
                // 只应由 `invalidateIndexCache()` 修改；
                // 实例只**读**它做比较，绝不写回。
                return i
            }
        }
        return -1
    }

    /** 在已排序列表里找索引（identity 语义） */
    private fun indexOfList(list: List<AccessibilityNodeInfo>, child: AccessibilityNodeInfo): Int {
        for (i in list.indices) {
            if (list[i] === child) return i
        }
        return -1
    }

    /**
     * 找哪个直接子节点包含 node —— 1:1 抄天坦 q(list, node)
     *
     * 注意：天坦找不到时设 f23906h = true（s() 会读这个标志）
     */
    private fun findChildContaining(list: List<AccessibilityNodeInfo>, node: AccessibilityNodeInfo): Int {
        for (i in list.indices) {
            val child = list[i]
            val count = child.childCount
            for (j in 0 until count) {
                val sub = child.getChild(j) ?: continue
                if (sub === node) return i
            }
        }
        lastSearchFailed = true
        return -1
    }

    // ==================== o() 取父节点 ====================

    /**
     * 取父节点 —— 对应天坦 `d7/C1421r.java:890~903` 的 `o(node, parent)`
     *
     * ★★ 本轮修正（真机「焦点卡死」的成因之一）
     *
     * 【天坦原文】
     * ```java
     * public final NodeInfo o(NodeInfo nodeInfo, NodeInfo nodeInfo2) {
     *     if (nodeInfo2 != null) {
     *         return nodeInfo2;
     *     }
     *     NodeInfo windowRoot = AccessibilityNodeInfoUtils.getWindowRoot(nodeInfo);
     *     if (windowRoot != null && !Intrinsics.areEqual(windowRoot, nodeInfo)) {
     *         return windowRoot;          // ← 窗口根回退
     *     }
     *     if (windowRoot == null) return null;
     *     windowRoot.x0();
     *     return null;
     * }
     * ```
     * 它是**两参数**函数。调用方传的是 `o(node, node.parent)`：
     *   · `node.parent != null` → 直接返回它（Kotlin 原来抄对了）
     *   · `node.parent == null` → **回退到 `getWindowRoot(node)`**，
     *     而不是返回 null（Kotlin 原来**漏了这一整支**）
     *
     * 【为什么这一支重要】当焦点需要在窗口顶部继续「上溯找下一个兄弟」时
     * （典型场景：**弹窗 / 浮层 / 输入法窗口的最后一个可聚焦元素**），
     * 天坦回退到窗口根继续找 → 能跨出当前子树继续遍历；
     * 直接返回 null 会让上溯链在窗口边界**断掉** →
     * 焦点卡死、滑动无反应（正是用户报的现象类型）。
     *
     * 【注意】`previous()` 里 `:187` 那处 `node.parent ?: return null`
     * **不改** —— 天坦 `C1421r.java:1262` 那里就是裸 `getParent()`，
     * 未走 `o()`。两处语义不同，不能统一。
     */
    private fun getParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val direct = try {
            node.parent
        } catch (e: Throwable) {
            null
        }
        if (direct != null) return direct

        // 窗口根回退 —— 对应 `AccessibilityNodeInfoUtils.getWindowRoot(node)`
        val windowRoot = getWindowRoot(node) ?: return null
        // 天坦：若窗口根就是本节点，返回 null（避免自环）
        if (windowRoot === node) return null
        return windowRoot
    }

    /**
     * `AccessibilityNodeInfoUtils.getWindowRoot(node)` —— 天坦 `w7/AccessibilityNodeInfoUtils.java:1100~1131`
     *
     * ══════════════════════════════════════════════════════════════
     * 【★★★ 真机 P0 根因（第四轮）：漏了「窗口对象」这条路径】
     * ══════════════════════════════════════════════════════════════
     *
     * 【天坦原文】（`w7/AccessibilityNodeInfoUtils.java:1100~1131` 原样摘录）
     * ```java
     * public static NodeInfo getWindowRoot(NodeInfo nodeInfo) {
     *     if (nodeInfo == null) { return null; }
     *     C2833A c2833aW = w(nodeInfo);                       // ← ① 先取「窗口对象」
     *     if (c2833aW != null) {
     *         return AbstractC3678q.d(c2833aW);               // ← ② 用窗口的 root 直接返回
     *     }
     *     HashSet hashSet = new HashSet();                    // ← ③ 兜底：沿 parent 上溯
     *     NodeInfo nodeInfoObtainFromNode = NodeInfo.obtainFromNode(nodeInfo);
     *     NodeInfo nodeInfo2 = null;
     *     while (true) {
     *         if (nodeInfo2 != null) {
     *             if (hashSet.contains(nodeInfo2)) { ...; return null; }
     *             hashSet.add(nodeInfo2);
     *         }
     *         NodeInfo parent = nodeInfoObtainFromNode.getParent();
     *         if (parent == null) { return nodeInfoObtainFromNode; }
     *         nodeInfo2 = nodeInfoObtainFromNode;
     *         nodeInfoObtainFromNode = parent;
     *     }
     * }
     * ```
     * 其中：
     *   · `w(nodeInfo)`   = `nodeInfo.getWindow()` → `AccessibilityWindowInfo`
     *   · `AbstractC3678q.d(window)` = `window.getRoot()` → `AccessibilityNodeInfo`
     *
     * 【我原来的实现】只有 ③（沿 parent 上溯）—— **整条 ① ② 路径缺失**。
     *
     * ══════════════════════════════════════════════════════════════
     * 【为什么这条路径决定真机成败】
     * ══════════════════════════════════════════════════════════════
     *
     * 【真机日志铁证】（v6 APK，`/sdcard/Download/baicha-v6-log.txt`）
     * ```
     * ❌ 下一焦点 | 失败 ← 遍历无果；诊断=访问 0（上限 200） / 不可见 0 / 未过可聚焦 0
     *    当前焦点=「菜单」(?/View)
     * ```
     * `访问 0` = `traversal.next(菜单)` **第一次调用就返回 null**。
     *
     * 【真机节点树】（`/home/baicha-reader/docs/ui-dump.xml` 实测）
     * ```
     * 祖父 View (children=4)
     *   子[0] View focusable=T clickable=T  bounds=[13,178][169,334]  ← 菜单所在
     *       子[0] 菜单 (content-desc="菜单", focusable=F)
     *       子[1] Button (focusable=F)
     *   子[1] View focusable=F clickable=F  bounds=[182,178][474,334]  ← 搜索框
     *   子[2] View focusable=T clickable=T  bounds=[955,178][1111,334]
     *   子[3] View focusable=T clickable=T  bounds=[1111,178][1267,334]
     * ```
     * 结构上 `next(菜单)` **应该**能找到 `子[2]`/`子[3]`（都 focusable+clickable）。
     * 但真机返回 null ⇒ **上溯链在第一步就断了** ⇒ `菜单.parent == null`。
     *
     * 【为什么 Compose 节点的 parent 会是 null】
     *   Compose 的语义树 ≠ View 树。`AccessibilityNodeInfo.getParent()`
     *   在跨 Compose 虚拟节点边界时**会返回 null**（框架层限制，
     *   节点由 `AccessibilityNodeProvider` 动态生成，parent 链不完整）。
     *   此时**唯一可靠的取根方式就是 `node.getWindow().getRoot()`** ——
     *   窗口对象是跨进程稳定的，它给的 root 一定是完整可遍历的树根。
     *
     * 【修复前的完整失败链】
     * ```
     * next(菜单)
     *   → findDown(菜单) = null（叶子）
     *   → findNextSiblingUp(菜单)
     *       → getParent(菜单)
     *           → 菜单.parent = null
     *           → getWindowRoot(菜单)
     *               → 菜单.parent = null → return 菜单  ← ❌ 返回了自己！
     *           → windowRoot === node → return null     ← ❌ 整条链断掉
     *       → 返回 null
     *   → 返回 null  ⇒ 访问 0
     * ```
     * 【修复后】
     * ```
     * getWindowRoot(菜单)
     *   → 菜单.window != null → window.root = 真正的树根 ✅
     *   → 从树根继续找兄弟 → 命中 子[2]/子[3] ✅
     * ```
     *
     * 【与 `o()` 的关系】`o()` 只在 `node.parent == null` 时才调本函数 ——
     *   而 Compose 场景下 `parent == null` **恰恰是常态**，
     *   所以这条路径不是「罕见兜底」，而是**主路径之一**。
     *
     * 【防环】保留访问集合与深度上限 —— 无障碍树理论上无环，
     * 但异常 App 可能构造出畸形树，读屏不能因此死循环。
     */
    private fun getWindowRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // ① 优先走「窗口对象」路径 —— 对应天坦 `w(nodeInfo)` + `AbstractC3678q.d()`
        //
        // 【为什么必须放最前】窗口对象是跨进程稳定的，
        //   它给的 root 一定是完整可遍历的树根；
        //   而 parent 上溯在 Compose 虚拟节点上会直接断掉。
        try {
            val window = node.window
            if (window != null) {
                val root = window.root
                if (root != null) return root
            }
        } catch (e: Throwable) {
            // 窗口已销毁 / 跨进程失效 → 落到 ② 兜底
        }

        // ② 兜底：沿 parent 上溯 —— 对应天坦 `getWindowRoot` 的 while 循环
        var cur: AccessibilityNodeInfo = node
        var depth = 0
        val seen = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(8)
        while (depth < MAX_WINDOW_ROOT_DEPTH) {
            if (seen.put(cur, true) != null) return cur   // 检测到环，返回当前
            val p = try {
                cur.parent
            } catch (e: Throwable) {
                null
            } ?: return cur
            cur = p
            depth++
        }
        return cur
    }

    // ==================== 排序与可聚焦判定 ====================

    /**
     * 是否需要排序 —— 天坦 a.I(node)
     */
    private fun needsSort(node: AccessibilityNodeInfo): Boolean =
        NodeOrderComparator.needsSort(node)

    /**
     * 排序后的子节点 —— 天坦 a.q(parent, false)
     */
    private fun sortedChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        NodeOrderComparator.sortChildren(node, true)

    /**
     * 可聚焦判定 —— 1:1 抄天坦 a(node)
     *
     *   a(node) = node != null && X(node) && filter.accept(node)
     */
    private fun isFocusable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (!isNodeFocusable(node)) return false
        return focusFilter.invoke(node)
    }

    /**
     * 天坦 AbstractC3693m.X(node) —— 基础可聚焦判定
     *
     *   X(node):
     *       if (node == null) return false
     *       if (node.s0()) return true                      ← isVisibleToUser
     *       return A0.f(node) && roleOf(node) != 15         ← WebView 特判
     */
    private fun isNodeFocusable(node: AccessibilityNodeInfo): Boolean {
        if (isVisibleToUser(node)) return true
        return isWebViewRelated(node) && NodeRoleHelper.roleOf(node) != NodeRoleHelper.ROLE_WEB_VIEW
    }

    private fun isVisibleToUser(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }

    /**
     * WebView 相关节点 —— 对应天坦 `NodeNavigator.isInRecyclerView`（`:106~124`）
     *
     * ★★ 本轮修正（P1）：原来只查了一个 actionList，漏了两条路径
     *
     * 【天坦原文】
     * ```java
     * // w7/NodeNavigator.java:106~114
     * public static boolean isInRecyclerView(NodeInfo nodeInfo) {
     *     if (nodeInfo == null) return false;
     *     return isInWebView(nodeInfo) || isInListView(nodeInfo);
     * }
     * // :105  isInWebView  → isInScrollView
     * // :128  isInScrollView(nodeInfo)
     * //         = AccessibilityNodeInfoUtils.hasActionMask2(nodeInfo, 1024, 2048)
     * // :110  isInListView → pkg.startsWith("org.mozilla.")
     * ```
     * 而 `hasActionMask2`（`NodeUtils.matchActionMask2`，`:285~289`）是
     * **位掩码 OR actionList 双路**：
     * ```java
     * (actions & 1024) == 1024 || (actions & 2048) == 2048
     *  || hasAction(1024) || hasAction(2048)
     * ```
     *
     * 【我原来漏了三条】
     *   ① 只查 2048，**漏了 1024**（ACTION_NEXT_HTML_ELEMENT）
     *   ② 只扫 actionList，**漏了 `(actions & id) == id` 位掩码路径**
     *   ③ 完全没有 mozilla 之外的分支 —— 这点我原来有（pkg 判断），保留
     *
     * 【真机影响】网页里可聚焦节点识别不全 → 触摸探索/焦点判定漏节点。
     *
     * 【注意】天坦这里有个**同名陷阱**：
     *   · `AccessibilityNodeInfoUtils.hasActionMask2`（:1091）= 只有位掩码
     *   · `NodeUtils.matchActionMask2`（:285）        = 位掩码 OR actionList
     *   天坦 `isInScrollView` 实际调用的是后者。白茶按后者实现。
     */
    private fun isWebViewRelated(node: AccessibilityNodeInfo): Boolean {
        // isInListView
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg.startsWith("org.mozilla.")) return true
        // isInWebView → isInScrollView → matchActionMask2(1024, 2048)
        return hasHtmlElementAction(node)
    }

    /**
     * `NodeUtils.matchActionMask2(node, 1024, 2048)` —— 双路判定
     *
     * 位掩码路径 `(actions & id) == id` **或** actionList 扫描。
     */
    private fun hasHtmlElementAction(node: AccessibilityNodeInfo): Boolean {
        val actions = try {
            node.actions
        } catch (e: Throwable) {
            0
        }
        // ① 位掩码路径
        if ((actions and ACTION_NEXT_HTML_ELEMENT) == ACTION_NEXT_HTML_ELEMENT) return true
        if ((actions and ACTION_PREVIOUS_HTML_ELEMENT) == ACTION_PREVIOUS_HTML_ELEMENT) return true
        // ② actionList 路径
        return try {
            node.actionList.any {
                it.id == ACTION_NEXT_HTML_ELEMENT || it.id == ACTION_PREVIOUS_HTML_ELEMENT
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 天坦 AbstractC3693m.b0(node) = o0.w.v0(node) —— 取节点副本
     *
     * 【抽成可注入钩子（本轮改动）】
     *
     * `AccessibilityNodeInfo.obtain(node)` 是**静态方法**，
     * 在 JVM 单测里被 `isReturnDefaultValues` 替换成返回 null ——
     * 于是遍历一走到「要返回结果」这一步就 NPE，整个遍历层无法测。
     *
     * 生产行为**完全不变**（默认实现就是原来的静态调用）；
     * 测试可通过 [obtainOverride] 提供替身，从而让遍历逻辑第一次可被真实断言。
     */
    private fun obtain(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        obtainOverride?.let { hook ->
            hook(node)?.let { return it }
        }
        return AccessibilityNodeInfo.obtain(node)
    }

}