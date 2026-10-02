package com.baicha.reader.core

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 粒度导航 —— 按字 / 按词 / 按行
 *
 * 天坦的粒度导航（C2120a0 / C2153r0）：
 *   - 默认粒度：整个节点
 *   - 按字：逐字读
 *   - 按词：逐词读
 *   - 按行：逐行读
 */
object GranularityNavigator {

    /** 粒度级别 */
    enum class Level {
        /** 整个节点 */
        DEFAULT,

        /** 按字 */
        CHARACTER,

        /** 按词 */
        WORD,

        /** 按行 */
        LINE
    }

    /** 当前粒度 —— 全局状态 */
    @Volatile
    var currentLevel = Level.DEFAULT
        private set

    /**
     * 切换粒度 —— 循环切换
     */
    fun cycle(): Level {
        currentLevel = when (currentLevel) {
            Level.DEFAULT -> Level.CHARACTER
            Level.CHARACTER -> Level.WORD
            Level.WORD -> Level.LINE
            Level.LINE -> Level.DEFAULT
        }
        return currentLevel
    }

    /**
     * 设置粒度
     */
    fun setLevel(level: Level) {
        currentLevel = level
    }

    /**
     * 按当前粒度切分文本
     *
     * @return 切分后的片段列表
     */
    fun split(text: String, level: Level = currentLevel): List<String> {
        if (text.isEmpty()) return emptyList()

        return when (level) {
            Level.DEFAULT -> listOf(text)
            Level.CHARACTER -> splitByCharacter(text)
            Level.WORD -> splitByWord(text)
            Level.LINE -> splitByLine(text)
        }
    }

    /**
     * 按字切分 —— 中文按字，英文按单词
     */
    private fun splitByCharacter(text: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()

        for (ch in text) {
            if (isCjk(ch)) {
                // 中文字符：单独成段
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
                result.add(ch.toString())
            } else if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    /**
     * 按词切分 —— 中文用简单分词，英文按空格
     */
    private fun splitByWord(text: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()

        var i = 0
        while (i < text.length) {
            val ch = text[i]

            if (isCjk(ch)) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
                // 中文：2 字一组（简单分词）
                val next = if (i + 1 < text.length && isCjk(text[i + 1])) {
                    text.substring(i, i + 2)
                } else {
                    ch.toString()
                }
                result.add(next)
                i += next.length
            } else if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
                i++
            } else {
                sb.append(ch)
                i++
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    /**
     * 按行切分
     */
    private fun splitByLine(text: String): List<String> {
        return text.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 是否 CJK 字符
     */
    private fun isCjk(ch: Char): Boolean {
        return ch.code in 0x4E00..0x9FFF ||      // 中文
                ch.code in 0x3040..0x30FF ||      // 日文
                ch.code in 0xAC00..0xD7AF ||      // 韩文
                ch.code in 0x3400..0x4DBF         // 中文扩展
    }

    /**
     * 粒度名（中文）
     */
    fun levelName(level: Level = currentLevel): String = when (level) {
        Level.DEFAULT -> "默认"
        Level.CHARACTER -> "按字"
        Level.WORD -> "按词"
        Level.LINE -> "按行"
    }
}
