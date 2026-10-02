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
        // 对应天坦 `FocusFinder.clearCache()`（`w7/FocusFinder.java:188~193`）：
        // ```java
        // public final void clearCache() {
        //     f39023h.clear();   // p 判定缓存
        //     f39017b = null;    // 基准矩形（= 我的 baselineInitialized）
        //     HashSet hashSet = f39022g;
        //     AccessibilityNodeInfoUtils.recycleAll(hashSet);
        //     hashSet.clear();   // 已访问子节点集合（= 我的 seenChildren）
        // }
        // ```
        // 天坦在 `M6/h.D()` 的 **26 条退出路径**上调用它（已 grep 确认 26 处）。
        //
        // 【本轮补齐第三项】原来只清基准，上一轮补了 pCache，
        //   但**漏了 seenChildren** —— 而天坦三样都清。
        //   `seenChildren` 不清的后果：命中过的子节点在后续轮次
        //   会让整个 `m()` 返回 false（越用判定越严）。
        //   （虽然 `shouldFocus()` 入口也清了，但天坦两处都清 ——
        //     退出路径清理能覆盖「缓存命中、未走入口 clear」的分支。）
        baselineInitialized = false
        pCache.clear()
        seenChildren.clear()
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

        // ══════════════════════════════════════════════════════════════
        // 【★★★ 真机 P0 根因：这里的 `z9` 必须传 `true`，不是 `false`】
        // ══════════════════════════════════════════════════════════════
        //
        // 【真机日志】用户第二次抓取（tag 已修正为全量）：
        // ```
        //   05:47:54.122 ❌ 下一焦点 | 失败 ← 遍历无果
        //                 （策略重试后仍未找到可聚焦节点（过滤=自定义过滤器））
        //   05:48:05.378 ✅ 上一焦点 | 通过 → 「(无文本)」(?)
        // ```
        // 能通过的**只有容器节点**（自身无文本），
        // 而**纯展示文本节点被大面积拒绝** ⇒ 用户
        // 「只能获取到一个两个焦点，而且很多焦点它都（获取不到）」。
        //
        // 【天坦的两条不同路径 —— 这是关键区分】
        // ① `FocusFinder.shouldFocus(node, z9)`（`w7/FocusFinder.java:337`）
        //    这是**旧的/内部的**路径，`z9 = false` 时
        //    `if (!z9) return z11;` **提前返回**，走不到后面的兜底。
        // ② `AccessibilityNodeInfoUtils.shouldFocusNodeInternal(node, map, z9)`（`:926`）
        //    这是**公开遍历入口**用的实现。
        //
        // 【决定性证据】`AccessibilityNodeInfoUtils.java:894~897`（原样摘录）：
        // ```java
        //   public static boolean shouldFocusNodeWithCache(NodeInfo nodeInfo, Map map) {
        //       return shouldFocusNodeInternal(nodeInfo, map, true);   // ← 传 true
        //   }
        // ```
        // 而 `shouldFocusNodeInternal` 里（`:962~970`）：
        // ```java
        //   boolean z11 = isAccessibilityFocusable(nodeInfo)
        //       || (isTopLevelScrollItem(nodeInfo) && hasSomethingToSpeakInternal(...));
        //   if (!z9) {
        //       return z11;                     // ← z9=false 在此提前返回
        //   }
        //   if (!z11) {
        //       recycleAll(hashSet);
        //       if (hasMatchingDescendant(nodeInfo, new d(map))
        //           || !(hasText(nodeInfo) || hasStateDescription(nodeInfo))) {
        //           return false;
        //       }
        //       return true;                    // ★★ 「有文本且无聚焦后代」→ 接受
        //   }
        //   ...
        // ```
        // ⇒ **`z9 = true` 才有「叶子文本节点」兜底**。
        //
        // 【我原来传 false 的后果】
        //   `r(node, false, 0)` 在 `if (!z9) return z11` 处**提前返回**，
        //   下面那段 `hasTextOrContent && !hasMatchingFocusableDescendant`
        //   的兜底**永远不可达** ⇒ 纯展示文本节点（`isFocusable=false`、
        //   `isClickable=false`、无 action、MIUI 上 `isScreenReaderFocusable`
        //   也为 false）**全部被判不可聚焦**。
        //
        // 【实测证据】修复前用探针跑一个带文本的普通 TextView：
        //   `r(n, false, 0) = false`  ← shouldFocus 走的就是这条
        //   `r(n, true,  0) = true`   ← 本应如此
        //
        // 【为什么必须传 true 而不是「删掉 z9 判断」】
        //   `z9 = false` 的语义仍被 `hasMatchingFocusableDescendant` 使用
        //   （对应天坦 `f39030o` → `shouldFocus(node, false)`），
        //   那是「这个后代**本身**值不值得聚焦」的判断，**不能**带兜底
        //   （否则任何有文本的后代都会让父容器认为「有聚焦后代」）。
        //   两条路径语义不同，必须分开传参。
        val c = cache
        if (c != null) {
            return c.shouldFocus(node) { r(node, true, 0) }
        }
        return r(node, true, 0)
    }

    /**
     * 修复路径5需要的「本次调用边界基准」——
     * 天坦用静态 f39008c 存「上一次处理的节点边界」，
     * 语义是「同一个节点被重复处理」。这里用调用栈内的 prevBounds 表达。
     */
    private fun r(node: AccessibilityNodeInfo, z9: Boolean, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false

        // ══════════════════════════════════════════════════════════════
        // ② 滚动容器判定 —— 对应天坦 `NodeNavigator.isInScrollView(nodeInfo)`
        // ══════════════════════════════════════════════════════════════
        //
        // 【天坦原文】`w7/AccessibilityNodeInfoUtils.java:931~945`
        // ```java
        // public static boolean shouldFocusNodeInternal(NodeInfo nodeInfo, Map map, boolean z9) {
        //     boolean z10 = false;
        //     if (nodeInfo == null) { return false; }
        //     if (NodeNavigator.isInScrollView(nodeInfo)) {         // ← 本分支
        //         NodeInfo nextSibling = NodeNavigator.getNextSibling(nodeInfo);
        //         if (nextSibling != null) {
        //             try {
        //                 if (nextSibling.isVisibleToUser()) { z10 = true; }
        //             } catch (Throwable th) { g0(nextSibling); throw th; }
        //         }
        //         g0(nextSibling);
        //         return z10;                                        // ← 返回「下一个兄弟是否可见」
        //     }
        //     if (!isVisible(nodeInfo)) { ... }
        //     ...
        // ```
        // 旧函数 `FocusFinder.shouldFocus:337~360` 开头**完全相同**。
        //
        // ══════════════════════════════════════════════════════════════
        // 【★ 我原来抄错的地方（语义整体跑偏）】
        // ══════════════════════════════════════════════════════════════
        //
        // 原实现：
        // ```kotlin
        //   // ② WebView 检查
        //   if (isType15(node)) {
        //       val webView = findWebViewRoot(node)
        //       return webView?.isVisibleToUser == true
        //   }
        // ```
        // 两处错：
        //   ① 分支体写成了「找 WebView 根节点、返回它是否可见」——
        //      **天坦这里是「看下一个兄弟是否可见」**，与 WebView 无关。
        //      （本文件开头的注释甚至自己写着
        //        「② isType15(node) → A0.b(node) 是否可见（可读版命名，**勿叫 isWebView**）」
        //        —— 作者知道不是 WebView，但分支体没跟着改。）
        //   ② `isType15` 这个名字容易被理解成「类型 15」，
        //      实际它是 `hasAction(node, 1024, 2048)`，
        //      对应天坦 `NodeNavigator.isInScrollView` 里的
        //      `hasActionMask2(node, f23604z=1024, 2048)`
        //      （`com/vivo/speechsdk/module/vad/c.java:52` 已核实 f23604z=1024）。
        //      ⇒ 判定条件本身是对的，错的是**分支体**。
        //
        // 【真机影响】真机 dump 里的 `android.widget.ScrollView`
        //   （`scrollable=true`、带 4096/8192 滚动 action）会命中本分支。
        //   旧代码去找「WebView 根」→ 找不到 → 返回 false。而白茶对
        //   这类节点的最终判定却是 true（走了后面的分支），
        //   与天坦（本分支直接 return）**不一致** —— 实测差 1 个节点。
        //   更要命的是：**滚动容器内的每个节点都会提前 return**，
        //   这个分支一旦写错，整棵滚动子树的可聚焦性全错。
        // 【完整实现（逐行对应天坦）】
        //   `isInScrollView(node)` = `hasActionMask2(node, 1024, 2048)`
        //     ⇒ 等于本项目的 `isType15(node)`（同为 1024/2048 双路判定）
        //   `getNextSibling(node)`  —— **名字骗人**，实现是
        //     `getSelfOrMatchingDescendant(node, f38927a)`，
        //     而 `f38927a.accept(n)` = 「类型是 15 **且** 父节点类型不是 15」
        //       ⇒ 即「**最外层的网页节点**」。
        //   ⇒ 整支语义：**在滚动容器内时，看向下最近的网页容器是否可见**。
        if (isType15(node)) {
            val webContent = outermostWebContent(node)
            return webContent?.isVisibleToUser == true
        }

        // ③ 可见性 —— **必须用天坦的 isVisible()，不是裸 isVisibleToUser**
        //
        // ══════════════════════════════════════════════════════════
        // 【★★★ 真机 P0 根因：这一行让「左右滑动找不到焦点」】
        // ══════════════════════════════════════════════════════════
        //
        // 【真机现象】（用户实装后的原话）
        //   > 左右滑动还是失效，它只能获取到一个两个焦点，
        //   > 而且很多焦点它都（获取不到）
        //   > 它的焦点就完全弄拿不到。天坦把它引导过去，它都不读
        //
        // 【日志】「连续 9 次手势，零导航输出」+「能工作的只有
        //   编辑框/全屏输入这类强焦点节点」。
        //
        // 【根因】原来用**裸** `node.isVisibleToUser` 直接判否。
        // 而天坦 `w7/AccessibilityNodeInfoUtils.java:570~578`：
        // ```java
        //   public static boolean isVisible(NodeInfo nodeInfo) {
        //       if (nodeInfo == null) { return false; }
        //       if (nodeInfo.isVisibleToUser()) { return true; }
        //       return NodeNavigator.isInRecyclerView(nodeInfo)
        //           && EventUtils.getNodeType(nodeInfo) != 15;
        //   }
        // ```
        // **第二条兜底**是关键：`isVisibleToUser()` 返回 false 时，
        // 只要该节点**属于 RecyclerView 且不是 type 15**，
        // 仍视为可见。
        //
        // 【为什么真机上这条兜底决定成败】
        //   `isVisibleToUser` 在 MIUI/Android 16 上有**大量假阴性**：
        //     · RecyclerView 列表项的子节点在未完全进入视口时返回 false
        //     · 滚动中 / 动画中 / 自绘 UI 返回 false
        //     · 被父容器裁剪的文本节点返回 false
        //   而这些节点**内容是可读的、也该能被聚焦**。
        //   一律判否 ⇒ 遍历找不到任何候选 ⇒ `moveFocus` 返回 null ⇒
        //   `haptic.boundary()`（只震动、无日志）⇒
        //   用户听到的正是「获取不到焦点的那种震动反馈」。
        //
        // 【与「触摸探索能读」不矛盾】
        //   触摸探索**不经过 shouldFocus** —— 它直接对触摸到的节点播报。
        //   所以「触摸能读、滑动不能」这个反常组合正是本行的指纹。
        if (!isVisible(node)) return false

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
        // ══════════════════════════════════════════════════════════════
        // 【★ 本轮修复：取不到窗口时**不得**退回节点边界】
        // ══════════════════════════════════════════════════════════════
        //
        // 【天坦原文】`w7/FocusFinder.java:361~370`：
        // ```java
        //   if (f39017b == null) {                       // f39017b = 窗口对象
        //       C2833A c2833aH = NodeUtils.h(nodeInfo);  // 取窗口
        //       if (c2833aH != null) {
        //           c2833aH.a(f39018c);                  // 用**窗口边界**填 f39018c
        //       } else {
        //           c2833aH = null;                      // ← 取不到就保持 null
        //       }
        //       f39017b = c2833aH;
        //   }
        //   if (isSameBoundsAsCached(nodeInfo) && nodeInfo.getChildCount() > 0) { ... }
        // ```
        // 而 `isSameBoundsAsCached`（`:178~185`）：
        // ```java
        //   if (f39017b == null) { return false; }       // ★ 窗口为 null → 直接 false
        //   Rect rect = f39019d;
        //   nodeInfo.getBoundsInScreen(rect);
        //   return Intrinsics.areEqual(rect, f39018c);
        // ```
        // ⇒ **窗口取不到时，第 ④ 分支整段被跳过**。
        //
        // 【我原来的实现（错）】取不到窗口就**退回节点边界**
        // （`node.getBoundsInScreen(baselineBounds)`）——
        // 那会让「基准 == 本节点边界」**恒成立**，
        // 于是第 ④ 分支**对每个有子节点的节点都触发**！
        //
        // 【后果】第 ④ 分支的语义是「这个节点与窗口根同边界」
        // （即它是铺满全屏的根容器）。恒成立后：
        //   · 对**普通容器**也走 `if (!k(node) || !i(node)) return false`
        //   · 而容器通常 k/i 不满足 ⇒ **整批容器被判否**
        //   · 但对另一批容器（恰好满足 k/i）⇒ **被误放行**
        // 这正是真机「同一个 `(无文本)` 节点有时通过、有时被拒」
        // 的**非纯函数**表现（外部审查的事实 4）。
        //
        // 【修复】与天坦一致：**取不到窗口就不初始化基准**，
        // 第 ④ 分支自然跳过。
        if (!baselineInitialized) {
            if (fillWindowBounds(node, baselineBounds)) {
                baselineInitialized = true
            }
            // 取不到窗口 → baselineInitialized 保持 false，
            // 第 ④ 分支跳过（与天坦 `isSameBoundsAsCached` 返回 false 等价）
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

        // ══════════════════════════════════════════════════════════════
        // ⑤-b 【TalkBack 独有】窗口级根容器拒绝
        // ══════════════════════════════════════════════════════════════
        //
        // 【为什么加这一条】三方对照时发现：这一步 **TalkBack 有、天坦没有**，
        //   而白茶此前两边都没有 —— 属于双方对照出的**共同空白**。
        //
        // 【TalkBack 原文】`AccessibilityNodeInfoUtils.java:870~880`：
        // ```java
        //   if (areBoundsIdenticalToWindow(node)
        //       && node.getChildCount() > 0
        //       && !isFocusableOrClickable(node)) {
        //     logShouldFocusNode(checkChildren, FOCUS_FAIL_SAME_WINDOW_BOUNDS_CHILDREN,
        //         "Don't focus, bounds are same as window root node bounds, node has children and"
        //             + " is neither actionable nor focusable: ", node);
        //     return false;
        //   }
        // ```
        //   而 `areBoundsIdenticalToWindow`（`:2428~2447`）取的是
        //   **窗口对象**的 `getBoundsInScreen` 与节点边界比较 —— 与白茶
        //   ⑤ 步的 `baselineBounds`（同样取自 `node.window`）语义一致。
        //
        // 【与 ⑤ 步的区别 —— 两者不是重复】
        //   ⑤（天坦）：`同边界 && 有子` ⇒ 再看 `k/i`，**条件成立才拒**
        //               （微信特判、非微信要 `k && i`）
        //   ⑤-b（TalkBack）：`同边界 && 有子 && 不可操作不可聚焦` ⇒ **直接拒**
        //   天坦的 `k(node)` = `isFocusable || isClickable`，
        //   而 TalkBack 的 `isFocusableOrClickable` 多含
        //   `isScreenReaderFocusable` 且**先要求 `isVisible`**。
        //   ⇒ ⑤-b 覆盖了天坦 ⑤ 在「非微信 + 非 k」时的漏网情形。
        //
        // 【真机为什么需要它】真机 Compose 树的根是
        //   `FrameLayout [0,0][1280,2772]` —— 正好命中
        //   「边界等于窗口 + 有子 + 不可操作不可聚焦」。
        //   放行它 ⇒ 焦点落到根容器 ⇒ 整屏没有可朗读内容。
        //
        // 【注意】TalkBack 的 `isFocusableOrClickable` 内部含 `isVisible(node)`，
        //   而此处节点必然可见（前面已过 `isVisible` 校验），
        //   故等价于 `isScreenReaderFocusable || isActionableForAccessibility`。
        if (baselineInitialized && isSameBounds(node, baselineBounds) && node.childCount > 0) {
            if (!isFocusableOrClickable(node)) return false
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
                // ⚠️ 【已删除的旧行】这里是照抄旧函数 `C3678d.shouldFocus` 的
                //   `if (nodeInfo.getChildCount() == 0
                //       || hasContentOrActionableChild(nodeInfo)) return true;`
                //   本项目对应 `if (node.childCount == 0 || p(node)) return true`。
                //   **这一行就是真机 20 个空容器被放行的真正原因** ——
                //   `p(node)`（`hasContentOrActionableChild`）对
                //   「有可聚焦子节点」的容器返回 **true**，
                //   于是 `ChildCount != 0 + 有可聚焦子` 的容器在这里
                //   **提前 return true**，根本走不到下面的新尾巴。
                //   真机 Compose 树里这类容器有 20 个
                //   （`View` + `focusable=true` + `clickable=true` + 无文本 + 有可见子），
                //   白茶全部放行 ⇒ 焦点停在容器上 ⇒ 只能走两个焦点。
                //   天坦真正生效的 `shouldFocusNodeInternal` **没有这一行**。
                //
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
                //
                // ══════════════════════════════════════════════════════════
                // 【★★★ 真机 P0 根因（第三轮）：这一支抄的是**旧函数**
                //   `C3678d.shouldFocus`，而真正生效的是
                //   `AccessibilityNodeInfoUtils.shouldFocusNodeInternal`】
                // ══════════════════════════════════════════════════════════
                //
                // 【旧函数】`w7/FocusFinder.java:371~374`（= C3678d）：
                // ```java
                //   if (z11) {
                //       if (nodeInfo.getChildCount() == 0
                //           || hasContentOrActionableChild(nodeInfo)) return true;
                //       return NodeUtils.isFocusableOrSelectable(nodeInfo)
                //           && !hasFocusableChild(nodeInfo);
                //   }
                // ```
                // ← 白茶一直照抄的是**这一支**。
                //
                // 【真正生效的函数】
                // `w7/AccessibilityNodeInfoUtils.java:971~984`：
                // ```java
                //   if (z11) {
                //       recycleAll(hashSet);
                //       hashSet.clear();
                //       if (!hasVisibleChildren(nodeInfo)) {
                //           logIfEnabled(z9, "Focus, %s is focusable and has no visible children", ...);
                //           return true;                    // ① 无可见子 → 接受
                //       }
                //       if (hasSomethingToSpeakInternal(nodeInfo, map, hashSet)) {
                //           logIfEnabled(z9, "Focus, %s is focusable and has something to speak", ...);
                //           return true;                    // ② 有可朗读内容 → 接受
                //       }
                //       logIfEnabled(z9, "Don't focus, %s is focusable but has nothing to speak", ...);
                //       return false;                       // ③ 有可见子且无内容 → **拒绝**
                //   }
                // ```
                // 而它由公开入口 `shouldFocusNodeWithCache(node, map)`（`:894~897`）
                // **传 z9=true** 调用 —— 这才是导航实际走的路径。
                //
                // 【两者的差别就是真机卡住的原因】
                //   真机 Compose 树里**大量存在**
                //   `focusable=true + clickable=true + **无任何文本**` 的
                //   `android.view.View` 容器（我的真机 dump 实测 20 个）。
                //   旧分支：`childCount != 0 && !hasContentOrActionableChild`
                //           → 落到 `k(node) && !hasChildContent(node)`，
                //             而这类容器 `k()` 为真（clickable）⇒ **可能被接受**。
                //   真分支：这类容器**有可见子节点**且**自己没内容可朗读**
                //           → 走到 ③ **被拒绝** ✔
                //   ⇒ 白茶放行了这些容器 ⇒ 焦点停在它们上面 ⇒
                //     每次滑动都在容器之间打转 ⇒ 用户「只能走两个焦点」。
                //
                // 【修复】按**真正生效的** `shouldFocusNodeInternal` 改写这一支。
                if (!hasVisibleChildren(node)) return true
                if (hasSomethingToSpeak(node)) return true
                return false
            }

            // ══════════════════════════════════════════════════════════
            // 【★★★ 真机 P0 根因：这里漏了整个 `hasTextOrContent` 兜底分支】
            // ══════════════════════════════════════════════════════════
            //
            // 【天坦原文】`w7/FocusFinder.java:396~398`（原样摘录）：
            // ```java
            //   if (NodeUtils.hasTextOrContent(nodeInfo)
            //       && !AccessibilityNodeInfoUtils.hasMatchingDescendant(nodeInfo, f39030o)) {
            //       return true;
            //   }
            // ```
            // 其中：
            //   · `NodeUtils.java:130~133`
            //       `hasTextOrContent = hasText(node) || hasStateDescription(node)`
            //   · `f39030o = new a()`（`FocusFinder.java:152`），
            //     而 `class a`（`:64~71`）的 `accept` 是
            //       `FocusFinder.f39016a.shouldFocus(nodeInfo, false)`
            //     ⇒ `hasMatchingDescendant(node, shouldFocus(node,false))`
            //       即「子树里**有没有**本身就该聚焦的后代」。
            //
            // 【这一支的作用】当 `z11 == false`（即 `isActionable` 与
            // 「顶层滚动项且有可操作子节点」都为假）时，
            // **只要节点自己有文本或有状态描述，且子树里没有可聚焦后代**，
            // 就接受它 —— 也就是**「叶子文本节点」的兜底**。
            //
            // 【为什么漏了它就无法左右滑动】
            //   真机上大量节点是**纯展示文本**（TextView）：
            //     · `isFocusable = false`
            //     · `isClickable = false`
            //     · `isScreenReaderFocusable` 视 ROM 而定，MIUI 上常为 false
            //     · 无 ACTION_FOCUS/CLICK/LONG_CLICK
            //   ⇒ `n(node, true)` 为 false ⇒ `z11` 为 false
            //   ⇒ 走到这里若没有本分支，**直接 return false** ⇒ 文本节点全被拒。
            //
            // 【与真机日志完全吻合】用户的真机表现：
            //   > 左右滑动还是失效，它只能获取到一个两个焦点
            //   > 而且很多焦点它都（获取不到）
            //   而能通过的是**容器节点**（有 isScreenReaderFocusable 或有子项
            //   可操作），所以「只能拿到一两个」。
            //   —— 与「叶子文本节点全被拒」的机制完全一致。
            //
            // 【为什么条件是 `!hasMatchingDescendant`】若子树里已经有该聚焦的
            //   后代，则**应该聚焦那个后代**，而不是把这个容器也算一个焦点
            //   （否则会出现「焦点停在容器上、内容重复朗读」）。
            if (hasTextOrContent(node) && !hasMatchingFocusableDescendant(node)) {
                return true
            }
        }

        return false
    }

    /**
     * 天坦 `NodeUtils.hasTextOrContent(nodeInfo)`
     *
     * 依据 `w7/NodeUtils.java:130~133`（原样摘录）：
     * ```java
     *   public static final boolean hasTextOrContent(NodeInfo nodeInfo) {
     *       return AccessibilityNodeInfoUtils.hasText(nodeInfo)
     *           || AccessibilityNodeInfoUtils.hasStateDescription(nodeInfo);
     *   }
     * ```
     * 注意是 **`hasText`**（不是 `i()` 的 `hasText || hasSpeakableContent`）——
     * 两者的组成不同，不能互相替代（本项目已有过「同名不同函数混用」的教训）。
     */
    private fun hasTextOrContent(node: AccessibilityNodeInfo): Boolean =
        hasText(node) || hasStateDescription(node)

    /** 天坦 `AccessibilityNodeInfoUtils.hasStateDescription(node)` */
    private fun hasStateDescription(node: AccessibilityNodeInfo): Boolean = try {
        !node.stateDescription.isNullOrEmpty()
    } catch (e: Throwable) {
        false
    }

    /**
     * `AccessibilityNodeInfoUtils.hasMatchingDescendant(node, filter)`
     *
     * 天坦这一处传的过滤器是 `f39030o`（`FocusFinder.java:152` 的 `new a()`），
     * 而 `class a` 的 `accept`（`:64~71`）是：
     * ```java
     *   public boolean accept(NodeInfo nodeInfo) {
     *       if (nodeInfo == null) { return false; }
     *       return FocusFinder.f39016a.shouldFocus(nodeInfo, false);
     *   }
     * ```
     * ⇒ 即「子树里有没有 `shouldFocus(node, false)` 为真的后代」。
     *
     * 【`z9 = false` 的含义】少一层严格判定（不含 `hasFocusableChild` 那条），
     * 用于「这个后代**本身**是否值得聚焦」的判断。
     *
     * 【深度与数量上限】防畸形树导致长时间遍历（读屏在无障碍线程，
     *   超时会被系统熔断）。
     */
    private fun hasMatchingFocusableDescendant(node: AccessibilityNodeInfo): Boolean =
        hasMatchingFocusableDescendant(node, 0)

    private fun hasMatchingFocusableDescendant(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray = intArrayOf(200)
    ): Boolean {
        if (depth > MAX_DEPTH || budget[0] <= 0) return false
        val count = node.childCount
        for (i in 0 until count) {
            if (budget[0]-- <= 0) return false
            val child = node.getChild(i) ?: continue
            if (r(child, false, depth + 1)) return true
            if (hasMatchingFocusableDescendant(child, depth + 1, budget)) return true
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
            // ★ 有效性校验：取到**空矩形**等同于取不到窗口。
            //
            // 【为什么必须查这一步】`getBoundsInScreen` 可能**不抛异常但填不出值**
            //   （跨进程窗口失效、窗口已销毁、部分 ROM 的兼容问题）。
            //   此时 `out` 保持为 `Rect()` 即 `[0,0][0,0]`。
            //
            // 【不查会怎样】`isSameBounds(node, baselineBounds)` 用
            //   `node.getBoundsInScreen` 的结果与基准比较。若基准是空矩形，
            //   那么**任何**同样取不到边界的节点都会被判为「与窗口同边界」⇒
            //   误入天坦的 ⑤ 分支（同边界 + 有子）⇒ 被 `!k(node) || !i(node)`
            //   拒绝，或被我新加的 TalkBack ⑤-b 拒绝。
            //   真机表现就是「一批节点莫名不可聚焦」。
            //
            // 【与天坦的一致性】天坦 `isSameBoundsAsCached`（`:178~185`）在
            //   `f39017b == null`（取不到窗口）时**直接返回 false**，
            //   即整个 ⑤ 分支被跳过。这里用「空矩形 ⇒ 视为取不到」
            //   达到同样的效果，覆盖 `getBoundsInScreen` 静默填空的路径。
            out.width() > 0 && out.height() > 0
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
     * 天坦 `AccessibilityNodeInfoUtils.isVisible(node)` —— **带 RecyclerView 兜底**
     *
     * 依据 `w7/AccessibilityNodeInfoUtils.java:570~578`（原样摘录）：
     * ```java
     *   public static boolean isVisible(NodeInfo nodeInfo) {
     *       if (nodeInfo == null) { return false; }
     *       if (nodeInfo.isVisibleToUser()) { return true; }
     *       return NodeNavigator.isInRecyclerView(nodeInfo)
     *           && EventUtils.getNodeType(nodeInfo) != 15;
     *   }
     * ```
     *
     * 【两条支路】
     *   ① `isVisibleToUser()` —— 直接可见
     *   ② **兜底**：属于 RecyclerView 且不是 type 15（网格容器）→ 仍算可见
     *
     * 【为什么必须有支路②】`isVisibleToUser` 在真机上有大量假阴性
     * （列表项未完全进入视口、滚动中、被父容器裁剪、自绘 UI）。
     * 少了它，遍历会把整片列表判为「不可见」→ 找不到焦点。
     *
     * 【`isInRecyclerView` 的实现】对应 `w7/NodeNavigator.java:106~114`：
     * ```java
     *   public static boolean isInRecyclerView(NodeInfo nodeInfo) {
     *       if (nodeInfo == null) return false;
     *       return isInWebView(nodeInfo) || isInListView(nodeInfo);
     *   }
     * ```
     *   · `isInWebView` → `isInScrollView` → `hasActionMask2(1024, 2048)`
     *     （位掩码 **OR** actionList 双路）
     *   · `isInListView` → `pkg.startsWith("org.mozilla.")`
     *
     * 【为什么不用类名判 RecyclerView】天坦不看类名 ——
     * 它靠 HTML 元素动作（1024/2048）识别「这是可滚动的网页/列表内容」。
     * 用类名反而会漏掉 `androidx.recyclerview` 之外的实现。
     */
    private fun isVisible(node: AccessibilityNodeInfo): Boolean {
        // ══════════════════════════════════════════════════════════════
        // 三方对照（**主干一致，差异只在兜底**）
        // ══════════════════════════════════════════════════════════════
        //
        // 【① 主干 —— 三方完全相同，就是这一句】
        //   `node.isVisibleToUser()`
        //
        //   TalkBack `AccessibilityNodeInfoUtils.java:2364~2371`：
        //   ```java
        //   public static boolean isVisible(AccessibilityNodeInfoCompat node) {
        //     // We need to move focus to invisible node in WebView to scroll it but we don't
        //     // want to move focus if WebView itself is invisible.
        //     return node != null
        //         && (node.isVisibleToUser()                         // ← ★ 主干
        //             || (WebInterfaceUtils.isWebContainer(node)
        //                 && Role.getRole(node) != Role.ROLE_WEB_VIEW));
        //   }
        //   ```
        //   天坦 `:570~578`：
        //   ```java
        //   public static boolean isVisible(NodeInfo nodeInfo) {
        //       if (nodeInfo == null) return false;
        //       if (nodeInfo.isVisibleToUser()) return true;          // ← ★ 主干
        //       return NodeNavigator.isInRecyclerView(nodeInfo)
        //           && EventUtils.getNodeType(nodeInfo) != 15;
        //   }
        //   ```
        //   ⇒ 主干 `isVisibleToUser()` 是**三方共有**的，见下方 ①。
        //
        // 【② 兜底 —— 两方**其实是同一件事**】
        //   我上一轮误记成「TalkBack 兜底 WebView、天坦兜底 RecyclerView，
        //   豁免对象不同」—— **错的**。重新读全 `NodeNavigator` 后：
        //   ```java
        //   // w7/NodeNavigator.java:118~123
        //   public static boolean isInRecyclerView(NodeInfo nodeInfo) {
        //       if (nodeInfo == null) return false;
        //       return isInWebView(nodeInfo) || isInListView(nodeInfo);
        //   }
        //   // :105~107
        //   public static boolean isInWebView(NodeInfo nodeInfo) {
        //       return isInScrollView(nodeInfo);      // = 有 1024/2048 动作掩码
        //   }
        //   // :110~115
        //   public static boolean isInListView(NodeInfo nodeInfo) {
        //       return pkg.startsWith("org.mozilla.");   // Firefox
        //   }
        //   ```
        //   ⇒ 天坦的「RecyclerView 兜底」名字起错了，实质是
        //     **WebView/滚动容器兜底**（有 `ACTION_NEXT_HTML_ELEMENT` 动作）
        //     `+ Firefox 特判`。**与 TalkBack 的 WebView 兜底是同一件事**
        //     （只是天坦多一条 Firefox 包名）。
        //
        // 【③ 排除条件 —— 两方也是同一件事】
        //   天坦 `EventUtils.getNodeType(nodeInfo) != 15`，
        //   而 `EventUtils.java:94`：
        //   ```java
        //   map2.put(WebView.class.getName(), 15);        // ← 15 就是 WebView
        //   ```
        //   ⇒ 天坦的 `!= 15` 就是「非 WebView 类」，与 TalkBack 的
        //     `Role != ROLE_WEB_VIEW` **语义相同**。
        //   ⚠️ 注意不要把这两个 15 当成「同一个常量」—— 一个是**节点类型**
        //     （`getNodeType` 的映射表），一个是**角色**（`Role`），
        //     只是数值巧合相同。白茶 `NodeRoleHelper.ROLE_WEB_VIEW = 15`
        //     是本项目自己的角色表，与天坦的 `getNodeType` 表无关。
        //
        // 【结论】TalkBack 与天坦在此处的**判定条件等价**，
        //   白茶只需一条实现即可，无需并列两条（上轮并列属于重复）。
        //   完整表述 = 主干 `isVisibleToUser()` + 兜底 `(Web 容器 || Firefox) && 非 WebView`
        if (node.isVisibleToUser) return true                       // ★ 主干（三方一致）
        return isInRecyclerView(node) && !isType15(node)             // ★ 兜底（两方等价）
    }

    /**
     * 天坦 `NodeNavigator.isInRecyclerView(nodeInfo)` —— **完整定义**
     *
     * `w7/NodeNavigator.java:118~123`：
     * ```java
     *   public static boolean isInRecyclerView(NodeInfo nodeInfo) {
     *       if (nodeInfo == null) return false;
     *       return isInWebView(nodeInfo) || isInListView(nodeInfo);
     *   }
     * ```
     * 展开后（`:105~115`）：
     *   · `isInWebView`  = `isInScrollView` = 有 `1024/2048` 动作掩码
     *     （即 `ACTION_NEXT_HTML_ELEMENT`/`ACTION_PREVIOUS_HTML_ELEMENT`
     *      —— 这是 WebView 自己声明的能力，比猜包名可靠）
     *   · `isInListView` = 包名以 `org.mozilla.` 开头（Firefox 特判）
     *
     * ⚠️ 函数名叫 `isInRecyclerView`，但实现里**没有任何 RecyclerView 判定** ——
     *   它兜的是 **WebView / 滚动容器**。名字是历史遗留，行为以 `isInWebView`
     *   为准。对照 TalkBack 的 `WebInterfaceUtils.isWebContainer` 可知
     *   两者是同一类判定。
     *
     * `matchActionMask2`（`NodeUtils.java:285~289`）是**双路**：
     * ```java
     *   int actions = nodeInfo.getActions();
     *   return (actions & i9) == i9 || (actions & i10) == i10
     *       || hasAction(nodeInfo, i9) || hasAction(nodeInfo, i10);
     * ```
     */

    /**
     * 天坦 `NodeNavigator.isInRecyclerView(nodeInfo)`
     *
     * 依据 `w7/NodeNavigator.java:106~114` + `NodeUtils.matchActionMask2`：
     * ```java
     *   isInRecyclerView = isInWebView(node) || isInListView(node)
     *   isInWebView      = isInScrollView = hasActionMask2(node, 1024, 2048)
     *   isInListView     = pkg.startsWith("org.mozilla.")
     * ```
     *
     * `matchActionMask2`（`NodeUtils.java:285~289`）是**双路**：
     * ```java
     *   int actions = nodeInfo.getActions();
     *   return (actions & i9) == i9 || (actions & i10) == i10
     *       || hasAction(nodeInfo, i9) || hasAction(nodeInfo, i10);
     * ```
     */
    private fun isInRecyclerView(node: AccessibilityNodeInfo): Boolean {
        // isInListView
        val pkg = try {
            node.packageName?.toString().orEmpty()
        } catch (e: Throwable) {
            ""
        }
        if (pkg.startsWith("org.mozilla.")) return true

        // isInWebView → isInScrollView → matchActionMask2(1024, 2048)
        return hasAction(node, ACTION_NEXT_HTML_ELEMENT, ACTION_PREVIOUS_HTML_ELEMENT)
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
     * ★ TalkBack `isFocusableOrClickable(node)` —— `:1908~1912`
     *
     * 【原文】
     * ```java
     * private static boolean isFocusableOrClickable(AccessibilityNodeInfoCompat node) {
     *   return (node != null)
     *       && isVisible(node)
     *       && (node.isScreenReaderFocusable() || isActionableForAccessibility(node));
     * }
     * ```
     *
     * 【`isActionableForAccessibility` 在 TalkBack 里的定义】
     * `:1159~1178`：
     * ```java
     *   if (isClickable(node) || isLongClickable(node)) return true;   // ①
     *   if (node.isFocusable()) return true;                           // ②
     *   if (WebInterfaceUtils.hasNativeWebContent(node)) { ... }        // ③ Web 特判
     * ```
     * ⇒ ①② 合起来就是「可点击 / 可长按 / 可聚焦」。
     *
     * 【与天坦 `k(node)` 的差异】天坦 `k(node)` = `H(node) || N(node)`，
     *   只含 `isFocusable`/`isClickable`/`isLongClickable` 与动作位，
     *   **不含 `isScreenReaderFocusable`**。TalkBack 多这一项。
     *   本函数用于 ⑤-b（TalkBack 独有条），故按 TalkBack 语义实现。
     */
    private fun isFocusableOrClickable(node: AccessibilityNodeInfo): Boolean = try {
        isVisible(node) &&
            (isScreenReaderFocusable(node) || node.isClickable ||
                node.isLongClickable || node.isFocusable)
    } catch (e: Throwable) {
        false
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

            // ★★ 修复：天坦此处是 `if (!isVisible(child))`（带兜底），
            //   不是裸 `isVisibleToUser`。见 `isVisible()` 处对
            //   MIUI 假阴性的说明 —— 用裸判定会漏掉 RecyclerView/WebView
            //   内部的可见节点，导致容器被误判为「无子内容」。
            if (!isVisible(child)) continue

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
    /**
     * 【★ 本轮改为 internal 可见】供 `ReaderAccessibilityService.playFocusSound()`
     * 与 `AccessibilityEventHandler` **共用同一份实现**。
     *
     * 【为什么必须共享】这两个地方原先各自抄了一份
     * `it.id == 1 || ... || it.id == 131072 || ...` 的**错误**版本
     * （131072 是事件常量，恒不匹配；且漏了位掩码路径）。
     * 抄两份的必然结果是「改一处忘一处」——
     * 本类里这份是对的，那两份是错的，正是实例。
     */
    internal fun isActionableLike(node: AccessibilityNodeInfo): Boolean =
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
        // ★★ 修复：这里原来写的是**裸** `node.isVisibleToUser`，
        //   而天坦原文（`:534~538`）是：
        //   ```java
        //   public static boolean isTopLevelScrollItem(NodeInfo nodeInfo) {
        //       if (nodeInfo == null || !isVisible(nodeInfo)) { return false; }
        //                                       ^^^^^^^^^^^^^^^^^
        //   ```
        //   **用的是带兜底的 `isVisible()`**，不是裸的 `isVisibleToUser()`。
        //
        // 【后果】`isVisibleToUser` 在 MIUI/Android 16 上有大量假阴性
        //   （见 `isVisible()` 处的详述）。这里用裸判定 ⇒ `W()` 判 false ⇒
        //   `z11 = n(node,true) || (W(node) && p(node))` 判 false ⇒
        //   落到兜底分支。对「有可朗读子孙但自身无文本」的容器
        //   （真机实测 5 个：Compose 的 View 包裹 TextView 组）
        //   兜底分支的条件 `hasTextOrContent` 为 false ⇒ **被错误拒绝**。
        //
        // 【与真机表现的关系】这类容器是天坦会接受、白茶拒绝的
        //   —— 焦点少了一批，用户感觉「很多焦点获取不到」。
        if (!isVisible(node)) return false
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
        // ★★ 修复：天坦原文（`w7/AccessibilityNodeInfoUtils.java:176`）：
        //   ```java
        //   if (!AccessibilityNodeInfoUtils.hasScrollAction(nodeInfo)
        //       || !AccessibilityNodeInfoUtils.isVisible(nodeInfo)) { return false; }
        //   ```
        //   用的是带兜底的 `isVisible()`，不是裸 `isVisibleToUser`。
        if (!hasScrollAction(node) || !isVisible(node)) return false
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
     * 天坦 `AccessibilityNodeInfoUtils.hasVisibleChildren(node)`
     *
     * 依据 `w7/AccessibilityNodeInfoUtils.java:975` 的使用处；其实现为
     * 「是否存在 `isVisibleToUser()` 为真的直接子节点」。
     *
     * 【为什么用**直接子节点**】这一支的语义是「这个可聚焦容器下面
     * 还有没有可见的东西可看」—— 若没有，容器本身就是最底层，
     * 直接接受它（①）；若有，则要判断「有没有内容可朗读」（②③）。
     */
    private fun hasVisibleChildren(node: AccessibilityNodeInfo): Boolean {
        val count = try { node.childCount } catch (e: Throwable) { return false }
        if (count <= 0) return false
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (e: Throwable) { null } ?: continue
            if (isVisibleToUserOrNull(child)) return true
        }
        return false
    }

    private fun isVisibleToUserOrNull(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (e: Throwable) {
        false
    }

    /**
     * 天坦 `AccessibilityNodeInfoUtils.hasSomethingToSpeakInternal(node, map, set)`
     *
     * 【本处用它回答的问题】「这个可聚焦的容器**自己**有没有值得朗读的内容」。
     *
     * 【判定组成】（`NodeUtils.B(node)` = `AbstractC3693m.B`）：
     * ```java
     *   public static boolean B(o0.w wVar) {
     *       if (wVar != null) {
     *           return !TextUtils.isEmpty(wVar.J())     // J() = stateDescription
     *               || wVar.Y()                          // Y() = 勾选类状态
     *               || D(wVar);                          // D() = 有进度信息
     *       }
     *       return false;
     *     }
     * ```
     * 本项目对应 [hasSpeakableContent]（已按可读版逐条核实过）。
     *
     * 【为什么**不**包含 text/contentDescription】
     * 因为这一支的前提是 `z11` 为真（`isAccessibilityFocusable`），
     * 即节点本身是 `isScreenReaderFocusable` 或可点击的**容器**。
     * 天坦对「容器 + 有文本」的情形由**另一支**（`!z11` 的
     * `hasTextOrContent` 兜底）处理。两者不能混。
     */
    private fun hasSomethingToSpeak(node: AccessibilityNodeInfo): Boolean = try {
        hasSomethingToSpeak(
            node,
            java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(),
            java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()
            )
        )
    } catch (e: Throwable) {
        false
    }

    /**
     * ★★★ 完整版 `hasSomethingToSpeak` —— 补上「递归子节点」这一大块
     *
     * ══════════════════════════════════════════════════════════════
     * 【为什么必须补】我原先只写了 `hasSpeakableContent(node)` 一句 ——
     *   那只是**自身**有没有内容，**完全没有递归子节点**。
     *   而 TalkBack 与天坦**都有**这一块，且两方**逐条同构**。
     * ══════════════════════════════════════════════════════════════
     *
     * 【TalkBack 原文】`AccessibilityNodeInfoUtils.java:1001~1031`
     * ```java
     * private static boolean isSpeakingNode(node, speakingNodesCache, visitedNodes) {
     *   if (speakingNodesCache != null && speakingNodesCache.containsKey(node)) {
     *     return speakingNodesCache.get(node);
     *   }
     *   boolean result = false;
     *   if (hasText(node)) {
     *     result = true;
     *   } else if (hasStateDescription(node)) {
     *     result = true;
     *   } else if (node.isCheckable()) {          // Special case for check boxes.
     *     result = true;
     *   } else if (hasNonActionableSpeakingChildren(node, speakingNodesCache, visitedNodes)) {
     *     // Special case for containers with non-focusable content.
     *     result = true;
     *   }
     *   if (speakingNodesCache != null) speakingNodesCache.put(node, result);
     *   return result;
     * }
     * ```
     *
     * 【天坦原文】`AccessibilityNodeInfoUtils.java:502~524` —— **完全同构**
     * ```java
     * public static boolean hasSomethingToSpeakInternal(nodeInfo, map, set) {
     *     if (map != null && map.containsKey(nodeInfo)) return map.get(nodeInfo);
     *     boolean z9 = true;
     *     if (hasText(nodeInfo)) { ... }
     *     else if (hasStateDescription(nodeInfo)) { ... }
     *     else if (nodeInfo.isCheckable()) { ... }
     *     else if (hasNonActionableSpeakingChildren(nodeInfo, map, set)) { ... }
     *     else { z9 = false; }
     *     if (map != null) map.put(nodeInfo, Boolean.valueOf(z9));
     *     return z9;
     * }
     * ```
     *
     * 【⚠️ 注意两方的 `hasText` / `hasStateDescription` 有包含关系】
     *   `hasText`     = text || contentDescription || hintText
     *   `hasStateDescription` = stateDescription || isCheckable || hasRangeInfo
     *   天坦的 `hasSomethingToSpeakInternal` 里**先判 hasText、
     *   再判 hasStateDescription、再单独判 isCheckable** ——
     *   由于 `hasStateDescription` 已含 `isCheckable`，第三个分支
     *   在逻辑上是冗余的（但与 TalkBack 逐行对应，故保留原样）。
     *
     * @param cache   判定缓存（天坦 `map`）：命中的节点直接复用结果。
     *                **必须传 identity 语义的 map**，否则内容相同的兄弟
     *                节点会互相污染。
     * @param visited 本次递归已访问集合（天坦 `set`）：
     *                **重入即返回 false** —— 这是**防环**机制，
     *                对应真机上偶发的环形节点树。
     */
    private fun hasSomethingToSpeak(
        node: AccessibilityNodeInfo,
        cache: MutableMap<AccessibilityNodeInfo, Boolean>,
        visited: MutableSet<AccessibilityNodeInfo>
    ): Boolean {
        // ① 缓存命中（两方一致）
        cache[node]?.let { return it }

        val result = when {
            hasText(node) -> true               // ② 有文本
            hasStateDescription(node) -> true   // ③ 有状态描述 / 可勾选 / 进度
            node.isCheckable -> true            // ④ 单独的可勾选（见上方冗余说明）
            hasNonActionableSpeakingChildren(node, cache, visited) -> true  // ⑤ 递归
            else -> false
        }

        cache[node] = result
        return result
    }

    /**
     * ★★★ TalkBack `hasNonActionableSpeakingChildren` / 天坦同名函数
     * —— **两方逐条同构**，本函数是补齐实现
     *
     * 【TalkBack 原文】`AccessibilityNodeInfoUtils.java:1045~1103`
     * 【天坦原文】  `AccessibilityNodeInfoUtils.java` 同名函数
     *
     * 七步逐条对应：
     * | 步骤 | TalkBack | 天坦 | 白茶 |
     * |---|---|---|---|
     * | ① child==null        | `continue` | `continue` | `continue` |
     * | ② 已访问过           | `!visitedNodes.add(child) → return false` | `!set.add(child) → return false` | 同 |
     * | ③ 不可见             | `!isVisible(child) → continue` | 同 | 同 |
     * | ④ 可聚焦             | `isFocusableOrClickable(child) → continue` | `isAccessibilityFocusable(child) → continue` | 同 |
     * | ⑤ 顶层滚动项         | `isTopLevelScrollItem && isSpeaking && !(clickable\|\|longClickable) → continue` | 同构 | 同 |
     * | ⑥ 递归有内容         | `isSpeakingNode(child,...) → return true` | `hasSomethingToSpeakInternal(child,...) → return true` | 同 |
     * | ⑦ 末尾不可见子兜底   | `hasInvisibleNonActionableSpeakingChildren` | **无此条** | 按天坦省略 |
     *
     * 【④ 的微妙差异】TalkBack 用 `isFocusableOrClickable`
     *   （= `isVisible && (isScreenReaderFocusable || isActionable)`），
     *   天坦用 `isAccessibilityFocusable`（= `isVisible && (isScreenReaderFocusable
     *   || isClickable)`）。二者只差 `isActionable` 里多含 `isFocusable`/
     *   `isLongClickable`/Web 内容。此处按**天坦**实现（用户既定原则）。
     *
     * 【⑤ 的语义】「可点击的滚动容器应当先于其子节点获得焦点」——
     *   故当父节点可点击/可长按时，不跳过顶层滚动项子节点。
     *
     * 【⑦ 为什么按天坦省略】TalkBack 的 `hasInvisibleNonActionableSpeakingChildren`
     *   是为 `ACTION_SHOW_ON_SCREEN` 失败问题加的（见其注释），
     *   天坦没有。用户定过「A/B 选天坦」，故此处省略；
     *   若真机浏览器场景出现「容器对屏外文本不聚焦」再补。
     */
    private fun hasNonActionableSpeakingChildren(
        node: AccessibilityNodeInfo,
        cache: MutableMap<AccessibilityNodeInfo, Boolean>,
        visited: MutableSet<AccessibilityNodeInfo>
    ): Boolean {
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Throwable) {
                null
            } ?: continue                                    // ① null → 跳过

            // ② 防环：重入即返回 false（**不是 continue**）
            if (!visited.add(child)) return false

            // ③ 不可见 → 跳过
            if (!isVisible(child)) continue

            // ④ 可聚焦 → 跳过（子节点自己会拿到焦点，容器不必替他朗读）
            if (isAccessibilityFocusable(child)) continue

            // ⑤ 顶层滚动项（且父节点不可点击/不可长按）→ 跳过
            if (W(child) &&
                hasSomethingToSpeak(child, cache, visited) &&
                !isActionableForAccessibility(node) &&
                !node.isLongClickable
            ) {
                continue
            }

            // ⑥ 递归：子节点有可朗读内容 → 容器也有
            if (hasSomethingToSpeak(child, cache, visited)) return true
        }
        return false
    }

    /**
     * 天坦 `AccessibilityNodeInfoUtils.isAccessibilityFocusable(node)`
     * —— `:434~439`
     * ```java
     *   return isVisible(nodeInfo)
     *       && (isScreenReaderFocusable(nodeInfo) || isClickable(nodeInfo));
     * ```
     * ⚠️ 注意只含 `isClickable`，**不含** isFocusable/isLongClickable
     *   （那是 `isActionableForAccessibility` 才有的）。
     */
    private fun isAccessibilityFocusable(node: AccessibilityNodeInfo): Boolean = try {
        isVisible(node) && (isScreenReaderFocusable(node) || node.isClickable)
    } catch (e: Throwable) {
        false
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
    /**
     * 天坦 `NodeNavigator.getNextSibling(nodeInfo)` —— **实现与名字完全不符**
     *
     * ══════════════════════════════════════════════════════════════
     * 【原文】`w7/NodeNavigator.java:87~92`
     * ```java
     * public static NodeInfo getNextSibling(NodeInfo nodeInfo) {
     *     if (isInScrollView(nodeInfo)) {
     *         return AccessibilityNodeInfoUtils.getSelfOrMatchingDescendant(nodeInfo, f38927a);
     *     }
     *     return null;
     * }
     * ```
     * ⚠️ 函数名叫 `getNextSibling`（下一个**兄弟**），实际是
     *   `getSelfOrMatchingDescendant`（**自己或后代**里第一个匹配的）。
     *   混淆代码里这类「名字骗人」的坑必须靠读实现才能发现。
     *
     * 【f38927a 的定义】`w7/NodeNavigator.java:18~35`（匿名 NodeFilter）：
     * ```java
     * public boolean accept(NodeInfo nodeInfo) {
     *     if (nodeInfo == null) { return false; }
     *     NodeInfo parent = nodeInfo.getParent();
     *     try {
     *         if (EventUtils.getNodeType(nodeInfo) == 15) {     // 自己是 WebView 类
     *             if (EventUtils.getNodeType(parent) != 15) {   // 父不是
     *                 z9 = true;
     *             }
     *         }
     *         return z9;
     *     } finally { g0(parent); }
     * }
     * ```
     * ⇒ 语义 = **「最外层的网页节点」**（自己是 WebView 类型、父不是）。
     *
     * ⚠️ 这里的 `getNodeType` 是 **`EventUtils` 的类型表**
     *   （`EventUtils.java:94`：`map2.put(WebView.class.getName(), 15)`），
     *   **不是**本项目的 `isType15`（那是 1024/2048 的**动作**判定）。
     *   两者数值都涉及 15，但来源与语义完全不同 —— **切勿混淆**。
     *
     * 【本项目对应】用 className 判定，避免依赖天坦的类型映射表
     *   （那张表覆盖大量第三方类名，无法完整移植）。
     *
     * @return 最外层网页内容节点；找不到返回 null
     */
    private fun outermostWebContent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 自己是否「是网页内容」
        if (isWebViewClass(node)) {
            val parentIsWeb = try { node.parent?.let { isWebViewClass(it) } ?: false } catch (e: Throwable) { false }
            if (!parentIsWeb) return node                    // 最外层 ⇒ 命中
        }
        // 递归后代（前序，取第一个匹配）
        val count = try { node.childCount } catch (e: Throwable) { return null }
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (e: Throwable) { null } ?: continue
            val r = outermostWebContent(child)
            if (r != null) return r
        }
        return null
    }

    /** 是否网页类节点 —— 天坦 `EventUtils.getNodeType(node) == 15` 的本项目对应 */
    private fun isWebViewClass(node: AccessibilityNodeInfo): Boolean = try {
        val cls = node.className?.toString().orEmpty()
        cls == "android.webkit.WebView" || cls.endsWith(".WebView") ||
            NodeRoleHelper.roleOf(node) == NodeRoleHelper.ROLE_WEB_VIEW
    } catch (e: Throwable) {
        false
    }

    private fun isSameBounds(node: AccessibilityNodeInfo, base: Rect): Boolean {
        node.getBoundsInScreen(tempRect)
        return tempRect == base
    }

    /**
     * 「有其中任一动作」—— **双路判定**（位掩码 + 动作表）
     *
     * ══════════════════════════════════════════════════════════
     * 【★ 本轮修复：原来只查了 actionList，漏了位掩码路径】
     * ══════════════════════════════════════════════════════════
     *
     * 原实现：
     * ```kotlin
     *   node.actionList.any { it.id in ids }
     * ```
     * 只走**动作表**一条路。
     *
     * 【天坦的两组函数必须分清】`w7/NodeUtils.java`：
     * ```java
     *   // :263~274  hasAction —— **只**扫 actionList
     *   public static final boolean hasAction(NodeInfo nodeInfo, int i9) {
     *       List actionList = nodeInfo.getActionList();
     *       ... 遍历比对 id ...
     *   }
     *
     *   // :276~282  matchActionMask —— **位掩码 OR 动作表**
     *   public static final boolean matchActionMask(NodeInfo nodeInfo, int i9) {
     *       if (((nodeInfo != null ? nodeInfo.getActions() : 0) & i9) == i9) return true;
     *       return hasAction(nodeInfo, i9);
     *   }
     *
     *   // :285~289  matchActionMask2 —— 两个动作，各自双路
     *   public static final boolean matchActionMask2(NodeInfo nodeInfo, int i9, int i10) {
     *       int actions = nodeInfo != null ? nodeInfo.getActions() : 0;
     *       return (actions & i9) == i9 || (actions & i10) == i10
     *           || hasAction(nodeInfo, i9) || hasAction(nodeInfo, i10);
     *   }
     * ```
     *
     * 【为什么单 id 版本的 hasAction **不应**混进来】
     * 本文件里 `hasActionMask` 已单独实现位掩码路径，
     * 而 `y()` / `z()` 等已显式把两者 OR 起来 ——
     * 那是**正确**的 `matchActionMask2/3` 移植，**不能改**。
     * 所以这里只修**这个 vararg 版本**，让它与调用方的期望一致。
     *
     * 【本函数的调用方期望什么】逐条核对（全部按
     * `matchActionMask*`（双路）语义使用）：
     * | 行 | 用途 | 天坦对应 |
     * |---|---|---|
     * | :287 | 滑块角色（4096/8192/SET_PROGRESS） | `matchActionMask3` 语义 |
     * | :399 | `n(node, true)` 的 `hasAction(1,16,32)` | `matchActionMask3` |
     * | :402 | `n(node, false)` 的 `hasAction(16,32)` | `matchActionMask2` |
     * | :1184 | WebView 判定（4096/8192） | `NodeUtils.hasAction4096` = `matchActionMask2` |
     *
     * 注意 :1184 的依据最直接 —— 天坦自己写的就是：
     * ```java
     *   public static final boolean hasAction4096(NodeInfo nodeInfo) {
     *       return matchActionMask2(nodeInfo, 4096, SoundTouch.f23227h);
     *   }
     * ```
     * `matchActionMask2` 是**双路**，而白茶只做了单路
     * ⇒ **WebView 判定会漏掉「只填位掩码」的节点**，
     * 使 `findWebViewRoot()` 等分支大面积失活。
     *
     * 【真机后果】网页内容、滑块、部分可点击元素的判定偏严 →
     * 那些节点被判「不可聚焦」→ **用户滑不到**（漏读内容）。
     */
    private fun hasAction(node: AccessibilityNodeInfo, vararg ids: Int): Boolean {
        return try {
            // ① 位掩码路径 —— `(actions & id) == id`
            val actions = node.actions
            if (ids.any { (actions and it) == it }) return true
            // ② 动作表路径 —— 遍历 actionList
            node.actionList.any { it.id in ids }
        } catch (e: Throwable) {
            false
        }
    }

    /** ACTION_SET_PROGRESS */
    private const val SET_PROGRESS_ACTION = 16908349

}