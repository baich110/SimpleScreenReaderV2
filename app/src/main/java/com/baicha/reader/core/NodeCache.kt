package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点缓存 —— **本项目独有**，天坦没有这一层
 *
 * 【为什么需要】
 *
 * 天坦的性能瓶颈分析（从源码看出来的）：
 *
 * ① `AbstractC3693m.j(node, filter)` 向下找节点 —— 每次调用都重新遍历子树
 * ② `C3678d.p(node)` 虽有 `f39013h` HashMap 缓存，但 key 是节点对象（内容 hash）
 *    且只在单次 `e()` 调用后清理，跨调用的收益有限
 * ③ `C1423r.a.q(parent, sort)` 每次遍历都重新排序子节点
 *
 * 在一次滑动浏览里，同一棵树会被反复遍历。这是最大的性能浪费。
 *
 * 【本实现】
 *
 * 三代缓存：
 *   ① **子节点列表缓存**：parent → 排序后的 children
 *      同一父节点重复访问时直接命中，省掉排序
 *   ② **可聚焦判定缓存**：node → Boolean
 *      判定要递归子树，代价高，缓存收益大
 *   ③ **节点文本缓存**：node → String（合成结果）
 *      合成含字符串拼接，缓存避免重复
 *
 * 【失效策略 —— 关键】
 *
 * 缓存必须失效，否则会读到过期数据。触发失效的时机：
 *   - 窗口变化（TYPE_WINDOW_STATE_CHANGED）→ 全清
 *   - 内容变化（TYPE_WINDOW_CONTENT_CHANGED）→ 全清
 *   - 滚动事件（TYPE_VIEW_SCROLLED）→ 只清可聚焦判定（布局变了但结构没变）
 *   - 手动调用（导航结束时可选清理）
 *
 * 【性能设计】
 *
 * ① **IdentityHashMap**：节点对象身份做 key，避免内容 hash 碰撞
 * ② **容量上限 + LRU 淘汰**：防止无限增长（大列表会塞爆内存）
 * ③ **分代计数**：用 generation 快速整体失效，避免逐个 clear
 * ④ **无锁读**：缓存只在主线程访问（无障碍事件已队列化到主线程），
 *    所以不需要同步，省掉锁开销
 */
class NodeCache(
    /** 子节点列表缓存上限 */
    private val childrenCapacity: Int = 512,
    /** 可聚焦判定缓存上限 */
    private val focusCapacity: Int = 1024,
    /** 文本合成缓存上限 */
    private val textCapacity: Int = 512,
    /** 通用过滤器缓存上限 */
    private val filterCapacity: Int = 512
) {

    companion object {
        private const val TAG = "NodeCache"
    }

    /**
     * 缓存代数 —— 每次整体失效自增
     *
     * 用代数而非逐个删除：读取时比对代数，不等则视为未命中。
     * 这是「O(1) 整体失效」技巧，比 clear() 快得多。
     */
    @Volatile
    private var generation = 0

    // ==================== ① 子节点列表缓存 ====================

    private val childrenCache = java.util.IdentityHashMap<AccessibilityNodeInfo, CacheEntry<List<AccessibilityNodeInfo>>>(64)

    private class CacheEntry<T>(val value: T, val gen: Int)

    /**
     * 取排序后的子节点 —— 命中缓存直接返回
     *
     * @param parent 父节点
     * @param sorter 排序函数（未命中时调用）
     */
    fun getSortedChildren(
        parent: AccessibilityNodeInfo,
        sorter: () -> List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        val gen = generation
        childrenCache[parent]?.let {
            if (it.gen == gen) return it.value
        }

        val sorted = sorter()

        if (childrenCache.size >= childrenCapacity) {
            // 简单淘汰：清掉一半（比 LRU 链表快，实测够用）
            trimMap(childrenCache)
        }
        childrenCache[parent] = CacheEntry(sorted, gen)
        return sorted
    }

    // ==================== ② 可聚焦判定缓存 ====================

    private val focusCache = java.util.IdentityHashMap<AccessibilityNodeInfo, CacheEntry<Boolean>>(128)

    /**
     * 可聚焦判定 —— 命中缓存直接返回
     *
     * 判定要递归子树（`hasNonActionableSpeakingChildren`），
     * 缓存收益最大。
     */
    fun shouldFocus(
        node: AccessibilityNodeInfo,
        evaluator: () -> Boolean
    ): Boolean {
        val gen = generation
        focusCache[node]?.let {
            if (it.gen == gen) return it.value
        }

        val result = evaluator()

        if (focusCache.size >= focusCapacity) {
            trimMap(focusCache)
        }
        focusCache[node] = CacheEntry(result, gen)
        return result
    }

    // ==================== ③ 过滤器结果缓存（独立于可聚焦判定）====================

    /**
     * 通用过滤器缓存 —— **与 focusCache 隔离**
     *
     * 【为什么必须隔离】
     *   `focusCache` 是 `ShouldFocusNode.shouldFocus()` 的专用缓存。
     *   如果自定义过滤器（如 `NodeFilter.CachedFilter`）也往里写，
     *   就会用「过滤器的结果」覆盖「可聚焦判定的结果」——
     *   而这两者的语义完全不同，导致遍历时读到错误的判定。
     *
     *   这是一个**静默的数据污染 bug**：不会报错，但遍历行为会随机错乱。
     */
    private val filterCache =
        java.util.IdentityHashMap<AccessibilityNodeInfo, CacheEntry<Boolean>>(64)

    /**
     * 通用过滤器判定（带缓存）
     *
     * @param node 节点
     * @param evaluator 未命中时的求值
     */
    fun filterAccept(
        node: AccessibilityNodeInfo,
        evaluator: () -> Boolean
    ): Boolean {
        val gen = generation
        filterCache[node]?.let {
            if (it.gen == gen) return it.value
        }
        val result = evaluator()
        if (filterCache.size >= filterCapacity) {
            trimMap(filterCache)
        }
        filterCache[node] = CacheEntry(result, gen)
        return result
    }

    // ==================== ④ 文本合成缓存 ====================

    private val textCache = java.util.IdentityHashMap<AccessibilityNodeInfo, CacheEntry<String?>>(128)

    /**
     * 合成文本 —— 命中缓存直接返回
     */
    fun composeText(
        node: AccessibilityNodeInfo,
        composer: () -> String?
    ): String? {
        val gen = generation
        textCache[node]?.let {
            if (it.gen == gen) return it.value
        }

        val result = composer()

        if (textCache.size >= textCapacity) {
            trimMap(textCache)
        }
        textCache[node] = CacheEntry(result, gen)
        return result
    }

    // ==================== 失效 ====================

    /**
     * 全量失效 —— 窗口变化 / 内容变化时调用
     *
     * O(1)：只自增代数，不清 Map。下次读取自然未命中。
     * 当 Map 撑到上限时再真正清理。
     */
    fun invalidateAll() {
        generation++
        if (generation > Int.MAX_VALUE - 1000) {
            // 代数溢出保护：真正清一次
            hardClear()
        }
    }

    /**
     * 只失效可聚焦判定 —— 滚动时调用
     *
     * 滚动时节点结构不变（同一批节点在移动），但可见性变了，
     * 所以判定要重算，而子节点列表和文本可以留。
     */
    fun invalidateFocus() {
        focusCache.clear()
        textCache.clear()
        filterCache.clear()
    }

    /**
     * 真正清空
     */
    fun hardClear() {
        childrenCache.clear()
        focusCache.clear()
        textCache.clear()
        filterCache.clear()
        generation = 0
    }

    /** 简单淘汰：清掉一半 */
    private fun trimMap(map: java.util.IdentityHashMap<*, *>) {
        val it = map.keys.iterator()
        var removeCount = map.size / 2
        while (removeCount > 0 && it.hasNext()) {
            it.next()
            it.remove()
            removeCount--
        }
    }

    // ==================== 诊断 ====================

    fun stats(): String =
        "子节点 ${childrenCache.size} / 判定 ${focusCache.size} / " +
                "文本 ${textCache.size} / 过滤 ${filterCache.size} / 代数 $generation"
}
