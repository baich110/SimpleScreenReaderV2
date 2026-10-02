package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 网页元素导航协调器 —— 决定「这次导航走 HTML 语义还是走树遍历」
 *
 * 【为什么需要这一层】
 *   同一个导航（比如「链接」），在网页里和在原生 App 里要走完全不同的路径：
 *     - 网页：ACTION_NEXT_HTML_ELEMENT，由 WebView 按 HTML 语义找
 *     - 原生：普通树遍历 + 过滤器
 *   判断依据是「当前节点是不是来自 WebView」。
 *
 * 【为什么不直接看包名】
 *   TalkBack 用 `WebInterfaceUtils.supportsWebActions(node)` ——
 *   即「节点是否暴露 HTML 元素动作」，这是 WebView 自己声明的能力，
 *   比猜包名（com.android.chrome / 各种 WebView 壳）可靠得多。
 *
 * 【缓存】
 *   `supportsWebActions` 要遍历 actionList，属于中成本操作。
 *   这里按节点身份缓存结果，导航高频调用时零重复扫描。
 *   注意缓存随节点回收 —— 用 WeakHashMap 语义（NodeCache 同一套约定）。
 */
class WebElementNavigator(
    private val maxCacheSize: Int = DEFAULT_CACHE_SIZE
) {

    /**
     * 节点身份 → 是否支持 HTML 动作
     *
     * 【为什么用身份键】
     *   同一条链接节点在两次导航间可能内容相同但对象不同；
     *   反之同一对象的内容也不会变（无障碍树节点在一次快照内不可变）。
     *   用身份键可避免「新节点的内容哈希撞上旧缓存」。
     */
    private val cache = HashMap<Int, Boolean>(64)
    private var cacheEpoch = 0

    /** 统计：命中 / 未命中（用于性能审计） */
    var hits = 0L
        private set
    var misses = 0L
        private set

    /**
     * 尝试用 HTML 语义导航。
     *
     * @param node    起始节点
     * @param mode    当前导航模式
     * @param forward 方向
     * @return true = 已由系统处理（调用方不要再走树遍历）
     */
    fun tryNavigate(
        node: AccessibilityNodeInfo?,
        mode: NavigationMode,
        forward: Boolean
    ): Boolean {
        if (node == null) return false

        // 该导航在网页里有对应语义吗？
        val element = WebElementNavigation.elementFor(mode) ?: return false

        // 该节点是 WebView 吗？（走缓存）
        if (!supportsWebActions(node)) return false

        return WebElementNavigation.navigate(node, element, forward)
    }

    /** 节点是否支持 HTML 动作（带缓存） */
    fun supportsWebActions(node: AccessibilityNodeInfo): Boolean {
        val key = System.identityHashCode(node)
        cache[key]?.let {
            hits++
            return it
        }
        misses++
        val result = WebElementNavigation.isSupported(node)
        // 简单的容量保护：满了整体清空（比 LRU 便宜，且命中率影响很小）
        if (cache.size >= maxCacheSize) {
            cache.clear()
            cacheEpoch++
        }
        cache[key] = result
        return result
    }

    /** 清空缓存 —— 窗口内容变化时调用 */
    fun clear() {
        cache.clear()
        cacheEpoch++
    }

    /** 缓存代数（调试用） */
    val epoch: Int get() = cacheEpoch

    companion object {
        const val DEFAULT_CACHE_SIZE = 128
    }
}
