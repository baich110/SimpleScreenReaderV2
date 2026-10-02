package com.baicha.reader.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 输入法适配器 —— 对应天坦 `X6/k`（输入法交互）
 *
 * ══════════════════════════════════════════════════════════
 * 【两份参考实现的做法】
 * ══════════════════════════════════════════════════════════
 *
 * **天坦 X6/k**：
 *   - 候选词上屏走双路径：API 33+ 用 `InputMethod.commitText()`，
 *     低版本用旧路径 + 300ms 延迟清理节点
 *   - 输入法请求播报 → `SpeechController.K0(...)`
 *   - 输入法请求打断 → 直接停播报
 *
 * **TalkBack**：未实现候选词上屏路径，依赖系统默认行为。
 *
 * 【抉择：选天坦】该能力上 TalkBack 无对应实现，直接照天坦。
 *
 * ══════════════════════════════════════════════════════════
 * 【本实现相对天坦的改进】
 * ══════════════════════════════════════════════════════════
 *
 * 天坦只有「commuteText 成功 / 静默失败」两条路，调用方拿不到结果。
 * 本实现改成**三级降级链**，每一级都明确返回成功与否：
 *
 *   ① InputMethod.commitText()      —— 官方接口，最可靠（API 33+）
 *   ② 按文本找可点击祖先并 click()   —— 覆盖自绘候选词的输入法
 *   ③ 返回 false，由调用方播报「无法上屏」提示
 *
 * 第 ③ 级是关键：读屏里**静默失败**比失败本身更糟 ——
 * 用户不知道是没点上还是读屏坏了。
 */
class ImeAdapter(private val service: AccessibilityService) {

    /**
     * 候选词上屏。
     *
     * @param text 要上屏的文字
     * @return 实际使用的方法（null = 全部失败）
     */
    fun commitCandidate(text: String?): CommitMethod? {
        if (text.isNullOrEmpty()) return null

        // ---- ① 官方输入连接 ----
        if (ImeTextCommitter.commit(service, text)) {
            return CommitMethod.INPUT_CONNECTION
        }

        // ---- ② 节点点击兜底 ----
        //
        // 部分自绘候选词不暴露输入连接，但在树里是可点击节点。
        // 这是天坦旧路径的思路（它用 F0.p 走另一套）。
        if (activateNodeByTexts(listOf(text))) {
            return CommitMethod.NODE_CLICK
        }

        // ---- ③ 明确失败 ----
        ReaderLog.w(TAG, "候选词上屏失败：$text")
        return null
    }

    /** 退格 */
    fun deleteBackward(count: Int = 1): Boolean =
        ImeTextCommitter.deleteBackward(service, count)

    /** 当前是否处于可输入状态 */
    fun canCommit(): Boolean = ImeTextCommitter.isAvailable(service)

    /**
     * 输入法请求播报候选词。
     *
     * 对应天坦 `X6/k` 里把文本交给 `SpeechController`。
     *
     * @param queueMode 0 = 打断当前播报；其他 = 排队
     */
    fun speakCandidate(text: String?, queueMode: Int, speech: SpeechEngine) {
        if (text.isNullOrEmpty()) return
        speech.speak(text, interrupt = queueMode == 0)
    }

    /** 输入法请求打断播报（用户开始打字时要立刻静音） */
    fun interruptSpeech(speech: SpeechEngine) {
        speech.stop()
    }

    // ==================== 内部：节点点击兜底 ====================

    /**
     * 按文本查找并激活节点。
     *
     * 【为什么要向上找 10 层】
     *   候选词的文本节点本身通常不可点击，可点击的是它的容器。
     *   天坦同样做了向上查找。
     */
    private fun activateNodeByTexts(texts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val textSet = texts.toHashSet()
        val target = findNodeByTexts(root, textSet, 0) ?: return false

        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth < MAX_CLICK_ANCESTOR_DEPTH) {
            if (node.isClickable && node.isEnabled) {
                val ok = try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Throwable) {
                    false
                }
                ReaderLog.d(TAG, "节点点击兜底: ${texts.firstOrNull()}, ok=$ok")
                return ok
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun findNodeByTexts(
        node: AccessibilityNodeInfo,
        texts: Set<String>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > MAX_SEARCH_DEPTH) return null
        val t = node.text?.toString()?.trim()
        val c = node.contentDescription?.toString()?.trim()
        if ((!t.isNullOrEmpty() && texts.contains(t)) ||
            (!c.isNullOrEmpty() && texts.contains(c))
        ) {
            return node
        }
        val count = try {
            node.childCount
        } catch (e: Throwable) {
            0
        }
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (e: Throwable) {
                null
            } ?: continue
            val found = findNodeByTexts(child, texts, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** 上屏方法 —— 用于日志与调试 */
    enum class CommitMethod(val label: String) {
        /** 通过官方输入连接提交（最可靠） */
        INPUT_CONNECTION("输入连接"),
        /** 通过点击候选词节点（兜底） */
        NODE_CLICK("节点点击")
    }

    companion object {
        private const val TAG = "ImeAdapter"

        /** 向上找可点击祖先的最大层数 —— 与天坦一致（10 层） */
        private const val MAX_CLICK_ANCESTOR_DEPTH = 10

        /** 文本搜索最大深度 */
        private const val MAX_SEARCH_DEPTH = 20
    }
}
