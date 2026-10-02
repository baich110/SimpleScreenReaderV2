package com.baicha.reader.core

/**
 * 缓存注册表 —— 本项目独有的工程化设施
 *
 * 【解决的问题】
 *
 * 上一轮审计发现的问题：缓存有 8 处，分散在 6 个类里，
 * 每处都要记得「窗口变化时失效、内容变化时失效、横竖屏时失效…」。
 *
 * 这是**靠记忆维持的约定**，一定会有人忘（包括我自己 ——
 * `TatansTraversal` 的索引缓存就是因为没挂钩子才出的 bug）。
 *
 * 【方案：注册 + 分级失效】
 *
 * 所有缓存向本注册表登记自己的失效回调，并声明失效级别；
 * 服务在对应时机调用一次 `invalidate(level)`，全部自动处理。
 *
 * 失效级别（对应不同的变化剧烈程度）：
 *
 *   WINDOW   窗口变化 —— 最彻底，所有缓存都要清
 *   CONTENT  内容变化 —— 结构变了，所有缓存都要清
 *   SCROLL   滚动     —— 只有可见性/位置变了，结构没变
 *                        （子节点列表可留，判定/边界要清）
 *   CONFIG   配置变化 —— 屏幕尺寸变了，排序阈值与全部缓存
 *
 * 【新增缓存的正确姿势】
 *
 *     CacheRegistry.register(CacheRegistry.Level.WINDOW) { myCache.clear() }
 *
 * 而不是「记得在服务里加一行 clear()」。
 */
object CacheRegistry {

    /**
     * 失效级别
     */
    enum class Level {
        /** 窗口变化：最彻底 */
        WINDOW,

        /** 内容变化：结构变了 */
        CONTENT,

        /** 滚动：只有可见性/位置变，结构没变 */
        SCROLL,

        /** 配置变化：屏幕尺寸变（含 WINDOW 的全部） */
        CONFIG
    }

    /** 登记的失效回调（按级别分组） */
    private val handlers = HashMap<Level, MutableList<Pair<String, () -> Unit>>>(4)

    @Volatile
    var enabled: Boolean = true

    /** 统计（诊断用） */
    @Volatile
    var invalidateCount = 0L
        private set

    /**
     * 登记一个缓存的失效回调
     *
     * @param level 该缓存在哪个级别需要失效
     * @param name 缓存名（诊断用）
     * @param onInvalidate 失效动作
     */
    fun register(level: Level, name: String, onInvalidate: () -> Unit) {
        synchronized(this) {
            handlers.getOrPut(level) { ArrayList(8) }.add(name to onInvalidate)
        }
    }

    /**
     * 按级别失效
     *
     * @param level 触发的失效级别
     * @return 实际执行的失效回调数
     */
    fun invalidate(level: Level): Int {
        if (!enabled) return 0

        val toRun = synchronized(this) {
            val out = ArrayList<Pair<String, () -> Unit>>(16)
            // 逐级包含关系：
            //   CONFIG ⊇ WINDOW ⊇ CONTENT
            //   SCROLL 独立（只清滚动敏感的缓存）
            when (level) {
                Level.CONFIG -> {
                    out.addAll(handlers[Level.CONFIG] ?: emptyList())
                    out.addAll(handlers[Level.WINDOW] ?: emptyList())
                    out.addAll(handlers[Level.CONTENT] ?: emptyList())
                    out.addAll(handlers[Level.SCROLL] ?: emptyList())
                }
                Level.WINDOW -> {
                    out.addAll(handlers[Level.WINDOW] ?: emptyList())
                    out.addAll(handlers[Level.CONTENT] ?: emptyList())
                    out.addAll(handlers[Level.SCROLL] ?: emptyList())
                }
                Level.CONTENT -> {
                    out.addAll(handlers[Level.CONTENT] ?: emptyList())
                    out.addAll(handlers[Level.SCROLL] ?: emptyList())
                }
                Level.SCROLL -> {
                    out.addAll(handlers[Level.SCROLL] ?: emptyList())
                }
            }
            out
        }

        var ok = 0
        for ((name, action) in toRun) {
            try {
                action()
                ok++
            } catch (e: Throwable) {
                // 单个缓存失效失败不能阻断其余
                ReaderLog.w("CacheRegistry", "$name 失效失败: ${e.message}")
            }
        }

        invalidateCount++
        return ok
    }

    /** 已登记的缓存名清单（诊断） */
    fun registeredNames(): List<String> = synchronized(this) {
        handlers.values.flatten().map { it.first }
    }

    /** 清空登记与计数（仅测试用） */
    fun reset() {
        synchronized(this) { handlers.clear() }
        invalidateCount = 0L
    }

    fun stats(): String = synchronized(this) {
        val detail = Level.values().joinToString(" ") { lv ->
            "${lv.name}=${handlers[lv]?.size ?: 0}"
        }
        "登记[$detail] 已失效 $invalidateCount 次"
    }
}
