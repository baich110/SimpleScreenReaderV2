package com.baicha.reader.core

import org.junit.Test

/**
 * 真机 P0 回归：滚动定位请求泄漏 —— 「左右滑动永远获取不到焦点」
 *
 * ══════════════════════════════════════════════════════════
 * 【真机现象】
 * ══════════════════════════════════════════════════════════
 * 用户（视障）用震动判断：
 *   「左右滑动就是获取不到焦点的那种震动反馈，
 *     一到边界或者是没有焦点的那种震动反馈，
 *     从来都没有正常获取到焦点过。」
 *
 * ══════════════════════════════════════════════════════════
 * 【根因（本测试锁定）】
 * ══════════════════════════════════════════════════════════
 * `moveFocusInternal` 开头原来是：
 * ```kotlin
 * val jump = scrollJump
 * if (jump != null) {                    // ← 只看 hook 非空
 *     val handled = jump.invoke(scrollJumpToTop)
 *     if (handled) return null           // ← 直接返回，不做遍历
 * }
 * ```
 * 而 `scrollJump` 钩子在服务 `onServiceConnected` 时注入后**永不撤销**，
 * `scrollJumpToTop` 也只在切到 SCROLL_TOP/SCROLL_BOTTOM 时赋值
 * （切换分支的 `else -> {}` 为空）。
 *
 * ⇒ **只要用户曾经切到过「跳到顶部/底部」导航，
 *   此后每一次滑动都会走滚动定位并 return null，焦点永远不动。**
 *   又因 `NavigationStore` 持久化导航模式，**重启也恢复不了**。
 *
 * ══════════════════════════════════════════════════════════
 * 【修法】滚动定位改为**一次性请求**：
 *   · 只有当前导航是 SCROLL_TOP/BOTTOM 时置 requested
 *   · moveFocusInternal 检查并立即消费
 *   · 其他导航显式 clearScrollJump()
 */
class ScrollJumpLeakRegressionTest {

    private val src: String by lazy {
        java.io.File("src/main/java/com/baicha/reader/core/FocusManager.kt").readText()
    }
    private val code: String by lazy {
        src.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /**
     * ★★ 触发条件必须同时检查 `scrollJumpRequested`
     *
     * 只看 hook 非空就是原来的 bug。
     */
    @Test
    fun jump_requires_explicit_request() {
        org.junit.Assert.assertTrue(
            "触发条件必须含 scrollJumpRequested（否则 hook 注入后每次导航都触发）",
            code.contains("if (jump != null && scrollJumpRequested)")
        )
        org.junit.Assert.assertFalse(
            "不能只判 hook 非空",
            code.contains("if (jump != null) {")
        )
    }

    /**
     * ★★ 请求必须被**一次性消费** —— 消费后立即复位
     */
    @Test
    fun request_is_consumed_once() {
        val start = code.indexOf("if (jump != null && scrollJumpRequested)")
        val body = code.substring(start, minOf(code.length, start + 500))
        org.junit.Assert.assertTrue(
            "消费后必须立即复位，否则会泄漏到下一次导航：$body".take(200),
            body.contains("scrollJumpRequested = false")
        )
    }

    /**
     * ★★ 必须提供 clearScrollJump() 且服务侧在非滚动定位导航时调用
     */
    @Test
    fun clear_exists_and_is_called() {
        org.junit.Assert.assertTrue(
            "必须提供 clearScrollJump()",
            code.contains("fun clearScrollJump()")
        )
        val svc = java.io.File(
            "src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt"
        ).readText()
        val svcCode = svc.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        org.junit.Assert.assertTrue(
            "服务侧必须在其他导航时显式清除（原来的 else -> {} 空分支是 bug）",
            svcCode.contains("else -> focus.clearScrollJump()")
        )
    }

    /**
     * ★ 初始状态必须是「未请求」—— 服务刚启动时不该有任何跳转意图
     */
    @Test
    fun request_starts_false() {
        org.junit.Assert.assertTrue(
            "scrollJumpRequested 初值必须是 false",
            code.contains("private var scrollJumpRequested = false")
        )
    }
}
