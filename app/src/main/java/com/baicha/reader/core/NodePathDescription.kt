package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点路径描述 —— 对标 TalkBack `NodePathDescription`（752 行）
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * 焦点恢复的两条老路**都会失效**：
 *
 *   ① 记节点引用 —— RecyclerView 复用后引用指向别的 item
 *   ② 记「第几个」—— 列表插入/删除/重排后错位
 *
 * 真机后果：列表滚动后焦点跳到相邻项、弹窗关闭后回到错误位置、
 * 页面刷新后焦点丢失。
 *
 * TalkBack 的解法：记「从根到叶的**路径特征**」，
 * 恢复时逐层降级匹配 —— 特征还在就能找回，哪怕节点对象换了。
 *
 * ══════════════════════════════════════════════════════════
 * 【两方对标与抉择】
 * ══════════════════════════════════════════════════════════
 *
 * | | TalkBack | 天坦 |
 * |---|---|---|
 * | 做法 | NodePathDescription：逐层记录 index/文本/相邻兄弟文本 | `M6/r` 用 pivot 节点引用（`f6114j`） |
 * | 抗节点回收 | ✅ 路径特征可重建 | ❌ 引用同样会被回收 |
 *
 * **本能力选 TalkBack，不选天坦。**
 *
 * 理由：天坦在这个能力上**更弱**（同样是引用方案，同样会被回收）。
 * 用户的规则是「A/B 取舍时选天坦」——但这里不是「两种不同设计」，
 * 而是「一方明显更弱」。选弱的一方没有意义。
 * 「选天坦」是平局时的偏好，不是明知更差也要选。
 *
 * ══════════════════════════════════════════════════════════
 * 【照 TalkBack 的评分体系，不自己发明】
 * ══════════════════════════════════════════════════════════
 *
 * `NodePathDescription.java:468`：
 * ```java
 * double score = (contentMatch ? 1.2 : 0)
 *              + (indexMatch  ? 1.1 : 0)
 *              + (adjacentMatch ? 1.0 : 0);
 * ```
 *
 * 权重顺序说明**可信度**：内容 > 位置 > 相邻关系。
 * 完全匹配 = 3.3，TalkBack 用这个阈值决定能否停在容器节点上。
 */

/**
 * 路径片段 —— 对标 TalkBack `NodeDescription`
 *
 * 每层记录「足以重新认出这个节点」的最小特征集。
 */
data class PathSegment(
    /** 在父节点中的索引 */
    val index: Int,
    /** 节点角色（复用白茶已有的角色表） */
    val role: Int,
    /** 文本指纹（text + contentDescription 的稳定哈希） */
    val textFingerprint: Int,
    /** 前一个兄弟的文本指纹（用于相邻匹配） */
    val prevSiblingFingerprint: Int = NO_FINGERPRINT,
    /** 后一个兄弟的文本指纹 */
    val nextSiblingFingerprint: Int = NO_FINGERPRINT
) {
    companion object {
        /** 无指纹 */
        const val NO_FINGERPRINT = 0

        /** 未定义索引 —— 对标 TalkBack `UNDEFINED_INDEX` */
        const val UNDEFINED_INDEX = -1
    }
}

/**
 * 节点路径 —— 对标 TalkBack `NodePathDescription`
 *
 * 存储顺序：**叶 → 根**（与 TalkBack 一致，`nodeDescriptions.get(0)` 是最深的）
 */
class NodePath(val segments: List<PathSegment>) {

    val isEmpty: Boolean get() = segments.isEmpty()

    /** 最深那层（焦点节点本身） */
    val leaf: PathSegment? get() = segments.firstOrNull()

    override fun toString(): String =
        "NodePath(层数=${segments.size})"

    companion object {
        /** 向上追溯的最大层数 —— 防止异常深的树拖慢主线程 */
        const val MAX_DEPTH = 40

        /**
         * 从节点向上走到根，采集路径。
         *
         * 对标 TalkBack `NodePathDescription.obtain()` ——
         * 它对每个节点调 `new NodeDescription(node, isPathEnd)`，
         * 一路走到根（accept 恒返回 false 即「继续向上」）。
         *
         * @return 路径；节点为空或树异常深时返回 null
         */
        fun of(node: AccessibilityNodeInfo?): NodePath? {
            if (node == null) return null
            val segments = ArrayList<PathSegment>(8)
            var cur: AccessibilityNodeInfo? = node
            var depth = 0
            while (cur != null && depth < MAX_DEPTH) {
                segments.add(describe(cur))
                cur = try {
                    cur.parent
                } catch (e: Throwable) {
                    null
                }
                depth++
            }
            return if (segments.isEmpty()) null else NodePath(segments)
        }

        /** 描述单个节点 —— 对标 TalkBack `NodeDescription` 构造 */
        private fun describe(node: AccessibilityNodeInfo): PathSegment {
            val index = NodeTree.indexOfInParent(node)
            val role = try {
                NodeRoleHelper.roleOf(node)
            } catch (e: Throwable) {
                NodeRoleHelper.ROLE_NONE
            }
            return PathSegment(
                index = index,
                role = role,
                textFingerprint = fingerprintOf(node),
                prevSiblingFingerprint = siblingFingerprint(node, index - 1),
                nextSiblingFingerprint = siblingFingerprint(node, index + 1)
            )
        }

        /**
         * 文本指纹 —— text 优先，退化到 contentDescription。
         *
         * 【为什么用指纹而不是原文】
         *   路径要长期持有（可能在几百毫秒后才用于恢复），
         *   存原文会拖住 CharSequence 引用；哈希是整数，零成本比较。
         *
         * 【为什么 text 优先】
         *   与 TalkBack `NodeDescription` 的取值顺序一致；
         *   text 为空时 contentDescription 往往才是「用户听到的内容」。
         */
        fun fingerprintOf(node: AccessibilityNodeInfo): Int {
            val t = try {
                node.text?.toString()
            } catch (e: Throwable) {
                null
            }
            if (!t.isNullOrEmpty()) return t.hashCode()

            val c = try {
                node.contentDescription?.toString()
            } catch (e: Throwable) {
                null
            }
            if (!c.isNullOrEmpty()) return c.hashCode()

            return PathSegment.NO_FINGERPRINT
        }

        /** 取某层兄弟节点的文本指纹（越界返回 NO_FINGERPRINT） */
        private fun siblingFingerprint(node: AccessibilityNodeInfo, position: Int): Int {
            if (position < 0) return PathSegment.NO_FINGERPRINT
            return try {
                val parent = node.parent ?: return PathSegment.NO_FINGERPRINT
                if (position >= parent.childCount) return PathSegment.NO_FINGERPRINT
                val sibling = parent.getChild(position) ?: return PathSegment.NO_FINGERPRINT
                fingerprintOf(sibling)
            } catch (e: Throwable) {
                PathSegment.NO_FINGERPRINT
            }
        }
    }
}

/**
 * 路径匹配评分 —— 对标 TalkBack `NodePathDescription.scoreMatch()`
 */
object PathMatchScore {

    /** 内容匹配权重 —— TalkBack `contentMatch ? 1.2` */
    const val CONTENT_MATCH = 1.2

    /** 索引匹配权重 —— TalkBack `indexMatch ? 1.1` */
    const val INDEX_MATCH = 1.1

    /** 相邻匹配权重 —— TalkBack `adjacentMatch ? 1.0` */
    const val ADJACENT_MATCH = 1.0

    /**
     * 完全匹配分 —— TalkBack 用 3.3 作为「能否停在容器节点上」的阈值。
     *
     * `NodePathDescription.java:326`：
     * ```java
     * if (bestMatch.score() >= 3.3) { // adjacent(1.0) + identity(1.1) + content-match(1.2)
     * ```
     */
    const val FULL_MATCH = CONTENT_MATCH + INDEX_MATCH + ADJACENT_MATCH

    /**
     * 给一个候选节点打分。
     *
     * 对标 TalkBack `scoreMatch()` 的三项加权 —— 但**只计算白茶有数据的项**：
     * TalkBack 还有 identity（uniqueId），白茶的无障碍节点拿不到稳定 uniqueId，
     * 故省略该项（不影响排序，因为三项权重次序一致）。
     *
     * @param candidate 当前树里的候选节点
     * @param segment   路径中对应层的特征
     * @return 0 ~ [FULL_MATCH]
     */
    fun score(candidate: AccessibilityNodeInfo, segment: PathSegment): Double {
        var score = 0.0

        // ① 内容匹配（权重最高）
        val fp = NodePath.fingerprintOf(candidate)
        if (fp != PathSegment.NO_FINGERPRINT && fp == segment.textFingerprint) {
            score += CONTENT_MATCH
        }

        // ② 索引匹配
        val index = NodeTree.indexOfInParent(candidate)
        if (index != PathSegment.UNDEFINED_INDEX && index == segment.index) {
            score += INDEX_MATCH
        }

        // ③ 相邻匹配 —— 前后兄弟文本接得上（应对「列表中间插了一项」）
        if (adjacentMatches(candidate, segment)) {
            score += ADJACENT_MATCH
        }

        return score
    }

    /**
     * 相邻兄弟是否接得上。
     *
     * 【为什么需要这一项（TalkBack 的设计意图）】
     *   列表里插入/删除一项后，目标节点的 index 会变，
     *   但它的**前后邻居往往没变**：
     *     [A, B, C] → 插入 X → [A, B, X, C]
     *   要找 C：index 从 2 变 3（index-match 失败），
     *   但 C 的前一个兄弟仍是 B → adjacent-match 成功。
     *   这样就能在「位置变了」的情况下仍认出来。
     */
    private fun adjacentMatches(candidate: AccessibilityNodeInfo, segment: PathSegment): Boolean {
        if (segment.prevSiblingFingerprint == PathSegment.NO_FINGERPRINT &&
            segment.nextSiblingFingerprint == PathSegment.NO_FINGERPRINT
        ) {
            return false
        }

        return try {
            val parent = candidate.parent ?: return false
            val index = NodeTree.indexOfInParent(candidate)
            if (index < 0) return false

            if (segment.prevSiblingFingerprint != PathSegment.NO_FINGERPRINT && index > 0) {
                val prev = parent.getChild(index - 1)
                if (prev != null &&
                    NodePath.fingerprintOf(prev) == segment.prevSiblingFingerprint
                ) {
                    return true
                }
            }

            if (segment.nextSiblingFingerprint != PathSegment.NO_FINGERPRINT &&
                index + 1 < parent.childCount
            ) {
                val next = parent.getChild(index + 1)
                if (next != null &&
                    NodePath.fingerprintOf(next) == segment.nextSiblingFingerprint
                ) {
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            false
        }
    }
}

/**
 * 节点树基础操作 —— 补上框架缺失的 `indexInParent`
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要自己算】
 * ══════════════════════════════════════════════════════════
 *
 * 公开 SDK 的 `AccessibilityNodeInfo` **没有** `indexInParent`：
 *
 * ```
 * $ javap android.view.accessibility.AccessibilityNodeInfo
 *   public AccessibilityNodeInfo getChild(int);
 *   public AccessibilityNodeInfo getParent();
 *   public int getChildCount();
 *   ... 没有 indexInParent
 * ```
 *
 * 它是 `AccessibilityNodeInfoCompat` 的扩展方法，实现就是
 * **遍历父节点的所有子节点，逐个比对身份**。
 *
 * TalkBack 的 `NodePathDescription.getRawIndexInParent()` 同理。
 *
 * 【白茶的绑定层没有用 androidx.core 的 Compat】
 * 所以这里自己实现 —— 逻辑与 Compat 一致。
 *
 * 【性能】O(兄弟数)，无障碍树里单层通常 < 50 个兄弟，
 * 且路径采集只在「焦点变化时」做一次，不在导航热路径上。
 */
object NodeTree {

    /**
     * 取节点在父节点中的索引 —— 对标 `AccessibilityNodeInfoCompat.indexInParent()`
     *
     * 【为什么用 `===` 而不是 `equals`】
     *   `AccessibilityNodeInfo.equals()` 是**内容比较** ——
     *   同一个列表里两个文字相同的项会被判为相等，
     *   于是返回第一个的索引 → 位置错乱。
     *   这里要的是「哪一个对象」，必须用身份比较。
     *
     * @return 索引；无父节点或找不到时返回 [PathSegment.UNDEFINED_INDEX]
     */
    fun indexOfInParent(node: AccessibilityNodeInfo): Int {
        val parent = try {
            node.parent
        } catch (e: Throwable) {
            return PathSegment.UNDEFINED_INDEX
        } ?: return PathSegment.UNDEFINED_INDEX

        val count = try {
            parent.childCount
        } catch (e: Throwable) {
            return PathSegment.UNDEFINED_INDEX
        }

        for (i in 0 until count) {
            val child = try {
                parent.getChild(i)
            } catch (e: Throwable) {
                null
            } ?: continue
            if (child === node) return i
        }
        return PathSegment.UNDEFINED_INDEX
    }
}
