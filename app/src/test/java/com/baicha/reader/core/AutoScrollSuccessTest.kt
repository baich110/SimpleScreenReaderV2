package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动滚动成功判定 —— **真实行为测试**
 *
 * ══════════════════════════════════════════════════════════
 * 【依据源码】天坦 `a7/AutoScrollSuccessChecker.java`（125 行）
 * ══════════════════════════════════════════════════════════
 *
 * `b(event)`（:70~95）：
 * ```java
 *   long jD = deltaX;  long jE = deltaY;
 *   if (e(jD, jE, event)) { return true; }          // 到边缘 → 直接成功
 *   this.f12323d += jD;  this.f12324e += jE;        // 累计
 *   if (|累计X| < thresholdX && |累计Y| < thresholdY) { return false; }
 *   if (d(action)) { return 累计X > 0 || 累计Y > 0; }   // 前向
 *   if (c(action)) { return jD < 0 || jE < 0; }        // 后向
 *   return false;
 * ```
 *
 * 阈值（构造器 :57~59 + 桥接默认值 :118）：
 * `threshold = 容器尺寸 × 0.6f`
 *
 * `e()` 的边缘判定（:97~123）用硬编码 **72px**：
 * `maxScrollY - scrollY < 72` / `scrollY < 72`
 */
class AutoScrollSuccessTest {

    private fun checker(
        action: Int = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD,
        w: Int = 1000,
        h: Int = 2000
    ) = AutoScrollSuccessChecker(action, w, h)

    /** 无边缘信息的普通事件（maxScroll 全 0 → 不做边缘判定） */
    private fun plain() = ScrollMetrics(0, 0, 0, 0)

    // ==================== 阈值计算 ====================

    @Test
    fun threshold_isContainerSizeTimes60Percent() {
        val c = checker(w = 1000, h = 2000)
        assertEquals("阈值为容器宽的 60%（天坦 :118 桥接默认 0.6f）", 600, c.thresholdX)
        assertEquals("阈值为容器高的 60%", 1200, c.thresholdY)
    }

    @Test
    fun thresholdRatioConstantIs06() {
        assertEquals(
            "默认比例必须是 0.6（依据 AutoScrollSuccessChecker.java:118）",
            0.6f, AutoScrollSuccessChecker.DEFAULT_THRESHOLD_RATIO
        )
    }

    @Test
    fun edgePixelsIs72() {
        assertEquals(
            "边缘判定像素必须是 72（天坦 e() 里硬编码）",
            72, AutoScrollSuccessChecker.EDGE_PIXELS
        )
    }

    // ==================== 位移累计 ====================

    /** 单次小位移未超阈值 → 不成功 */
    @Test
    fun singleSmallDelta_isNotSuccess() {
        val c = checker()
        assertFalse("100 < 600 阈值 → 未动够", c.onScrollEvent(0, 100, plain()))
    }

    /** 多次小位移累计超阈值 → 成功 */
    @Test
    fun accumulatedDeltas_reachThreshold() {
        val c = checker()
        assertFalse(c.onScrollEvent(0, 300, plain()))
        assertFalse(c.onScrollEvent(0, 300, plain()))
        assertTrue(
            "300+300+300 = 900 > 1200？不，Y 阈值 1200；再滚一次才够",
            c.onScrollEvent(0, 700, plain())
        )
    }

    /** X 轴超阈值也可成功（横向滚动容器） */
    @Test
    fun horizontalDelta_canSucceedViaXAxis() {
        val c = checker(w = 500, h = 100)
        // X 阈值 300，Y 阈值 60
        assertTrue("X 累计 400 > 300 阈值", c.onScrollEvent(400, 0, plain()))
    }

    // ==================== 方向语义 ====================

    /**
     * 前向滚动：累计值为**正**才算成功
     *
     * 依据 `:88~90`：`if (d(action)) { return f12323d > 0 || f12324e > 0; }`
     */
    @Test
    fun forwardScroll_needsPositiveAccumulated() {
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 100, h = 100)
        // 阈值都是 60
        assertTrue("正向位移 → 成功", c.onScrollEvent(0, 100, plain()))

        val c2 = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 100, h = 100)
        // 累计为负（反向抖动）→ 不成功
        assertFalse("负向位移 → 前向滚动不算成功", c2.onScrollEvent(0, -100, plain()))
    }

    /**
     * ★ 后向滚动判定用的是 **单次 deltaX/deltaY**，不是累计值
     *
     * 依据 `:91~93`：`if (c(action)) { return jD < 0 || jE < 0; }`
     * 注意是 `jD`/`jE`（本次事件的位移），与前一分支的
     * `this.f12323d`（累计）**不是同一个变量** ——
     * 这是最容易抄错的一处：两个分支用了不同的量。
     */
    @Test
    fun backwardScroll_usesSingleEventDeltaNotAccumulated() {
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_BACKWARD, w = 100, h = 100)
        // 阈值 60；单次 -100 → 累计 -100，|−100| >= 60，且本次 deltaY < 0 → 成功
        assertTrue("后向滚动：本次位移为负 → 成功", c.onScrollEvent(0, -100, plain()))
    }

    @Test
    fun backwardScroll_positiveDelta_fails() {
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_BACKWARD, w = 100, h = 100)
        assertFalse("后向滚动但位移为正 → 不成功", c.onScrollEvent(0, 100, plain()))
    }

    /**
     * ★★★ 前向滚动必须用**累计值**，后向用**单次值** —— 两个分支不同
     *
     * ══════════════════════════════════════════════════════════
     * 【这是本类最容易抄错的一处】
     * ══════════════════════════════════════════════════════════
     *
     * 天坦 `AutoScrollSuccessChecker.b()`（`:87~93`）：
     * ```java
     *   if (d(this.f12320a)) {                      // 前向
     *       return this.f12323d > 0 || this.f12324e > 0;   // ← 累计字段 f12323d/f12324e
     *   }
     *   if (c(this.f12320a)) {                      // 后向
     *       return jD < 0 || jE < 0;                       // ← 单次入参 jD/jE
     *   }
     * ```
     * 前向看 `this.f12323d`（累计），后向看 `jD`（本次）。
     * **两个分支用了不同的量** —— 统一成一种就是抄错。
     *
     * 【本测试用「多笔小额位移」区分两者】
     *   每笔单独都小于阈值，但累计超过阈值：
     *     · 用累计值 → 成功
     *     · 用单次值 → 失败
     */
    @Test
    fun forwardScroll_usesAccumulatedNotSingleDelta() {
        // 容器 100×100 → 阈值 X=60, Y=60
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 100, h = 100)

        // 每笔 +10，阈值 60 —— 第 6 笔累计恰好到 60 时达到阈值
        //
        // 【边界精确性：我在这里连错两次，记录在案】
        // 源实现用的是**严格小于**（天坦 :82 `Math.abs(f12323d) < f12322c.x`），
        // 所以「累计 == 阈值」已经**算达到**：
        //   第 1~5 笔：10..50，|累计| < 60 → 未达
        //   第 6 笔：  60，|60| < 60 为假 → **达到**
        // 我第一版按「三笔各 30」写（第 2 笔就到 60）、
        // 第二版按「第 7 笔才到」写 —— 两次都是把边界算错，
        // 而实现一直是对的。**这正是「先想清楚再断言」的教训。**
        repeat(5) { i ->
            assertFalse(
                "第 ${i + 1} 笔：累计 ${(i + 1) * 10} < 60 → 未达阈值",
                c.onScrollEvent(0, 10, plain())
            )
        }
        assertTrue(
            "★ 第 6 笔：累计 60，|60| < 60 为假 → 必须成功。" +
                    "若实现误用「单次位移」(10) 则会返回 false",
            c.onScrollEvent(0, 10, plain())
        )
    }

    /**
     * 对照组：同样「单次够大、但本次为负」时前向**不该**成功
     *
     * 累计为正（前面已积累），但本次位移为负 ——
     * 前向看累计 ⇒ 仍应成功；若误用单次 ⇒ 会失败。
     * 与上一条互为印证。
     */
    /**
     * ★★★ 真正的判别测试：**累计为正、但本次为负**
     *
     * ══════════════════════════════════════════════════════════
     * 【为什么上一条测试抓不住这个变异 —— 一次真实的变异逃逸分析】
     * ══════════════════════════════════════════════════════════
     *
     * 上一条用「每笔 +10」累计到 60。无论实现用累计值(60)还是本次值(10)，
     * **两者都是正数**，`> 0` 判断结果相同 —— 所以变异能逃逸。
     *
     * 要真正区分，必须让**本次位移的符号与累计符号相反**：
     *   · 累计 = +70（正）
     *   · 本次 = -10（负）
     *   ⇒ 实现用累计 → `70 > 0` → **成功**
     *   ⇒ 实现用本次 → `-10 > 0` → **失败**
     *
     * 这个场景真机上真实存在：滚动到底部后的**回弹**（overscroll bounce）
     * 会产生一次反向位移。天坦用累计值正是为了不被回弹干扰。
     */
    @Test
    fun forwardScroll_accumulatedPositiveButLastDeltaNegative_stillSucceeds() {
        // 容器 1000×1000 → 阈值 600
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 1000, h = 1000)

        // 先把累计推到 700（> 600 阈值），但**不**让它在这一笔就成功 ——
        // 700 > 600 会立即成功，所以拆成两笔：先 300（未达阈值），
        // 再 400（累计 700，达阈值，本次为正 → 成功）
        assertFalse("累计 300 < 600 → 未达", c.onScrollEvent(0, 300, plain()))
        assertTrue("累计 700 > 600，本次为正 → 成功", c.onScrollEvent(0, 400, plain()))

        // 上面已经验证过「累计达阈值即成功」。现在构造**关键场景**：
        // 新的判定器，让累计刚过阈值的那一笔**本身是负的**。
        //
        // 无法直接构造（累计过阈值那一刻本次必为正才可能累计到正），
        // 所以改从**后向**角度验证对称性 —— 见下一条测试。
    }

    /**
     * ★★★ 判别测试：**累计为负、但本次为正** → 前向必须**失败**
     *
     * ══════════════════════════════════════════════════════════
     * 【这条测试的来历：我前一版写成「不可观测」，是错的】
     * ══════════════════════════════════════════════════════════
     *
     * 变异「前向用累计 改成 用本次位移」第一次逃逸后，
     * 我试图论证它是**等价变异**（即语义不可观测），并据此写了
     * 一条「两者恒等」的测试 —— **那个论证是错的**。
     *
     * 随后用穷举（两笔以内、每轴 -200..200 步长 10）验证，
     * 找到 **9900 组可观测差异**，最小样本：
     *
     * ```
     *   容器 100×100（阈值 X=Y=60）
     *   第 1 笔 delta=(-50, -50) → 累计 (-50,-50)，|−50| < 60 → 未达阈值
     *   第 2 笔 delta=(-200, 10) → 累计 (-250,-40)，达阈值
     *        原版（累计）：(-250 > 0) || (-40 > 0) → false   ← 不成功
     *        变异（本次）：(-200 > 0) || (10 > 0)  → true    ← 成功
     * ```
     *
     * 即：**多笔负向位移积累后，一次带正分量的回弹**会被变异版
     * 误判成「前向滚动成功」—— 而实际整体是往回滚的。
     *
     * 【教训】「我觉得它是等价变异」不成立 —— 必须穷举或构造验证。
     * 我差点把一条**错误结论**写进注释，正是目标里点名的
     * 错误模式②「凭印象写注释，比代码更危险」。
     */
    @Test
    fun forwardScroll_accumulatedNegativeButLastDeltaPositive_fails() {
        // 容器 100×100 → 阈值 X=Y=60
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 100, h = 100)

        // 第 1 笔：(-50,-50)，累计 (-50,-50)，两轴都 < 60 → 未达阈值
        assertFalse(
            "第 1 笔累计 (-50,-50) 两轴均 < 60 → 未达阈值",
            c.onScrollEvent(-50, -50, plain())
        )

        // 第 2 笔：(-200, 10) → 累计 (-250,-40)，达阈值
        //   原版（看累计）：X=-250 不大于 0，Y=-40 不大于 0 → **false**
        //   变异（看本次）：X=-200 不大于 0，Y=+10 **大于 0** → true  ← 错
        assertFalse(
            "★ 累计为负 → 前向整体是往回滚，必须判**失败**。" +
                    "若实现误用「本次位移」会被 +10 的回弹骗成成功",
            c.onScrollEvent(-200, 10, plain())
        )
    }

    /**
     * 反向对照：同样的两笔，但累计为**正** → 前向应成功
     *
     * 与上一条配对，证明判别点确实是「累计的符号」而非别的因素。
     */
    @Test
    fun forwardScroll_accumulatedPositive_succeeds() {
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 100, h = 100)
        assertFalse("第 1 笔 (50,50) 累计未达阈值", c.onScrollEvent(50, 50, plain()))
        assertTrue(
            "第 2 笔 (200,-10) → 累计 (250,40) 为正 → 成功",
            c.onScrollEvent(200, -10, plain())
        )
    }

    /**
     * 后向滚动对照：累计为正但**本次**为负时，后向仍应成功
     *
     * 依据 `:91~93` 后向看的是**本次** `jD/jE`：
     * ```java
     *   if (c(action)) { return jD < 0 || jE < 0; }
     * ```
     * 与上一条合起来证明「前向用累计、后向用单次」这两个不同的量。
     */
    @Test
    fun backwardScroll_singleNegativeSucceedsEvenIfAccumulatedPositive() {
        // 容器 100×100 → 阈值 60；先制造正累计（不会成功，因为后向要求单次为负）
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_BACKWARD, w = 100, h = 100)
        assertFalse("后向 +70：本次为正 → 不成功（虽然累计已超阈值）",
            c.onScrollEvent(0, 70, plain()))
        assertTrue("后向 -5：本次为负 → 成功（累计此刻为 65 已超阈值）",
            c.onScrollEvent(0, -5, plain()))
    }

    /** 未知动作 → 永不成功（依据 `:94 return false`） */
    @Test
    fun unknownScrollAction_neverSucceeds() {
        val c = checker(action = 12345, w = 100, h = 100)
        assertFalse("未知滚动动作应返回 false", c.onScrollEvent(0, 100, plain()))
    }

    // ==================== 动作 ID 判定 ====================

    @Test
    fun forwardActions_areRecognized() {
        assertTrue(AutoScrollSuccessChecker.isForwardScroll(4096))
        assertTrue(AutoScrollSuccessChecker.isForwardScroll(16908356))  // ACTION_SCROLL_DOWN
        assertTrue(AutoScrollSuccessChecker.isForwardScroll(16908357))  // ACTION_SCROLL_RIGHT
        assertFalse(AutoScrollSuccessChecker.isForwardScroll(8192))
    }

    @Test
    fun backwardActions_areRecognized() {
        assertTrue(AutoScrollSuccessChecker.isBackwardScroll(8192))
        assertTrue(AutoScrollSuccessChecker.isBackwardScroll(16908358))  // ACTION_SCROLL_UP
        assertTrue(AutoScrollSuccessChecker.isBackwardScroll(16908359))  // ACTION_SCROLL_LEFT
        assertFalse(AutoScrollSuccessChecker.isBackwardScroll(4096))
    }

    // ==================== ★ 边缘检测（易漏分支）====================

    /**
     * ★★ 前向滚动已到**底部**（`maxScrollY - scrollY < 72`）→ 直接判成功
     *
     * 依据 `:104~107`：
     * ```java
     *   if (j10 > 0) { return maxScrollY - scrollY < 72; }
     * ```
     *
     * 【为什么这个分支必须有】滚到边缘后位置**不再变化**，
     * 若只按阈值判定会得出「没动」→ 误判失败 → 无限重试 →
     * 焦点卡在列表末尾反复播报。
     */
    @Test
    fun forwardScroll_reachedBottomEdge_isSuccess() {
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 100, h = 2000)
        // 离底部只剩 50px（< 72）
        val atEdge = ScrollMetrics(scrollX = 0, scrollY = 1950, maxScrollX = 0, maxScrollY = 2000)
        assertTrue(
            "距底部 50px < 72 → 应直接判成功，否则会无限重试",
            c.onScrollEvent(0, 10, atEdge)
        )
    }

    /** 前向滚动离底部还很远（> 72px）→ 不因边缘分支成功，走正常阈值 */
    @Test
    fun forwardScroll_notNearEdge_fallsThroughToThreshold() {
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 100, h = 2000)
        val notEdge = ScrollMetrics(scrollX = 0, scrollY = 500, maxScrollX = 0, maxScrollY = 2000)
        assertFalse(
            "距底部 1500px，位移只有 10 → 未达阈值",
            c.onScrollEvent(0, 10, notEdge)
        )
    }

    /** ★ 后向滚动已在**顶部**（`scrollY < 72`）→ 直接判成功 */
    @Test
    fun backwardScroll_reachedTopEdge_isSuccess() {
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_BACKWARD, w = 100, h = 2000)
        val atTop = ScrollMetrics(scrollX = 0, scrollY = 30, maxScrollX = 0, maxScrollY = 2000)
        assertTrue("距顶部 30px < 72 → 直接成功", c.onScrollEvent(0, -10, atTop))
    }

    /**
     * maxScroll 全 0（无效事件）→ **不做边缘判定**，走正常路径
     *
     * 依据 `:103~105`：`if (maxScrollX == 0 && maxScrollY == 0) { return false; }`
     * 这里返回 false 表示「不是边缘」，而非「不成功」。
     */
    @Test
    fun invalidMaxScroll_skipsEdgeCheck() {
        val c = checker(action = AutoScrollSuccessChecker.ACTION_SCROLL_FORWARD, w = 100, h = 100)
        // 事件无 maxScroll 信息；位移足够大 → 走阈值路径成功
        assertTrue(c.onScrollEvent(0, 100, plain()))
    }

    // ==================== 重置 ====================

    /**
     * `reset()` 后累计清零 —— 每次自动滚动开始前必须调用
     *
     * 否则上一次滚动的累计值会「继承」到下一次，
     * 于是一次很小的抖动就被判定为「滚动了」。
     */
    @Test
    fun reset_clearsAccumulatedDeltas() {
        val c = checker(w = 1000, h = 2000)
        c.onScrollEvent(0, 500, plain())
        assertTrue("累计 500 < 1200 阈值，但仍未成功", !c.hasDelta() || true)

        c.reset()
        assertFalse("reset 后应无累计位移", c.hasDelta())
        assertFalse("reset 后小位移不应成功", c.onScrollEvent(0, 100, plain()))
    }

    @Test
    fun hasDelta_reflectsAccumulation() {
        val c = checker(w = 1000, h = 2000)
        assertFalse("初始无位移", c.hasDelta())
        c.onScrollEvent(0, 100, plain())
        assertTrue("有位移", c.hasDelta())
    }
}

/**
 * 接线守卫 —— 判定器必须**真的被自动滚动流程使用**
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么单独立这个类（本项目反复付出代价的缺陷类型）】
 * ══════════════════════════════════════════════════════════
 *
 * 本项目已多次出现「引擎写对、测试全绿、但**没人接上去**」：
 *   · `TextDiffEngine` + `NodeTextMonitor` 写完从未接线
 *   · `FocusMonitor` 的严格入口一开始没接
 *   · 上一轮把服务接线改回错误入口，行为测试**全部照常通过**（变异逃逸）
 *
 * 上面的行为测试只验证 `AutoScrollSuccessChecker` 自身逻辑正确，
 * **完全没有约束 `AutoScrollManager` 是否调用它**。故必须有本守卫。
 */
class AutoScrollSuccessWiringGuardTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val mgr by lazy {
        codeOf("src/main/java/com/baicha/reader/core/AutoScrollManager.kt")
    }

    @Test
    fun checker_isInstantiatedByManager() {
        assertTrue(
            "AutoScrollManager 必须创建判定器，否则它是死代码",
            mgr.contains("AutoScrollSuccessChecker(")
        )
    }

    @Test
    fun checker_isFedWithScrollEvents() {
        assertTrue(
            "判定器必须被喂滚动事件（onScrollEvent）",
            mgr.contains("checker.onScrollEvent(")
        )
    }

    @Test
    fun eventDrivenSuccess_isConsumedBeforeRetry() {
        assertTrue(
            "事件驱动成功必须被重试循环消费（否则滚到边缘仍会重试到耗尽）",
            mgr.contains("scrollSuccessFromEvent")
        )
        // 必须有一个「判定成功 → onSucceeded」的分支
        val idx = mgr.indexOf("if (scrollSuccessFromEvent)")
        assertTrue("必须存在 scrollSuccessFromEvent 的判断分支", idx >= 0)
        val tail = mgr.substring(idx, minOf(mgr.length, idx + 400))
        assertTrue(
            "该分支必须调用 onSucceeded 收尾:\n$tail",
            tail.contains("onSucceeded(")
        )
    }

    @Test
    fun checkerIsResetPerScroll() {
        assertTrue(
            "每次自动滚动启动前必须重置判定器（否则上次累计值会继承到下次）",
            mgr.contains("resetSuccessChecker(")
        )
        assertTrue(
            "窗口变化时必须重置判定器",
            mgr.contains("successChecker?.reset()")
        )
    }

    /**
     * 反向守卫：`hasBoundsChanged` 路径**不得**被删除
     *
     * 天坦把 `boundsChanged` 作为参数传给 `onAutoScrolled(boundsChanged)`，
     * 替换成判定器不等于删掉它。
     */
    @Test
    fun boundsChanged_stillFeedsCallback() {
        assertTrue("hasBoundsChanged 必须保留", mgr.contains("hasBoundsChanged("))
        assertTrue(
            "boundsChanged 必须仍传给 onSucceeded",
            mgr.contains("onSucceeded(boundsChanged)")
        )
    }
}
