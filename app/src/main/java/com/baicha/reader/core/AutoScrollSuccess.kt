package com.baicha.reader.core

/**
 * 自动滚动成功判定 —— 对应天坦 `a7/AutoScrollSuccessChecker.java`（125 行）
 *
 * ══════════════════════════════════════════════════════════
 * 【为什么需要这个类】
 * ══════════════════════════════════════════════════════════
 *
 * 自动滚动（焦点走到屏幕边缘时自动滚一屏）之后必须回答一个问题：
 * **这次滚动到底动了没有？**
 *
 * 答错的两种后果都很难受：
 *   · 把「没动」判成「动了」→ 焦点卡在屏幕边缘，反复播报同一个元素，
 *     用户以为读屏死了
 *   · 把「动了」判成「没动」→ 明明已经滚到新内容，读屏却报「到底了」
 *
 * ══════════════════════════════════════════════════════════
 * 【天坦的做法（本类的依据）】
 * ══════════════════════════════════════════════════════════
 *
 * 天坦不在滚动后去问「节点位置变了吗」，而是**累计滚动事件的位移量**，
 * 与一个基于滚动容器尺寸的阈值比较。
 *
 * `a7/AutoScrollSuccessChecker.java:70~95`（原样摘录）：
 * ```java
 *   public final boolean b(AccessibilityEvent accessibilityEvent) {
 *       long jD = AbstractC3660b.d(accessibilityEvent);   // deltaX
 *       long jE = AbstractC3660b.e(accessibilityEvent);   // deltaY
 *       if (e(jD, jE, accessibilityEvent)) {              // ← 先判「是否已滚到边缘」
 *           return true;
 *       }
 *       long j9 = this.f12323d + jD;                      // 累计 X
 *       this.f12323d = j9;
 *       long j10 = this.f12324e + jE;                     // 累计 Y
 *       this.f12324e = j10;
 *       if (Math.abs(this.f12323d) < this.f12322c.x
 *           && Math.abs(this.f12324e) < this.f12322c.y) { // ← 两个轴都没超阈值
 *           return false;
 *       }
 *       if (d(this.f12320a)) {                            // 前向滚动
 *           return this.f12323d > 0 || this.f12324e > 0;
 *       }
 *       if (c(this.f12320a)) {                            // 后向滚动
 *           return jD < 0 || jE < 0;
 *       }
 *       return false;
 *   }
 * ```
 *
 * 阈值 `f12322c` 在构造器里算出来（`:57~59`）：
 * ```java
 *   this.f12322c = new Point((int) (rect.width() * f9), (int) (rect.height() * f9));
 * ```
 * 其中 `f9` 默认 **0.6**（`:118` 的 synthetic 桥接：
 * `this(i9, rect, (i10 & 4) != 0 ? 0.6f : f9)`）。
 *
 * 即 **阈值 = 滚动容器宽/高的 60%** —— 这个「60%」是设计过的：
 * 太小会把抖动算成滚动，太大则一次滚动永远达不到。
 *
 * ══════════════════════════════════════════════════════════
 * 【边缘检测（`e()`，:97~123）】
 * ══════════════════════════════════════════════════════════
 *
 * ```java
 *   int maxScrollX = accessibilityEvent.getMaxScrollX();
 *   int maxScrollY = accessibilityEvent.getMaxScrollY();
 *   int scrollX = accessibilityEvent.getScrollX();
 *   int scrollY = accessibilityEvent.getScrollY();
 *   if (maxScrollX == 0 && maxScrollY == 0) { return false; }   // 无效事件
 *   if (d(this.f12320a)) {            // 前向
 *       if (j10 > 0) { return maxScrollY - scrollY < 72; }
 *       if (j9 > 0) { return maxScrollX - scrollX < 72; }
 *   }
 *   if (c(this.f12320a)) {            // 后向
 *       if (j10 < 0) { return scrollY < 72; }
 *       if (j9 < 0 && scrollX < 72) { return true; }
 *   }
 * ```
 *
 * **重要性**：滚到边缘时位置**不再变化**，按阈值判定会得出「没动」→
 * 被误判为失败并无限重试。天坦用「距边缘 < 72px 就直接判成功」
 * 提前收尾，这个分支**必须**保留。
 *
 * ══════════════════════════════════════════════════════════
 * 【本类与天坦的差异（如实记录）】
 * ══════════════════════════════════════════════════════════
 *
 * `e()` 里天坦有个 `if (!AbstractC3684x.h()) return false;` ——
 * `AbstractC3684x` 是系统版本判定类的系列（该文件同时提供
 * `AbstractC3684x.c()` 等）。这一句是**版本闸门**：
 * 低版本系统上事件不携带有效的 scroll/maxScroll，判定无意义。
 *
 * 白茶未对齐该版本闸门 —— 因为白茶 minSdk 已是 26，
 * 而 `getMaxScrollX/Y`、`getScrollX/Y` 自 API 1/14 起就存在。
 * 若将来真机上发现边缘检测异常，**首先怀疑这里**。
 */
class AutoScrollSuccessChecker(
    /** 滚动动作 ID（4096=前向 / 8192=后向 等），决定方向语义 */
    private val scrollAction: Int,

    /** 滚动容器宽（像素） */
    private val containerWidth: Int,

    /** 滚动容器高（像素） */
    private val containerHeight: Int,

    /**
     * 阈值比例 —— 天坦默认 **0.6**
     *
     * 依据 `AutoScrollSuccessChecker.java:118` 的 synthetic 桥接默认值。
     */
    private val thresholdRatio: Float = DEFAULT_THRESHOLD_RATIO
) {

    companion object {
        /** 天坦默认阈值比例（`:118` 桥接方法里的 0.6f） */
        const val DEFAULT_THRESHOLD_RATIO = 0.6f

        /**
         * 边缘判定像素 —— 天坦 `e()` 里硬编码的 **72**
         *
         * `maxScrollY - scrollY < 72` / `scrollY < 72`
         */
        const val EDGE_PIXELS = 72

        // ---- 滚动动作 ID（与 AccessibilityNodeInfo.ACTION_SCROLL_* 一致）----

        /** 前向滚动（向下/向右翻） */
        const val ACTION_SCROLL_FORWARD = 4096

        /** 后向滚动（向上/向左翻） */
        const val ACTION_SCROLL_BACKWARD = 8192

        /**
         * 「是前向滚动」—— 天坦 `d(i9)`（`:110~112`）
         *
         * ```java
         *   return i9 == 4096 || i9 == NodeInfo.a.f34760E.b() || i9 == NodeInfo.a.f34761F.b();
         * ```
         * 后两个是 `AccessibilityAction.ACTION_SCROLL_DOWN/ACTION_SCROLL_RIGHT`
         * 的**动作 ID**（16908356 / 16908357）。白茶用常量值对齐。
         */
        fun isForwardScroll(action: Int): Boolean =
            action == ACTION_SCROLL_FORWARD ||
                    action == ACTION_SCROLL_DOWN ||
                    action == ACTION_SCROLL_RIGHT

        /**
         * 「是后向滚动」—— 天坦 `c(i9)`（`:106~108`）
         *
         * ```java
         *   return i9 == 8192 || i9 == NodeInfo.a.f34758C.b() || i9 == NodeInfo.a.f34759D.b();
         * ```
         * 后两个是 `ACTION_SCROLL_UP/ACTION_SCROLL_LEFT`（16908358 / 16908359）。
         */
        fun isBackwardScroll(action: Int): Boolean =
            action == ACTION_SCROLL_BACKWARD ||
                    action == ACTION_SCROLL_UP ||
                    action == ACTION_SCROLL_LEFT

        // ---- 全局滚动动作 ID（AccessibilityNodeInfo.AccessibilityAction）----

        /** `ACTION_SCROLL_DOWN` */
        const val ACTION_SCROLL_DOWN = 16908356

        /** `ACTION_SCROLL_RIGHT` */
        const val ACTION_SCROLL_RIGHT = 16908357

        /** `ACTION_SCROLL_UP` */
        const val ACTION_SCROLL_UP = 16908358

        /** `ACTION_SCROLL_LEFT` */
        const val ACTION_SCROLL_LEFT = 16908359
    }

    /** 阈值：容器尺寸 × 比例（天坦构造器里算好的 `f12322c`） */
    val thresholdX: Int = (containerWidth * thresholdRatio).toInt()

    val thresholdY: Int = (containerHeight * thresholdRatio).toInt()

    /** 累计位移 X —— 天坦 `f12323d` */
    private var totalDeltaX = 0L

    /** 累计位移 Y —— 天坦 `f12324e` */
    private var totalDeltaY = 0L

    /**
     * 是否已累计到「有意义的位移」—— 天坦 `a()`（`:67~69`）
     *
     * ```java
     *   return (this.f12323d == 0 && this.f12324e == 0) ? false : true;
     * ```
     */
    fun hasDelta(): Boolean = !(totalDeltaX == 0L && totalDeltaY == 0L)

    /**
     * 消费一个滚动事件，返回「是否应判定为滚动成功」——
     * 1:1 对应天坦 `b(event)`（`:70~95`）
     *
     * @return true = 应判定成功（停止重试）
     */
    fun onScrollEvent(deltaX: Long, deltaY: Long, event: ScrollMetrics): Boolean {
        // ① 先判「是否已到边缘」—— 到边缘时位置不再变，按阈值会误判为失败
        if (isReachedEdge(deltaX, deltaY, event)) return true

        // ② 累计
        totalDeltaX += deltaX
        totalDeltaY += deltaY

        // ③ 两个轴都未超阈值 → 还没动够
        if (Math.abs(totalDeltaX) < thresholdX && Math.abs(totalDeltaY) < thresholdY) {
            return false
        }

        // ④ 达到阈值 → 按方向确认位移符号
        if (isForwardScroll(scrollAction)) {
            return totalDeltaX > 0 || totalDeltaY > 0
        }
        if (isBackwardScroll(scrollAction)) {
            return deltaX < 0 || deltaY < 0
        }
        return false
    }

    /**
     * 是否已滚到边缘 —— 1:1 对应天坦 `e()`（`:97~123`）
     *
     * 【为什么必须单独判】滚到边缘后位置**不再变化**，
     * 若只按阈值判定，会得出「没动」→ 误判失败 → 无限重试。
     */
    private fun isReachedEdge(deltaX: Long, deltaY: Long, event: ScrollMetrics): Boolean {
        val maxScrollX = event.maxScrollX
        val maxScrollY = event.maxScrollY
        val scrollX = event.scrollX
        val scrollY = event.scrollY

        // 无效事件（不带 maxScroll 信息）→ 不做边缘判定
        if (maxScrollX == 0 && maxScrollY == 0) return false

        if (isForwardScroll(scrollAction)) {
            if (deltaY > 0) return maxScrollY - scrollY < EDGE_PIXELS
            if (deltaX > 0) return maxScrollX - scrollX < EDGE_PIXELS
        }
        if (isBackwardScroll(scrollAction)) {
            if (deltaY < 0) return scrollY < EDGE_PIXELS
            if (deltaX < 0 && scrollX < EDGE_PIXELS) return true
        }
        return false
    }

    /** 重置（每次自动滚动开始前调用）—— 天坦 `d()` / `a.a()` */
    fun reset() {
        totalDeltaX = 0
        totalDeltaY = 0
    }
}

/**
 * 滚动事件里判定所需的 4 个值
 *
 * 【为什么抽成数据类】`AccessibilityEvent` 在 JVM 单测里无法实例化
 * （同 `AccessibilityWindowInfo`），抽出来后判定逻辑**可以完整单测** ——
 * 这是本轮「抽取纯逻辑」方法的又一次应用。
 */
data class ScrollMetrics(
    val scrollX: Int,
    val scrollY: Int,
    val maxScrollX: Int,
    val maxScrollY: Int
)
