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
    /**
     * 粒度级别
     *
     * 对齐 TalkBack `CursorGranularity` 的核心几档
     * （TalkBack 共 35 种，含 Web 专用的 30 种；这里取通用 6 档）。
     *
     * 天坦的导航码里也有对应项：
     *   GRANULARITY_CHARACTERS / WORDS / SENTENCE / PARAGRAPHS / LINES
     */
    enum class Level {
        /** 整个节点 */
        DEFAULT,

        /** 按字 */
        CHARACTER,

        /** 按词 */
        WORD,

        /** 按句 —— 天坦有，原实现缺失 */
        SENTENCE,

        /** 按行 */
        LINE,

        /** 按段 —— TalkBack/天坦都有，原实现缺失 */
        PARAGRAPH
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
            Level.WORD -> Level.SENTENCE
            Level.SENTENCE -> Level.LINE
            Level.LINE -> Level.PARAGRAPH
            Level.PARAGRAPH -> Level.DEFAULT
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
            Level.SENTENCE -> splitBySentence(text)
            Level.LINE -> splitByLine(text)
            Level.PARAGRAPH -> splitByParagraph(text)
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
     * 按句切分
     *
     * 以中英文句末标点为界（天坦 SENTENCE 粒度的等价实现）。
     */
    private fun splitBySentence(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>(8)
        val sb = StringBuilder()

        for (ch in text) {
            sb.append(ch)
            if (isSentenceEnd(ch)) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) result.add(s)
                sb.clear()
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return if (result.isEmpty()) listOf(text) else result
    }

    /** 是否句末标点 */
    private fun isSentenceEnd(ch: Char): Boolean =
        ch == '。' || ch == '！' || ch == '？' ||
                ch == '.' || ch == '!' || ch == '?' ||
                ch == '；' || ch == ';' || ch == '\n'

    /**
     * 按段切分
     *
     * 以空行为界（天坦 PARAGRAPHS 粒度的等价实现）。
     */
    private fun splitByParagraph(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.split(Regex("\n\\s*\n|\r\\s*\r"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(text) }
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
        Level.SENTENCE -> "按句"
        Level.LINE -> "按行"
        Level.PARAGRAPH -> "按段"
    }
}
