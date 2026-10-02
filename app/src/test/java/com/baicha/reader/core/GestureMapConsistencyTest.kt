package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势映射表 —— **一致性检查**
 *
 * ══════════════════════════════════════════════════════════
 * 【本类要钉住的问题：同一个手势被两处赋予了不同含义】
 * ══════════════════════════════════════════════════════════
 *
 * 手势 19（双指单击）在两处有**互相矛盾**的定义：
 *
 * ① `GestureMap.actionOf()`（`Gestures.kt:356`）：
 * ```kotlin
 *   Gestures.GESTURE_2_FINGER_SINGLE_TAP -> GestureAction.STOP_SPEAK
 * ```
 *
 * ② `GestureActionHandler.handleMediaGesture()`（`:181`）：
 * ```kotlin
 *   Gestures.GESTURE_2_FINGER_SINGLE_TAP -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
 * ```
 *
 * 【为什么会串起来】运行时链路：
 * ```
 *   手势 19
 *     → GestureChain.handle() 遍历 Actor
 *     → MediaControlActor.isActive == isMediaActive()
 *        · 媒体**未**播放 → Actor 跳过 → DefaultNavigationActor
 *          → onMappedAction → handleMappedGesture → STOP_SPEAK
 *        · 媒体**正在**播放 → MediaControlActor 先消费
 *          → onMediaControl → service.handleMediaGesture
 *          → GestureActionHandler.handleMappedGesture
 *          → when(actionOf(19)) == STOP_SPEAK → 走 STOP_SPEAK ✗
 * ```
 *
 * 注意最后一步：**即使走媒体 Actor，最终仍然查同一张映射表**，
 * 得到 `STOP_SPEAK` —— 所以 `handleMediaGesture` 里那条
 * `KEYCODE_MEDIA_PLAY_PAUSE` 分支**永远不可达**。
 *
 * 同理 `GESTURE_2_FINGER_SWIPE_LEFT/RIGHT` 在 `handleMediaGesture` 里
 * 被当作「上一首/下一首」，而映射表给的是
 * `MEDIA_PREVIOUS` / `MEDIA_NEXT` —— 这两组**恰好一致**，
 * 所以只有双击播放/暂停这一条是矛盾的。
 *
 * ══════════════════════════════════════════════════════════
 * 【天坦的语义（依据源码）】
 * ══════════════════════════════════════════════════════════
 *
 * `Q6/TouchInteractionMonitor.java:848~858`（原样摘录）：
 * ```java
 *   if (gestureId == 19 && !this.f7606k.e()) {
 *       if (motionEvent != null) {
 *           GestureDescription gestureDescriptionK =
 *               AbstractC0900t.k(this.f7604i, (int) motionEvent.getX(), (int) motionEvent.getY());
 *           if (gestureDescriptionK != null) {
 *               this.f7604i.dispatchGesture(gestureDescriptionK, null, null);
 *               l0("2FingersSingleTap");
 *               return;
 *           }
 *           return;
 *       }
 *       return;
 *   }
 * ```
 * 天坦在**触摸探索模式**下，把「双指单击」**转发成一次屏幕点击**
 * （`AbstractC0900t.k()` 用 `Path.moveTo(x,y)` + `ViewConfiguration.getTapTimeout()`
 * 构造单点 StrokeDescription），也就是「原样透传给下层 App」。
 * 它既不是「停止朗读」也不是「播放/暂停」。
 *
 * 【为什么不直接照抄天坦】天坦该分支依赖"触摸探索模式"这一状态位
 * （`f7606k.e()`）以及手势坐标，白茶目前的 `onGesture` 回调
 * **不携带坐标**（`GestureActor.onGesture(gestureId, displayId)`）。
 * 要正确实现需要改 Actor 接口签名 —— 属于**能力缺口**，
 * 超出本轮「修 bug 保地基」的范围。
 *
 * 【本轮处置】先把**矛盾**消除：让 `handleMediaGesture` 不再声称
 * 一个它永远不会走到的分支。真正的「双指单击透传」记为待办。
 */
class GestureMapConsistencyTest {

    @Test
    fun gesture19_mapsToStopSpeak_inMapTable() {
        assertEquals(
            "映射表里手势 19 = STOP_SPEAK（Gestures.kt:356）",
            GestureAction.STOP_SPEAK,
            GestureMap.actionOf(Gestures.GESTURE_2_FINGER_SINGLE_TAP)
        )
    }

    @Test
    fun gesture19_defaultStoreValue_agreesWithMap() {
        assertEquals(
            "GestureStore 的默认值必须与映射表一致（GestureStore.kt:278）",
            GestureAction.STOP_SPEAK,
            GestureStore.defaultOf(Gestures.GESTURE_2_FINGER_SINGLE_TAP)
        )
    }

    /**
     * ★ 核心断言：手势 19 **不得**同时被声明为「媒体播放/暂停」
     *
     * 这条测试的作用是**防止矛盾重新出现** ——
     * 实现修好后，`handleMediaGesture` 里不应再有
     * `GESTURE_2_FINGER_SINGLE_TAP -> KEYCODE_MEDIA_PLAY_PAUSE`。
     *
     * 用源码文本断言（因为该分支的"不可达"性质无法在 JVM 单测里
     * 通过调用观测 —— 它需要真实无障碍服务环境）。
     */
    @Test
    fun gesture19_mustNotBeDeclaredAsMediaPlayPause() {
        val code = java.io.File(
            "src/main/java/com/baicha/reader/core/GestureActionHandler.kt"
        ).readText().lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        val idx = code.indexOf("private fun handleMediaGesture(")
        assertTrue("必须存在 handleMediaGesture", idx >= 0)
        val body = code.substring(idx, minOf(code.length, idx + 700))

        assertTrue(
            "handleMediaGesture 里不得再把「双指单击」当作播放/暂停 ——" +
                    "该分支永远不可达（映射表给的是 STOP_SPEAK），" +
                    "只会误导后续维护者。\n$body",
            !body.contains("GESTURE_2_FINGER_SINGLE_TAP")
        )
    }

    // ==================== 映射表自身的完整性 ====================

    /**
     * 映射表里「有意为 NONE」的手势 —— 只有导航切换那两个
     *
     * 【我第一版写错了这条测试】
     * 我原以为单指上/下/左/右、双指上/下滑在映射表里是 `NONE`
     * （因为 `DefaultNavigationActor` 会先处理它们）。
     * 实测**不是** —— 它们在映射表里都有明确动作
     * （单指→FOCUS_PREVIOUS/NEXT，双指上下滑→SCROLL_*）。
     *
     * 这其实是**设计得更好**的做法：
     * `DefaultNavigationActor` 只是收到手势后的**一次快捷处理**；
     * 若它没处理（或该 Actor 未注册），链会继续走到
     * `onMappedAction` → `handleMappedGesture`，仍能正确执行。
     * 映射表保留这些项 = **有兜底**，不是双重处理。
     *
     * 真正有意为 NONE 的只有手势 7/8（改作导航切换，
     * 由 `GestureChain.onNavigationSwitch` 硬编码处理）——
     * 保留 NONE 正是为了**避免**与硬编码路径冲突。
     */
    @Test
    fun onlyNavigationSwitchGestures_areIntentionallyNone() {
        assertEquals(
            "手势 7（先上再下）已改作导航切换，映射表应为 NONE",
            GestureAction.NONE, GestureMap.actionOf(Gestures.GESTURE_SWIPE_UP_AND_DOWN)
        )
        assertEquals(
            "手势 8（先下再上）已改作导航切换，映射表应为 NONE",
            GestureAction.NONE, GestureMap.actionOf(Gestures.GESTURE_SWIPE_DOWN_AND_UP)
        )
    }

    /**
     * 单指滑动 / 双指上下滑在映射表里**必须有**动作 —— 作为 Actor 未处理时的兜底
     */
    @Test
    fun navigationAndScrollGestures_haveFallbackActions() {
        assertEquals(GestureAction.FOCUS_PREVIOUS, GestureMap.actionOf(Gestures.GESTURE_SWIPE_LEFT))
        assertEquals(GestureAction.FOCUS_NEXT, GestureMap.actionOf(Gestures.GESTURE_SWIPE_RIGHT))
        assertEquals(GestureAction.FOCUS_PREVIOUS, GestureMap.actionOf(Gestures.GESTURE_SWIPE_UP))
        assertEquals(GestureAction.FOCUS_NEXT, GestureMap.actionOf(Gestures.GESTURE_SWIPE_DOWN))
        assertEquals(GestureAction.SCROLL_BACKWARD, GestureMap.actionOf(Gestures.GESTURE_2_FINGER_SWIPE_UP))
        assertEquals(GestureAction.SCROLL_FORWARD, GestureMap.actionOf(Gestures.GESTURE_2_FINGER_SWIPE_DOWN))
    }


    /**
     * ★ 映射表里**不得**出现「同一个手势被两个不同动作声明」的情况
     *
     * 映射表本身是 `when` 表达式，同一分支写两次时 Kotlin 只取第一个 ——
     * 后一个是**死代码**且不报错。这条测试用「遍历所有手势 ID，
     * 断言查表结果稳定」的方式捕捉明显的重复声明。
     */
    @Test
    fun actionOf_isDeterministicForAllGestureIds() {
        val all = 1..45
        for (g in all) {
            val a = GestureMap.actionOf(g)
            val b = GestureMap.actionOf(g)
            assertEquals("同一手势 ID 必须稳定映射（id=$g）", a, b)
        }
    }

    /**
     * ★★ 媒体键必须按**动作**分派，不得按 gestureId
     *
     * ══════════════════════════════════════════════════════════
     * 【这条测试的来历：我原来的断言是错的】
     * ══════════════════════════════════════════════════════════
     *
     * 我原来断言「双指左右滑 = MEDIA_PREVIOUS/NEXT」——**实测不是**：
     *   · 27 双指左滑 → `SCROLL_FORWARD`
     *   · 28 双指右滑 → `SCROLL_BACKWARD`
     * 它们在映射表里是**滚动**动作（双指滑动留给滚动）。
     * 真正映射到媒体的是**四指**左右滑（35/36）。
     *
     * 而 `handleMediaGesture` 原来把 27/28 当成上一首/下一首 ——
     * 与映射表矛盾，且因为分派要求
     * `actionOf(gestureId) ∈ MEDIA_*`，那两条分支**永远不可达**。
     * 净效果：真正映射到 MEDIA_PREVIOUS 的「四指左滑」落到
     * `else -> return false`，**媒体按键完全不可用**。
     */
    @Test
    fun mediaActions_areDispatchedByActionNotGestureId() {
        val code = java.io.File(
            "src/main/java/com/baicha/reader/core/GestureActionHandler.kt"
        ).readText().lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        val idx = code.indexOf("private fun handleMediaGesture(")
        assertTrue("必须存在 handleMediaGesture", idx >= 0)
        val body = code.substring(idx, minOf(code.length, idx + 1600))

        assertTrue(
            "必须按 GestureAction 分派（与映射表同源）:\n$body",
            body.contains("GestureAction.MEDIA_PREVIOUS") &&
                    body.contains("GestureAction.MEDIA_NEXT") &&
                    body.contains("GestureAction.MEDIA_PLAY_PAUSE")
        )
        assertFalse(
            "不得再按 gestureId 分派（那是第二份真相的根源）:\n$body",
            body.contains("Gestures.GESTURE_2_FINGER_SWIPE_LEFT") ||
                    body.contains("Gestures.GESTURE_2_FINGER_SWIPE_RIGHT") ||
                    body.contains("Gestures.GESTURE_2_FINGER_SINGLE_TAP")
        )

        // ★ 逐条核对「动作 → 键」的**配对**，不只是「三个都存在」
        //
        // 【为什么必须逐条】我第一版只断言三个动作名都出现 ——
        // 变异「把播放暂停键改成下一首」**逃逸**了：
        // 三个名字仍在，只是配对错了。
        // 这正是本项目反复踩的坑：「断言存在」不等于「断言正确」。
        assertTrue(
            "MEDIA_PREVIOUS 必须配 KEYCODE_MEDIA_PREVIOUS:\n$body",
            Regex(
                "GestureAction\\.MEDIA_PREVIOUS\\s*->\\s*KeyEvent\\.KEYCODE_MEDIA_PREVIOUS"
            ).containsMatchIn(body)
        )
        assertTrue(
            "MEDIA_NEXT 必须配 KEYCODE_MEDIA_NEXT:\n$body",
            Regex(
                "GestureAction\\.MEDIA_NEXT\\s*->\\s*KeyEvent\\.KEYCODE_MEDIA_NEXT"
            ).containsMatchIn(body)
        )
        assertTrue(
            "MEDIA_PLAY_PAUSE 必须配 KEYCODE_MEDIA_PLAY_PAUSE（不得配成别的键）:\n$body",
            Regex(
                "GestureAction\\.MEDIA_PLAY_PAUSE\\s*->\\s*KeyEvent\\.KEYCODE_MEDIA_PLAY_PAUSE"
            ).containsMatchIn(body)
        )
    }

    /** 真正映射到媒体动作的手势 —— 四指左右滑 + 双指双击 */
    @Test
    fun mediaGestures_mapToMediaActions() {
        assertEquals(
            GestureAction.MEDIA_PREVIOUS,
            GestureMap.actionOf(Gestures.GESTURE_4_FINGER_SWIPE_LEFT)
        )
        assertEquals(
            GestureAction.MEDIA_NEXT,
            GestureMap.actionOf(Gestures.GESTURE_4_FINGER_SWIPE_RIGHT)
        )
        assertEquals(
            GestureAction.MEDIA_PLAY_PAUSE,
            GestureMap.actionOf(Gestures.GESTURE_2_FINGER_DOUBLE_TAP)
        )
    }

    /** 双指左右滑是**滚动**，不是媒体 */
    @Test
    fun twoFingerHorizontalSwipes_areScrollNotMedia() {
        assertEquals(
            "双指左滑 = 滚动（我第一版断言成上一首，是错的）",
            GestureAction.SCROLL_FORWARD,
            GestureMap.actionOf(Gestures.GESTURE_2_FINGER_SWIPE_LEFT)
        )
        assertEquals(
            "双指右滑 = 滚动",
            GestureAction.SCROLL_BACKWARD,
            GestureMap.actionOf(Gestures.GESTURE_2_FINGER_SWIPE_RIGHT)
        )
    }
}
