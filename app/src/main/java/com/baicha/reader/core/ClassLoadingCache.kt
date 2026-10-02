package com.baicha.reader.core

/**
 * 类加载缓存 —— 对应 TalkBack `ClassLoadingCache`（87 行）
 *
 * 【为什么需要】
 *
 * `NodeRoleHelper.roleOf()` 判断节点角色时要做**继承链检查**
 * （例如「是不是 CompoundButton 的子类」）。原实现每次调用：
 *
 *     Class.forName(cls)        ← 昂贵！涉及类加载器、可能加锁
 *     Class.forName(parent)     ← 更昂贵（父类基本固定，却每次都查）
 *     parent.isAssignableFrom(c)
 *
 * 而 `roleOf()` 在**每次节点访问**时都会被调用：
 * 滑动浏览时每个节点、遍历时每个候选、合成时每个片段。
 *
 * 实测影响：`Class.forName` 在大列表快速滚动时是明显的卡顿来源
 * （类加载会拿 ClassLoader 锁，与无障碍线程竞争）。
 *
 * 【TalkBack 的做法】
 *   `HashMap<String, Class<?>> mCachedClasses` 缓存类名字符串 → Class 对象。
 *   注意 TalkBack 连「加载失败」也缓存（存 null），避免反复尝试。
 *
 * 【本实现（相对 TalkBack 的强化）】
 *
 * ① **缓存负结果**：TalkBack 用 `containsKey` + 存 null 实现；
 *    本实现用哨兵 `NULL_CLASS` 与 null 值区分「未缓存」和「缓存了失败」，语义更清晰。
 * ② **线程安全**：TalkBack 用普通 HashMap（在无障碍线程单线程访问）。
 *    本实现用 ConcurrentHashMap —— 因为我们的 `roleOf()` 可能被主线程
 *    （事件处理）和无障碍线程同时调用。
 * ③ **继承关系缓存**：除了缓存 Class 对象，还缓存
 *    「(子类名, 父类名) → 是否可赋值」的结果，避免重复做 isAssignableFrom。
 *    这是 TalkBack 没有的 —— 而 isAssignableFrom 本身也不便宜。
 * ④ **容量上限**：防止异常输入（大量随机类名）撑爆内存。
 */
object ClassLoadingCache {

    private const val TAG = "ClassCache"

    /** 缓存上限 */
    private const val MAX_CLASS_CACHE = 512
    private const val MAX_ASSIGN_CACHE = 2048

    /** 加载失败的哨兵（区分「未缓存」与「已知加载失败」） */
    private val NULL_CLASS = Class::class.java

    /** 类名 → Class 对象（跨线程安全） */
    private val classCache = java.util.concurrent.ConcurrentHashMap<String, Class<*>>(64)

    /** (子类名, 父类名) → 是否可赋值 */
    private val assignCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>(128)

    // ==================== 类加载 ====================

    /**
     * 加载或取缓存的 Class —— 对应 TalkBack `loadOrGetCachedClass()`
     *
     * @return Class 对象；null = 加载失败（失败结果也会被缓存）
     */
    fun loadOrGetClass(className: String?): Class<*>? {
        if (className.isNullOrEmpty()) return null

        // 缓存命中（含失败缓存）
        classCache[className]?.let {
            return if (it === NULL_CLASS) null else it
        }

        val loaded: Class<*>? = try {
            Class.forName(className)
        } catch (e: Throwable) {
            null
        }

        // 容量控制：满了就清一半（避免异常输入撑爆）
        if (classCache.size >= MAX_CLASS_CACHE) {
            trimCache(classCache, MAX_CLASS_CACHE / 2)
        }

        // ★ 负结果也缓存（对齐 TalkBack 行为，避免反复尝试加载不存在的类）
        classCache[className] = loaded ?: NULL_CLASS
        return loaded
    }

    // ==================== 继承关系 ====================

    /**
     * 判断类是否是某个父类（或接口）的子类型
     *
     * @param className 子类名（节点 className）
     * @param parentClassName 父类/接口名
     */
    fun isInstanceOf(className: String?, parentClassName: String): Boolean {
        if (className == null) return false
        // 同名 → 直接 true（最常见的情况，省掉缓存查找）
        if (className == parentClassName) return true

        val key = "$className\u0000$parentClassName"

        // ① 关系缓存命中
        assignCache[key]?.let { return it }

        // ② 计算
        val result = try {
            val child = loadOrGetClass(className)
            val parent = loadOrGetClass(parentClassName)
            if (child == null || parent == null) {
                false
            } else {
                parent.isAssignableFrom(child)
            }
        } catch (e: Throwable) {
            false
        }

        // ③ 写缓存
        if (assignCache.size >= MAX_ASSIGN_CACHE) {
            trimCache(assignCache, MAX_ASSIGN_CACHE / 2)
        }
        assignCache[key] = result
        return result
    }

    /**
     * 判断对象是否是某个类名描述的实例 —— 对应 TalkBack `checkInstanceOf()`
     */
    fun checkInstanceOf(obj: Any?, className: String?): Boolean {
        if (obj == null || className.isNullOrEmpty()) return false
        val clazz = loadOrGetClass(className) ?: return false
        return clazz.isInstance(obj)
    }

    // ==================== 维护 ====================

    /** 清空缓存（缓存异常膨胀时手动调用） */
    fun clear() {
        classCache.clear()
        assignCache.clear()
    }

    /** 淘汰一半（简单的容量控制，比 LRU 快） */
    private fun trimCache(map: MutableMap<*, *>, keep: Int) {
        var remove = map.size - keep
        val it = map.keys.iterator()
        while (remove > 0 && it.hasNext()) {
            it.next()
            it.remove()
            remove--
        }
    }

    fun stats(): String = "类 ${classCache.size} / 继承关系 ${assignCache.size}"
}
