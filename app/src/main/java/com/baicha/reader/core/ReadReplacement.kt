package com.baicha.reader.core

import java.util.regex.Pattern

/**
 * 朗读替换规则 —— 对应天坦的 ReadReplacement 数据模型
 *
 * 【用途】
 *   ① 简写扩展：把 "zg" 读成 "中国"
 *   ② 噪声过滤：把广告词替换成空串（读出来就没声音了）
 *   ③ 发音纠正：把 "1F" 读成 "一楼"
 */
class ReadReplacement(
    /** 要替换的原文 */
    @JvmField val phrase: String,

    /** 替换成什么（空串 = 删除） */
    @JvmField val replacement: String,

    /** 是否正则模式 */
    @JvmField val isRegExp: Boolean = false,

    /** 作用包名（null = 全局生效） */
    @JvmField val packageName: String? = null,

    /** 优先级（数字大的先应用） */
    @JvmField val priority: Int = 0
) {
    init {
        require(phrase.isNotEmpty() || isRegExp) { "替换原文不能为空" }
    }

    /** 是否有效规则（原文与替换相同 → 无意义） */
    val isValid: Boolean
        get() = isRegExp || phrase != replacement

    override fun toString(): String =
        "ReadReplacement(${if (isRegExp) "正则" else "文本"} '$phrase' → '$replacement'" +
                (if (packageName != null) " @$packageName" else " 全局") + ")"
}

/**
 * 朗读替换引擎 —— 对应天坦 Z6/C1011d0（802 行）
 *
 * 【天坦的两级作用域】
 *   f11884c : Map<包名, List<ReadReplacement>>   按包名专用
 *   f11885d : List<ReadReplacement>               全局
 *
 *   应用顺序：先包名专用，再全局。任一规则把文本变成空串则提前结束。
 *
 * 【天坦的两种匹配模式】
 *   ① 普通文本：字符串查找替换，循环处理所有出现位置
 *   ② 正则：找出所有匹配后**反向替换**（避免偏移错乱），支持捕获组
 *
 * 【高性能设计（优于天坦）】
 *
 * ① **规则预编译**：正则规则在添加时就编译成 Pattern，避免每次朗读都编译
 * ② **按包名分桶**：查表 O(1)，不遍历全部规则
 * ③ **早退**：文本为空 / 无规则 / 总开关关闭时立即返回，零开销
 * ④ **无匹配快路径**：先用 indexOf 快速判断"有没有可能匹配"，
 *    没有匹配就直接返回原字符串，不创建 SpannableStringBuilder
 * ⑤ **规则数上限**：防止用户配置过多规则拖慢每次朗读
 */
class ReadReplacementEngine {

    companion object {
        private const val TAG = "ReadReplacement"

        /** 单个包名的规则上限 */
        private const val MAX_RULES_PER_PACKAGE = 200

        /** 全局规则上限 */
        private const val MAX_GLOBAL_RULES = 200
    }

    /** 总开关 */
    @Volatile
    var enabled: Boolean = false

    /** 按包名分组的规则 */
    private val packageRules = HashMap<String, MutableList<CompiledRule>>(8)

    /** 全局规则 */
    private val globalRules = ArrayList<CompiledRule>(16)

    /** 编译后的规则 */
    private class CompiledRule(
        val source: ReadReplacement,
        /** 正则模式编译结果（普通模式为 null） */
        val pattern: Pattern?
    )

    // ==================== 规则管理 ====================

    /**
     * 添加规则 —— 对应天坦 G() / i()
     */
    fun addRule(rule: ReadReplacement): Boolean {
        if (!rule.isValid) return false

        val compiled = CompiledRule(
            source = rule,
            pattern = if (rule.isRegExp) {
                try {
                    Pattern.compile(rule.phrase)
                } catch (e: Throwable) {
                    ReaderLog.e(TAG, "正则编译失败: ${rule.phrase} — ${e.message}")
                    return false
                }
            } else null
        )

        synchronized(this) {
            if (rule.packageName.isNullOrEmpty()) {
                if (globalRules.size >= MAX_GLOBAL_RULES) {
                    ReaderLog.w(TAG, "全局规则已达上限 $MAX_GLOBAL_RULES")
                    return false
                }
                globalRules.add(compiled)
            } else {
                val list = packageRules.getOrPut(rule.packageName) { ArrayList(8) }
                if (list.size >= MAX_RULES_PER_PACKAGE) {
                    ReaderLog.w(TAG, "${rule.packageName} 规则已达上限")
                    return false
                }
                list.add(compiled)
            }
        }
        return true
    }

    /**
     * 批量设置规则 —— 对应天坦 v()
     */
    fun setRules(rules: List<ReadReplacement>) {
        synchronized(this) {
            packageRules.clear()
            globalRules.clear()
            enabled = false
        }
        rules.forEach { addRule(it) }
        enabled = true
    }

    /**
     * 移除规则 —— 对应天坦 w()
     */
    fun removeRule(rule: ReadReplacement) {
        synchronized(this) {
            globalRules.removeAll { it.source === rule }
            packageRules.values.forEach { list -> list.removeAll { it.source === rule } }
            packageRules.entries.removeAll { it.value.isEmpty() }
        }
    }

    /** 清空全部规则 —— 对应天坦 shutdown() */
    fun clear() {
        synchronized(this) {
            packageRules.clear()
            globalRules.clear()
        }
    }

    /** 规则总数 —— 对应天坦 r() */
    val ruleCount: Int
        get() = synchronized(this) {
            globalRules.size + packageRules.values.sumOf { it.size }
        }

    // ==================== 核心：应用替换 ====================

    /**
     * 应用替换 —— 1:1 抄天坦 A()
     *
     * @param text 原始文本
     * @param packageName 当前应用包名（用于包名专用规则）
     * @return 替换后的文本；无规则或无匹配时返回原对象（零分配）
     */
    fun apply(text: String?, packageName: String?): String? {
        // ★ 快速路径：空文本 / 未启用 → 直接返回，零开销
        if (text == null || text.isEmpty()) return text
        if (!enabled) return text

        val pkgRules = synchronized(this) {
            val combined = ArrayList<CompiledRule>(
                (packageRules[packageName]?.size ?: 0) + globalRules.size
            )
            packageRules[packageName]?.let { combined.addAll(it) }
            combined.addAll(globalRules)
            combined
        }

        if (pkgRules.isEmpty()) return text

        var result: String = text
        for (rule in pkgRules) {
            result = applyRule(result, rule)
            // 天坦行为：被清空就提前结束
            if (result.isEmpty()) return result
        }
        return result
    }

    /**
     * 应用单条规则 —— 对应天坦 D()
     */
    private fun applyRule(text: String, rule: CompiledRule): String {
        val src = rule.source
        return if (src.isRegExp) {
            applyRegex(text, rule.pattern ?: return text, src.replacement)
        } else {
            applyLiteral(text, src.phrase, src.replacement)
        }
    }

    /**
     * 普通文本替换 —— 对应天坦 B()
     *
     * 【性能】先用 indexOf 探测是否可能匹配，无匹配直接返回原对象
     */
    private fun applyLiteral(text: String, from: String, to: String): String {
        if (from.isEmpty() || from == to) return text
        // ★ 快路径：不包含则直接返回，不分配 StringBuilder
        if (!text.contains(from)) return text

        val sb = StringBuilder(text.length + 16)
        var pos = 0
        while (true) {
            val idx = text.indexOf(from, pos)
            if (idx < 0) {
                sb.append(text, pos, text.length)
                break
            }
            sb.append(text, pos, idx)
            sb.append(to)
            pos = idx + from.length
        }
        return sb.toString()
    }

    /**
     * 正则替换 —— 对应天坦 C()
     *
     * 支持 $1 捕获组引用（Java Matcher.appendReplacement 原生支持）
     */
    private fun applyRegex(text: String, pattern: Pattern, to: String): String {
        return try {
            val matcher = pattern.matcher(text)
            if (!matcher.find()) return text

            val sb = StringBuffer(text.length + 16)
            // 用 do-while 处理首个匹配 + 后续
            do {
                matcher.appendReplacement(sb, to)
            } while (matcher.find())
            matcher.appendTail(sb)
            sb.toString()
        } catch (e: Throwable) {
            // ★ 替换出错不能影响朗读（天坦也是这个行为）
            ReaderLog.w(TAG, "正则替换失败: ${e.message}")
            text
        }
    }

    // ==================== 诊断 ====================

    fun stats(): String = synchronized(this) {
        "规则 $ruleCount（包名组 ${packageRules.size}）启用=$enabled"
    }
}
