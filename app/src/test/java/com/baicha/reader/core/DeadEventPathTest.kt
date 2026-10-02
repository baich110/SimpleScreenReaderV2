package com.baicha.reader.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件入口唯一性 —— **同名死代码是陷阱**
 *
 * ══════════════════════════════════════════════════════════
 * 【发现】本项目有**两个** `onEvent()`：
 * ══════════════════════════════════════════════════════════
 *
 * | 类 | 方法 | 是否被调用 |
 * |---|---|---|
 * | `ReaderAccessibilityService` | `onAccessibilityEvent(event)`（系统回调） | ✔ |
 * | `EventDispatcher` | `onEvent(event)`（过滤+入队） | ✔ |
 * | `AccessibilityEventHandler` | `onEvent(event)` | **✘ 零调用点** |
 *
 * 真实事件链：
 * ```
 *   onAccessibilityEvent(event)                    ← 系统回调
 *     ① updateScreenState()                        ← Service 自己的实现
 *     ② if SCROLLED: autoScroll.onScrollEvent(event)
 *     ③ dispatcher.onEvent(event)                  ← 过滤 + 入队
 *   eventQueue.consumer = { eventHandler.handleEvent(event) }   ← 主线程消费
 * ```
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么死代码在这里是**陷阱**而不是无害】
 * ══════════════════════════════════════════════════════════
 *
 * `AccessibilityEventHandler.onEvent()` 内部调的是**它自己那份**
 * `updateScreenState()`，而那份**不写** `dispatcher.isScreenOn`：
 *
 * | 实现 | 写 dispatcher.isScreenOn |
 * |---|---|
 * | `ReaderAccessibilityService.updateScreenState()` | **是** ✔ |
 * | `AccessibilityEventHandler.updateScreenState()` | **否** |
 *
 * 而 `EventDispatcher` 靠 `isScreenOn` 做**息屏过滤**（规则 1、2）：
 * ```kotlin
 *   if (type == TYPE_VIEW_ACCESSIBILITY_FOCUSED && !isScreenOn) return true
 *   if (!isScreenOn) { ... return true }
 * ```
 *
 * ⇒ 将来若有人「顺手」把 Service 的调用改成 `eventHandler.onEvent()`，
 *   `dispatcher.isScreenOn` **永远是默认值 true** →
 *   **息屏后的 14 条过滤规则全部失效** → 息屏时仍大量处理事件
 *   → 耗电 + 误播报（对用户是"锁屏后读屏还在自言自语"）。
 *
 * 故本轮**删除** Handler 侧的死代码三角
 * （`onEvent` / `updateScreenState` / `screenOn`），
 * 让事件入口只剩一条。
 */
class DeadEventPathTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val handler by lazy {
        codeOf("src/main/java/com/baicha/reader/core/AccessibilityEventHandler.kt")
    }

    private val svc by lazy {
        codeOf("src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt")
    }

    /**
     * ★ Handler 里不得再有 `onEvent()` —— 事件入口必须唯一
     */
    @Test
    fun handler_hasNoDeadOnEvent() {
        assertFalse(
            "★ AccessibilityEventHandler 不得再有 onEvent() —— " +
                    "它是零调用点的死代码，且与 Service 的同名方法行为不同" +
                    "（不写 dispatcher.isScreenOn），是个陷阱",
            handler.contains("fun onEvent(")
        )
    }

    /** Handler 里不得再有那份「不写 dispatcher.isScreenOn」的 updateScreenState */
    @Test
    fun handler_hasNoDuplicateUpdateScreenState() {
        assertFalse(
            "Handler 不得再有 updateScreenState() —— 它的存在让" +
                    "「改错入口」变得毫无阻碍（编译能过、名字对得上）",
            handler.contains("fun updateScreenState(")
        )
        assertFalse(
            "Handler 不得再有只写不读的 screenOn 字段",
            handler.contains("private var screenOn")
        )
    }

    /**
     * ★★ 唯一入口必须**确实**把屏幕状态写进 dispatcher
     *
     * 这是「息屏过滤」能生效的前提。
     */
    @Test
    fun service_updateScreenState_writesDispatcherFlag() {
        val idx = svc.indexOf("private fun updateScreenState()")
        assertTrue("Service 必须保留 updateScreenState", idx >= 0)
        val body = svc.substring(idx, minOf(svc.length, idx + 700))
        assertTrue(
            "★ updateScreenState 必须写 dispatcher.isScreenOn —— " +
                    "否则息屏过滤（EventDispatcher 规则 1、2）永远是默认值 true，\n" +
                    "息屏后仍会大量处理事件（耗电 + 误播报）:\n$body",
            body.contains("dispatcher.isScreenOn")
        )
    }

    /** Service 的事件回调必须仍调用 updateScreenState */
    @Test
    fun service_eventCallback_callsUpdateScreenState() {
        val idx = svc.indexOf("override fun onAccessibilityEvent(")
        assertTrue("必须有 onAccessibilityEvent", idx >= 0)
        val body = svc.substring(idx, minOf(svc.length, idx + 700))
        assertTrue(
            "事件回调必须先更新屏幕状态（息屏过滤依赖它）:\n$body",
            body.contains("updateScreenState()")
        )
    }

    /** `handleEvent` 仍必须是活的（由 EventQueue 消费） */
    @Test
    fun handleEvent_isStillWired() {
        assertTrue(
            "eventHandler.handleEvent 必须仍由 EventQueue 消费",
            svc.contains("eventHandler.handleEvent(event)")
        )
        assertTrue(
            "Handler 必须仍提供 handleEvent",
            handler.contains("fun handleEvent(")
        )
    }
}
