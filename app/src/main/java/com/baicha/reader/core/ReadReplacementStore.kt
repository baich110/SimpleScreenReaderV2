package com.baicha.reader.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 朗读替换规则存储 —— 对应天坦的规则持久化
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要】
 * ══════════════════════════════════════════════════════════
 *
 * `ReadReplacementEngine` 实现了完整的替换逻辑（两级作用域 + 正则），
 * 但**规则从未被加载** —— `enabled` 默认 false 且没人调 `setRules()`，
 * 所以整个功能是**静默失效**的。
 *
 * 本类补上「存储层」，让规则能持久化并在服务启动时加载。
 *
 * ══════════════════════════════════════════════════════════
 * 【存储格式（JSON）】
 * ══════════════════════════════════════════════════════════
 *
 *   [
 *     {"phrase":"zg","replacement":"中国","regex":false,"pkg":null,"prio":0},
 *     {"phrase":"\\d+F","replacement":"$1楼","regex":true,"pkg":"com.x","prio":1}
 *   ]
 *
 * 用 JSON 而非 SharedPreferences 的 StringSet：
 *   ① 规则有多个字段，StringSet 需要自己拼分隔符（易错）
 *   ② JSON 可读，便于导出/导入
 *   ③ 正则里的特殊字符不会被分隔符误伤
 *
 * 【容错】
 *   解析失败的单条规则被跳过，不影响其余规则
 *   —— 用户手改坏了某一项，不该导致整个功能失效。
 */
object ReadReplacementStore {

    private const val TAG = "ReplacementStore"
    private const val PREFS_NAME = "baicha_read_replacement"
    private const val KEY_RULES = "rules"
    private const val KEY_ENABLED = "enabled"

    // ==================== 读取 ====================

    /**
     * 加载全部规则到引擎
     *
     * @return 实际加载的规则数
     */
    fun loadInto(context: Context, engine: ReadReplacementEngine): Int {
        val rules = load(context)
        if (rules.isEmpty()) {
            ReaderLog.d(TAG, "无替换规则")
            engine.enabled = false
            return 0
        }
        engine.setRules(rules)
        // setRules 已置 enabled=true，但尊重用户的独立开关
        engine.enabled = isEnabled(context)
        ReaderLog.i(TAG, "加载 ${rules.size} 条替换规则，启用=${engine.enabled}")
        return rules.size
    }

    /**
     * 读取规则列表
     */
    fun load(context: Context): List<ReadReplacement> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RULES, null) ?: return emptyList()

        return try {
            val array = JSONArray(json)
            val out = ArrayList<ReadReplacement>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseRule(obj)?.let { out.add(it) }
            }
            out
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "规则解析失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 解析单条规则 —— 单条失败返回 null，不影响其余
     */
    private fun parseRule(obj: JSONObject): ReadReplacement? {
        return try {
            val phrase = obj.optString("phrase", "")
            val replacement = obj.optString("replacement", "")
            val isRegex = obj.optBoolean("regex", false)
            val pkg = obj.optString("pkg", "").takeIf { it.isNotEmpty() }
            val priority = obj.optInt("prio", 0)

            if (phrase.isEmpty() && !isRegex) return null
            ReadReplacement(
                phrase = phrase,
                replacement = replacement,
                isRegExp = isRegex,
                packageName = pkg,
                priority = priority
            )
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "单条规则解析失败，已跳过: ${e.message}")
            null
        }
    }

    // ==================== 写入 ====================

    /**
     * 保存规则列表
     */
    fun save(context: Context, rules: List<ReadReplacement>) {
        try {
            val array = JSONArray()
            for (r in rules) {
                val obj = JSONObject().apply {
                    put("phrase", r.phrase)
                    put("replacement", r.replacement)
                    put("regex", r.isRegExp)
                    put("pkg", r.packageName ?: "")
                    put("prio", r.priority)
                }
                array.put(obj)
            }
            prefs(context).edit().putString(KEY_RULES, array.toString()).apply()
            ReaderLog.i(TAG, "已保存 ${rules.size} 条替换规则")
        } catch (e: Throwable) {
            ReaderLog.e(TAG, "保存规则失败: ${e.message}")
        }
    }

    /**
     * 添加一条规则（读取 → 追加 → 写回）
     *
     * @return 是否成功
     */
    fun addRule(context: Context, rule: ReadReplacement): Boolean {
        val rules = load(context).toMutableList()
        rules.add(rule)
        save(context, rules)
        return true
    }

    /**
     * 删除一条规则
     */
    fun removeRule(context: Context, rule: ReadReplacement): Boolean {
        val rules = load(context).toMutableList()
        val removed = rules.removeAll {
            it.phrase == rule.phrase &&
                    it.packageName == rule.packageName &&
                    it.isRegExp == rule.isRegExp
        }
        if (removed) save(context, rules)
        return removed
    }

    /** 清空全部规则 */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RULES).apply()
    }

    // ==================== 开关 ====================

    /** 替换功能总开关 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ==================== 预置规则 ====================

    /**
     * 内置的常用替换规则
     *
     * 用途：盲人用户高频遇到的「读出来不对」的场景
     */
    fun builtinRules(): List<ReadReplacement> = listOf(
        // 常见标点念法纠正（很多 TTS 把全角标点读成"空格"或跳过）
        ReadReplacement("·", "点", isRegExp = false),
        // 网址简化：把 https:// 读成"网址"
        ReadReplacement("https?://", "网址 ", isRegExp = true),
        // 冗余修饰符号（TTS 会念"星号"）
        ReadReplacement("\\*+", "", isRegExp = true)
    )

    /**
     * 合并内置规则与用户规则（内置在前，用户可覆盖）
     */
    fun loadWithBuiltin(context: Context, engine: ReadReplacementEngine): Int {
        val user = load(context)
        val all = builtinRules() + user
        if (all.isEmpty()) {
            engine.enabled = false
            return 0
        }
        engine.setRules(all)
        engine.enabled = isEnabled(context)
        return all.size
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
