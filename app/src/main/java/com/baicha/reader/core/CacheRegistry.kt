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
     * 登记一个缓存的失效回调 —— **同名覆盖**（不追加）
     *
     * ══════════════════════════════════════════════════════════
     * 【★ 本轮修复：原来只 add 不查重 ⇒ 服务每次重启都累积 7 条】
     * ══════════════════════════════════════════════════════════
     *
     * 原实现：
     * ```kotlin
     *   handlers.getOrPut(level) { ArrayList(8) }.add(name to onInvalidate)
     * ```
     *
     * 而 `CacheRegistry` 是 **object（进程级单例）**，服务每次
     * `onServiceConnected()` 都会调 `registerCacheHandlers()`
     * 注册 7 条 handler。无障碍服务**被系统反复重启是常态**
     * （崩溃自动重启 / 用户开关 / 内存回收 / 配置变化）。
     *
     * 【两条后果】
     * ① **内存泄漏**：每条 handler 是捕获了服务实例的 lambda
     *    （`{ nodeCache.invalidateAll() }` 里的 `nodeCache` 即 `this.nodeCache`）。
     *    旧服务实例本该被 GC，却因被 lambda 强引用在单例里**永不回收**
     *    —— n 次重启 ⇒ n 个服务实例及其全部子组件（NodeCache /
     *    SpeechEngine / …）无法释放。
     * ② **热点路径退化**：每次 `invalidate(WINDOW)` 执行 **7n** 个回调，
     *    其中 7(n−1) 个操作的是**已废弃实例**（纯浪费）。
     *    窗口变化是高频事件。
     *
     * 【为什么之前没暴露】上一个 P0（服务启动即崩）修好前，
     * 服务根本活不到被反复重启 —— 这个问题现在才会真正显现。
     *
     * 【修复】按 `(level, name)` **去重**：同名 handler 覆盖旧的。
     *
     * 【为什么选去重而不是 clear+重注册】
     *   · 去重**不依赖调用方记得清理**，更稳；
     *   · 语义更合理：同名就是同一个缓存的失效动作，本就该覆盖；
     *   · 且服务重启时，旧实例的 handler 自动被新实例顶掉，
     *     旧实例随之可被 GC。
     *
     * @param level 该缓存在哪个级别需要失效
     * @param name 缓存名（**作为去重键**，同时用于诊断）
     * @param onInvalidate 失效动作
     */
    fun register(level: Level, name: String, onInvalidate: () -> Unit) {
        synchronized(this) {
            val list = handlers.getOrPut(level) { ArrayList(8) }
            val existing = list.indexOfFirst { it.first == name }
            if (existing >= 0) {
                // 同名 → 覆盖（顶掉旧实例的 lambda，使其可被 GC）
                list[existing] = name to onInvalidate
            } else {
                list.add(name to onInvalidate)
            }
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
