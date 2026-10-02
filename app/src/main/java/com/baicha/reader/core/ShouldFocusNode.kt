package com.baicha.reader.core

import android.graphics.Rect
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 可聚焦判定 —— 1:1 精确抄天坦 w7/C3678d.r(node, z9)
 *
 * 【关键：滑动浏览走的是 r(node, false)，不是 j()】
 *
 * 天坦 M6/h.java:435 E() → D(i9, z9, z10, z11, i10)，i10 默认 0
 *   → t(0) = C3678d.g() = f39014i = 内部类 a = r(node, false)
 *
 * t(i9) 的遍历模式表：
 *   0(默认) → g() = a = r(node, false)     ★滑动浏览用这个★
 *   1       → f39053m = n  (列表遍历)
 *   3       → f39054n = o  (拆分遍历)
 *   4       → g().and(f39055o)
 *   5       → g().and(h(f39050j))
 *   6       → g().and(f39051k)
 *
 * 而 C3678d.h() = C0496d = t() = AbstractC3682f.j() 只在 WebView(role 15) 场景用
 * （M6/h.java:751/908/1994 都是 `roleOf == 15 && B(...)`）
 *
 * 【天坦原版 r(node, z9)】
 *   ① node == null → false
 *   ② isType15(node) → A0.b(node) 是否可见（可读版命名，勿叫 isWebView）
 *   ③ !node.isVisibleToUser → false
 *   ④ if (f39007b == null) { 初始化 f39008c = node 边界 }
 *   ⑤ if (c(node) && node.childCount > 0) {
 *         微信：text == "图像" || "浮窗" → true；!k(node) → false
 *         其他：!k(node) || !i(node) → false
 *      }
 *   ⑥ role = roleOf(node)
 *      if (role == 10 && hasAction(4096, 8192, SET_PROGRESS)) → true
 *      if (role != 8 && role != 16) {
 *          z11 = n(node, false) || (W(node) && p(node))
 *          if (!z9) return z11          ← z9=false 直接返回
 *          if (z11) { ... }
 *          if (i(node) && !isSystemBar(node)) return true
 *      }
 *   ⑦ false
 *
 * 【辅助】
 *   n(node, false) = n0 || a0 || l0 || hasAction(16, 32)
 *       n0 = isScreenReaderFocusable()
 *       a0 = isClickable()
 *       l0 = isLongClickable()
 *   k(node) = (isFocusable || hasAction(16)) || (isSelected || hasAction(32))
 *   i(node) = C(node) || B(node)   ← 有文本 或 有状态/进度
 *              （**不是** isClickable —— 我原先抄错了，见 i() 的注释）
 *   p(node) = hasText(node) && hasChildContent(node)
 *   W(node) = 父节点是 列表(8)/网格(5)/滚动视图(30)/横向滚动(31)
 */
object ShouldFocusNode {

    private const val WECHAT = "com.tencent.mm"

    /** 递归深度上限 */
    private const val MAX_DEPTH = 12

    private val tempRect = Rect()
    private val tempRect2 = Rect()

    /**
     * 边界基准 —— 对应天坦 f39008c / f39007b
     *
     * 【天坦语义（重要，容易搞错）】
     *   f39007b : 句柄对象（null = 未初始化）
     *   f39008c : 基准边界 —— 取自**本轮操作处理的第一个可见节点**
     *
     *   c(node) 检查 `node 的边界 == f39008c`，用于识别
     *   「节点与基准同大小」的情况（通常是根容器或整屏节点）。
     *
     * 【生命周期 —— 天坦的关键设计】
     *
     *   初始化：r() 里首次遇到可见节点时写入。
     *   清理：  `d()` 置 null，而 `d()` 在 **M6.h.D() 的每条退出路径**上
     *           都会被调用（源码里共 26 处）。
     *
     *   也就是说基准的作用域是 **「一次导航操作」**，不是「一个窗口」。
     *
     * 【原实现的 bug】
     *   本项目原先只在 `onWindowChanged()` 里清理 —— 作用域被拉长成「一个窗口」。
     *   后果：基准一旦被某个节点写入，后续所有判定都拿它比较，
     *   微信特判（"图像"/"浮窗"）的命中条件几乎永远不成立或永远成立。
     *
     * 【修正】
     *   新增 `resetOperationScope()`，在每次 FocusManager.moveFocus() 结束后调用，
     *   与天坦的 per-operation 作用域一致。
     */
    private val baselineBounds = Rect()

    /** 基准是否已初始化 —— 对应天坦 f39007b（null 判定） */
    private var baselineInitialized = false

    /**
     * 清空调用内状态 —— 窗口切换时由 FocusController 调用
     *
     * 【修正】去掉了跨调用的 cache（内容 hash 做 key 且无失效机制 = 读到过期结果）。
     * 现在只保留一个「本次判定的递归深度护栏」，无需外部清理。
     */
    /**
     * 全量清理 —— 窗口/内容变化时调用（对应天坦 d()）
     */
    fun clear() {
        tempRect.setEmpty()
        tempRect2.setEmpty()
        baselineBounds.setEmpty()
        baselineInitialized = false
        // ★ 清 p() 的缓存
        //
        // 【为什么必须清】这是 IdentityHashMap<AccessibilityNodeInfo, _> ——
        // 强引用节点。不清会：
        //   ① 内存持续增长（每个判定过的节点都留在 map 里）
        //   ② 拖住已回收的节点对象不被 GC
        //
        // 天坦的对应缓存 `f39013h` 也在 `C3678d.d()` 里清空。
        //
        // 【为什么在这里清而不是只靠容量】本类是「一次操作」作用域的，
        //   清空成本 O(1)（clear 而非逐个删），且语义清晰：
        //   每次导航开始 → 上一轮的判定缓存不再有意义。
        pCache.clear()
    }

    /**
     * 重置「本次操作」的作用域 —— 每次导航结束后调用
     *
     * 对应天坦在 `M6.h.D()` 每条退出路径上调用 `C3678d.f39006a.d()`。
     *
     * 为什么必须按操作重置：
     *   基准的作用是识别「当前正在处理的这个节点是否与基准同大小」。
     *   跨操作保留基准会导致判定条件失真。
     *
     * 【性能】只重置基准，不动 tempRect（那两个是纯临时缓冲）。
     */
    fun resetOperationScope() {
        baselineInitialized = false
        // ★ 本轮修正（P1）：同时清 pCache
        //
        // 【为什么】`pCache` 是「子节点是否有内容/可操作」的判定缓存，
        //   它只被 clear() 清（窗口切换时），而 resetOperationScope()
        //   每轮导航都调却不清它 → 滚动后持续读**上一轮的判定结果**。
        //   天坦的对应缓存 `f39013h` 与基准一起在 `d()` 里清。
        //
        // 【为什么不放在 clear() 里dedupe】clear() 是窗口级
        //   （低频），resetOperationScope() 是操作级（每轮导航）——
        //   两者语义不同，都要清，且这里更需要（导航频率高得多）。
        pCache.clear()
    }

    /**
     * 可聚焦判定 —— 1:1 抄天坦 C3678d.r(node, false)
     *
     * 【修正】原实现的 cache 用 AccessibilityNodeInfo 当 key（内容 hash），
     * 但节点内容会变（文本更新/位置移动），且没有任何失效机制 →
     * 跨页面读到过期结果，判定错误。
     *
     * 天坦的 f39007b/f39008c 只是「首次初始化边界」的哨兵，
     * 不是跨调用缓存。这里改为**不做持久缓存**，只在单次调用内递归用。
     */
    /**
     * 外部注入的缓存 —— 由服务在启动时设置
     *
     * 【性能】可聚焦判定要递归子树，代价高。
     * 天坦的 f39013h 缓存只在单次 e() 调用内有效，跨调用无收益。
     * 这里用 NodeCache 做跨调用缓存，窗口/内容变化时整体失效。
     */
    @Volatile
    var cache: NodeCache? = null

    fun shouldFocus(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // ★★ 本轮修正（真机 P0）：清空「本轮已访问子节点」集合
        //
        // 【天坦原文】`w7/FocusFinder.java:386`：
        // ```java
        // if (nodeType != 8 && nodeType != 16) {
        //     f39022g.clear();          // ← 每轮判定前清空
        //     boolean z11 = ...;
        // }
        // ```
        // `f39022g` 是静态 HashSet，语义是「**本轮判定内**已访问的子节点」。
        //
        // 【原来的 bug】`seenChildren` 是单例 `IdentityHashMap`，
        // **从声明到使用之间没有任何 clear**（全文件仅两处引用：
        // 声明 :669 与写入 :628）。后果：
        //   · 集合随服务运行时长单调增长；
        //   · 一旦某个 child 命中过，后续**任何一轮**判定再遇到它
        //     都会让整个 `m()` `return false`；
        //   · 真机表现：**越用越滑不到焦点**，且重启才恢复。
        //
        // 【为什么放在 shouldFocus 入口】
        //   天坦放在判定主体的开头（进入遍历循环之前）。
        //   白茶把判定委托给 NodeCache 的 `c.shouldFocus { ... }`，
        //   缓存命中时**不会**执行 lambda ⇒ 若把 clear 放在 lambda 内部，
        //   缓存命中路径就不会清 —— 所以必须放在这里，早于缓存查询。
        seenChildren.clear()

        val c = cache
        if (c != null) {
            return c.shouldFocus(node) { r(node, false, 0) }
        }
        return r(node, false, 0)
    }

    /**
     * 修复路径5需要的「本次调用边界基准」——
     * 天坦用静态 f39008c 存「上一次处理的节点边界」，
     * 语义是「同一个节点被重复处理」。这里用调用栈内的 prevBounds 表达。
     */
    private fun r(node: AccessibilityNodeInfo, z9: Boolean, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false

        // ② WebView 检查
        if (isType15(node)) {
            val webView = findWebViewRoot(node)
            return webView?.isVisibleToUser == true
        }

        // ③ 可见性
        if (!node.isVisibleToUser) return false

        // ④ 初始化边界基准 —— 对应天坦 f39007b / f39008c
        //
        // 【修正】原实现把 prevBounds 参数化，但顶层调用永远传 null →
        // 第⑤步（微信特判）永远执行不到，是死代码。
        // 天坦的实际语义是「静态基准字段，取本轮第一个可见节点的边界」，跨调用生效。
        // ★★ P0 修复（本轮）：基准矩形必须取**窗口边界**，不是节点边界
        //
        // 【天坦原文证据链】`w7/C3678d.java:343~347`：
        //   ```java
        //   if (f39007b == null) {
        //       C2846A c2846aH = AbstractC3688i.h(wVar);   // → wVar.T()
        //       if (c2846aH != null) c2846aH.a(f39008c);   // → getBoundsInScreen
        //       ...
        //   }
        //   ```
        // 逐环核实：
        //   · `AbstractC3688i.java:113`  `h(wVar)` → `wVar.T()`
        //   · `o0/w.java:761`            `T()` → `C2846A.o(getWindow())`
        //   · `o0/C2846A.java:51~53`     `a(rect)` → `f34738a.getBoundsInScreen(rect)`
        //       而 `f34738a` 是 `AccessibilityWindowInfo`
        //   ⇒ **`f39008c` 装的是「窗口边界」**。
        //
        // 【我原来的实现】取「第一个被判定节点的边界」——
        // 判据完全不同。后果：
        //   · 第 ⑤ 步 `c(node) && 同边界` 的命中集合整体错位
        //   · 微信「图像/浮窗」特判（依赖该分支）失准
        //
        // 【改动】改为取窗口边界。
        if (!baselineInitialized) {
            if (!fillWindowBounds(node, baselineBounds)) {
                // 取不到窗口（接口异常）→ 退回节点边界，但**标记来源**，
                // 避免静默地用错语义
                node.getBoundsInScreen(baselineBounds)
            }
            baselineInitialized = true
        }

        // ⑤ 与基准边界相同 且 有子节点
        if (baselineInitialized && isSameBounds(node, baselineBounds) && node.childCount > 0) {
            if (WECHAT == node.packageName?.toString()) {
                val text = node.text?.toString()
                if (text == "图像" || text == "浮窗") return true
                if (!k(node)) return false
            } else {
                if (!k(node) || !i(node)) return false
            }
        }

        // ⑥ 角色判定
        val role = NodeRoleHelper.roleOf(node)

        if (role == NodeRoleHelper.ROLE_SEEK_CONTROL &&
            hasAction(node, 4096, 8192, SET_PROGRESS_ACTION)
        ) {
            return true
        }

        if (role != NodeRoleHelper.ROLE_LIST && role != NodeRoleHelper.ROLE_PAGER) {
            // ★★ P0 修复（本轮）：`n(node, true)` 而非 `n(node, false)`
            //
            // 【天坦原文】`w7/C3678d.java:369`：
            //   ```java
            //   boolean z11 = o(this, wVar, false, 2, null)
            //                 || (AbstractC3693m.W(wVar) && p(wVar));
            //   ```
            // 这里调的是 **`o()`，不是 `n()`** ——
            // 而 `o()` 是 Kotlin 默认参数的 **synthetic 桥接方法**
            // （`C3678d.java:159~164`）：
            //   ```java
            //   public static synthetic boolean o(C3678d c, o0.w wVar,
            //                                     boolean z9, int i9, Object obj) {
            //       if ((i9 & 2) != 0) { z9 = true; }   // ← 实参 i9=2 ⇒ z9 被改写为 true
            //       return c.n(wVar, z9);
            //   }
            //   ```
            // 调用点传的 `i9 = 2` 表示「第 2 个参数用默认值」，
            // 而 `z9` 的默认值是 **`true`** ⇒ 实际执行 `n(wVar, true)`。
            //
            // 【我原来抄成 false 的后果】
            //   `n(node, true)` 分支比 `false` 多接受两类节点
            //   （见下方 n()）：`isScreenReaderFocusable`、`isFocusable`、
            //   以及 ACTION_FOCUS(1)。
            //   用 false 会让「只声明了 isFocusable 的节点」全部被判为
            //   不可聚焦 —— 真机表现正是用户描述的
            //   「左右滑动找不到焦点，一直震到边界」。
            //
            // 【注意】`n()` 两个分支内部的字段映射本身是对的，
            //   **不要交换 if 分支** —— 只是这里该传 true。
            val z11 = n(node, true) || (W(node) && p(node))

            if (!z9) return z11

            if (z11) {
                if (node.childCount == 0 || p(node)) return true
                // ★★ 修复真机 P0：这里原是 `k(node) && !k(node)` ——
                //   **恒为 false**（一个条件与自己取反做与运算），
                //   导致该分支永不返回 true。
                //
                // 【天坦原文】`w7/C3678d.java:379`：
                //   ```java
                //   return AbstractC3688i.k(wVar) && !k(wVar);
                //   ```
                //   注意：这是**两个不同的函数**，只是都叫 `k` ——
                //     · `AbstractC3688i.k(node)`  = 静态，`H(node) || N(node)`
                //       → 本项目对应 `k()`
                //     · `C3678d.k(node)`          = 实例方法，判断「子节点结构」
                //       → 本项目对应 `hasChildContent()`
                //
                //   我抄写时把两者合并成了同一个 `k()`，于是变成
                //   `k && !k` —— 语法上合法、测试能过、编译不报错，
                //   但**逻辑上永远为假**。
                //
                // 【影响】所有走到这个分支的节点都被判定为「不可聚焦」，
                //   导航找不到任何焦点 → 每次滑动都返回 null →
                //   服务侧触发 `haptic.boundary()`「到达边界」震动。
                //   这正对应用户的实测描述：
                //   「左右滑动从来都没有正常获取到焦点过，
                //     一到边界或者是没有焦点的那种震动反馈」。
                return k(node) && !hasChildContent(node)
            }

            if (i(node) && !isSystemBar(node)) return true
        }

        return false
    }

    /**
     * 取**窗口边界** —— 对应天坦 `f39008c` 的填充路径
     *
     * 证据链：`C3678d.java:343` → `AbstractC3688i.h()` →
     *        `o0/w.java:761 T()` → `C2846A.o(getWindow())` →
     *        `o0/C2846A.java:52 getBoundsInScreen(rect)`
     *
     * 【为什么不是节点边界】该基准用于第 ⑤ 步
     *   「节点边界 == 基准 且 有子节点」——判断的是
     *   「这个节点是否铺满整个窗口」（即根容器）。
     *   用节点边界做基准，就退化成「与第一个被判定节点同边界」，
     *   语义完全不同。
     *
     * @return true = 成功取到窗口边界
     */
    private fun fillWindowBounds(node: AccessibilityNodeInfo, out: Rect): Boolean {
        return try {
            val window = node.window ?: return false
            window.getBoundsInScreen(out)
            true
        } catch (e: Throwable) {
            false
        }
    }

    // ==================== 辅助（1:1 抄天坦） ====================

    /**
     * n(node, z9) —— 天坦 C3678d.n()
     *
     *   z9=false: n0 || a0 || l0 || hasAction(16, 32)
     *   z9=true:  n0 || g0 || a0 || l0 || hasAction(1, 16, 32)
     */
    private fun n(node: AccessibilityNodeInfo, z9: Boolean): Boolean {
        if (z9) {
            return isScreenReaderFocusable(node) || node.isFocusable ||
                    node.isClickable || node.isLongClickable ||
                    hasAction(node, 1, 16, 32)
        }
        return isScreenReaderFocusable(node) || node.isClickable ||
                node.isLongClickable || hasAction(node, 16, 32)
    }

    /**
     * k(node) = H(node) || N(node) —— 天坦 AbstractC3688i.k()
     */
    private fun k(node: AccessibilityNodeInfo): Boolean {
        // ★★ 本轮修正（用**可读版反编译**核对）
        //
        // 【对应函数】`w7/NodeUtils.java:142~146` 的 `isFocusableOrSelectable`：
        //   ```java
        //   public static final boolean isFocusableOrSelectable(NodeInfo nodeInfo) {
        //       return AccessibilityNodeInfoUtils.isActionableForAccessibility(nodeInfo)
        //           || AccessibilityNodeInfoUtils.isLongClickable(nodeInfo);
        //   }
        //   ```
        // 展开（逐层到 `w7/AccessibilityNodeInfoUtils.java`）：
        //   · `isActionableForAccessibility`（`:414~419`）
        //       = `isClickable || hasActionMask(16)`
        //   · `isLongClickable` = `isLongClickable`
        // ⇒ **`(isClickable || hasAction(16)) || isLongClickable`**
        //
        // 【我原来的实现】`(isFocusable || hasAction(16)) || (isSelected || hasAction(32))`
        //   —— 两处都错：
        //     · 第一项应是 **isClickable**，我写成了 isFocusable
        //     · 第二项应是 **isLongClickable**，我写成了 isSelected/action32
        //
        // 【影响】该函数用在 `FocusFinder.shouldFocus()` 第 395 行
        //   （可读版）——「可聚焦且无子节点」分支的收尾判定：
        //     `return NodeUtils.isFocusableOrSelectable(nodeInfo) && !hasFocusableChild(nodeInfo);`
        //   判据错了会让该分支的节点接受/拒绝整体偏离天坦。
        //
        // 【为什么之前没发现】旧版反编译里这两个函数都叫 `k`（一个静态
        //   一个实例），极难分辨；可读版把它们分别命名后才看清。
        return isActionableForAccessibility(node) || node.isLongClickable
    }

    /**
     * `AccessibilityNodeInfoUtils.isActionableForAccessibility(node)`
     *
     * `w7/AccessibilityNodeInfoUtils.java:414~419`：
     * ```java
     * return nodeInfo.isClickable() || hasActionMask(nodeInfo, 16);
     * ```
     * 注意用的是 **hasActionMask**（位掩码 + actionList 双路），
     * 不是单纯扫 actionList —— 见 [hasActionMask] 与 [hasAction] 的差别。
     */
    private fun isActionableForAccessibility(node: AccessibilityNodeInfo): Boolean =
        // 天坦白话：`isClickable() || hasActionMask(nodeInfo, 16)`
        // 而 `matchActionMask(node, id)` = 位掩码命中 **或** actionList 命中
        //   （`NodeUtils.java:277~283`）——
        // 白茶拆成两个函数 hasActionMask / hasAction，此处按天坦语义取「或」
        node.isClickable || hasActionMask(node, 16) || hasAction(node, 16)

    /**
     * `AbstractC3688i.i(node)` —— **不是** isClickable/isLongClickable
     *
     * 【我把这个函数抄错了，本轮修正】
     * 天坦 `w7/AbstractC3688i.java:120~123`：
     *   ```java
     *   public static final boolean i(o0.w wVar) {
     *       return AbstractC3693m.C(wVar) || AbstractC3693m.B(wVar);
     *   }
     *   ```
     * 即 `C(node) || B(node)`：
     *   · `C(node)`（`AbstractC3693m.java:337`）= 有文本（text/desc/hint）
     *       **且不是滚动容器**（`node.s() == null`，即无 collectionInfo）
     *   · `B(node)`（`:330`）= `J() 非空 || Y() || D()`
     *       —— 「有可朗读内容」的更宽判定（含进度类）
     *
     * 【我原来的实现】写成了 `isClickable || isLongClickable` ——
     *   这**恰好是**天坦另一个函数（`AbstractC3693m.i()`）的语义，
     *   两者字母相同但不是同一个东西。
     *
     * 【影响】该函数用在 `:238` 的「接受该节点为焦点」分支：
     *   `if (i(node) && !isSystemBar(node)) return true`
     *   用错语义会让**纯文本节点被判为不可聚焦** ——
     *   而聊天/网页里大量焦点目标正是纯文本节点。
     *   用户表现为「滑不到焦点」。
     *
     * 【本项目的映射】
     *   `C(node)` → [hasText]（已有，语义一致）
     *   `B(node)` → [hasSpeakableContent]（新增）
     */
    private fun i(node: AccessibilityNodeInfo): Boolean =
        hasText(node) || hasSpeakableContent(node)

    /**
     * `AbstractC3693m.B(node)` —— 「有可朗读内容」
     *
     * 天坦 `w7/AbstractC3693m.java:330~334`：
     * ```java
     * public static boolean B(o0.w wVar) {
     *     if (wVar != null) {
     *         return !TextUtils.isEmpty(wVar.J()) || wVar.Y() || D(wVar);
     *     }
     *     return false;
     * }
     * ```
     *   · `J()`  = 状态描述（`stateDescription`）
     *   · `Y()`  = 已勾选/已选中之类的状态
     *   · `D()`  = 有进度信息（`rangeInfo`）
     *
     * 这三者都是「用户能听到内容」的来源 ——
     * 即使节点没有 text/desc，只要有状态或进度，也应可聚焦。
     */
    private fun hasSpeakableContent(node: AccessibilityNodeInfo): Boolean {
        // ★★ 本轮重核修正（可读版 `AccessibilityNodeInfoUtils.java:332~337`）
        //
        // 【天坦原文】就是 `hasStateDescription(nodeInfo)` 一个函数：
        // ```java
        // public static boolean hasStateDescription(NodeInfo nodeInfo) {
        //     if (nodeInfo != null) {
        //         return !TextUtils.isEmpty(nodeInfo.getStateDescription())
        //             || nodeInfo.isCheckable()          // ← 只有 isCheckable
        //             || hasRangeInfo(nodeInfo);
        //     }
        //     return false;
        // }
        // ```
        //
        // 【我原来的两处错】
        //   ① 勾选判定写宽了：我写 `isCheckable || isChecked || isSelected`，
        //      天坦**只有 isCheckable**。
        //      `isChecked`/`isSelected` 是「当前状态」，`isCheckable` 才是
        //      「这个控件具备勾选语义」。写宽会让普通选中项被当成
        //      「有可朗读内容」→ 过度接受节点。
        //   ② 进度判定漏了区间校验：我只判 `max - min > 0`，
        //      天坦 `hasRangeInfo`（`:348~357`）还要求
        //      **`current ∈ [min, max]`**：
        //      ```java
        //      return fB - fC > 0 && fA >= fC && fA <= fB;
        //      ```
        //      漏掉会让「current 越界」的畸形进度条被误认为有内容。

        // ① 状态描述
        val stateDesc = try {
            node.stateDescription
        } catch (e: Throwable) {
            null
        }
        if (!stateDesc.isNullOrEmpty()) return true

        // ② isCheckable（**只有它**，不含 isChecked/isSelected）
        if (node.isCheckable) return true

        // ③ hasRangeInfo：区间有效 **且** current 落在区间内
        return hasRangeInfo(node)
    }

    /**
     * `AccessibilityNodeInfoUtils.hasRangeInfo(node)`
     *
     * 可读版 `:348~357`：
     * ```java
     * float max = i.max, min = i.min, cur = i.current;
     * return max - min > 0 && cur >= min && cur <= max;
     * ```
     */
    private fun hasRangeInfo(node: AccessibilityNodeInfo): Boolean {
        val range = try {
            node.rangeInfo
        } catch (e: Throwable) {
            null
        } ?: return false
        return (range.max - range.min) > 0f &&
            range.current >= range.min &&
            range.current <= range.max
    }

    /**
     * p(node) —— 天坦 C3678d.p()
     */
    private fun p(node: AccessibilityNodeInfo): Boolean {
        // 【本轮修正】原来是 `hasText(node) && hasChildContent(node)` ——
        // **与天坦语义完全不符**（那是我自己拼的组合，不是天坦的 p）。
        //
        // 天坦 `w7/C3678d.java:301~313`：
        // ```java
        // public final boolean p(o0.w wVar) {
        //     HashMap map = f39013h;                      // ← 有缓存
        //     Boolean bool = (Boolean) map.get(wVar);
        //     if (bool != null) return bool.booleanValue();
        //     boolean z9 = true;
        //     if (!AbstractC3688i.i(wVar) && !m(wVar)) z9 = false;
        //     map.put(wVar, Boolean.valueOf(z9));
        //     return z9;
        // }
        // ```
        // 即 `i(node) || m(node)`：**「自己可朗读」或「有可操作的子节点」**。
        //
        // 注意 `i()` 也在本轮被我修正过（原写成 isClickable，
        // 应为 `C || B` = 有文本 或 有状态/进度）。
        // 两个错误叠加会让判定结果完全偏离。
        val cached = pCache[node]
        if (cached != null) return cached

        val result = i(node) || m(node)
        // 容量保护：单次操作内节点极多时避免无界增长
        // （正常导航一轮远小于此，触发即说明树结构异常）
        if (pCache.size >= P_CACHE_CAPACITY) pCache.clear()
        pCache[node] = result
        return result
    }

    /**
     * `C3678d.m(node)` —— 是否有「可操作的子节点」
     *
     * 天坦 `w7/C3678d.java:279~294`：
     * ```java
     * public final boolean m(o0.w wVar) {
     *     int iP = wVar.p();                    // childCount
     *     for (int i9 = 0; i9 < iP; i9++) {
     *         o0.w wVarO = wVar.o(i9);          // getChild(i)
     *         if (wVarO != null) {
     *             if (!f39012g.add(wVarO)) { wVarO.x0(); return false; }  // 重复 → false
     *             if (wVarO.s0()                                    // 子节点可见
     *                 && !o(this, wVarO, false, 2, null)            // 且不是「被过滤的」
     *                 && ((!p(wVarO) || !AbstractC3693m.W(wVarO)
     *                      || (AbstractC3693m.H(wVar) && AbstractC3693m.N(wVar)))
     *                     && p(wVarO))) {                          // 且子节点本身可朗读
     *                 return true;
     *             }
     *         }
     *     }
     *     return l(wVar, iP);       // 见下
     * }
     * ```
     *
     * 【与 p() 构成互递归】`m()` 里调 `p(wVarO)`，`p()` 里又调 `m()`。
     *   天坦用 `f39013h`（p 的缓存）与 `f39012g`（去重集合）保证收敛。
     *   本实现用 [pCache] 做同样的事 ——
     *   **缓存不只是优化，它是终止递归的必要条件**。
     */
    private fun m(node: AccessibilityNodeInfo): Boolean {
        // ★★ 本轮重核修正（用可读版 FocusFinder.java:293~309）
        //
        // 【天坦原文】
        // ```java
        // public final boolean hasActionableChild(NodeInfo nodeInfo) {
        //     int childCount = nodeInfo.getChildCount();
        //     for (int i9 = 0; i9 < childCount; i9++) {
        //         NodeInfo child = nodeInfo.getChild(i9);
        //         if (child != null) {
        //             if (!f39022g.add(child)) { child.x0(); return false; }
        //             if (child.isVisibleToUser()
        //                 && !bridgeIsActionable_DefaultTrue(this, child, false, 2, null)   // ← 我漏了
        //                 && ((!hasContentOrActionableChild(child)
        //                      || !AccessibilityNodeInfoUtils.isTopLevelScrollItem(child)
        //                      || (isActionableForAccessibility(nodeInfo)
        //                          && isLongClickable(nodeInfo)))
        //                     && hasContentOrActionableChild(child))) {
        //                 return true;
        //             }
        //         }
        //     }
        //     return hasInvisibleTextChild(nodeInfo, childCount);
        // }
        // ```
        //
        // 【我原来错在哪】
        //   ① **漏了 `!isActionable(child, true)` 这一整条** —— 它是排除项：
        //      「子节点自身已可操作」时不再把它当「容器的可操作子节点」。
        //      漏掉会让本该被排除的子节点把父节点判成可聚焦。
        //   ② 三目条件抄反：我只写了 `!w || bothSelectable`，
        //      天坦是三层嵌套，末项固定为 `hasContentOrActionableChild(child)`。
        //   ③ `k(node) && nn(node)` 应该只是第三层的一个子条件，
        //      且用的是 **isActionableForAccessibility**（可点击）而非我的 k。
        //   ④ 兜底应是 `hasInvisibleTextChild`（我的 l()），这部分原来是对的。
        val count = node.childCount
        for (idx in 0 until count) {
            val child = node.getChild(idx) ?: continue
            // 重复节点 → 天坦直接 return false（f39022g 是**静态**集合）
            if (seenChildren.put(child, true) != null) return false

            if (!child.isVisibleToUser) continue

            // ① 排除：子节点自身可操作
            if (n(child, true)) continue

            // ② 三层条件
            val childHasContentOrActionable = p(child)          // hasContentOrActionableChild
            val topLevelScroll = W(child)                        // isTopLevelScrollItem
            val parentClickableAndLongClickable =
                isActionableForAccessibility(node) && node.isLongClickable

            val inner = !childHasContentOrActionable ||
                !topLevelScroll ||
                parentClickableAndLongClickable

            if (inner && childHasContentOrActionable) {
                return true
            }
        }
        // 兜底：`hasInvisibleTextChild(nodeInfo, childCount)` —— 见 l()
        return l(node, count)
    }

    /**
     * 子节点去重集合 —— 天坦 `f39022g`（**静态** `HashSet`）
     *
     * 【为什么是静态共享集合而非每次新建】
     *   天坦 `FocusFinder.java:59` 声明为 `public static final HashSet`，
     *   并且 `shouldFocus()` 在进入判定前会 `f39022g.clear()`
     *   （可读版第 386 行）。它的语义是「**本轮判定**里已访问过的节点」——
     *   跨 `hasActionableChild` 的多层递归共享，用于检测
     *   「同一子节点被重复计入」这种畸形树结构。
     *
     * 【我原来的实现】每次调用 `m()` 都新建一个局部 map ——
     *   去重范围缩到「单次 m() 调用内」，与天坦的跨递归语义不符。
     *
     * 【注意】用 IdentityHashMap 而非 HashSet：节点必须按**身份**去重，
     *   否则内容相同的两个兄弟会被误判为重复 → 返回 false（漏判）。
     */
    private val seenChildren = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(16)

    /**
     * `AccessibilityNodeInfoUtils.isTopLevelScrollItem` 里用到的
     * `a0(parent, f39055e)` —— 类名匹配
     *
     * `f39055e` 已核实用源码确认（`AccessibilityNodeInfoUtils.java:284`）：
     *   `AbstractC4096c.a("com.sec.android.touchwiz.widget.TwAdapterView")`
     * 即三星 TouchWiz 的 AdapterView —— 天坦把它当「滚动列表」的特例。
     */
    private fun isSamsungAdapterView(node: AccessibilityNodeInfo): Boolean =
        classNameEquals(node, SAMSUNG_ADAPTER_VIEW)

    /**
     * `C3678d.l(node, count)` —— 「存在不可见但含文本的非可操作子节点」
     *
     * 天坦 `w7/C3678d.java:265~277`：
     * ```java
     * public final boolean l(o0.w wVar, int i9) {
     *     if (AbstractC3693m.f39046f.accept(wVar)) return false;
     *     for (int i10 = 0; i10 < i9; i10++) {
     *         o0.w wVarO = wVar.o(i10);
     *         if (wVarO != null && !wVarO.s0()          // 不可见
     *             && AbstractC3693m.C(wVarO)            // 有文本
     *             && !wVarO.n0()                        // 不可聚焦
     *             && !AbstractC3688i.j(wVarO)) {        // 且不是「可操作」的
     *             // "Non-actionable invisible node with text found"
     *             return true;
     *         }
     *     }
     *     return false;
     * }
     * ```
     *
     * 【这个判定的用途】某些 App 把可读内容放在**不可见的子节点**里
     * （无障碍 bug，但真实存在）。此时父容器本身应可聚焦，
     * 否则用户完全读不到那块内容。
     *
     * 【本轮修正】我原来写的是 `count > 3`（凭空猜的阈值）——
     * 与天坦语义无关。天坦的 `i9` 参数只是循环上界（childCount），
     * **不是阈值**。
     */
    private fun l(node: AccessibilityNodeInfo, count: Int): Boolean {
        // ★★ 本轮重核补上前置过滤器（可读版 `FocusFinder.java:278~280`）
        //
        // 天坦原文：
        // ```java
        // public final boolean hasInvisibleTextChild(NodeInfo nodeInfo, int i9) {
        //     if (AccessibilityNodeInfoUtils.f39056f.accept(nodeInfo)) {
        //         return false;            // ← 前置短路，我漏了
        //     }
        //     for (int i10 = 0; i10 < i9; i10++) { ... }
        // }
        // ```
        // `f39056f` = `AccessibilityNodeInfoUtils` 内部类 `g`
        // （`w7/AccessibilityNodeInfoUtils.java:173~181`）：
        //   接受条件 = `hasScrollAction(node) && isVisible(node)
        //              && nodeType ∈ {3, 8, 5, 30, 31}`
        //   （或三星 AdapterView）
        // 语义 = **「本节点是滚动容器」**。
        //
        // 【为什么滚容要短路返回 false】
        //   滚动容器内部的「不可见含文本子节点」是**正常现象**
        //   （RecyclerView 回收屏外 item）——
        //   若据此把容器判成可聚焦，会让滚动容器本身抢走焦点。
        if (isScrollableContainerNode(node)) return false

        for (idx in 0 until count) {
            val child = node.getChild(idx) ?: continue
            val invisible = !child.isVisibleToUser
            val hasText = hasText(child)
            val notFocusable = !isFocusableLike(child)
            val notActionable = !isActionableLike(child)
            if (invisible && hasText && notFocusable && notActionable) {
                ReaderLog.d(TAG, "发现「不可见含文本的非可操作子节点」→ 父节点应可聚焦")
                return true
            }
        }
        return false
    }

    /**
     * `o0.w.n0()` —— 「可聚焦」
     *
     * 天坦 `AbstractC3688i` 体系里 `n0()` 对应
     * `isScreenReaderFocusable || isFocusable`。
     */
    private fun isFocusableLike(node: AccessibilityNodeInfo): Boolean =
        // ★★ P1 修复（本轮）：**不能** OR 上 isFocusable
        //
        // 【天坦原文】`o0/w.java:1019~1021` 的 `n0()`：
        //   ```java
        //   public boolean n0() {
        //       return Build.VERSION.SDK_INT >= 28
        //           ? this.f34743a.isScreenReaderFocusable()   // ← 只有这一个
        //           : i(1);
        //   }
        //   ```
        // 而 `isFocusable` 是**另一个函数** `g0()`（`o0/w.java:931~933`）。
        // 两者语义不同：
        //   · `isScreenReaderFocusable` = 「读屏应该聚焦它」
        //   · `isFocusable`            = 「输入焦点可以用它」
        // 后者包含大量读屏不该停留的中间容器 ——
        // OR 进来会让 `l()` 的「不可聚焦」判定变宽松，
        // 从而漏判「不可见含文本的非可操作子节点」。
        isScreenReaderFocusable(node)

    /**
     * `AbstractC3688i.j(node)` —— 「可操作」
     *
     * 天坦 `w7/AbstractC3688i.java:125~128`：
     * ```java
     * return wVar.g0() || wVar.a0() || wVar.l0()
     *     || z(wVar, 1, 16, 32)              // isClickable/LongClickable/有 action
     *     || y(wVar, VAD_z, 2048);           // 有「滚动」类 action
     * ```
     */
    private fun isActionableLike(node: AccessibilityNodeInfo): Boolean =
        // ★★ P1 修复（本轮）：拆成与天坦一致的两路判定
        //
        // 【天坦原文】`w7/AbstractC3688i.java:125~128` 的 `j()`：
        //   ```java
        //   return wVar.g0()                       // isFocusable
        //       || wVar.a0()                       // isClickable
        //       || wVar.l0()                       // isLongClickable
        //       || z(wVar, 1, 16, 32)              // ← 三动作组
        //       || y(wVar, 1024, 2048);            // ← 双动作组
        //   ```
        // 【我原来的错误】写成 `hasAction(1,16,32,2048)` ——
        // 把**两组**合成一次 OR：
        //   · `z(1,16,32)` 与 `y(1024,2048)` 都含「位掩码 + actionList」两路，
        //     合并后位掩码路径全丢（见 y()/z() 的注释）
        //   · 且 `y` 里那个 1024 在合并后虽未丢失，
        //     但语义被摊平，无法与天坦逐项对照
        //
        // 【顺带】`node.isScrollable` 我原来加在这里 ——
        //   天坦的 `j()` 没有它（滚动性由 action 位体现）。
        //   去掉以免判定过于宽松。
        node.isFocusable ||          // g0()
            node.isClickable ||      // a0()
            node.isLongClickable ||  // l0()
            z(node, 1, 16, 32) ||    // z(1,16,32)
            y(node, ACTION_NEXT_HTML_ELEMENT, ACTION_PREVIOUS_HTML_ELEMENT)  // y(1024,2048)

    /**
     * `AbstractC3688i.y(node, id1, id2)` —— **双路判定**
     *
     * 天坦 `w7/AbstractC3688i.java:259~263`：
     * ```java
     * public static final boolean y(o0.w wVar, int i9, int i10) {
     *     int iH = wVar != null ? wVar.h() : 0;
     *     return (iH & i9) == i9 || (iH & i10) == i10   // ① action 位掩码
     *         || w(wVar, i9) || w(wVar, i10);           // ② actionList 扫描
     * }
     * ```
     * ① `h()` = `AccessibilityNodeInfo.getActions()`（位掩码，`o0/w.java:939`）
     * ② `w(node, id)` = 遍历 `node.f()`（actionList）比对 id（`:239~249`）
     *
     * 【我原来的错误】只做了 ②（`hasAction` 扫 actionList），
     * **整个漏掉了 ① 位掩码路径** —— 两者是不同的数据源，
     * 某些节点只填了位掩码而 actionList 为空。
     */
    private fun y(node: AccessibilityNodeInfo, id1: Int, id2: Int): Boolean =
        // 对应 `NodeUtils.java:285~289` 的 `matchActionMask2`：
        //   `(actions & i9) == i9 || (actions & i10) == i10
        //    || hasAction(i9) || hasAction(i10)`
        hasActionMask(node, id1) || hasActionMask(node, id2) ||
            hasAction(node, id1) || hasAction(node, id2)

    /** `AbstractC3688i.z(node, id1, id2, id3)` —— 三动作版 */
    private fun z(node: AccessibilityNodeInfo, id1: Int, id2: Int, id3: Int): Boolean =
        hasActionMask(node, id1) || hasActionMask(node, id2) || hasActionMask(node, id3) ||
            hasAction(node, id1) || hasAction(node, id2) || hasAction(node, id3)

    /**
     * `o0.w.h()` 的位掩码判定 —— `(actions & id) == id`
     *
     * 天坦 `AbstractC3688i.x()`（`:252~257`）就是这个：
     * ```java
     * if (((wVar != null ? wVar.h() : 0) & i9) == i9) return true;
     * ```
     */
    private fun hasActionMask(node: AccessibilityNodeInfo, id: Int): Boolean = try {
        (node.actions and id) == id
    } catch (e: Throwable) {
        false
    }

    /** p() 的缓存 —— 天坦 `f39013h`；也是互递归终止的必要条件 */
    private val pCache = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(64)

    /** p() 缓存容量上限 */
    private const val P_CACHE_CAPACITY = 512

    private const val TAG = "ShouldFocusNode"

    /**
     * W(node) —— 天坦 AbstractC3693m.W()
     *   父节点是 列表(8)/网格(5)/滚动视图(30)/横向滚动(31)
     */
    private fun W(node: AccessibilityNodeInfo): Boolean {
        // ★★ 本轮重核修正（可读版 `AccessibilityNodeInfoUtils.java:534~558`）
        //
        // 【天坦原文】
        // ```java
        // public static boolean isTopLevelScrollItem(NodeInfo nodeInfo) {
        //     if (nodeInfo == null || !isVisible(nodeInfo)) return false;
        //     NodeInfo parent = nodeInfo.getParent();
        //     if (parent == null) return false;
        //     if (hasScrollAction(parent)) return true;              // ← 我漏了
        //     int nodeType = EventUtils.getNodeType(parent);
        //     if (nodeType == 8 || nodeType == 5 || nodeType == 30 || nodeType == 31) {
        //         z9 = true;                                        // ← 我有（用 role 近似）
        //     } else if (a0(parent, f39055e)) {                     // ← 我漏了
        //         z9 = true;
        //     }
        //     return z9;
        // }
        // ```
        //
        // 【我漏掉的两条】
        //   ① `hasScrollAction(parent)` —— **优先级最高**，只要父节点
        //      暴露任一滚动 action（`AccessibilityNodeInfoUtils.java:489~491`
        //      列了 6 个：SCROLL_FORWARD/BACKWARD/DOWN/UP/RIGHT/LEFT）
        //      就直接返回 true。这一条覆盖面比 nodeType 判定广得多 ——
        //      很多自定义滚动容器的 nodeType 不是 8/5/30/31，
        //      但有滚动 action。
        //   ② `a0(parent, f39055e)` —— 三星 TouchWiz AdapterView 特判
        //      （`f39055e = "com.sec.android.touchwiz.widget.TwAdapterView"`，
        //       `:284` 已核实）。
        //
        // 【为什么这处影响大】`W()` 用在 `m()` 的第二层条件里；
        //   漏判会让「父节点是可滚动容器」的子节点大量走不到返回 true 的分支。
        if (!node.isVisibleToUser) return false
        val parent = node.parent ?: return false
        return try {
            // ① 父节点有滚动 action —— 最高优先级
            if (hasScrollAction(parent)) return true

            // ② 父节点角色是列表/网格/滚动容器
            val role = NodeRoleHelper.roleOf(parent)
            if (role == NodeRoleHelper.ROLE_LIST ||
                role == NodeRoleHelper.ROLE_GRID ||
                role == NodeRoleHelper.ROLE_SCROLL_VIEW ||
                role == NodeRoleHelper.ROLE_HORIZONTAL_SCROLL_VIEW
            ) {
                return true
            }

            // ③ 三星 TouchWiz AdapterView
            isSamsungAdapterView(parent)
        } finally {
            runCatching { parent.recycle() }
        }
    }

    /**
     * `AccessibilityNodeInfoUtils.hasScrollAction(node)`
     *
     * `w7/AccessibilityNodeInfoUtils.java:489~491`：检查 6 个滚动 action，
     * 任一存在即 true。底层用 `r0(node, ...actions)` 做多动作判定。
     */
    private fun hasScrollAction(node: AccessibilityNodeInfo): Boolean =
        r0(
            node,
            ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD,
            ACTION_SCROLL_DOWN, ACTION_SCROLL_UP,
            ACTION_SCROLL_RIGHT, ACTION_SCROLL_LEFT
        )

    /**
     * `AccessibilityNodeInfoUtils.r0(node, vararg actions)` —— 任一动作存在
     *
     * 天坦 `:489` 的底层实现：对每个 action 做 `hasActionMask`
     * （位掩码 **或** actionList，见 `NodeUtils.matchActionMask`）。
     */
    private fun r0(node: AccessibilityNodeInfo, vararg actions: Int): Boolean =
        actions.any { hasActionMask(node, it) || hasAction(node, it) }

    /**
     * 类名匹配 —— 对应 `AbstractC3693z.a(className, cls)`
     *
     * 天坦用 `Class.forName(...)` 拿 Class 再比；白茶用字符串比较
     * （无需反射，且对混淆过的第三方类名同样可靠）。
     */
    private fun classNameEquals(node: AccessibilityNodeInfo, className: String): Boolean = try {
        node.className?.toString() == className
    } catch (e: Throwable) {
        false
    }

    /**
     * `AccessibilityNodeInfoUtils.f39056f` —— 「是滚动容器」
     *
     * 可读版 `w7/AccessibilityNodeInfoUtils.java:173~181`（内部类 `g`）：
     * ```java
     * public boolean accept(NodeInfo nodeInfo) {
     *     if (!hasScrollAction(nodeInfo) || !isVisible(nodeInfo)) return false;
     *     int nodeType = EventUtils.getNodeType(nodeInfo);
     *     return nodeType == 3 || nodeType == 8 || nodeType == 5
     *         || nodeType == 30 || nodeType == 31
     *         || a0(nodeInfo, f39055e);
     * }
     * ```
     * 用途：`l()` 的前置短路 —— 滚动容器内部的不可见子节点是
     * 正常现象（RecyclerView 回收屏外 item），不该据此判容器可聚焦。
     */
    private fun isScrollableContainerNode(node: AccessibilityNodeInfo): Boolean {
        if (!hasScrollAction(node) || !node.isVisibleToUser) return false
        val role = NodeRoleHelper.roleOf(node)
        if (role == NodeRoleHelper.ROLE_DROP_DOWN_LIST ||   // nodeType 3
            role == NodeRoleHelper.ROLE_LIST ||              // nodeType 8
            role == NodeRoleHelper.ROLE_GRID ||              // nodeType 5
            role == NodeRoleHelper.ROLE_SCROLL_VIEW ||       // nodeType 30
            role == NodeRoleHelper.ROLE_HORIZONTAL_SCROLL_VIEW // nodeType 31
        ) {
            return true
        }
        return isSamsungAdapterView(node)
    }

    /** 三星 TouchWiz AdapterView —— 天坦 `f39055e`（`:284` 已核实） */
    private const val SAMSUNG_ADAPTER_VIEW = "com.sec.android.touchwiz.widget.TwAdapterView"


    /**
     * 是否有文本 —— 天坦 AbstractC3693m.C()
     */
    private fun hasText(node: AccessibilityNodeInfo): Boolean {
        if (node.collectionInfo != null) return false
        // 逐项对应天坦 `AbstractC3693m.C()`（`:337~342`）：
        //   ```java
        //   return (TextUtils.isEmpty(wVar.L())
        //        && TextUtils.isEmpty(wVar.v())
        //        && TextUtils.isEmpty(wVar.A())) ? false : true;
        //   ```
        // 三个取值函数逐一核实（`o0/w.java`）：
        //   · `L()`（`:682`）→ `getText()`            ⇒ Kotlin `node.text`
        //   · `v()`（`:1161`）→ `getContentDescription()` ⇒ `node.contentDescription`
        //   · `A()`（`:583`）→ `getHintText()`        ⇒ `node.hintText`
        //
        // 【为什么这里要保持显式顺序】核查发现同文件别处（微信特判）
        // 把 L/v 的映射写反过。本函数是 OR 结构，即使写反结果也不变
        // —— 属于**沉默错误**：不报错、结果对，但一旦有人改成
        // AND 或调整顺序就会立刻出错。故此处显式标注对应关系。
        val hasMainText = !node.text.isNullOrEmpty()              // L()
        val hasContentDesc = !node.contentDescription.isNullOrEmpty()  // v()
        val hasHint = !node.hintText.isNullOrEmpty()              // A()
        return hasMainText || hasContentDesc || hasHint
    }

    /**
     * 有子节点内容 —— 天坦 C3678d.k()
     */
    private fun hasChildContent(node: AccessibilityNodeInfo): Boolean {
        val count = node.childCount
        if (count > 3) return true

        node.getBoundsInScreen(tempRect)
        // 【语义】天坦用 HashSet<o0.w>（identity）。这里也必须 identity，
        // 否则内容相同的兄弟节点会被误判为重复 → 返回 false（漏判）。
        //
        // 【性能】天坦的 f39012g 是**静态复用**的 HashSet（每次 d() 清空）。
        //   本项目原先每次调用 new 一个 IdentityHashMap → 判定热路径上的分配。
        //   改为复用同一个实例：因为本方法是判定的一部分，每次进入
        //   都先 clear()，语义与天坦一致，但省掉一次分配。
        //
        //   注意：复用的前提是「本方法不重入」。hasChildContent 内部会递归调用
        //   r()，而 r() 又可能再次进入 hasChildContent —— 所以**不能复用**。
        //   这里保持每次分配（正确性优先），但用 IdentityHashMap 而非 HashSet
        //   以减少哈希冲突开销。
        val seen = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(4)
        for (idx in 0 until count) {
            val child = node.getChild(idx) ?: continue
            if (seen.containsKey(child)) return false
            seen[child] = true
            if (child.isVisibleToUser) {
                if (child.childCount > 0) return true
                child.getBoundsInScreen(tempRect2)
                if (tempRect2 == tempRect) return true
                // ★★ P0 修复（本轮）：`n(child, true)` 而非 `r(child, false, 0)`
                //
                // 【天坦原文】`w7/C3678d.java:252`（k() 的循环体）：
                //   ```java
                //   if (o(this, wVarO, false, 2, null)) { return true; }
                //   ```
                // 同样是 `o()` 桥接 → **`n(wVarO, true)`**。
                //
                // 【为什么不是 r()】`r()` 是「完整的可聚焦判定」（会递归整棵子树），
                //   而这里需要的是 `n()`（轻量的「有交互性」判定）。
                //   我原来写的 `r(child, false, 0)` 有两个错：
                //     ① 函数选错（r vs n）
                //     ② 传参错（false vs true）
                //   两者都会让判定结果偏离天坦。
                if (n(child, true)) return true
            }
        }
        return false
    }

    /**
     * 是否系统栏 —— 天坦 AbstractC3693m.y(node, f39020o)
     */
    private fun isSystemBar(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: return false
        return id == "com.android.systemui:id/back" ||
                id == "com.android.systemui:id/home" ||
                id == "com.android.systemui:id/recent_apps"
    }

    /**
     * isScreenReaderFocusable —— 天坦 o0.w.n0()
     */
    private fun isScreenReaderFocusable(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                node.isScreenReaderFocusable
            } else {
                val extras = node.extras
                extras != null && extras.getBoolean(
                    "androidx.view.accessibility.AccessibilityNodeInfoCompat.SCREEN_READER_FOCUSABLE_KEY",
                    false
                )
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 是否 WebView —— 天坦 AbstractC3688i.v()
     */
    private fun isType15(node: AccessibilityNodeInfo): Boolean {
        // ★★ P0 修复（本轮）：语义被整体抄错
        //
        // 【天坦原文】`w7/AbstractC3688i.java:232~237`：
        //   ```java
        //   public static final boolean v(o0.w wVar) {
        //       if (wVar == null) return false;
        //       return y(wVar, com.vivo.speechsdk.module.vad.c.f23598z, 2048);
        //   }
        //   ```
        // 其中 `f23598z` 已核实用 javap/源码确认为 **1024**
        //   （`com/vivo/speechsdk/module/vad/c.java:52` —
        //    它其实是 `AccessibilityNodeInfo.ACTION_SCROLL_FORWARD` 的字面量，
        //    商业包只是复用了这个常量池位置）。
        // 即 `v()` = `y(node, 1024, 2048)` ——
        // **「节点有 1024 或 2048 号 action」**，与 className 无关。
        //
        // 【我原来的实现】用类名 `contains("WebView") || contains("webkit")` ——
        // 完全不同的判据。后果：
        //   · 真正有滚动 action 的 WebView 节点（className 可能是
        //     `android.webkit.WebView` 之外的名字，或就是普通 View）
        //     会被漏判
        //   · 使得依赖它的 `findWebViewRoot()` / A0.b() 分支大面积失活
        //
        // 【顺带】天坦 `g()` 才是 className 判定（`AbstractC3688i.java:135`），
        //   与本函数不是一回事 —— 又一个「同名/邻近函数」陷阱。
        return hasAction(node, ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD)
    }

    // ==================== Action 常量 ====================
    //
    // 全部用 `javap android.jar` 逐个核实，并用**可读版反编译**
    // `w7/AccessibilityNodeInfoUtils.java:655~660` 交叉确认。

    /** 1024 —— `ACTION_NEXT_HTML_ELEMENT`（可读版 `:657` 直接标注） */
    private const val ACTION_NEXT_HTML_ELEMENT = 1024

    /** 2048 —— `ACTION_PREVIOUS_HTML_ELEMENT`（可读版 `:659`） */
    private const val ACTION_PREVIOUS_HTML_ELEMENT = 2048

    /**
     * 天坦 `NodeUtils.isType15()` 用的就是 1024/2048
     * （`w7/NodeUtils.java:255~260`）。
     *
     * 【命名纠正】我最初把它命名为 `isWebView()` —— **错的**。
     * 可读版显示它叫 `isType15`，含义是「有 HTML 元素遍历 action」。
     * 真正的 WebView 判定是 `NodeNavigator.isInWebView()`
     * （`w7/NodeNavigator.java:105`，内部走 `isInScrollView`）。
     * 白茶暂不引入后者（用途不明），但**命名必须准确**。
     */
    private const val ACTION_SCROLL_FORWARD = 4096
    private const val ACTION_SCROLL_BACKWARD = 8192
    private const val ACTION_SCROLL_UP = 16908344
    private const val ACTION_SCROLL_DOWN = 16908346
    private const val ACTION_SCROLL_LEFT = 16908345
    private const val ACTION_SCROLL_RIGHT = 16908347

    /**
     * 找 WebView 根 —— 天坦 A0.b()
     */
    private fun findWebViewRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 10) {
            if (NodeRoleHelper.roleOf(cur) == NodeRoleHelper.ROLE_WEB_VIEW) return cur
            cur = cur.parent
            depth++
        }
        return node
    }

    /**
     * 边界是否和指定基准一致 —— 天坦 C3678d.c()
     */
    private fun isSameBounds(node: AccessibilityNodeInfo, base: Rect): Boolean {
        node.getBoundsInScreen(tempRect)
        return tempRect == base
    }

    private fun hasAction(node: AccessibilityNodeInfo, vararg ids: Int): Boolean {
        return try {
            node.actionList.any { it.id in ids }
        } catch (e: Throwable) {
            false
        }
    }

    /** ACTION_SET_PROGRESS */
    private const val SET_PROGRESS_ACTION = 16908349
}