package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 路径找回节点 —— 对标 TalkBack `NodePathDescription.findNodeToRefocus()`
 *
 * ══════════════════════════════════════════════════════════
 * 【TalkBack 的算法（照它的思路，不自己发明）】
 * ══════════════════════════════════════════════════════════
 *
 * `findNodeToRefocus()`（NodePathDescription.java:95~140）：
 *
 * ```
 * ① 旧节点还在吗（refresh + shouldFocusNode）→ 直接用
 * ② matchAncestorUniqueId()  —— 找旧路径里「唯一标识未变」的祖先
 * ③ findUpward()            —— 从叶子往根找匹配的祖先
 * ④ findDownward()          —— 从祖先往下找符合路径的后代
 * ⑤ 都没有 → 退回 targetUp
 * ```
 *
 * 向下匹配时用 [PathMatchScore] 评分，且**只有完全匹配**
 * （score >= 3.3）才允许停在容器节点上 —— 否则宁可继续往下找。
 *
 * ══════════════════════════════════════════════════════════
 * 【白茶的适配】
 * ══════════════════════════════════════════════════════════
 *
 * ① 省略 `matchAncestorUniqueId` —— 白茶拿不到稳定的 `uniqueId`
 *    （公开 SDK 无此 API），由 `findUpward` 覆盖该职能。
 * ② 搜索预算：TalkBack 无深度限制（靠 `visited` 集合防环）。
 *    白茶加 [MAX_VISITED] 上限 —— 读屏在焦点恢复路径上，
 *    不能让一次异常深的树把主线程拖住。
 */
object NodePathResolver {

    /** 向下搜索时访问节点的上限 —— 防止异常深的树拖慢主线程 */
    const val MAX_VISITED = 1200

    /**
     * 按路径找回节点。
     *
     * @param root 当前活动窗口的根节点
     * @param path 之前采集的路径（叶 → 根）
     * @return 找回的节点；找不到返回 null
     */
    fun resolve(root: AccessibilityNodeInfo?, path: NodePath?): AccessibilityNodeInfo? {
        if (root == null || path == null || path.isEmpty) return null

        val segments = path.segments
        val leaf = segments.firstOrNull() ?: return null

        // ① 首选：整个路径从根往下逐层走一遍，每层都取「最高分」的子节点。
        //
        //    这对应 TalkBack 的 findDownward ——
        //    但我们从根开始（而不是从匹配到的祖先开始），
        //    因为白茶没有 uniqueId 无法可靠定位祖先。
        walkDownFromRoot(root, segments)?.let { return it }

        // ② 兜底：全树搜索文本指纹与叶子相同的节点。
        //
        //    应对「层级结构变了」（例如从 LinearLayout 换成
        //    ConstraintLayout，index 链全变，但内容没变）。
        return findByContent(root, leaf.textFingerprint)
    }

    /**
     * 从根逐层下钻 —— 每层选评分最高的子节点。
     *
     * 【为什么「每层选最高分」而不是「要求每层都完全匹配」】
     *   RecyclerView 回收后，整条路径的 index 可能全部错位一格，
     *   若要求逐层严格匹配，会一路失败到底。
     *   逐层取最优（TalkBack 的 scoring 思路）能容忍局部错位：
     *   某一层选错了，下层仍可能靠内容修正回来。
     */
    private fun walkDownFromRoot(
        root: AccessibilityNodeInfo,
        segments: List<PathSegment>
    ): AccessibilityNodeInfo? {
        // segments 是叶→根，下钻要反着来（根→叶）
        val topDown = segments.asReversed()

        var current: AccessibilityNodeInfo = root
        var visited = 0

        // 第 0 层对应根，从第 1 层开始下钻
        for (i in 1 until topDown.size) {
            val segment = topDown[i]
            val best = bestChild(current, segment, visited) ?: return null
            current = best.node
            visited = best.visited
        }

        // 走到叶子层后，验证是否真的对得上 ——
        // 避免「一路蒙对到某个无关节点」。
        val leafSegment = segments.first()
        return if (NodePath.fingerprintOf(current) == leafSegment.textFingerprint ||
            leafSegment.textFingerprint == PathSegment.NO_FINGERPRINT
        ) {
            current
        } else {
            null
        }
    }

    private class Best(val node: AccessibilityNodeInfo, val visited: Int)

    /** 在 [parent] 的子节点中找与 [segment] 最匹配的 */
    private fun bestChild(
        parent: AccessibilityNodeInfo,
        segment: PathSegment,
        visitedBefore: Int
    ): Best? {
        val count = try {
            parent.childCount
        } catch (e: Throwable) {
            return null
        }
        if (count <= 0) return null

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0.0
        var visited = visitedBefore

        for (i in 0 until count) {
            if (visited >= MAX_VISITED) break
            visited++
            val child = try {
                parent.getChild(i)
            } catch (e: Throwable) {
                null
            } ?: continue

            val score = PathMatchScore.score(child, segment)
            if (score > bestScore) {
                bestScore = score
                bestNode = child
            }
        }

        return if (bestNode == null) null else Best(bestNode, visited)
    }

    /**
     * 全树按内容指纹找 —— 层级结构变化后的兜底。
     *
     * 【为什么要兜底】
     *   上面的逐层下钻依赖「路径骨架大致不变」。
     *   但 App 改版、布局容器替换后，index 链会整体改变 ——
     *   这时只有**内容**还能认出来。
     *
     *   这是 TalkBack `findUpward` + `adjacentContentMatches` 的
     *   简化等价物：它靠内容/相邻文本而不是位置。
     */
    private fun findByContent(root: AccessibilityNodeInfo, fingerprint: Int): AccessibilityNodeInfo? {
        if (fingerprint == PathSegment.NO_FINGERPRINT) return null

        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && visited < MAX_VISITED) {
            val node = queue.removeFirst()
            visited++

            if (NodePath.fingerprintOf(node) == fingerprint) return node

            val count = try {
                node.childCount
            } catch (e: Throwable) {
                0
            }
            for (i in 0 until count) {
                if (visited + queue.size >= MAX_VISITED) break
                val child = try {
                    node.getChild(i)
                } catch (e: Throwable) {
                    null
                } ?: continue
                queue.add(child)
            }
        }
        return null
    }
}
