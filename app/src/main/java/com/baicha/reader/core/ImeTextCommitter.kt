package com.baicha.reader.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 输入法文字提交 —— 对应天坦 `X6/k.s()`（候选词上屏）
 *
 * ══════════════════════════════════════════════════════════
 * 【两份参考实现的做法（已逐行比对）】
 * ══════════════════════════════════════════════════════════
 *
 * **天坦**（X6/k.java:214~236）—— 显式双路径：
 *   ```
 *   if (SDK_INT >= 33) {
 *       InputMethod im = service.getInputMethod()
 *       InputMethod.AccessibilityInputConnection conn = im?.getCurrentInputConnection()
 *       if (conn != null) conn.commitText(str, 1, null)      // 官方 API
 *   } else {
 *       F0.p(service, str, false, 2, null)                    // 旧路径：通过 IME 交互
 *       postDelayed(300ms) { 清除输入法节点 }
 *   }
 *   ```
 *
 * **TalkBack** —— 未实现此路径，依赖系统默认行为。
 *
 * 【抉择：选天坦】
 *   本能力上 TalkBack 没有对应实现（grep 全仓库无
 *   AccessibilityInputConnection 使用），因此没有「两方取舍」问题，
 *   直接照天坦。
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要它 —— 一个真实的无障碍痛点】
 * ══════════════════════════════════════════════════════════
 *
 * 视障用户用读屏操作第三方输入法（天坦输入法、讯飞、搜狗）时，
 * 输入法会朗读候选词，用户双击候选词想上屏。
 *
 * 但候选词是**输入法自己绘制的浮层**，它在无障碍树里
 * 可能是一个不可点击的文本节点 —— 于是：
 *   用户双击 → 没有任何反应 → 以为读屏坏了
 *
 * 天坦的解法：不走「点击节点」，而是**直接把文字提交进输入连接**：
 *   commitText("你好", 1, null)
 * 这是输入法的官方接口，比自己找节点点更可靠。
 *
 * ══════════════════════════════════════════════════════════
 * 【本实现相对天坦的改进】
 * ══════════════════════════════════════════════════════════
 *
 * ① **不吞失败**：天坦的 else 分支调 F0.p() 后就 postDelayed 清理，
 *    调用方无法知道成功与否。本实现返回 Boolean。
 * ② **不做无谓的延迟清理**：天坦在旧路径 postDelayed(300ms) 调
 *    `AbstractC3693m.g0(node)` 刷新节点 —— 那是它自有的节点缓存机制，
 *    白茶的节点缓存由 CacheRegistry 统一失效，不需要这条特殊路径。
 * ③ **无副作用常量**：不引入 300ms 魔法延迟，避免时序耦合。
 */
object ImeTextCommitter {

    /**
     * 把文本提交到当前输入连接（候选词上屏）。
     *
     * @param service 无障碍服务（用于取 InputMethod）
     * @param text    要提交的文字，空串直接返回 false
     * @return 是否成功提交
     */
    fun commit(service: AccessibilityService?, text: String?): Boolean {
        if (service == null) return false
        if (text.isNullOrEmpty()) return false

        // API 33 以下没有 InputMethod 无障碍 API —— 与天坦同样直接放弃，
        // 而不是「假装成功」。调用方据此走兜底路径。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            ReaderLog.d(TAG, "API < 33，无 InputMethod 无障碍接口")
            return false
        }

        return try {
            val inputMethod = service.inputMethod ?: run {
                ReaderLog.d(TAG, "当前无 InputMethod（输入法未连接）")
                return false
            }
            val connection = inputMethod.currentInputConnection ?: run {
                ReaderLog.d(TAG, "输入连接不可用")
                return false
            }
            // commitText(text, newCursorPosition=1, textStyle=null)
            // newCursorPosition=1 表示光标移到提交文本之后 —— 与天坦一致
            connection.commitText(text, 1, null)
            ReaderLog.d(TAG, "输入法提交成功：$text")
            true
        } catch (e: Throwable) {
            // 输入法可能在提交瞬间被切换/销毁 —— 不让服务崩
            ReaderLog.w(TAG, "输入法提交失败：${e.message}")
            false
        }
    }

    /** 当前是否可以提交（供 UI 层判断是否显示「上屏」操作） */
    fun isAvailable(service: AccessibilityService?): Boolean {
        if (service == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            service.inputMethod?.currentInputConnection != null
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 删除光标前的字符（输入法退格）。
     *
     * 天坦同样通过输入连接实现；这里提供对称能力，
     * 供「编辑」导航选择器使用。
     */
    fun deleteBackward(service: AccessibilityService?, count: Int = 1): Boolean {
        if (service == null || count <= 0) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        return try {
            val connection = service.inputMethod?.currentInputConnection ?: return false
            // deleteSurroundingText(beforeLength, afterLength)
            connection.deleteSurroundingText(count, 0)
            true
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "输入法退格失败：${e.message}")
            false
        }
    }

    private const val TAG = "ImeTextCommitter"
}
