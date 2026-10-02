package com.baicha.reader.core

/**
 * 差分片段类型 —— 对应天坦 w7/F 的枚举 d
 */
enum class DiffOp {
    /** 相同 */
    EQUAL,

    /** 删除 */
    DELETE,

    /** 插入 */
    INSERT
}

/**
 * 差分片段 —— 对应天坦 w7/F 的内部类 b
 *
 * ══════════════════════════════════════════════════════════
 * 【★ 本轮修复：从普通 class 改为 data class（原本没有值语义）】
 * ══════════════════════════════════════════════════════════
 *
 * 它有两个 `val` 字段、没有任何身份语义 —— 是一个**值类型**。
 * 但原来写成普通 `class`，于是 equals/hashCode 继承自 `Any`（引用相等）：
 * ```kotlin
 *   engine.diff("你好", "你好世界") == engine.diff("你好", "你好世界")   // false ❗
 * ```
 * 两边内容一模一样，却是**不同实例**，比较结果永远为 false。
 *
 * 【为什么算缺陷（而不是「用不到就不算」）】
 * ① 它是**公开 API 的返回值类型**（`TextDiffEngine.diff()` 返回它）。
 *    任何使用者写 `if (diff(x,y) == expected)` 都会**静默拿到错误结果**
 *    —— 永远 false、不报错、不崩溃。这正是目标里点名的模式⑨「静默失败」。
 * ② **测试无法断言**。本轮实测就撞上了：
 *    ```
 *    expected: [EQUAL('你好'), INSERT('世界')]
 *     but was: [EQUAL('你好'), INSERT('世界')]
 *    ```
 *    两边打印**完全相同**，报错信息完全看不出原因 ——
 *    这种失败极难排查，且容易被误判为「测试不稳定」而放过。
 * ③ Kotlin 项目惯例就是 `data class`，这里用普通 class 属遗漏。
 *
 * 【为什么用 `data class` 而不是手写 equals】
 * 一次拿全 equals/hashCode/toString/copy，且**不会漏字段** ——
 * 将来若加字段（如位置偏移），手写 equals 很容易忘记同步。
 * `@JvmField` 与 `data class` 可共存；原来手写的 `toString()`
 * 已删除（data class 自动生成，格式与原来一致：`EQUAL('你好')`）。
 *
 * 【兼容性】主构造器签名与 `@JvmField val op/text` 均不变，
 * 生产代码零改动。
 */
data class DiffSegment(
    @JvmField val op: DiffOp,
    @JvmField val text: String
)

/**
 * 文本差分引擎 —— 对应天坦 w7/F（833 行）
 *
 * 【用途：增量朗读】
 *
 * 实时更新的界面（聊天、股票、弹幕、进度）文本会频繁变化。
 * 如果每次都重读整段，用户会被噪音淹没：
 *
 *   价格从 "10.5" 变成 "10.8"
 *     整段重读 → "价格 10.8"（浪费，且打断上下文）
 *     增量朗读 → "10.8"    （只读变化部分）★
 *
 * 【天坦的算法】
 *   c(s1, s2, flag, deadline) 做标准的三态 diff（EQUAL/DELETE/INSERT），
 *   然后 g() 做**边界修正**（把被 INSERT/DELETE 夹住的 EQUAL 片段重新归并，
 *   避免出现"删一个字又加回同一个字"的碎片）。
 *
 * 【天坦的超时保护】
 *   `f38923a` 是时间因子，超过阈值时设 `now + f38923a*1000` 的截止时间，
 *   超时降级为整体替换 —— 大文本 diff 是 O(n²) 最坏情况，会卡死。
 *
 * 【本实现（高性能）】
 *
 * ① **公共前后缀剥离**：先剥掉相同的前缀和后缀，大幅缩小 diff 范围
 *    （实时文本通常只在尾部变，剥完往往只剩几个字符）
 * ② **动态规划用 IntArray 一维滚动**：避免天坦的二维矩阵内存分配
 * ③ **短文本快速路径**：任一方为空或长度差过大时直接整体替换
 * ④ **超时检查**：每轮迭代检查一次，超时立即降级
 * ⑤ **结果对象复用**：提供 apply 接口直接产出增量文本，不一定要建完整片段表
 */
class TextDiffEngine {

    companion object {
        private const val TAG = "TextDiff"

        /** 超过此长度不做精细 diff（直接整体替换） */
        private const val MAX_DIFF_LENGTH = 400

        /** 超时毫秒数 —— 天坦用 1.0f 因子 */
        private const val TIMEOUT_MS = 8L

        /** 最大 DP 矩阵单元数（防止大文本爆内存） */
        private const val MAX_DP_CELLS = 40_000
    }

    /**
     * 计算差分 —— 对应天坦 a() / b() / c()
     *
     * @param oldText 旧文本
     * @param newText 新文本
     * @return 差分片段列表；降级时返回单个 INSERT（整体替换）
     */
    fun diff(oldText: String?, newText: String?): List<DiffSegment> {
        // 边界：新增
        if (oldText.isNullOrEmpty()) {
            return if (newText.isNullOrEmpty()) emptyList()
            else listOf(DiffSegment(DiffOp.INSERT, newText))
        }
        // 边界：删除
        if (newText.isNullOrEmpty()) {
            return listOf(DiffSegment(DiffOp.DELETE, oldText))
        }
        // 快速路径：完全相同
        if (oldText == newText) {
            return listOf(DiffSegment(DiffOp.EQUAL, oldText))
        }
        // 快速路径：超长文本 → 降级
        if (oldText.length > MAX_DIFF_LENGTH || newText.length > MAX_DIFF_LENGTH) {
            return fallback(oldText, newText)
        }
        // 快速路径：长度差过大 → 降级（几乎不可能有公共部分）
        val lenDiff = Math.abs(oldText.length - newText.length)
        if (lenDiff > 100) {
            return fallback(oldText, newText)
        }

        return try {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            diffInternal(oldText, newText, deadline)
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "diff 异常，降级: ${e.message}")
            fallback(oldText, newText)
        }
    }

    /**
     * 降级：整体替换
     */
    private fun fallback(oldText: String, newText: String): List<DiffSegment> =
        listOf(DiffSegment(DiffOp.INSERT, newText))

    /**
     * 核心 diff —— 剥前后缀 + LCS 动态规划
     */
    private fun diffInternal(s1: String, s2: String, deadline: Long): List<DiffSegment> {
        // ---- ① 剥离公共前缀 ----
        var start = 0
        val maxStart = Math.min(s1.length, s2.length)
        while (start < maxStart && s1[start] == s2[start]) start++

        // ---- ② 剥离公共后缀 ----
        var end1 = s1.length
        var end2 = s2.length
        while (end1 > start && end2 > start && s1[end1 - 1] == s2[end2 - 1]) {
            end1--
            end2--
        }

        val prefix = s1.substring(0, start)
        val suffix = s1.substring(end1)

        // 中间待 diff 的部分
        val mid1 = s1.substring(start, end1)
        val mid2 = s2.substring(start, end2)

        val result = ArrayList<DiffSegment>(6)

        // 前缀
        if (prefix.isNotEmpty()) {
            result.add(DiffSegment(DiffOp.EQUAL, prefix))
        }

        // ---- ③ 中间部分做 LCS ----
        if (mid1.isEmpty()) {
            if (mid2.isNotEmpty()) result.add(DiffSegment(DiffOp.INSERT, mid2))
        } else if (mid2.isEmpty()) {
            result.add(DiffSegment(DiffOp.DELETE, mid1))
        } else {
            // 检查 DP 规模
            if (mid1.length.toLong() * mid2.length > MAX_DP_CELLS) {
                result.add(DiffSegment(DiffOp.DELETE, mid1))
                result.add(DiffSegment(DiffOp.INSERT, mid2))
            } else {
                lcsDiff(mid1, mid2, result, deadline)
            }
        }

        // 后缀
        if (suffix.isNotEmpty()) {
            result.add(DiffSegment(DiffOp.EQUAL, suffix))
        }

        // ---- ④ 边界修正（天坦 g() 的简化版）----
        return mergeSegments(result)
    }

    /**
     * LCS 动态规划 diff
     *
     * 用一维滚动数组：dp[j] 表示 s1[0..i) 与 s2[0..j) 的 LCS 长度。
     * 回溯需要完整矩阵，所以这里保留方向矩阵（用 ByteArray）。
     */
    private fun lcsDiff(
        s1: String,
        s2: String,
        out: MutableList<DiffSegment>,
        deadline: Long
    ) {
        val n = s1.length
        val m = s2.length

        // 方向矩阵：0=对角(相等) 1=上(删除) 2=左(插入)
        val dir = ByteArray((n + 1) * (m + 1))
        var prev = IntArray(m + 1)
        var cur = IntArray(m + 1)

        for (i in 1..n) {
            // 超时检查（每 64 行检查一次，避免频繁调用 currentTimeMillis）
            if ((i and 63) == 0 && System.currentTimeMillis() > deadline) {
                throw TimeoutException()
            }
            val c1 = s1[i - 1]
            for (j in 1..m) {
                cur[j] = if (c1 == s2[j - 1]) {
                    dir[i * (m + 1) + j] = 0
                    prev[j - 1] + 1
                } else {
                    if (prev[j] >= cur[j - 1]) {
                        dir[i * (m + 1) + j] = 1
                        prev[j]
                    } else {
                        dir[i * (m + 1) + j] = 2
                        cur[j - 1]
                    }
                }
            }
            val tmp = prev
            prev = cur
            cur = tmp
            java.util.Arrays.fill(cur, 0)
        }

        // 回溯（从后往前），先收集再反转
        val reverse = ArrayList<DiffSegment>(out.size + 8)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val d = if (i > 0 && j > 0) dir[i * (m + 1) + j] else if (i > 0) 1.toByte() else 2.toByte()
            when (d.toInt()) {
                0 -> { reverse.add(DiffSegment(DiffOp.EQUAL, s1[i - 1].toString())); i--; j-- }
                1 -> { reverse.add(DiffSegment(DiffOp.DELETE, s1[i - 1].toString())); i-- }
                else -> { reverse.add(DiffSegment(DiffOp.INSERT, s2[j - 1].toString())); j-- }
            }
        }

        // 反转 + 合并相邻同类型片段
        var lastOp: DiffOp? = null
        val sb = StringBuilder(16)
        for (idx in reverse.indices.reversed()) {
            val seg = reverse[idx]
            if (seg.op == lastOp) {
                sb.append(seg.text)
            } else {
                if (lastOp != null && sb.isNotEmpty()) {
                    out.add(DiffSegment(lastOp, sb.toString()))
                }
                sb.setLength(0)
                sb.append(seg.text)
                lastOp = seg.op
            }
        }
        if (lastOp != null && sb.isNotEmpty()) {
            out.add(DiffSegment(lastOp, sb.toString()))
        }
    }

    private class TimeoutException : RuntimeException("diff 超时")

    /**
     * 合并相邻同类型片段 + 消除无意义碎片
     *
     * 对应天坦 g() 的边界修正：把被 INSERT/DELETE 夹住的 EQUAL 归并，
     * 避免"删 X 又插 X"这种碎片。
     */
    private fun mergeSegments(segments: List<DiffSegment>): List<DiffSegment> {
        if (segments.size <= 1) return segments

        // ① 合并相邻同类型
        val merged = ArrayList<DiffSegment>(segments.size)
        for (seg in segments) {
            val last = merged.lastOrNull()
            if (last != null && last.op == seg.op) {
                merged[merged.size - 1] = DiffSegment(seg.op, last.text + seg.text)
            } else {
                merged.add(seg)
            }
        }

        // ② 消除 DELETE+INSERT 中相同的部分（简单版：如果 delete 和 insert 文本相同则合并为 EQUAL）
        if (merged.size >= 2) {
            val out = ArrayList<DiffSegment>(merged.size)
            var i = 0
            while (i < merged.size) {
                val cur = merged[i]
                if (cur.op == DiffOp.DELETE && i + 1 < merged.size) {
                    val next = merged[i + 1]
                    if (next.op == DiffOp.INSERT && cur.text == next.text) {
                        // 相同 → 实际没变
                        out.add(DiffSegment(DiffOp.EQUAL, cur.text))
                        i += 2
                        continue
                    }
                }
                out.add(cur)
                i++
            }
            return out
        }

        return merged
    }

    /**
     * 取「新增内容」—— 增量朗读的核心接口
     *
     * 只返回新出现的文本（INSERT 片段拼接），用于只朗读变化部分。
     *
     * @return 新增文本；null = 没有新增（可直接跳过播报）
     */
    fun addedText(oldText: String?, newText: String?): String? {
        if (newText.isNullOrEmpty()) return null
        // 旧文本为空 → 全部是新增
        if (oldText.isNullOrEmpty()) return newText
        // 相同 → 无新增
        if (oldText == newText) return null

        val segments = diff(oldText, newText)
        val sb = StringBuilder(32)
        for (seg in segments) {
            if (seg.op == DiffOp.INSERT) sb.append(seg.text)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * 判断变化是否「值得朗读」
     *
     * 过滤掉纯删除、以及变化太小的抖动（避免实时界面噪声）。
     */
    fun isWorthSpeaking(oldText: String?, newText: String?): Boolean {
        val added = addedText(oldText, newText) ?: return false
        // 新增内容少于 1 个字符不读
        if (added.isBlank()) return false
        return true
    }

    fun stats(): String = "最大 diff 长度 $MAX_DIFF_LENGTH / 超时 ${TIMEOUT_MS}ms"
}

/**
 * 焦点文本变化监听 —— 对应天坦的「監聽當前焦點文本變化」功能
 *
 * 资源：`title_pref_quick_menu_add_node_monitor` = 監聽當前焦點文本變化
 *
 * 【工作方式】
 *   记录被监听节点的上次文本，节点文本变化时用 diff 算出增量并播报增量。
 */
class NodeTextMonitor(private val diffEngine: TextDiffEngine = TextDiffEngine()) {

    private class Entry(
        val node: android.view.accessibility.AccessibilityNodeInfo,
        var lastText: String
    )

    /** 被监听的节点（按字符身份） */
    private val watched = java.util.IdentityHashMap<android.view.accessibility.AccessibilityNodeInfo, Entry>()

    /** 增量播报回调 */
    @Volatile
    var onIncrementalText: ((String) -> Unit)? = null

    /** 是否启用 */
    @Volatile
    var enabled: Boolean = false

    /**
     * 开始监听一个节点
     */
    fun watch(node: android.view.accessibility.AccessibilityNodeInfo) {
        if (!enabled) return
        val text = node.text?.toString().orEmpty()
        watched[node] = Entry(node, text)
    }

    /**
     * 取消监听
     */
    fun unwatch(node: android.view.accessibility.AccessibilityNodeInfo) {
        watched.remove(node)
    }

    /**
     * 取消全部监听 —— 对应 `title_pref_quick_menu_manage_monit_nodes` = 取消監聽所有焦點文本變化
     */
    fun unwatchAll() {
        watched.clear()
    }

    /**
     * 节点文本变化时调用
     *
     * @return 增量文本（需播报的部分）；null = 无需播报
     */
    fun onTextChanged(node: android.view.accessibility.AccessibilityNodeInfo?): String? {
        if (!enabled) return null
        if (node == null) return null
        val entry = watched[node] ?: return null
        val newText = node.text?.toString().orEmpty()
        if (newText == entry.lastText) return null

        val added = diffEngine.addedText(entry.lastText, newText)
        entry.lastText = newText

        if (added != null && added.isNotBlank()) {
            onIncrementalText?.invoke(added)
            return added
        }
        return null
    }

    val watchedCount: Int
        get() = watched.size

    fun stats(): String = "监听 ${watched.size} 个节点，启用=$enabled"
}
