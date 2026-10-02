# 白茶读屏 —— 全项目 Bug 排查报告

> 项目：`/home/baicha-reader`（`com.baicha.reader`）
> 排查日期：2026-10-02
> 范围：`app/src/main/java/` 全部 74 个 Kotlin 文件（core 71 个 + ui 3 个），共 24171 行
> 性质：**只读审计，未修改任何代码**
> 方法：全部结论以「文件:行号 + 原文」为依据；对每个候选问题做跨文件调用点核实；对 3 个高危断言做了独立复算/模拟验证

---

## 关于本次审计的环境说明

1. **审计期间 `NodeRoleHelper.kt` 被外部进程并发修改**（mtime `2026-10-02 19:00:33`，`git status` 显示 ` M`，行数 335 → 452）。本报告中标注了 `NodeRoleHelper` 的结论均以**实际读到的版本**为准，并在该条目内注明。另有一个新文件 `app/src/test/java/com/baicha/reader/core/NodeRoleHelperFidelityTest.kt` 于 19:01 出现，同样非本次审计产生。
2. **本报告未修改任何代码**（含未新增/未删除代码文件、未执行 git 操作）。
3. 报告中区分 **确定 / 很可能 / 存疑** 三档，存疑项均如实标注，不凑数。

---

## A. 问题清单

按严重度排序。严重度定义：
- **P0**：会让服务崩溃 / 核心功能完全不可用
- **P1**：功能部分失效 / 用户能明显感知 / 资源泄漏
- **P2**：潜在风险 / 边界情况 / 轻微性能问题

| # | 文件:行号 | 严重度 | 类型 | 一句话描述 |
|---|---|---|---|---|
| 1 | `FocusManager.kt:200,209,347-353` + `ReaderAccessibilityService.kt:999-1003` | **P0** | 状态粘连/逻辑错误 | `scrollJumpToTop` 设置后永不复位，`scrollJump` 又在**每次** `moveFocus` 开头无条件触发 —— 一旦切到「跳到顶部/底部」导航，之后**所有**导航（左右滑动）都变成「跳转定位」并 `return null`，焦点彻底不动 |
| 2 | `ShouldFocusNode.kt:166,168,278` | **P0** | 死代码/分支不可达 | `r()` 的两个调用点 `z9` 恒为 `false`，`if (!z9) return z11` 必定返回 → **281–309 行整段不可达**，含刚"修复"的 `k(node) && !hasChildContent(node)`；`MAX_DEPTH` 也因 `depth` 恒 0 永不触发 |
| 3 | `ShouldFocusNode.kt:669,628` | **P0** | 无界集合/状态污染 | `seenChildren`（单例 `IdentityHashMap`）**从不清空**，跨调用永久累积；命中即 `return false`，导致 `m()` 随运行时长单调恶化，容器被误判为不可聚焦 |
| 4 | `TatansTraversal.kt:602` | **P1** | 抄写错误/状态回退 | `Companion.cacheGeneration = generation` 把**实例快照**写回静态字段 —— 缓存代数被"拽回"旧值，`invalidateIndexCache()` 的失效语义被破坏，双向扩展搜索用错误的中心索引 → 焦点跳错位置 |
| 5 | `GestureActionHandler.kt:64,128` | **P1** | 静默失效 | `performGlobalAction` 声明后**从未被赋值**（全项目仅 2 处出现：声明+读取）→ 返回/主页/多任务/通知栏/截屏/分屏 **6 个系统手势全部静默失败**，用户按了没反应且无任何提示 |
| 6 | `NodeCache.kt` 全文 + `AutoScrollManager.kt:305` | **P1** | 线程安全 | `NodeCache` 用 `IdentityHashMap` 且**无任何同步**，代码注释自称"只在主线程访问"；但 `AutoScrollManager.kt:305` 的 filter 经无障碍线程调用 `shouldFocus()` → 前提被证伪，存在 Map 并发写损坏风险 |
| 7 | `AutoScrollManager.kt:344,348,82,85,86` | **P1** | 线程安全 | 滚动状态字段（`lastSnapshot`/`lastDetectedDirection`/`lastScrollDirection`/`scrollDeltaX/Y`）在**无障碍线程**写、主线程读/改，全部**无 `@Volatile`、无锁** |
| 8 | `ShouldFocusNode.kt:63,64,93,96,858` | **P1** | 线程安全 | 单例 `object` 的 `baselineBounds`/`baselineInitialized`/`tempRect`/`tempRect2`/`pCache` 全非线程安全，却在「主线程导航 + 无障碍线程滚动」下并发访问 |
| 9 | `ReaderAccessibilityService.kt:563` | **P1** | 资源泄漏 | `NodePathCollector`（进程级 `object`）的 `onCollected` 闭包捕获 `this`(Service) 与 `eventHandler`，`onDestroy()` **从未置 null / reset** → 静态引用泄漏整个服务 |
| 10 | `ReaderAccessibilityService.kt:1151-1175` | **P1** | 资源泄漏 | `onDestroy()` 遗漏 `webNavigator.clear()`、`textMonitor` 反注册、`focus.monitor` 清理（`registerCacheHandlers` 注册的闭包也仍持有旧 Service） |
| 11 | `ShouldFocusNode.kt:138-140` + `FocusManager.kt:320,813` | **P1** | 缓存过期 | `resetOperationScope()` 每轮导航都调，但**只重置基准、不清 `pCache`**；`pCache` 完全在 `CacheRegistry` 失效体系之外 → 滚动后持久读到过期判定 |
| 12 | `EventQueue.kt:187-190` | **P1** | 竞态 | `drainScheduled=false`（锁内）与 `if (size > 0) scheduleDrain()`（锁外）之间存在窗口，新事件可能永久滞留队列 → 「滑动后不播报，要再滑一次」 |
| 13 | `SpeechEngine.kt:344-350` | **P2** | 唤醒锁泄漏 | B 通道懒创建时 `notifySpeaking(true)` 已调用，但函数在 `createEngine` 后 `return` → `trySpeakNext` 未跑，若该 item 永不完成则 `false` 通知缺失，引用计数 +1 不回落 |
| 14 | `NodeOrderComparator.kt:262,323,330` | **P2** | 抄写残留 | `i13` 声明后赋值但**从未被读取**（两处 `i13 = 0` 亦然），是移植时抄漏的靶点；虽当前行为不受影响，但掩盖了原始逻辑 |
| 15 | `NodeOrderComparator.kt:452-478` | **P2** | 逻辑死代码 | `sortRecycler` 的"内容高度修正"用 `getBoundsInScreen(contentRect)`，与 `:454` 取 `rect` 是**同一 API** → `contentRect.height() != rect.height()` 恒 false，整个修正分支是死代码 |
| 16 | `NodePathDescription.kt:477` | **P2** | 语义错误 | `skippedCount++` 在 `request()` 入口无条件自增（注释称"被防抖丢弃的次数"，实际统计的是"请求次数"），诊断数据失真 |
| 17 | `NodeRoleHelper.kt`（452 行版）:215-225 | **P2** | 分支优先级 | Pager 分支（按 ACTION_PAGE_*）排在 `collectionInfo` 分支**之前**，会把带翻页 action 的网格提前判成 `ROLE_PAGER` → `W()`/`isScrollableContainerNode()` 的 `ROLE_GRID` 判定失配 |
| 18 | `ShouldFocusNode.kt:1101-1127` vs `:1161-1170` | **P2** | 判据不一致 | `isType15()` 入口用 **action** 判定，`findWebViewRoot()` 出口用 **className 精确匹配** → WebView 根大概率找不到，恒返回 `node` 自身 |
| 19 | `NodeCache.kt:201-207` | **P2** | 缓存效率 | `invalidateAll()` 只 `generation++` 不物理清 Map → 死条目持续挤占容量，触发 `trimMap` 随机清掉一半**热**数据 |
| 20 | `EventQueue.kt:169-192` | **P2** | 主线程占用 | `drainRunnable` 单次最多 16 条（注释按"每条 2ms"估算），但每条 `consumer` 可能做数百次 IPC（遍历树），最坏情形主线程被占满一帧以上 |

> 统计：**P0 = 3 个、P1 = 9 个、P2 = 8 个**，共 20 项。

---

## B. 问题详情

### 【1】P0 — `scrollJumpToTop` 粘滞 + `scrollJump` 无条件触发，导致焦点彻底不动

**文件**：`core/FocusManager.kt:198-211`、`:344-356`；`core/ReaderAccessibilityService.kt:994-1003`、`:574-585`

**代码原文**

`FocusManager.kt:198-211`：
```kotlin
    /** 本次导航是否为「跳到顶部」 */
    @Volatile
    private var scrollJumpToTop = true

    /**
     * 设置滚动定位方向 —— 由服务在调用 moveFocus 前设置。
     * ...
     */
    fun setScrollJumpDirection(top: Boolean) {
        scrollJumpToTop = top
    }
```

`FocusManager.kt:344-356`（`moveFocusInternal` 的**最开头**）：
```kotlin
        val jump = scrollJump
        if (jump != null) {
            val handled = jump.invoke(scrollJumpToTop)
            if (handled) {
                ReaderLog.d(TAG, if (scrollJumpToTop) "已跳到顶部" else "已跳到底部")
                return null   // 位置已改变，焦点由滚动事件回带，本次不设焦点
            }
            // 容器不支持 → 交给下面的普通遍历（不静默）
            ReaderLog.d(TAG, "容器不支持滚动定位 → 回退遍历")
        }
```

`ReaderAccessibilityService.kt:999-1003`（只在导航**匹配**时设置方向）：
```kotlin
        when (navigation.current) {
            NavigationMode.SCROLL_TOP -> focus.setScrollJumpDirection(top = true)
            NavigationMode.SCROLL_BOTTOM -> focus.setScrollJumpDirection(top = false)
            else -> {}                        // ★ 切走时不做任何复位
        }
```

`ReaderAccessibilityService.kt:574-585`（`scrollJump` 钩子被注入，永不撤销）：
```kotlin
        focus.scrollJump = { top ->
            val current = focus.getFocused() ?: rootInActiveWindow
            val ok = if (top) ScrollToPosition.scrollToTop(current)
            else ScrollToPosition.scrollToBottom(current)
            if (ok) {
                focus.onScrollStart(if (top) FocusManager.DIR_PREVIOUS else FocusManager.DIR_NEXT)
                autoScroll.onScrollSettled?.invoke()
            }
            ok
        }
```

**为什么是 bug**

三个事实叠加，构成一条完整的「焦点死锁」链路：

1. `scrollJump` 钩子由服务在 `onServiceConnected` 注入一次，**永不撤销** —— 全项目搜索 `focus.scrollJump = null` 命中 **0 次**。
2. `scrollJumpToTop` 是**粘滞字段**：`setScrollJumpDirection()` 只在导航为 `SCROLL_TOP`/`SCROLL_BOTTOM` 时被调用（`:1000-1001`），切回其他导航走 `else -> {}`，**不会有任何复位**，值永久保留。
3. `moveFocusInternal` 在**最开头**（早于滚动拦截、防抖、粒度、遍历）就无条件 `jump.invoke(scrollJumpToTop)`，且 `handled == true` 时**直接 `return null`**（`:352`），后面的遍历、跨窗口兜底全部被跳过。

**真机后果**：用户用手势切到「跳到顶部」→ 此后**每一次左右滑动**都变成「让列表跳回顶部」并返回 `null`。若当前焦点在支持 `ACTION_SCROLL_TO_POSITION` 的列表里（绝大多数 App 的列表/RecyclerView 都支持），`handled` 为 `true`，**焦点永远不动**。用户感受正是「左右滑动找不到焦点 / 一到边界就震动」——而且**重启服务也无法恢复**，因为 `NavigationStore` 会持久化导航模式（`:378` `NavigationStore.save`）。

`NavigationMode.SCROLL_TOP`(`:141`) 与 `SCROLL_BOTTOM`(`:142`) 的 `implemented` 默认 `true`，因此二者**在 `switchable` 列表里**（`NavigationMode.kt:171` `values().filter { it.implemented }`），用户用「先下再上滑」循环切换导航时**一定会经过它们**。

**验证**：我按上述控制流写了模拟脚本复算 —— 16 个导航模式循环切换中，**18 次 `moveFocus` 调用全部执行了 `scrollJump` 并返回 null**，其中仅 2 次（真正的 SCROLL_TOP/SCROLL_BOTTOM）是预期行为，**其余 16 次本应正常遍历**。

**修复建议**

方案 A（最小改动，推荐）—— 用「一次性消费」的待处理标志替代粘滞布尔：

```kotlin
// FocusManager.kt —— 把「是否处于滚动定位」显式建模，替代粘滞的布尔
private var scrollJumpPending = false          // ★ 新增：只在被显式要求时置位

fun setScrollJumpDirection(top: Boolean) {
    scrollJumpToTop = top
    scrollJumpPending = true                   // ★
}

/** 由服务在切走 SCROLL_TOP/SCROLL_BOTTOM 时调用 */
fun clearScrollJump() {
    scrollJumpPending = false
}
```

```kotlin
// FocusManager.kt:344 起 —— 只在「本次导航是滚动定位」时才执行
if (scrollJumpPending) {
    scrollJumpPending = false                  // ★ 一次性消费，绝不粘滞
    val jump = scrollJump
    if (jump != null) {
        val handled = jump.invoke(scrollJumpToTop)
        if (handled) {
            ReaderLog.d(TAG, if (scrollJumpToTop) "已跳到顶部" else "已跳到底部")
            return null
        }
        ReaderLog.d(TAG, "容器不支持滚动定位 → 回退遍历")
    }
}
```

配套（服务侧 `:999-1003`）：
```kotlin
        when (navigation.current) {
            NavigationMode.SCROLL_TOP -> focus.setScrollJumpDirection(top = true)
            NavigationMode.SCROLL_BOTTOM -> focus.setScrollJumpDirection(top = false)
            else -> focus.clearScrollJump()    // ★ 切走时复位
        }
```

方案 B（更彻底，语义最清晰）—— 把方向作为参数传入，彻底消除跨调用状态：

```kotlin
fun moveFocus(
    direction: Int,
    strategyType: Int = TraversalStrategyFactory.TYPE_DEFAULT,
    filter: ((AccessibilityNodeInfo) -> Boolean)? = null,
    scrollJumpTop: Boolean? = null        // ★ null = 非滚动定位导航
): AccessibilityNodeInfo? {
    return try {
        if (scrollJumpTop != null) {      // 只有滚动定位导航才尝试
            val handled = scrollJump?.invoke(scrollJumpTop) ?: false
            if (handled) return null
        }
        moveFocusInternal(direction, strategyType, filter)
    } finally {
        ShouldFocusNode.resetOperationScope()
        PerfCounters.endNav(perfStart)
    }
}
```

**置信度：确定**（三条事实均已用 grep 核实：钩子无撤销点、方向设置仅 2 处、触发点无导航判定；控制流已模拟复算）

---

### 【2】P0 — `z9` 恒为 `false`，`ShouldFocusNode.r()` 的 281–309 行整段不可达

**文件**：`core/ShouldFocusNode.kt:162-169`、`:176-313`

**代码原文**

```kotlin
162:    fun shouldFocus(node: AccessibilityNodeInfo?): Boolean {
163:        if (node == null) return false
164:        val c = cache
165:        if (c != null) {
166:            return c.shouldFocus(node) { r(node, false, 0) }     // ★ z9 = false
167:        }
168:        return r(node, false, 0)                                  // ★ z9 = false
169:    }
```

```kotlin
176:    private fun r(node: AccessibilityNodeInfo, z9: Boolean, depth: Int): Boolean {
177:        if (depth > MAX_DEPTH) return false
...
276:            val z11 = n(node, true) || (W(node) && p(node))
277:
278:            if (!z9) return z11          // ★ z9 恒为 false ⇒ 必定在此 return
279:
280:            if (z11) {
281:                if (node.childCount == 0 || p(node)) return true
...
306:                return k(node) && !hasChildContent(node)
307:            }
308:
309:            if (i(node) && !isSystemBar(node)) return true
310:        }
311:
312:        return false
313:    }
```

**核实过程**（独立 grep，未依赖子代理结论）

对**整个 `app/src/main/java`** 树搜索 `ShouldFocusNode` 内 `r(` 的非注释调用，命中仅 2 处（`166`、`168`），**均在 `shouldFocus()` 内且硬编码传 `false`**：

```
166:            return c.shouldFocus(node) { r(node, false, 0) }
168:        return r(node, false, 0)
```

其余 `r(` 命中都在注释里（`:8/10/13/16/26/78/143/1037/1049/1057/1059` 行），或属于**其他文件的同名函数**（`TatansTraversal.r()` 是天坦遍历算法自己的"向下找"，与此无关）。

并且 `r()` **不再自递归**：原先唯一的递归路径 `hasChildContent() → r(child, false, 0)` 已在 `:1049-1063` 被改成 `n(child, true)`：

```kotlin
1049:                // ★★ P0 修复（本轮）：`n(child, true)` 而非 `r(child, false, 0)`
1057:                // 【为什么不是 r()】`r()` 是「完整的可聚焦判定」（会递归整棵子树），
1058:                //   而这里需要的是 `n()`（轻量的「有交互性」判定）。
1063:                if (n(child, true)) return true
```

**为什么是 bug**

`z9` 恒为 `false` ⇒ `:278 if (!z9) return z11` **必然执行**，因此：

- `:281` `if (node.childCount == 0 || p(node)) return true` —— **不可达**
- `:306` `return k(node) && !hasChildContent(node)` —— **不可达**（**这正是 commit `1702770`/`0993b75` 反复"修复"的那一处**）
- `:309` `if (i(node) && !isSystemBar(node)) return true` —— **不可达**

也就是说：**`r()` 的实际返回值只取决于 `:276` 的 `z11 = n(node, true) || (W(node) && p(node))`**。任何「`n()` 判否、但 `i(node)`（有文本/状态）为真」的节点 —— 例如**纯文本节点、只暴露 `stateDescription` 的节点、非系统栏的文本项** —— 都会被判为**不可聚焦**。

这正是用户描述的「左右滑动找不到焦点」的**第二条独立成因**（与问题 1 并存）。同时也说明：**当前对 `:282-306` 的"修复"对真机行为零影响** —— 必须先纠正这一点，否则针对该分支的后续调试全部是无效劳动，会持续误导排查方向。

`MAX_DEPTH`（`:61` = 12）同理失效：`depth` 参数只在 `:177` 使用，所有调用点传 `0`，且 `r()` 不再自递归 ⇒ **该护栏永不触发**（"看起来有保护、实际从未生效"的静默失效）。

**修复建议**

必须先确定设计意图，二选一：

**方案 A（保持「滑动浏览用 `r(node,false)`」的天坦语义）** —— 接受 `:281-309` 是死代码，但**应显式删除并标注**，避免后人反复"修复"无用分支：

```kotlin
        if (role != NodeRoleHelper.ROLE_LIST && role != NodeRoleHelper.ROLE_PAGER) {
            val z11 = n(node, true) || (W(node) && p(node))
            // 【注意】天坦 r(node, z9) 的 z9==true 分支（对应原 :281-309）
            // 在本项目从不被使用：shouldFocus() 只以 z9=false 调用（见 :166/:168）。
            // 若将来需要该分支，请显式传入 z9=true 并同时恢复下方被注释掉的判定。
            return z11
        }
```

**方案 B（恢复双模式，让死代码活过来）** —— 为需要 281-309 语义的调用方提供入口：

```kotlin
    fun shouldFocus(node: AccessibilityNodeInfo?): Boolean = shouldFocus(node, z9 = false)

    /**
     * @param z9 天坦 r(node, z9) 的第二参数。
     *   false = 滑动浏览（默认，直接返回 z11）
     *   true  = 「可聚焦且无子节点」的严格判定（继续走 k()/i() 分支）
     */
    fun shouldFocus(node: AccessibilityNodeInfo?, z9: Boolean): Boolean {
        if (node == null) return false
        seenChildren.clear()          // 见问题 3：去重集合必须是「本轮」作用域
        val c = cache
        return if (c != null) c.shouldFocus(node) { r(node, z9, 0) } else r(node, z9, 0)
    }
```

**置信度：确定**（调用点已全树穷举，无第三处调用；`r()` 无自递归已核实）

---

### 【3】P0 — `seenChildren` 单例集合从不清空，`m()` 随运行时长单调恶化

**文件**：`core/ShouldFocusNode.kt:669`、`:624-651`、`:628`；清理点 `:107-125`、`:138-140`

**代码原文**

```kotlin
669:    private val seenChildren = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(16)
```

```kotlin
624:        val count = node.childCount
625:        for (idx in 0 until count) {
626:            val child = node.getChild(idx) ?: continue
627:            // 重复节点 → 天坦直接 return false（f39022g 是**静态**集合）
628:            if (seenChildren.put(child, true) != null) return false      // ★ 整个 m() 立即返回
629:
630:            if (!child.isVisibleToUser) continue
...
651:    }
```

**核实**：全项目 `seenChildren` 仅出现 **2 次** —— 声明（`:669`）与写入（`:628`），**无任何 `clear()` / `remove()` / 重新赋值**。

**为什么是 bug**

`ShouldFocusNode` 是 Kotlin `object`（`:56`，进程级单例），`seenChildren` 因此**跨所有调用、所有窗口、整个服务生命周期存活**。

1. `:628` 的语义是「该子节点本轮已见过 → **整个 `m()` 立即 `return false`**」（注意**不是** `continue` 跳过该子节点，而是整个函数返回）。
2. `m()` 的返回值被 `p()` 消费（`:552` `val result = i(node) || m(node)`），`p()` 的结果又被写入 `pCache`（`:556`）并长期残留。
3. `p()` 又参与两条关键判定：`:276` 的 `z11 = n(...) || (W(node) && p(node))` 与 `:281` 的 `return true`。

于是：**一旦某个节点曾进入 `seenChildren`，它在后续任意时刻被再次访问时都会让 `m()` 立即返回 false**，使父容器被判为「不可聚焦」。

**真机后果**：Android 的 `AccessibilityNodeInfo` 是**会池化/复用实例**的 —— 同一逻辑节点的实例身份可能跨调用重复出现。因此该集合一旦污染，判定结果会**随服务运行时长持续恶化**：刚启动时焦点导航正常，用久了「越滑越滑不到焦点」。而且 `seenChildren` 按 identity **强引用**旧节点对象，**阻止这些对象被 GC**，进一步降低「出现全新身份」的概率，形成正反馈。

**关键对照（"抄漏"的直接证据）**：天坦的对应集合 `f39022g` 是**静态 `HashSet`，但每一轮判定前会 `clear()`**。本文件 `:656-667` 的注释**自己写明了这个事实**：

```kotlin
656:     * 【为什么是静态共享集合而非每次新建】
657:     *   天坦 `FocusFinder.java:59` 声明为 `public static final HashSet`，
658:     *   并且 `shouldFocus()` 在进入判定前会 `f39022g.clear()`
659:     *   （可读版第 386 行）。它的语义是「**本轮判定**里已访问过的节点」——
660:     *   跨 `hasActionableChild` 的多层递归共享，用于检测
```

注释描述正确，但**实现里那个 `clear()` 没抄过来** —— 典型的「注释写对了、代码抄漏了」。

**修复建议**

在每次判定**入口**清空（与天坦一致，作用域 = 单次 `shouldFocus`）：

```kotlin
    fun shouldFocus(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        // ★ 与天坦 f39022g.clear() 对齐：去重集合的作用域是「本轮判定」，
        //   不是「服务生命周期」。不清会导致 m() 跨调用累积并永久返回 false。
        seenChildren.clear()
        val c = cache
        if (c != null) {
            return c.shouldFocus(node) { r(node, false, 0) }
        }
        return r(node, false, 0)
    }
```

并同步在 `clear()` 中兜底（防止窗口切换残留）：

```kotlin
    fun clear() {
        tempRect.setEmpty()
        tempRect2.setEmpty()
        baselineBounds.setEmpty()
        baselineInitialized = false
        pCache.clear()
        seenChildren.clear()      // ★ 新增
    }
```

> 注意：`seenChildren.clear()` 必须放在**外层入口**而不是递归内层，否则会破坏跨递归去重语义。

**置信度：确定**（"从不清空"是全树 grep 可证的确定事实；"必然导致判定错误"在节点实例复用时确定）

---

### 【4】P1 — `Companion.cacheGeneration = generation` 把实例快照写回静态字段，破坏缓存失效

**文件**：`core/TatansTraversal.kt:101-112`、`:142`、`:551-607`

**代码原文**

```kotlin
101:        @Volatile
102:        private var cacheGeneration = 0
...
107:        fun invalidateIndexCache() {
108:            cacheGeneration++
109:            cacheParentHash = -1
110:            cacheChildIndex = -1
111:            cacheChildCount = -1
112:        }
```

```kotlin
141:    /** 本实例创建时的缓存代数快照 */
142:    private val generation: Int = cacheGeneration
```

```kotlin
556:            val hash = parent.hashCode()
557:            // ★ 双保险：内容哈希匹配 + 代数匹配（窗口/内容变化时代数自增）
558:            if (hash != -1 && hash == cacheParentHash &&
559:                count == cacheChildCount && Companion.cacheGeneration == generation
560:            ) {
...
596:                    cacheParentHash = parent.hashCode()
...
602:                Companion.cacheGeneration = generation     // ★★ 问题所在
603:                return i
```

**为什么是 bug**

`:142` 的设计意图很清楚：实例创建时**快照**当前代数，之后用 `Companion.cacheGeneration == generation`（`:559`）判断「我创建后缓存有没有被失效过」。

但 `:602` 在**索引命中并写缓存**时执行 `Companion.cacheGeneration = generation` —— 把**这个实例创建时的旧快照写回了静态字段**。后果：

1. 静态代数被"拽回"旧值，`invalidateIndexCache()` 的 `cacheGeneration++`（`:108`）效果**被抵消**。
2. 多实例共存时，代数不再是单调递增的全局版本号，而是**被最后一个命中的实例的年龄所决定**。一个"老"实例命中一次，就会让所有"新"实例的 `Companion.cacheGeneration == generation` 判定失败 → **索引缓存对这些实例永久失效**（退化为每次都做完整线性扫描）。
3. 反之，若判断式为真，则用的是**错误的世代**：`cacheChildIndex` 是另一世代留下的中心索引，双向扩展搜索会从一个错误中心开始，可能**找不到本应存在的子节点**（`:566` `if (lo < 0 && hi >= count) break` 提前退出）→ `indexOf` 返回 `-1`。

**真机后果**（对应 `:538-545` 注释自身的警告）：索引错位 → `findNextSibling`/`findPrevSibling` 的 `idx == -1` 分支被误走 → **焦点跳到错误的兄弟节点，或直接返回 null（"滑不到焦点"）**。

**修复建议**

`:602` 一行应当**删除**（写缓存时不该改动全局代数）：

```kotlin
                cacheParentHash = parent.hashCode()
                cacheChildIndex = i
                cacheChildCount = count
                // ★ 删除：Companion.cacheGeneration = generation
                //   代数只应由 invalidateIndexCache() 单调递增；
                //   写回实例快照会让全局版本号回退，破坏所有其他实例的失效判定。
                return i
```

建议同时把 `generation` 快照的语义写清，防止再次被误改：

```kotlin
    /** 本实例创建时的缓存代数快照（只读，绝不回写 Companion） */
    private val generation: Int = cacheGeneration

    /** 当前是否仍处于同一世代 */
    private val isGenerationValid: Boolean get() = Companion.cacheGeneration == generation
```

**置信度：确定**（`:602` 的赋值与 `:108` 的自增语义直接冲突，静态即可判定）

---

### 【5】P1 — `performGlobalAction` 从未注入，6 个系统手势全部静默失败

**文件**：`core/GestureActionHandler.kt:64`、`:128`、`:96-102`

**代码原文**

```kotlin
64:    var performGlobalAction: ((Int) -> Boolean)? = null
```

```kotlin
128:    private fun global(action: Int): Boolean = performGlobalAction?.invoke(action) ?: false
```

```kotlin
 96:            // ---- 系统动作 ----
 97:            GestureAction.BACK -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
 98:            GestureAction.HOME -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
 99:            GestureAction.RECENTS -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
100:            GestureAction.NOTIFICATIONS -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
101:            GestureAction.SCREENSHOT -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
102:            GestureAction.SCREEN_RECOGNIZE -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
```

**核实**：全项目（含 `app/src/test`）`performGlobalAction` **仅出现 2 次** —— 声明（`:64`）与读取（`:128`）。**无任何赋值点**。而 `ReaderAccessibilityService.kt:457-472` 构造 `GestureActionHandler` 时传了 12 个参数，**未包含 `performGlobalAction`**。

**为什么是 bug**

`?.invoke(action) ?: false` 的写法使「未注入」与「系统拒绝执行」**返回同一个 `false`** —— 调用方（`GestureChain.handle`）无法区分。用户三指下滑要求「返回」，实际什么都不会发生，**且没有任何音效/震动的失败反馈**。

这属于典型的**静默失败**：功能完全不可用，但用户和开发者都得不到任何线索。

**真机后果**：返回、主页、最近任务、通知栏、截屏、分屏 —— **6 个系统操作全部无效**。对视障用户而言，「返回」是最常用的操作之一，缺失会严重阻碍独立使用。

**修复建议**

在服务装配处注入（`ReaderAccessibilityService.kt:457-472` 的参数列表内新增一行）：

```kotlin
        gestureActions = GestureActionHandler(
            // ... 既有参数 ...
            onLongClickTarget = { focus.longClickTarget() },
            performGlobalAction = { action -> performGlobalAction(action) },   // ★ 新增
        )
```

并让失败可感知（`GestureActionHandler.global()`）：

```kotlin
    private fun global(action: Int): Boolean {
        val handler = performGlobalAction
        val ok = handler?.invoke(action) ?: false
        if (!ok) {
            // ★ 不静默：区分「未接线」与「系统拒绝」，并给用户可感知的失败反馈
            if (handler == null) {
                ReaderLog.e("GestureActionHandler", "系统动作未接线（performGlobalAction==null），action=$action")
                speak("系统动作不可用", true)
            } else {
                ReaderLog.w("GestureActionHandler", "系统动作被拒绝，action=$action")
            }
        }
        return ok
    }
```

（`speak` 已是本类构造参数，可直接使用。）

**置信度：确定**（赋值点缺失已全树 grep 核实）

---

### 【6】P1 — `NodeCache` 无同步却可被无障碍线程访问（注释前提被证伪）

**文件**：`core/NodeCache.kt:42`、`:102`、`:110-128`；`core/AutoScrollManager.kt:305`；`core/ReaderAccessibilityService.kt:755-757`

**代码原文**

`NodeCache.kt:42`（注释声明的前提）：
```kotlin
 * ④ **无锁读**：缓存只在主线程访问（无障碍事件已队列化到主线程），
 *    所以不需要同步，省掉锁开销
```

`NodeCache.kt:102`、`:110-118`：
```kotlin
102:    private val focusCache = java.util.IdentityHashMap<AccessibilityNodeInfo, CacheEntry<Boolean>>(128)
...
110:    fun shouldFocus(
111:        node: AccessibilityNodeInfo,
112:        evaluator: () -> Boolean
113:    ): Boolean {
114:        val gen = generation
115:        focusCache[node]?.let {
116:            PerfCounters.cacheHit()
117:            if (it.gen == gen) return it.value
118:        }
```

**证伪链**（逐环给出证据）：

1. `ReaderAccessibilityService.kt:755-757`（在 `onAccessibilityEvent` 内 —— **无障碍线程**）：
```kotlin
        if (et == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            autoScroll.onScrollEvent(event)
        }
```
2. `AutoScrollManager.kt:305`（滚动路径，仍在无障碍线程）：
```kotlin
            val traversal = TatansTraversal(scrollNode)
            traversal.setFilter { ShouldFocusNode.shouldFocus(it) }    // ★
```
3. `ShouldFocusNode.kt:162-169` → `NodeCache.shouldFocus(node) { ... }`（`:166`）。

**⇒ `NodeCache.focusCache` 会在无障碍线程被读/写，与主线程的事件处理路径并发。**

**为什么是 bug**

`java.util.IdentityHashMap` **不是线程安全的**。并发写（`focusCache[node] = CacheEntry(...)`，`:125`）与并发读/遍历（`trimMap` 的迭代删除，`:233-241`）同时发生时：

- Map 扩容期间另一线程可能读到 `null` 或**丢失刚写入的条目**（静默失效，表现为缓存时而命中时而不命中）；
- 极端情形下内部桶链损坏，`get` 可能进入**死循环** —— 会让无障碍线程或主线程直接卡死；
- `trimMap` 的迭代删除与另一线程的 `put` 并发 → `IdentityHashMap` 的迭代器**非 fail-fast**，更可能表现为**静默丢数据**而非抛异常。

**真机后果**：滚动场景下偶发的「焦点判定结果随机跳变」「缓存命中率异常低」，以及极小概率的**界面卡顿/无响应**。因为投毒的是缓存而非逻辑，**这类故障极难复现和定位**。

**修复建议（择一）**

**方案 A（推荐，改动最小）** —— 让 `NodeCache` 真正线程安全：

```kotlin
    private val focusLock = Any()
    private val textLock = Any()
    // ...（各缓存一把锁，避免交叉阻塞）

    fun shouldFocus(node: AccessibilityNodeInfo, evaluator: () -> Boolean): Boolean {
        val gen = generation
        synchronized(focusLock) {
            focusCache[node]?.let {
                PerfCounters.cacheHit()
                if (it.gen == gen) return it.value
            }
        }
        val result = evaluator()          // ★ 求值放在锁外，避免长临界区
        synchronized(focusLock) {
            if (focusCache.size >= focusCapacity) trimMap(focusCache)
            focusCache[node] = CacheEntry(result, gen)
        }
        PerfCounters.cacheMiss()
        return result
    }
```

同理包裹 `getSortedChildren` / `composeText` / `filterAccept` / `invalidateAll` / `invalidateFocus` / `hardClear` / `trimMap`。

**方案 B（更彻底）** —— 改用 `ConcurrentHashMap`。注意它用 `equals/hashCode`，而节点必须按 identity 语义，因此需包一层：

```kotlin
    private class NodeKey(val node: AccessibilityNodeInfo) {
        override fun equals(other: Any?) = other is NodeKey && other.node === node
        override fun hashCode() = System.identityHashCode(node)
    }
    private val focusCache = ConcurrentHashMap<NodeKey, CacheEntry<Boolean>>(128)
```

**方案 C（权宜）** —— 若确认无障碍线程路径可去掉，则移除 `AutoScrollManager.kt:305` 处对 `ShouldFocusNode.shouldFocus()` 的直接调用，改为把该遍历整体投递到主线程。但这会改变滚动时序，需真机验证，**不如 A/B 稳妥**。

**置信度：确定**（并发可达性已由 3 步调用链证实；实际故障概率未做运行时验证）

---

### 【7】P1 — `AutoScrollManager` 滚动状态跨线程访问且无 `@Volatile`

**文件**：`core/AutoScrollManager.kt:82,85,86,344,348`、`:365-392`

**代码原文**

```kotlin
82:    private var lastScrollDirection = 0
85:    private var scrollDeltaX = 0
86:    private var scrollDeltaY = 0
...
344:    private var lastSnapshot: ScrollEventSnapshot? = null
...
348:    var lastDetectedDirection: Int = ScrollDirectionDetector.UNKNOWN
```

`onScrollEvent`（`:365-392`，**无障碍线程**调用）：
```kotlin
365:    fun onScrollEvent(event: android.view.accessibility.AccessibilityEvent): Int {
366:        val current = try { ScrollEventSnapshot(event) } catch (e: Throwable) { return ScrollDirectionDetector.UNKNOWN }
367:
368:        val direction = ScrollDirectionDetector.detect(current, lastSnapshot)   // ★ 读
369:        lastDetectedDirection = direction                                        // ★ 写
370:
371:        val dx = current.deltaX
372:        val dy = current.deltaY
373:        if (dx != 0 && dx != -1) scrollDeltaX += dx                              // ★ 非原子累加
374:        if (dy != 0 && dy != -1) scrollDeltaY += dy
375:
376:        lastSnapshot = current                                                   // ★ 写
377:
378:        handler.removeCallbacks(settleRunnable)
379:        handler.postDelayed(settleRunnable, SCROLL_SETTLE_MS)
380:        return direction
381:    }
```

对比同文件其他字段（`:78` `@Volatile var isScrolling`、`:98` `@Volatile var recoveryCallback`）—— **作者显然知道要加 `@Volatile`，但这 5 个字段漏了**。

**为什么是 bug**

同一个对象的状态被两个线程访问：
- **无障碍线程**：`onScrollEvent`（经 `ReaderAccessibilityService.kt:756`）
- **主线程**：`handler = Handler(Looper.getMainLooper())`（`:71`）驱动的 `settleRunnable`、`tryFocusAfterScroll`、以及 `stop()`（`:458-463`）

两类问题：
1. **可见性**：`lastSnapshot` / `lastDetectedDirection` 无 `@Volatile`，JIT 可将读取缓存在寄存器，主线程可能长时间看不到无障碍线程刚写入的快照 → 方向判定 /「是否真的动了」判定基于过期数据。
2. **竞态**：`scrollDeltaX += dx` 是**读-改-写**非原子序列。滚动洪峰时若两条事件并发进入，累加会丢失更新 → 「是否真的动了」判定偶发误判。

**真机后果**：滚动音效速率（依赖 `lastDetectedDirection`）偶发错误；"滚动成功/失败"判定抖动 → `recoveryCallback.onAutoScrollFailed()` 被误触发 → 走到跨窗口兜底 → **焦点意外跳到别的窗口**。

**修复建议**

```kotlin
    @Volatile
    private var lastScrollDirection = 0

    @Volatile
    private var lastSnapshot: ScrollEventSnapshot? = null

    @Volatile
    var lastDetectedDirection: Int = ScrollDirectionDetector.UNKNOWN

    // 累加量跨线程累加，volatile 不足以保护 +=
    private val scrollDeltaX = java.util.concurrent.atomic.AtomicInteger(0)
    private val scrollDeltaY = java.util.concurrent.atomic.AtomicInteger(0)
```

并把 `:373-374` 改为：

```kotlin
        if (dx != 0 && dx != -1) scrollDeltaX.addAndGet(dx)
        if (dy != 0 && dy != -1) scrollDeltaY.addAndGet(dy)
```

> 更稳妥：把 `:368-376` 的全部写入放进一个 `synchronized(scrollStateLock)` 临界区。该方法位于**无障碍线程热路径**，用锁包住极短临界区是可接受的。

**置信度：确定**（`@Volatile` 缺失与跨线程可达性均为静态可证）

---

### 【8】P1 — `ShouldFocusNode` 单例的判定状态非线程安全

**文件**：`core/ShouldFocusNode.kt:56,63,64,93,96,858`；调用链同问题 6

**代码原文**

```kotlin
56: object ShouldFocusNode {
...
63:    private val tempRect = Rect()
64:    private val tempRect2 = Rect()
...
93:    private val baselineBounds = Rect()
94:
95:    /** 基准是否已初始化 —— 对应天坦 f39007b（null 判定） */
96:    private var baselineInitialized = false
...
858:    private val pCache = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(64)
```

对比同文件唯一的 `@Volatile`（`:159-160`）：
```kotlin
159:    @Volatile
160:    var cache: NodeCache? = null
```

**为什么是 bug**

`object` = 进程级单例；上述字段全部是**可变共享状态**，却无 `@Volatile`、无锁。并发访问路径同问题 6（无障碍线程滚动 vs 主线程事件）。

具体后果：

- **`baselineBounds` 撕裂写**：`Rect` 的四条边非原子写入。`:217` `fillWindowBounds(node, baselineBounds)` 与 `:220` `node.getBoundsInScreen(baselineBounds)` 从两线程并发执行时，另一线程可能读到**混合了两次写入**的矩形。`isSameBounds`（`:1175-1178`，`tempRect == base`）随后用一个**撕裂的基准**做比较 → 微信特判（`:226-234`）与「节点是否铺满窗口」的判定随机化。
- **`tempRect` 复用冲突**：`:63` 的 `tempRect` 同时被 `isSameBounds()`（`:1176`）与 `hasChildContent()`（`:1027`）使用；`:64` 的 `tempRect2` 被 `hasChildContent()`（`:1047`）使用。两线程并发时互相覆盖，`tempRect2 == tempRect`（`:1048`）的判定失效。
- **`baselineInitialized` 可见性**：非 `@Volatile` 的 `Boolean`，一方的写入可能长时间对另一方不可见。
- **`pCache`**：`IdentityHashMap` 并发写，同问题 6 的 Map 损坏风险。

**修复建议**

**首选：消除共享状态**。把 `r()` 的递归上下文改为**调用栈内的局部对象**，从设计上根除跨线程污染，并顺带解决问题 3 与 11：

```kotlin
    /** 一次判定的上下文 —— 替代单例上的可变状态，天然线程安全 */
    private class EvalContext {
        val tempRect = Rect()
        val tempRect2 = Rect()
        val baselineBounds = Rect()
        var baselineInitialized = false
        val pCache = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(64)
        val seenChildren = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(16)
    }

    fun shouldFocus(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val ctx = EvalContext()          // ★ 每次判定独立，无共享
        return r(node, false, 0, ctx)
    }
```

（`pCache` 随之变成"单次判定内"的作用域 —— 这与天坦 `f39013h` 在 `e()` 调用后清理的语义**更加一致**，同时解决问题 11 的过期问题与问题 3 的泄漏问题。）

**次选：加锁**。在 `shouldFocus()` 外层 `synchronized(lock) { ... }`。但 `r()` 会做**数百次跨进程 IPC**，持锁期间会阻塞另一线程，**可能引入新的卡顿**，因此不如"消除共享"。

**置信度：确定**（"单例 + 非线程安全 + 并发可达"三者均静态可证；实际故障概率未运行时验证）

---

### 【9】P1 — `NodePathCollector.onCollected` 泄漏整个 Service

**文件**：`core/NodePathDescription.kt:409,435-436,503-509`；`core/ReaderAccessibilityService.kt:563-567,1151-1175`

**代码原文**

`NodePathDescription.kt:409`（进程级单例）：
```kotlin
409: object NodePathCollector {
```

`NodePathDescription.kt:435-436`：
```kotlin
435:    @Volatile
436:    var onCollected: ((NodePath) -> Unit)? = null
```

`ReaderAccessibilityService.kt:563-567`（注入的闭包捕获了 `this` 与 `eventHandler`）：
```kotlin
563:        NodePathCollector.onCollected = { path ->
564:            lastFocusPath = path
565:            // 同时补填 A6 的历史记录（让「本窗口上次焦点在哪」可用）
566:            eventHandler.recordPath(path)
567:        }
```

`ReaderAccessibilityService.kt:1151-1175`（`onDestroy` 全文）：
```kotlin
1151:    override fun onDestroy() {
1152:        super.onDestroy()
1153:        instance = null
1154:        isRunning = false
...
1166:        shutdownSafely("systemState") { systemState.unregister() }
1167:        shutdownSafely("continuousReader") { continuousReader.shutdown() }
1168:        shutdownSafely("ttsObserver") { ttsObserver.unregister() }
1169:        shutdownSafely("speechWakeLock") { speechWakeLock.releaseAll() }
1170:
1171:        onLogUpdate = null
1172:        synchronized(eventLog) { eventLog.clear() }
1173:
1174:        log("服务已销毁")
1175:    }
```

**核实**：全项目搜索 `NodePathCollector.reset` 与 `NodePathCollector.onCollected = null` —— **0 处命中**。

**为什么是 bug**

`NodePathCollector` 是 `object`，其静态字段 `onCollected` 持有 lambda，该 lambda 捕获：
- `this`（`ReaderAccessibilityService` 实例，含 `Context`）
- `eventHandler`（`AccessibilityEventHandler`，其构造参数又持有 `speech`/`sound`/`focus`/`autoScroll`/`audioFocus`/`screenRecovery`/`textMonitor` 等**整条对象图**）

`onDestroy` 明确清空了 `onLogUpdate`（`:1171`）—— 说明作者意识到「静态回调要置空」这一点，但**漏了 `NodePathCollector.onCollected`**。

**真机后果**：无障碍服务被系统重启（用户开关读屏、崩溃恢复、系统内存回收）时，**旧 Service 实例及其整条对象图无法被 GC**。反复开关读屏会持续累积。对手机上长期运行的服务而言，这是稳定的内存泄漏。

**修复建议**

在 `onDestroy()` 中显式清理：

```kotlin
        // ★ 补：清理静态回调，避免进程级单例持有已销毁的 Service 实例
        shutdownSafely("nodePathCollector") {
            NodePathCollector.onCollected = null
            NodePathCollector.reset()      // 内含 removeCallbacks(handler) + 清 pendingNode
        }
```

`NodePathCollector.reset()`（`NodePathDescription.kt:503-509`）已实现完整，直接调用即可。

**置信度：确定**

---

### 【10】P1 — `onDestroy()` 遗漏其余资源清理

**文件**：`core/ReaderAccessibilityService.kt:1151-1175`、`:646-673`

**代码原文**（`onDestroy` 见问题 9）

`registerCacheHandlers()`（`:646-673`）注册的闭包捕获了 `this`：
```kotlin
646:    private fun registerCacheHandlers() {
647:        // ---- 窗口/内容变化：全部清 ----
648:        CacheRegistry.register(CacheRegistry.Level.WINDOW, "nodeCache") {
649:            nodeCache.invalidateAll()
650:        }
...
657:        CacheRegistry.register(CacheRegistry.Level.WINDOW, "webElementNavigator") {
658:            webNavigator.clear()
659:        }
...
670:        CacheRegistry.register(CacheRegistry.Level.CONFIG, "screenMetrics") {
671:            NodeOrderComparator.initFromContext(this)
672:        }
673:    }
```

**遗漏清单**（均已核实无对应清理调用）：

| 对象 | 应有清理 | 现状 |
|---|---|---|
| `webNavigator` | `webNavigator.clear()` | 仅在 `CacheRegistry.Level.WINDOW` 回调里；`onDestroy` 未调 |
| `textMonitor` | `unwatch(watchedNode)` | `onDestroy` 未调，`watchedNode` 引用残留 |
| `focus.monitor` | 清理 `lastEditableFocus`（持节点引用） | `FocusMonitor` 有 `reset()`，但 `onDestroy` 未调 |
| `CacheRegistry` 自身 | 清空已注册回调 | `onDestroy` 未调 → **静态表持有旧 Service 的闭包** |
| `NodePathCollector` | 见问题 9 | 未调 |

**为什么是 bug**

`CacheRegistry` 是单例（`CacheRegistry.kt:33 object CacheRegistry`），其 `handlers`（`:53`）保存的是**捕获了 Service 实例的 lambda**。服务销毁后若不清空，这些闭包继续持有旧 Service，与新注册的回调**并存累积** → 每次服务重启都让回调列表增长一份，且触发失效时会调用到已销毁服务的对象（可能操作已关闭的 `nodeCache`）。

**修复建议**

```kotlin
    override fun onDestroy() {
        super.onDestroy()
        // ... 既有清理 ...

        // ★ 补：静态注册表与热路径单例的清理（防止单例持有已销毁的 Service）
        shutdownSafely("webNavigator") { webNavigator.clear() }
        shutdownSafely("textMonitor") { textMonitor.shutdown() }
        shutdownSafely("focusMonitor") { focus.monitor?.reset() }
        shutdownSafely("cacheRegistry") { CacheRegistry.clearAll() }
        shutdownSafely("nodePathCollector") {
            NodePathCollector.onCollected = null
            NodePathCollector.reset()
        }

        instance = null
        isRunning = false
        log("服务已销毁")
    }
```

需先补两个方法（`NodeTextMonitor` 定义于 `TextDiff.kt:344`）：

```kotlin
// TextDiff.kt —— NodeTextMonitor 内新增
    /** 停止监听并释放持有的节点引用（服务销毁时调用，防泄漏） */
    fun shutdown() {
        watchedNode?.let { unwatch(it) }
        watchedNode = null
    }
```

```kotlin
// CacheRegistry.kt 内新增
    /** 清空全部注册 —— 服务销毁时调用，避免静态表持有旧 Service 的闭包 */
    fun clearAll() {
        synchronized(handlers) { handlers.clear() }
    }
```

> 由于 `registerCacheHandlers()` 用 `"nodeCache"` 等**字符串名**做 key，若 `register` 是 `add` 语义则可能重复注册同名回调。建议同时改为幂等：

```kotlin
    // CacheRegistry.kt —— 让重复注册幂等（同名替换而非追加）
    fun register(level: Level, name: String, handler: () -> Unit) {
        synchronized(handlers) {
            val list = handlers.getOrPut(level) { mutableListOf() }
            list.removeAll { it.first == name }     // ★ 同名先移除
            list.add(name to handler)
        }
    }
```

**置信度：确定**（遗漏项已逐项核实；`CacheRegistry.clearAll` / `NodeTextMonitor.shutdown` 为需新增的方法）

---

### 【11】P1 — `pCache` 不在失效体系内，滚动后读到过期判定

**文件**：`core/ShouldFocusNode.kt:124,138-140,549-557,858`；`core/FocusManager.kt:317-322,805-814`；`core/ReaderAccessibilityService.kt:646-673`

**代码原文**

`ShouldFocusNode.kt:138-140`（每轮导航都调，但只做一件事）：
```kotlin
138:    fun resetOperationScope() {
139:        baselineInitialized = false
140:    }
```

`FocusManager.kt:317-322`（每轮 `moveFocus` 的 `finally`）：
```kotlin
317:        return try {
318:            moveFocusInternal(direction, strategyType, filter)
319:        } finally {
320:            ShouldFocusNode.resetOperationScope()      // ★ 每轮导航都调
321:            PerfCounters.endNav(perfStart)
322:        }
```

`ShouldFocusNode.kt:549-557`（`pCache` 读写，含唯一清理点）：
```kotlin
549:        val cached = pCache[node]
550:        if (cached != null) return cached
551:
552:        val result = i(node) || m(node)
553:        // 容量保护：单次操作内节点极多时避免无界增长
554:        // （正常导航一轮远小于此，触发即说明树结构异常）
555:        if (pCache.size >= P_CACHE_CAPACITY) pCache.clear()
556:        pCache[node] = result
557:        return result
```

`pCache` 的**全部**清空点只有两个：`:124`（在 `clear()` 内）与 `:555`（容量满）。而 `clear()` 的**唯一调用者**是 `FocusManager.kt:813`（`onWindowChanged()`，窗口变化/服务关闭）。

`ReaderAccessibilityService.kt:648-655`（失效体系**覆盖不到** `pCache`）：
```kotlin
648:        CacheRegistry.register(CacheRegistry.Level.WINDOW, "nodeCache") {
649:            nodeCache.invalidateAll()
650:        }
...
662:        CacheRegistry.register(CacheRegistry.Level.SCROLL, "focusAndText") {
663:            nodeCache.invalidateFocus()
664:        }
```

**为什么是 bug**

`pCache` 缓存的是 `p(node) = i(node) || m(node)` 的结果。而 `m()` 依赖 `child.isVisibleToUser`（`:630`）、`W(child)`（`:637`，又依赖父节点可见性与滚动 action）、`n(child, true)`（`:633`）—— **这些量在滚动后全都会变**。

但：
- 每轮导航只调 `resetOperationScope()`（只重置基准），**`pCache` 原样保留**；
- `CacheRegistry.Level.SCROLL` 只调 `nodeCache.invalidateFocus()`（`NodeCache` 内部的三个 map），**碰不到 `ShouldFocusNode.pCache`**。

⇒ **滚动之后，判定继续使用滚动前的 `pCache` 结果**。`pCache` 的实际生命周期被拉长为「一个窗口」，甚至（窗口不变时）**整个服务生命周期**。

**真机后果**：列表滚动后，本该因可见性变化而重新判定为新可聚焦的节点，仍被按旧结论拒绝 → **滚动后「滑不到焦点」**。这解释了为什么用户报告的问题在**列表/滚动场景**下尤其明显。

> 仓库内 `docs/焦点判定忠实度核查-第三轮.md:55` 与 `:661` 已独立登记同一条（"`resetOperationScope()` ⚠️ 不完整：只重置基准，未清 pCache；也未清去重集合"），本次排查复现并补充了后果链。

**修复建议**

**首选**：采用问题 8 的「`EvalContext` 局部化」方案 —— `pCache` 随判定上下文创建/销毁，**天然不存在过期问题**，也无需任何失效联动。

**次选**（保留跨调用缓存）：把 `pCache` 纳入 `resetOperationScope()` 与 `CacheRegistry`：

```kotlin
    /**
     * 重置「本次操作」的作用域 —— 每次导航结束后调用
     *
     * ★ pCache 也必须清：p() 依赖 isVisibleToUser / W() 等在滚动后会变的量，
     *   跨操作保留会读到过期判定（真机表现：滚动后滑不到焦点）。
     */
    fun resetOperationScope() {
        baselineInitialized = false
        pCache.clear()          // ★ 新增
        seenChildren.clear()    // ★ 新增（见问题 3）
    }
```

并在 `registerCacheHandlers()` 里为滚动级失效挂钩（双保险）：

```kotlin
        CacheRegistry.register(CacheRegistry.Level.SCROLL, "shouldFocusP") {
            ShouldFocusNode.onScrollInvalidate()      // 内部清 pCache + seenChildren
        }
```

**置信度：确定**（清理点已全树穷举；`p()` 依赖滚动敏感量已逐行核实）

---

### 【12】P1 — `EventQueue.drainRunnable` 的竞态窗口可致事件永久滞留

**文件**：`core/EventQueue.kt:169-192`、`:154-161`、`:117-147`

**代码原文**

```kotlin
169:    private val drainRunnable = object : Runnable {
170:        override fun run() {
171:            var processed = 0
172:            while (true) {
173:                val event = poll() ?: break
174:                try {
175:                    consumer?.invoke(event)
176:                } catch (e: Throwable) {
177:                    ReaderLog.e("EventQueue", "consume failed: ${e.message}")
178:                } finally {
179:                    recycle(event)
180:                }
181:                processed++
182:                if (processed >= MAX_DRAIN_PER_TICK) break          // ★ 16 条即 break
183:            }
184:
185:            synchronized(this) { drainScheduled = false }           // ★ 锁内
186:
187:            // 还有剩余 → 下一帧继续（让出主线程）
188:            if (size > 0) scheduleDrain()                           // ★ 锁外读 size
189:        }
190:    }
```

```kotlin
154:    private fun scheduleDrain() {
155:        if (drainScheduled) return                                 // ★ 锁外读
156:        synchronized(this) {
157:            if (drainScheduled) return
158:            drainScheduled = true
159:        }
160:        handler.post(drainRunnable)
161:    }
```

**为什么是 bug**

`drainRunnable.run()` 在 `break` 之后（`:185`）与 `size > 0` 检查（`:188`）之间**不持锁**。交错时序：

| 时刻 | 主线程（drain） | 无障碍线程（offer） |
|---|---|---|
| T1 | `synchronized { drainScheduled = false }` | |
| T2 | | `synchronized { size++ }`（新事件入队） |
| T3 | | `scheduleDrain()` → 读 `drainScheduled`，**若此刻仍读到 true 则直接 return** |
| T4 | 读 `size` → 若此时已被 `poll()` 清空，则 `size == 0` | |

若 T3 读到旧的 `true`，且 T4 读到 `size == 0`，则**新入队的事件既没有 pending drain、也不会被再次调度** → 事件永久滞留在环形缓冲里，直到下一次有事件来"顺手"唤醒。

（T3 读 `@Volatile` 变量且 T1 已写，实践上多数情况能读到新值；但 T4 的 `size` 读取是**独立**的快照，与 T3 无同步关系 —— 只要 T4 发生在 T2 的 `poll()` 之后就会读到 0。两个条件独立，窗口存在。）

**真机后果**：焦点事件丢失 → 用户**滑动后没有任何播报**，必须**再滑一次**才出声。在事件洪峰（快速滑动、动态界面）时概率上升。这与用户「滑动找不到焦点」的体感高度吻合，且**极难复现**（依赖线程交错时机）。

**修复建议**

把"是否还有待处理"与"复位调度标志"放在**同一个临界区**内：

```kotlin
    private val drainRunnable = object : Runnable {
        override fun run() {
            var processed = 0
            while (true) {
                val event = poll() ?: break
                try {
                    consumer?.invoke(event)
                } catch (e: Throwable) {
                    ReaderLog.e("EventQueue", "consume failed: ${e.message}")
                } finally {
                    recycle(event)
                }
                processed++
                if (processed >= MAX_DRAIN_PER_TICK) break
            }

            // ★ 关键修复：在同一个临界区内「检查剩余 + 复位标志」，
            //   要么本次直接再调度一次，要么让随后 offer() 的 scheduleDrain 生效。
            val needReschedule = synchronized(this) {
                if (size > 0) {
                    true                       // 还有剩余：保持 drainScheduled = true
                } else {
                    drainScheduled = false
                    false
                }
            }
            if (needReschedule) handler.post(drainRunnable)
        }
    }
```

要点：`size > 0` 判断与 `drainScheduled = false` **必须原子**。这样若仍有剩余则标志保持 `true` 并自己再 post；若无剩余则复位置 `false`，此后任何 `offer` 的 `scheduleDrain` 都能成功 post。

**置信度：确定**（窗口由代码结构直接可证；实际触发概率依赖线程交错，故其**影响**为"很可能的偶发丢事件"）

---

### 【13】P2 — `SpeechEngine` B 通道懒创建导致 `notifySpeaking(false)` 缺失

**文件**：`core/SpeechEngine.kt:332-350`

**代码原文**

```kotlin
332:        // ★ 通知「开始朗读」（A5）—— 服务据此持有唤醒锁
333:        //   放在 enqueue 成功之后：被去重丢弃的项不算开始
334:        notifySpeaking(true)                                   // ★ 先置 true
...
344:        // 若走 B 通道但引擎未创建 → 懒创建
345:        if (useChannelB && !readyB) {
346:            createEngine(CHANNEL_B, null)
347:            return   // 就绪后会触发 trySpeakNext                // ★ 提前 return
348:        }
```

**为什么是 bug**

`notifySpeaking(true)`（`:334`）已触发服务侧的 `speechWakeLock.acquire()`（`ReaderAccessibilityService.kt:316`）。随后 `:347` 直接 `return`，**跳过了 `trySpeakNext()`**。

虽然 `createEngine` 的 `OnInitListener`（`:187-189`）在就绪后会调 `trySpeakNext(channel)` 从而最终触发 `onDone` → `notifySpeaking(false)`，但存在**两个漏配窗口**：

1. 若 TTS 初始化**失败**（`:190-192` 只记日志，**不调 `notifySpeaking(false)`**），则 `true` 永不配对；
2. 若该 channel B 在就绪前被 `stop(clearQueue = true)` 清掉，`SpeechQueue.clear()` 会为被取消项调 **item 的 `onDone`**（`SpeechQueue.kt:222/230`），而那**不是** `notifySpeaking` —— 唤醒锁引用计数仍 +1。

**真机后果**：唤醒锁引用计数泄漏 → 屏幕被**持续保持常亮**（本类是 `SCREEN_BRIGHT_WAKE_LOCK`，`SpeechWakeLock.kt:156-157`），电池加速消耗。虽有 `releaseAll()` 兜底（服务销毁时），但服务长时间存活期间无法恢复。

**修复建议**

`notifySpeaking` 收敛为**状态机**，避免多处调用造成不配对：

```kotlin
// SpeechEngine.kt
    @Volatile private var lastNotifiedSpeaking = false

    private fun notifySpeaking(speaking: Boolean) {
        if (speaking == lastNotifiedSpeaking) return   // ★ 只在翻转时通知
        lastNotifiedSpeaking = speaking
        try {
            onSpeakingStateChanged?.invoke(speaking)
        } catch (e: Throwable) {
            ReaderLog.w("SpeechEngine", "朗读状态回调异常：${e.message}")
        }
    }
```

并在 `trySpeakNext` 的「队列空」分支与 `onUtteranceDone` 末尾统一调用 `notifySpeaking(isSpeaking())`，让状态由真实队列驱动。同时给初始化失败路径补配对：

```kotlin
            } else {
                ReaderLog.e(TAG, "通道${if (channel == CHANNEL_A) "A" else "B"} 初始化失败: $status")
                notifySpeaking(false)      // ★ 补偿：避免 acquire 永不释放
            }
```

**置信度：很可能**（漏配窗口在静态上成立；但 `:187-189` 的就绪回调在正常路径下会兜住，故非必然发生）

---

### 【14】P2 — `NodeOrderComparator.analyzeLayout` 的 `i13` 是死变量（抄写残留）

**文件**：`core/NodeOrderComparator.kt:262`、`:323`、`:330`

**代码原文**

```kotlin
262:        var i13 = 0
...
322:                firstSmallIdx = -1
323:                i13 = 0
324:                smallCount = 0
325:            } else {
326:                smallCount++
327:                if (smallCount == 1) firstSmallIdx = idx
328:                lastRect.set(rect)
329:                idx++
330:                i13 = 0
331:            }
```

**核实**：`grep -n "i13"` 全文仅 4 处 —— 声明（`:262`）与两处赋值（`:323`、`:330`）。**无任何读取点**。`:241` 的注释自述 `i13 = 0 循环内的常量 0`。

**为什么是 bug（性质：抄写残留，非行为错误）**

`i13` 在**两个分支里都被无条件赋值为 0**，且从未被读取 —— 编译层面是 `assigned but never accessed` 警告，语义层面**等价于没有这段代码**。

它之所以重要，不是因为当前有错，而是因为它是**移植时抄漏的指示器**：天坦原版里该变量必然参与某个判定（否则不会保留），而本项目把它抄成了"赋值 0 就丢"。同时它的存在说明该函数的移植**未经过逐行核对**，同一个函数内的其他分支（如 `:307-311` 的 `isBig` 判定、`:335-346` 的收尾结算）有同等风险。

**建议**

1. **保留但标注**（推荐，等待与天坦源码逐行核对）：
```kotlin
        // ⚠️ 天坦原版此处有 i13 参与判定，移植时未能确定其用途。
        //    当前实现等价于忽略它（两分支都仅赋 0 且无读取）——
        //    待与 w7/C1423r.a.l() 逐行核对后补齐。
        @Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")
        var i13 = 0
```

2. 核对天坦源码后，**要么补齐逻辑，要么删除**，不要让"看起来有、实际没用"的变量长期存在 —— 这正是本项目反复出现「抄写错误」的土壤。

**置信度：确定**（"死变量"这一事实确定；其**是否掩盖了丢失的逻辑**为**存疑**，需对照天坦原版才能定论）

---

### 【15】P2 — `sortRecycler` 的"内容高度修正"是死代码

**文件**：`core/NodeOrderComparator.kt:452-478`

**代码原文**

```kotlin
452:        for (node in list) {
453:            val rect = Rect()
454:            node.getBoundsInScreen(rect)
455:
456:            // 用「内容高度」修正边界
457:            // 天坦 J()：rect2 = new Rect(); wVar2.j(rect2) 是 contentBounds
458:            val contentRect = Rect()
459:            try {
460:                @Suppress("DEPRECATION")
461:                node.getBoundsInScreen(contentRect)          // ★ 与 :454 是同一个 API
462:            } catch (e: Throwable) {
463:                contentRect.set(rect)
464:            }
465:
466:            if (contentRect.height() != rect.height()) {      // ★ 恒为 false
467:                val adjusted = Rect(rect)
468:                val delta = contentRect.height() - rect.height()
469:                if (Math.abs(parentRect.top - rect.top) < threshold10dp) {
470:                    adjusted.top -= delta
471:                } else {
472:                    adjusted.bottom += delta
473:                }
474:                boundsMap[node] = adjusted
475:            } else {
476:                boundsMap[node] = rect
477:            }
478:        }
```

**为什么是 bug**

注释明确说明此处应取 **`contentBounds`**（天坦的 `wVar2.j(rect2)`）。但实现用的是 `node.getBoundsInScreen(contentRect)` —— **与 `:454` 取 `rect` 的是完全相同的 API、同一个节点**。因此 `contentRect` 与 `rect` 内容始终一致，`contentRect.height() != rect.height()`（`:466`）**恒为 false**，整个 `if` 分支（`:467-474`）**永不执行**，`:475-476` 的 `else` 永远生效。

⇒ 「用内容高度修正 RecyclerView 项边界」这一逻辑**完全没有生效**，`sortRecycler` 实际只做了「按 `top` 再按 `left` 排序」（`:481-488`）。

**真机后果**：RecyclerView 中「项高度与内容高度不一致」的场景（带展开/折叠、或 item 高度由动画决定）排序会偏离天坦，表现为**滚动列表里焦点顺序偶发错乱**。

**修复建议**

Android 上取「内容边界」需要用 `getBoundsInScreen` 之外的途径（天坦的 `wVar2.j(rect2)` 是其封装层的 `getBoundsInParent` 或 extras 中的内容矩形）。在未确认真实 API 前，**至少要把死代码显式标注**，避免它继续给人"已实现"的错觉：

```kotlin
            // ⚠️【已知缺陷】此处应取 contentBounds，但本实现误用了与 :454 相同的
            //    getBoundsInScreen → contentRect 与 rect 恒等 → 下方 if 恒为 false。
            //    即「内容高度修正」当前**未生效**，sortRecycler 实际只做 top/left 排序。
            //    待确认 AccessibilityNodeInfo 上取内容矩形的正确途径后补齐。
            val contentRect = Rect()
            node.getBoundsInScreen(contentRect)
```

**置信度：确定**（`contentRect.height() != rect.height()` 恒 false 由"同一 API 同一节点"直接可证）

---

### 【16】P2 — `NodePathCollector.skippedCount` 语义错误，诊断数据失真

**文件**：`core/NodePathDescription.kt:443-446`、`:475-481`

**代码原文**

```kotlin
443:    /** 跳过次数（被防抖丢弃） */
444:    @Volatile
445:    var skippedCount = 0L
446:        private set
```

```kotlin
475:    fun request(node: android.view.accessibility.AccessibilityNodeInfo?) {
476:        if (node == null) return
477:        skippedCount++                    // ★ 每次请求都 +1，而不是「被丢弃时 +1」
478:        pendingNode = java.lang.ref.WeakReference(node)
479:        handler.removeCallbacks(task)
480:        handler.postDelayed(task, DEBOUNCE_MS)
481:    }
```

**为什么是 bug**

注释（`:443`）说明该计数是「被防抖丢弃的次数」，但实现在 `request()` 入口**无条件自增** —— 统计的是**请求总次数**，与被丢弃次数无关。真正的"丢弃"（`removeCallbacks` 取消掉上一个待执行任务）没有被计数。

`stats()`（`:511`）输出 `"路径采集 $collectCount 次 / 防抖跳过 $skippedCount 次"` —— 这个比值会**误导性能判断**：实际"跳过" ≈ `skippedCount - collectCount`，而不是 `skippedCount`。

**真机后果**：非功能性 —— 但本项目正在做**性能调优**（`docs/性能基线.md`），错误的诊断数据会导致错误的优化决策。

**修复建议**

```kotlin
    fun request(node: android.view.accessibility.AccessibilityNodeInfo?) {
        if (node == null) return
        requestCount++                              // 请求总数
        if (pendingNode != null) skippedCount++     // ★ 只有确实丢弃了待采项才 +1
        pendingNode = java.lang.ref.WeakReference(node)
        handler.removeCallbacks(task)
        handler.postDelayed(task, DEBOUNCE_MS)
    }
```

并相应更新 `stats()`：

```kotlin
    fun stats(): String = "路径采集 $collectCount 次 / 请求 $requestCount 次 / 防抖跳过 $skippedCount 次"
```

**置信度：确定**

---

### 【17】P2 — `NodeRoleHelper` 的 Pager 分支抢占 Grid 判定（存疑）

> ⚠️ 本文件在审计期间被外部修改（335 → 452 行）。以下基于我读到的 452 行版本；若该文件正在被修正，请以最终版为准。

**文件**：`core/NodeRoleHelper.kt:215-225`

**代码原文**

```kotlin
215:        if (hasAction(node, ACTION_PAGE_UP, ACTION_PAGE_DOWN) ||
216:            hasAction(node, ACTION_PAGE_LEFT, ACTION_PAGE_RIGHT)
217:        ) {
218:            return ROLE_PAGER                      // ⑫ 先于 ⑬ 返回
219:        }
220:
221:        // ⑬ collectionInfo
222:        val collection = node.collectionInfo
223:        if (collection != null) {
224:            return if (collection.rowCount <= 1 || collection.columnCount <= 1) ROLE_LIST else ROLE_GRID
225:        }
```

**为什么可疑**

`⑫`（Pager，按 action 判定）排在 `⑬`（按 `collectionInfo` 判 LIST/GRID）**之前**。任何同时「暴露翻页 action」且「有 `collectionInfo`」的节点会被优先判为 `ROLE_PAGER`，**永远拿不到 `ROLE_GRID`**。

而 `ROLE_GRID` 被两处关键判定依赖：
- `ShouldFocusNode.W()`（`:910-913`）—— `isTopLevelScrollItem` 的 `role == ROLE_GRID` 分支
- `ShouldFocusNode.isScrollableContainerNode()`（`:981`）—— `l()` 的前置短路

⇒ 这类节点在 `W()`/`isScrollableContainerNode()` 里**判定失配**，滚动态判定整体偏向 `false` → 影响 `m()` 的三层条件（`:641-645`）与 `l()` 的短路（`:734`），最终**改变「谁可聚焦」的结论**。

**修复建议**

先确认天坦 `EventUtils` 中 ⑫ 与 ⑬ 的**真实先后顺序**（这是本次排查无法从代码内部判定的部分，需对照天坦源码）。若确认应让 `collectionInfo` 优先，则交换顺序：

```kotlin
        // ⑬ collectionInfo —— ★ 前提是确认天坦中它先于 Pager 判定
        val collection = node.collectionInfo
        if (collection != null) {
            return if (collection.rowCount <= 1 || collection.columnCount <= 1) ROLE_LIST else ROLE_GRID
        }

        // ⑫ Pager 专用动作
        if (hasAction(node, ACTION_PAGE_UP, ACTION_PAGE_DOWN) ||
            hasAction(node, ACTION_PAGE_LEFT, ACTION_PAGE_RIGHT)
        ) {
            return ROLE_PAGER
        }
```

**若无法确认顺序，请勿改动** —— 改为在日志中记录冲突样本（同时满足两条件的节点），积累真机数据后再决策。

**置信度：存疑**（「Pager 分支会抢在 Grid 之前」是**确定**的代码事实；但「这与天坦不一致」需对照天坦源码才能确认，故不主张修改）

---

### 【18】P2 — `isType15` 与 `findWebViewRoot` 判据不一致

**文件**：`core/ShouldFocusNode.kt:1101-1127`（入口）、`:1161-1170`（出口）

**代码原文**

```kotlin
1101:    private fun isType15(node: AccessibilityNodeInfo): Boolean {
...
1127:        return hasAction(node, ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD)   // ★ 按 action
1128:    }
```

```kotlin
1161:    private fun findWebViewRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
1162:        var cur: AccessibilityNodeInfo? = node
1163:        var depth = 0
1164:        while (cur != null && depth < 10) {
1165:            if (NodeRoleHelper.roleOf(cur) == NodeRoleHelper.ROLE_WEB_VIEW) return cur  // ★ 按 role
1166:            cur = cur.parent
1167:            depth++
1168:        }
1169:        return node                                                                  // ★ 兜底
1170:    }
```

配套的 `NODE_ROLE_MAP`（`NodeRoleHelper.kt:103`）是**精确类名查表**：
```kotlin
        "android.webkit.WebView" to ROLE_WEB_VIEW,
```
且查表点（`NodeRoleHelper.kt:147`）**没有 `classNameExtends` 兜底**：
```kotlin
147:        NODE_ROLE_MAP[cls]?.let { return it }
```

**为什么可疑**

`r()` 的入口用 **action**（1024/2048）判定「这是 WebView 类节点」（`:180-183`），但其中调用的 `findWebViewRoot()` 却用 **`roleOf() == ROLE_WEB_VIEW`** 去找"WebView 根"，而后者要求 `className` **精确等于** `android.webkit.WebView`。

真机上 WebView 的 `className` 常常不是这个字面量（子类、厂商封装、Hybrid 容器等）。两者判据不一致 ⇒ `findWebViewRoot()` 的循环**大概率走完 10 层并返回 `node` 自身**（`:1169`），使 `:181-182` 变成「拿自己判断自己可见性」，与天坦 `A0.b(node)` 的语义（向上找真正的 WebView 根）不符。

**修复建议**

先与天坦 `A0.b()` 核对"WebView 根"的判定依据。若确认应按类名，则应让判据**可覆盖子类**：

```kotlin
    private fun findWebViewRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 10) {
            // ★ 用 contains 覆盖子类/厂商封装，而非精确相等
            val cls = cur.className?.toString().orEmpty()
            if (NodeRoleHelper.roleOf(cur) == NodeRoleHelper.ROLE_WEB_VIEW ||
                cls.contains("WebView") || cls.contains("webkit")
            ) {
                return cur
            }
            cur = cur.parent
            depth++
        }
        return node
    }
```

（注：`isType15` 的注释 `:1116-1126` 明确说明「我原来的实现用 `contains("WebView")` 是错的，应改为 action 判定」—— 那是针对 `:1127` 的修正，**不应**连带影响 `findWebViewRoot` 的类名匹配。两者用途不同：前者判"节点是否有 HTML 遍历能力"，后者判"哪个节点是 WebView 容器"。）

**置信度：很可能**（判据不一致是确定事实；"大概率找不到"取决于真机 className，未运行时验证）

---

### 【19】P2 — `NodeCache.invalidateAll()` 只增代数不清 Map，死条目挤占容量

**文件**：`core/NodeCache.kt:201-207`、`:122-124`、`:233-241`

**代码原文**

```kotlin
201:    fun invalidateAll() {
202:        generation++
203:        if (generation > Int.MAX_VALUE - 1000) {
204:            // 代数溢出保护：真正清一次
205:            hardClear()
206:        }
207:    }
```

```kotlin
233:    private fun trimMap(map: java.util.IdentityHashMap<*, *>) {
234:        val it = map.keys.iterator()
235:        var removeCount = map.size / 2
236:        while (removeCount > 0 && it.hasNext()) {
237:            it.next()
238:            it.remove()
239:            removeCount--
240:        }
241:    }
```

**为什么是 bug**

`invalidateAll()` 是"逻辑失效"（O(1) 只增代数，读取时靠 `it.gen == gen` 判定），设计上是对的。但**旧条目仍物理留在 Map 里**，持续占用容量。

`trimMap` 的淘汰策略是「清掉迭代器顺序上的**前一半**」（`:236`）—— 这是**任意淘汰**，不是 LRU。当容量满触发时，被清掉的很可能是**刚写入的热条目**，而大量**已失效的死条目**（`gen` 不匹配）反而存活。

⇒ **有效缓存容量被死条目严重稀释**，命中率低于设计预期；极端情况下形成"写入 → 立即被淘汰 → 再写入"的抖动。

**真机后果**：非功能性，但会影响**焦点导航的性能**（`shouldFocus` 是最热路径，缓存不命中会反复做数百次 IPC）。

**修复建议**

**方案一（最简单有效）** —— 在 `invalidateAll()` 里同时物理清 Map：

```kotlin
    fun invalidateAll() {
        generation++
        // ★ 同时物理清空：否则死条目持续占用容量，
        //   导致 trimMap 淘汰到热数据（任意淘汰策略的固有缺陷）。
        //   clear() 是 O(n) 但只在窗口/内容变化时调用，可接受。
        childrenCache.clear()
        focusCache.clear()
        textCache.clear()
        filterCache.clear()
        if (generation > Int.MAX_VALUE - 1000) hardClear()
    }
```

**方案二** —— 改进 `trimMap` 为**优先清除过期条目**：

```kotlin
    private fun trimMap(map: IdentityHashMap<AccessibilityNodeInfo, CacheEntry<*>>, gen: Int) {
        val it = map.entries.iterator()
        var removed = 0
        var budget = map.size / 2
        while (it.hasNext() && budget > 0) {
            val e = it.next()
            if (e.value.gen != gen) { it.remove(); removed++; budget-- }   // 先清过期的
        }
        // 还不够就按原策略清一半
        val it2 = map.keys.iterator()
        var need = map.size / 2 - removed
        while (need > 0 && it2.hasNext()) { it2.next(); it2.remove(); need-- }
    }
```

**置信度：确定**（"死条目占容量"确定；性能影响程度未量化）

---

### 【20】P2 — `drainRunnable` 单批消费可能长时间占用主线程

**文件**：`core/EventQueue.kt:169-192`、`:282-288`

**代码原文**

```kotlin
282:        /**
283:         * 单次 drain 最多处理的事件数
284:         *
285:         * 16 是一个折中：既能在事件洪峰时快速消化，
286:         * 又不会长时间霸占主线程（16 条 × 平均 2ms = 32ms，仍在帧预算内）。
287:         */
288:        private const val MAX_DRAIN_PER_TICK = 16
```

**为什么是 bug（性质：性能风险，非逻辑错误）**

注释的估算前提是「每条事件平均 2ms」。但在本项目里，`consumer` 最终走到 `AccessibilityEventHandler.handleEvent()`（`ReaderAccessibilityService.kt:522`），其中包含：
- `composeNode()` → `SpeechCompositor.compose()` + `ReadReplacementEngine.apply()`
- 焦点事件可能触发 `focus.moveFocus()` → **整棵树的遍历**（数百次跨进程 `getChild()`）

单条焦点事件的真实耗时可达**十几到几十毫秒**。16 条连续处理 ⇒ **最坏 200ms+ 主线程阻塞** ⇒ 明显卡顿，甚至触发系统的无障碍服务超时熔断。

**真机后果**：事件洪峰（动态界面、连续快速滑动）时**界面卡顿**，读屏响应变慢。

**修复建议**

改为**按时间预算**而非固定条数，并保留条数上限作为双重护栏：

```kotlin
    /** 单次 drain 的时间预算（8ms ≈ 半帧，保证主线程及时让出） */
    private const val MAX_DRAIN_BUDGET_MS = 8L

    private val drainRunnable = object : Runnable {
        override fun run() {
            val deadline = android.os.SystemClock.uptimeMillis() + MAX_DRAIN_BUDGET_MS
            var processed = 0
            while (processed < MAX_DRAIN_PER_TICK &&
                   android.os.SystemClock.uptimeMillis() < deadline) {
                val event = poll() ?: break
                try {
                    consumer?.invoke(event)
                } catch (e: Throwable) {
                    ReaderLog.e("EventQueue", "consume failed: ${e.message}")
                } finally {
                    recycle(event)
                }
                processed++
            }
            // ...（配合问题 12 的原子重调度）
        }
    }
```

**置信度：很可能是性能问题**（单条事件耗时为估算，未实测；"固定条数上限不反映真实耗时"是确定的）**，建议纳入性能基线实测**

---

## C. 确认没问题但值得记录的项

以下项目经核查**未发现问题**，记录以避免后续重复排查。

### C1. `lateinit var` 全部有赋值（36 处）

对全部 36 个 `lateinit var` 逐个核对了赋值点，**无一处"声明未初始化"**：

| 变量 | 声明 | 赋值 | 判定 |
|---|---|---|---|
| `eventQueue` / `dispatcher` / `nodeCache` / `gestureChain` | `ReaderAccessibilityService.kt:84,88,100,104` | `:244,247,243,341` | ✅ |
| `speech` / `focus` / `haptic` / `sound` / `audioFocus` | `:92,96,133,139,136` | `:306,261,398,400,399` | ✅ |
| `gestureActions` | `:112` | `:457` | ✅ **已修复**（`git log` 显示原为已知 P0） |
| `autoScroll` / `webNavigator` / `imeAdapter` / `textMonitor` | `:142,146,154,150` | `:401,402,403,493` | ✅ |
| `screenRecovery` / `systemState` / `replacement` / `navigation` | `:120,182,128,108` | `:286,408,343,361` | ✅ |
| `voiceScroll` / `continuousReader` / `ttsObserver` / `speechWakeLock` / `eventHandler` | `:116,174,161,168,124` | `:351,419,323,313,499` | ✅ |
| UI 层 12 个（`tvStatus`/`tvLog`/`spinnerEngine`/…） | `ui/*.kt` | `onCreate` 内 `findViewById` | ✅ |

**特别注意**：`gestureActions`（`:112` 声明、`:457` 赋值）是已知的历史 P0（首个手势即抛 `UninitializedPropertyAccessException`），**当前代码已修好**。同时确认它在 3 处被使用（`:720` `isMediaPlaying`、`:734` `onMappedAction`、`:1053` `handleMediaGesture`）**均晚于 `:457` 的赋值**，初始化的**顺序依赖链完整**（`:457` 在 `setupGestureChain()`（`:624`）之前）。

### C2. `EventDispatcher` 的线程安全处理正确

`EventDispatcher` 在**无障碍线程**执行，其跨线程可变状态**全部正确标注了 `@Volatile`**：

```kotlin
52:    @Volatile var filterSelfPackageEvents: Boolean = false
56:    @Volatile var isScreenOn: Boolean = true
71:    @Volatile var fullScreenReadState: (() -> Boolean)? = null
86:    @Volatile private var isWechatSpeaking: Boolean = false
91:    @Volatile private var lastWindowStateChangedTime = 0L
94:    @Volatile private var lastClickedTime = 0L
97:    @Volatile private var lastAccessibilityFocusedTime = 0L
100:    @Volatile private var lastFocusHash = -1
105:    @Volatile var passedCount = 0L
109:    @Volatile var droppedCount = 0L
251:    @Volatile var isEditingText: Boolean = false
```

**该项无问题**，且与问题 7（`AutoScrollManager` 漏标）形成鲜明对比 —— 说明作者具备该意识，问题 7 属**遗漏**而非**认知缺失**。

### C3. `FocusActionHistory` 的容量控制完整

```kotlin
142:        records.addFirst(record)
143:        while (records.size > maxRecords) { records.removeLast() }
...
148:        windowRecords.remove(record.windowId)   // 重新插入以更新 LRU 顺序
149:        windowRecords[record.windowId] = record
150:        while (windowRecords.size > maxWindows) {
151:            val eldest = windowRecords.keys.firstOrNull() ?: break
152:            windowRecords.remove(eldest)
153:        }
```

`records`（`ArrayDeque`，上限 5）与 `windowRecords`（`LinkedHashMap`，上限 10）**均有硬上限且实现了正确淘汰**，与 TalkBack 的 `MAXIMUM_RECORD_QUEUE_SIZE`/`MAXIMUM_WINDOW_MAP_SIZE` 一致。**无无界增长。**

### C4. 其他有界集合（逐个核对通过）

| 集合 | 位置 | 上限机制 | 判定 |
|---|---|---|---|
| `WebElementNavigator.cache` | `WebElementNavigator.kt:36` | `:79-82` `if (size >= maxCacheSize) { clear(); epoch++ }` | ✅ |
| `SpatialTraversal.focusableNodes` | `SpatialTraversal.kt:55` | `:83,94` `size >= MAX_NODES` + `:124` `clear()` | ✅ |
| `SpeechQueue.pending` | `SpeechQueue.kt:102` | `:143-147` `while (pending.size >= capacity) removeFirst()` | ✅ |
| `NodeCache.*` 四个 map | `NodeCache.kt:68,102,143,170` | 各有 capacity + `trimMap`（见问题 19） | ⚠️ 有界但策略待改进 |
| `ReaderLog.memoryLog` | `ReaderLog.kt:99` | `MAX_MEMORY_LINES` 环形 | ✅ |
| `ReaderAccessibilityService.eventLog` | `ReaderAccessibilityService.kt:68` | `:1325-1327` `while (size > MAX_LOG) removeLast()` | ✅ |
| `NavigationManager.filterCache` | `NavigationMode.kt:269` | key 是枚举（固定 26 个），天然有界 | ✅ |
| `CachedTraversalStrategy.flattened` | `TraversalStrategy.kt:216` | `:243` `out.size >= MAX_FLATTEN` (2000) | ✅ |
| `ReadReplacement.packageRules` | `ReadReplacement.kt:81` | key 是包名，受规则条数限制 | ✅ |
| `ShouldFocusNode.pCache` | `ShouldFocusNode.kt:858` | `:555` `>= P_CACHE_CAPACITY` (512) | ⚠️ 见问题 11/19 |
| **`ShouldFocusNode.seenChildren`** | `ShouldFocusNode.kt:669` | **无任何上限、无任何清理** | ❌ **问题 3** |

### C5. 监听器/观察者注册与注销配对完整

| 注册点 | 注销点 | 判定 |
|---|---|---|
| `SystemStateAnnouncer.kt:186,189` `registerReceiver` | `:207` `unregisterReceiver`（`unregister()` 内） | ✅ 且 `onDestroy` 中调用（`ReaderAccessibilityService.kt:1166`） |
| `TtsSettingsObserver.kt:125,128,131` `registerContentObserver` ×3 | `:147,148,149` `unregisterContentObserver` ×3 | ✅ 且 `onDestroy` 中调用（`:1168`） |

**未发现**未配对的广播/ContentObserver 注册。

### C6. `onDestroy()` 中已正确清理的资源

`speech` / `sound` / `autoScroll` / `focus` / `eventQueue` / `nodeCache` / `boundsCache` / `gestureChain` / `systemState` / `continuousReader` / `ttsObserver` / `speechWakeLock` 均已清理，且用 `shutdownSafely` 包装（`:1185-1191`，**捕获异常但记日志**，非静默吞掉）——这是良好实践。**遗漏项见问题 9、10。**

### C7. 无真正"空 catch 块"

用脚本扫描全部 223 个 `catch` 块，**未发现**「`catch` 后紧跟 `}` 无条件返回」的纯空块。所有 catch 块至少 `return <默认值>` 或记日志：

```kotlin
// 典型（可接受）：NavigationMode.kt:368
} catch (e: Throwable) {
    false
}
```

**这是"静默失败"风险的主要来源**（调用方无法区分"没做"与"做失败"），但不是"吞异常"的极端形式。**问题 5（`performGlobalAction`）正是这一类风险的实例化。**

### C8. `EventQueue` 的环形缓冲与事件拷贝逻辑正确

- `:117-125` 用 `AccessibilityEvent.obtain(event)` **拷贝事件**，避免系统回收原对象后读到复用数据 ✅
- `:130-143` 队列满时丢**最旧**（背压）并 `recycle`，指针用位运算取模（要求 `capacity` 为 2 的幂，`:45-49` 已 `require` 校验）✅
- `:267-274` `recycle` 包裹 try/catch（已回收的 event 再次 recycle 会抛异常）✅
- `:227-242` `trimOverflow` 按 `eventTime` 丢弃过期事件 ✅

**唯一问题是调度竞态（问题 12）与单批耗时（问题 20），缓冲逻辑本身正确。**

### C9. `SpeechQueue` 的队列语义正确

- `:123-152` `enqueue` 在**同一把锁**内完成「FLUSH 清队 / 去重 / 容量淘汰 / 入队」✅
- `:143-147` 容量控制丢最旧并置 `cancelled` ✅
- `:169-182` `pollNext` 跳过 `cancelled` 项 ✅
- `:214-238` `clear` 为被取消项触发 `onDone` 回调（不静默）✅

**队列本身逻辑正确**，`notifySpeaking` 的配对问题（问题 13）在 `SpeechEngine` 层。

### C10. `FocusMonitor` 的多级回退链实现正确

`:99-146` 的三级回退（无障碍焦点 → 输入焦点 → 历史编辑框 + 输入法在屏校验）与 TalkBack `AccessibilityFocusMonitor` 语义一致：

```kotlin
133:        val lastEditable = lastEditableFocus
134:        if (lastEditable != null && refresh(lastEditable)) {   // ★ 先验证节点存活
135:            if (isImeWindowOnScreen()) {                        // ★ 再校验输入法在屏
136:                historyFallback++
137:                return lastEditable
```

**`refresh()` 判活 + `isImeWindowOnScreen()` 双条件是正确实现**（对应 TalkBack 关于 WebView 虚拟视图的原注释）。

### C11. `ScrollToPosition` 的参数与容器查找正确

- `:44-60` `findScrollableContainer` 有 `maxDepth` 护栏（防深树拖慢主线程）✅
- `:62-72` `supportsScrollToPosition` 用 `actionList.any { it.id == ACTION_SCROLL_TO_POSITION }` 判定（16908358，值正确）✅
- `:100-104` `scrollToBottom` 用 `Integer.MAX_VALUE` 让容器自行 clamp（与 TalkBack/GridTraversalManager 一致）✅

**注意**：`focus.scrollJump` 的**调用时机**有严重问题（问题 1），但 `ScrollToPosition` 本身正确。

### C12. 常量值抽样核实通过

| 常量 | 声明值 | 核实结论 |
|---|---|---|
| `ACTION_NEXT_HTML_ELEMENT` | `1024`（`ShouldFocusNode.kt:1136`） | ✅ 与 Android SDK 一致 |
| `ACTION_PREVIOUS_HTML_ELEMENT` | `2048`（`:1139`） | ✅ |
| `ACTION_SCROLL_FORWARD` / `BACKWARD` | `4096` / `8192`（`:1151,1152`） | ✅ 与天坦 `isType15` 一致 |
| `ACTION_SCROLL_UP/DOWN/LEFT/RIGHT` | `16908344`~`16908347`（`:1153-1156`） | ✅ |
| `SET_PROGRESS_ACTION` | `16908349`（`:1189`） | ✅ |
| `ACTION_PAGE_UP/DOWN/LEFT/RIGHT` | `16908360`~`16908363`（`NodeRoleHelper.kt:448-451`） | ✅ |
| `ACTION_SCROLL_TO_POSITION` | `16908358`（`ScrollToPosition.kt:33`） | ✅ |
| `WAKE_LOCK_FLAGS` | `SCREEN_BRIGHT_WAKE_LOCK or ON_AFTER_RELEASE`（`SpeechWakeLock.kt:156-157`） | ✅ = 536870922，与天坦字面量一致 |
| `NavigationMode` 的 `typeCode`/`traversalType` 分离 | `:128-132` | ✅ 注释明确警告了混用陷阱（`WORDS(2)` 撞 `TYPE_CACHED(2)`），实现已分离 |

### C13. `NavigationMode.switchable` 的 `implemented` 过滤正确

`:171` `values().filter { it.implemented }` —— 未实现的导航被排除在切换循环外，避免"切到空实现导致卡住"。`setMode` 也对未实现项**给出用户反馈**（`:296-301` 通过 `onUnimplemented` 回调播报"尚未实现"+ 震动），**不是静默拒绝** ✅

**但**：`SCROLL_TOP`/`SCROLL_BOTTOM` 的 `implemented` 默认 `true` ⇒ 在切换列表里 ⇒ **这是问题 1 能被触发的直接前提**。

---

## D. 排查覆盖度说明

### D1. 已逐行精读的文件（P0/P1 重点）

| 文件 | 行数 | 覆盖面 |
|---|---|---|
| `FocusManager.kt` | 863 | **全文精读** |
| `ShouldFocusNode.kt` | 1190 | **全文精读**（含 850 行之后的全部辅助函数） |
| `TatansTraversal.kt` | 707 | **全文精读** |
| `NodeOrderComparator.kt` | 548 | **全文精读** |
| `NodeRoleHelper.kt` | 452 | 精读 `:1-245`（`roleOf` 全链）+ 常量表；⚠️ 文件被并发修改 |
| `ReaderAccessibilityService.kt` | 1524 | **全文精读**（分 3 段） |
| `EventQueue.kt` | 290 | **全文精读** |
| `EventDispatcher.kt` | 402 | 精读 `:40-120`（状态字段）+ 关键方法 |
| `SpeechEngine.kt` | 625 | **全文精读** |
| `SpeechQueue.kt` | 241 | **全文精读** |
| `SpeechWakeLock.kt` | 159 | **全文精读** |
| `FocusMonitor.kt` | 247 | **全文精读** |
| `NodeCache.kt` | 248 | **全文精读** |
| `AccessibilityEventHandler.kt` | 809 | 精读 `:1-290`（状态 + 焦点记录 + 无障碍焦点处理） |
| `NodePathDescription.kt` | 514 | 精读 `:405-514`（`NodePathCollector` 全文） |
| `TraversalStrategy.kt` | 543 | 精读 `:50-249`（三种策略 + 缓存展开） |
| `GestureActionHandler.kt` | 250 | 精读 `:1-135`（含 `handleMappedGesture` 全表） |
| `GestureChain.kt` | 315 | 精读 `:60-180`（`handle` 分发逻辑） |
| `FocusActionHistory.kt` | 273 | 精读 `:100-273` |
| `AutoScrollManager.kt` | 565 | 精读状态字段 + `onScrollEvent`/`stop`/`tryFocusAfterScroll`/`findScrollableAncestor` |
| `NavigationMode.kt` | 415 | **全文精读**（枚举 + `NavigationManager`） |
| `ScrollToPosition.kt` | ~120 | **全文精读** |
| `NodeBoundsComparator.kt` | 206 | 精读 `:1-80`（`compare` 签名 + 阈值） |

**小计：23 个文件、约 12000 行逐行精读**（占 core 总量约 50%），覆盖了全部 P0 与 P1 所在文件。

### D2. 采用"模式扫描 + 抽样核实"的文件

其余约 40 个 core 文件按其风险特征做了针对性扫描（未逐行精读）：

- **全部 74 个 .kt 文件**都做了「注释剥离副本」上的机械扫描，因此**语法层面的模式缺陷是全项目覆盖的**。
- 对每个文件核对了：`lateinit` 赋值、集合容量上限、监听注册/注销配对、`catch` 块内容、`@Volatile` 标注、跨线程可达性。

**扫描方法说明（可复现）**：本项目大量注释中**包含反编译源码原文**（如 `ShouldFocusNode.kt:288` 的 `return AbstractC3688i.k(wVar) && !k(wVar);`），直接在源码上 grep 会产生大量假阳性。为此先用 Python 脚本生成**保留行号的注释剥离副本**（`/tmp/audit/clean/`，已验证每个文件行数与原文**完全一致**），再在其上执行模式匹配。这保证了「已知 P0 的 `k(node) && !k(node)`」不会因为出现在注释里而被误报。

### D3. 执行的机械扫描及其结果

| 扫描项 | 命令 | 结果 |
|---|---|---|
| 恒真/恒假 `k(x) && !k(x)` | `grep -rnE '\b(\w+)\([^()]*\)\s*(&&\|\|)\s*!\s*\1\('` | **0 命中**（仅在注释中有，已修） |
| `!x && x` / `x && !x` | `grep -rnE '!\s*([\w.]+)\s*(&&\|\|)\s*\1\b'` | **0 命中** |
| `x == x` / `x != x` | `grep -rnE '\b([\w.]+)\s*(==\|!=)\s*\1\b'` | 8 命中，**全部为合法代码**（`it.name == name`、`it.gen == gen`、`focus.windowId == windowId`、`record.textFingerprint == textFingerprint`） |
| `return true &&` / `false \|\|` | `grep -rnE 'return (true\s*&&\|false\s*\|\|)'` | **0 命中** |
| `if (true)` / `if (false)` | `grep -rnE 'if\s*\(\s*(true\|false)\s*\)'` | **0 命中** |
| `\|\| true` / `&& false` | `grep -rnE '(\|\|\s*(true\|false)\b\|&&\s*(true\|false)\b)'` | **0 命中** |
| `lateinit` 无赋值 | 逐个变量核对赋值点 | **0 命中**（见 C1） |
| 空 catch 块 | awk 脚本检测 `catch` 后紧跟 `}` | **0 命中**（见 C7） |
| 集合容量上限 | 逐个集合字段核对 | 1 处异常（`seenChildren`，问题 3），其余有界（见 C4） |
| 监听注册/注销配对 | `grep register*` vs `unregister*` | **全部配对**（见 C5） |
| 跨进程 API 热路径 | 统计 `getChild`/`parent`/`actionList`/`isVisibleToUser` 密度 | `ShouldFocusNode` 26 处、`TatansTraversal` 29 处（均为判定核心，必要开销；已由 `NodeCache` 缓解放大） |

### D4. 未覆盖 / 覆盖不足的部分（如实说明）

| 范围 | 状态 | 原因 |
|---|---|---|
| `ui/MainActivity.kt`（244 行） | **未精读** | 仅核对了 `lateinit` 视图绑定与 `onDestroy`，未逐行审查 UI 逻辑 |
| `ui/TtsSettingsActivity.kt`（268 行） | **部分** | 核对了 `previewTts` 的创建/销毁配对（`:53,68,258-261`，**正确**），未审查其余 UI 逻辑 |
| `ui/GestureSettingsActivity.kt`（336 行） | **未精读** | 仅核对 `lateinit listGestures` 绑定 |
| `ReaderLog.kt`（769 行） / `LogCore.kt`（590 行） | **未精读** | 属诊断设施，非读屏核心路径；仅核对了 `catch` 块与集合上限 |
| `TextDiff.kt`（411 行） | **部分** | 仅核对 `NodeTextMonitor`（`:344+`）的监听/清理与 `TextDiffEngine` 的集合使用 |
| `ContentChangeFilter.kt` / `WindowClassifier.kt` / `ScreenStateRecovery.kt` / `PassThroughMode.kt` / `GranularityNavigator.kt` / `SpeechCompositor.kt` / `ReadReplacement.kt` / `NodeFilter.kt` / `NodeBoundsCalculator.kt` 等约 25 个文件 | **模式扫描** | 已通过机械扫描排除语法层缺陷；**未逐行核对天坦移植忠实度**（那是另一类工作，需对照天坦源码） |
| **真机行为验证** | **完全未做** | 本次为**静态审计**。所有"真机后果"均为**基于代码路径的推断**，未经实际设备验证 |
| **对照天坦原版核对移植忠实度** | **未做** | 本次重点在"能编译能过测试但真机出问题"的**缺陷**，而非逐行比对移植保真度。问题 17、18 明确标注为**存疑**正因如此 |

### D5. 建议的后续验证顺序

按「先修最痛、再验证」排列：

1. **问题 1**（`scrollJump` 粘滞）—— **改 3 行即可验证**：修复后左右滑动应立即恢复。这是最可能直接解决用户当前抱怨的一条。
2. **问题 2**（`z9` 恒 false）—— 需先与天坦 `C3678d.r()` 的调用约定核对，确定是"应删死代码"还是"应传 true"。
3. **问题 3**（`seenChildren` 不清）—— 加一行 `clear()` 即可，且无副作用（与天坦语义一致）。
4. **问题 4**（`cacheGeneration` 回写）—— 删一行即可。
5. **问题 5**（`performGlobalAction` 未注入）—— 加一行注入，用户可立刻感知返回键是否恢复。
6. **问题 6/7/8**（线程安全）—— 需真机压力测试（快速滚动 + 动态界面）配合日志观察。

### D6. 对「本类缺陷为何能通过编译与测试」的观察

本次 20 项中有 **6 项属于"能编译、能过单测，但真机必然/很可能出问题"**（问题 1、2、3、4、5、12），它们的共同特征是：

- **逻辑自洽但语义错误**：`k(node) && !hasChildContent(node)` 语法合法；`z9` 恒 false 不违反类型系统；`scrollJump` 粘滞不触发任何警告。
- **单测无法触及真实时序与跨进程行为**：`Rect`/`AccessibilityNodeInfo` 在 JVM 测试中是空壳（`NodeBoundsComparator.kt:10-12` 的注释已明确承认这一点），因此问题 6/7/8/15 这类依赖真机对象与线程调度的缺陷**在单测中不可见**。
- **注释描述正确、代码抄漏**：问题 3（`seenChildren`）的注释**自己写明了**天坦会每轮 `clear()`，但代码没抄；问题 15（`contentRect`）的注释写明了应取 `contentBounds`，但代码用了同一个 API；问题 14（`i13`）的注释写明了变量用途，但代码只赋 0。

**建议**：针对上述第三类，做一次**「注释声明 vs 代码实现」的逐条核对** —— 凡注释里出现「天坦用 X」「我原先抄成 Y」的地方，都应当有一个断言或测试来锁定，否则注释会长期掩盖代码缺口。这是比再修几个具体 bug 更根本的改进。

---

## 附：高危结论的复核证据摘要

为保证可复核，以下三条最关键结论给出**可直接重放的核实命令**：

**结论 1（问题 1）—— `scrollJump` 钩子无撤销点、方向设置仅 2 处：**
```bash
cd /home/baicha-reader
grep -c "focus.scrollJump = null" app/src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt
# 输出 0 —— 说明钩子注入后永不撤销
grep -n "setScrollJumpDirection" app/src/main/java/com/baicha/reader/core/ReaderAccessibilityService.kt
# 输出 :1000 :1001 —— 仅在 SCROLL_TOP / SCROLL_BOTTOM 时设置，无 else 复位
sed -n '347,349p' app/src/main/java/com/baicha/reader/core/FocusManager.kt
# 输出 moveFocusInternal 开头无条件触发 scrollJump 的三行
```

**结论 2（问题 2）—— `z9` 恒为 false：**
```bash
cd /home/baicha-reader
grep -n "r(" app/src/main/java/com/baicha/reader/core/ShouldFocusNode.kt | grep -v "^\s*[0-9]*: *//"
# 非注释调用仅 :166 与 :168，均传 false
```

**结论 3（问题 5）—— `performGlobalAction` 无赋值点：**
```bash
cd /home/baicha-reader
grep -rn "performGlobalAction" app/src/main/java/ app/src/test/java/
# 仅 2 处：声明(:64) 与 读取(:128)，无任何赋值
```

---

*报告结束。本次审计未修改任何代码。*

---

## 附二：行号漂移说明（并发编辑导致）

审计结束时有另一个进程在同时修改本仓库，导致**部分行号发生漂移**。若按本报告的行号在源码中定位不到，请用**行内代码特征**检索（每个问题详情都贴了原文，且大多给出了 `grep` 复核命令）。

截至报告定稿时的漂移情况：

| 文件 | 漂移 | 对应问题 | 当前定位方式 |
|---|---|---|---|
| `TatansTraversal.kt` | `:602` → **`:605`**（另一进程在此之前新增了 3 行注释） | 问题 4 | `grep -n "Companion.cacheGeneration = generation"` |
| `NodeOrderComparator.kt` | `:262` → **`:290`**；`:323` → **`:351`**；`:330` → **`:358`** | 问题 14 | `grep -n "i13"` |
| `NodeOrderComparator.kt` | `:466` → **`:494`** | 问题 15 | `grep -n "contentRect.height() != rect.height()"` |
| `NodeRoleHelper.kt` | 审计期间被从 335 行改到 452 行 | 问题 17 | 见该节行内标注 |
| `ShouldFocusNode.kt` | **无漂移**（mtime 18:56，早于本审计） | 问题 2、3、8、11、18 | 行号可直接使用 |
| `FocusManager.kt` | **无漂移**（mtime 18:04） | 问题 1 | 行号可直接使用 |
| `ReaderAccessibilityService.kt` | **无漂移** | 问题 1、5、9、10 | 行号可直接使用 |
| `EventQueue.kt` / `NodeCache.kt` / `SpeechEngine.kt` / `GestureActionHandler.kt` | **无漂移** | 问题 5、6、12、13、19、20 | 行号可直接使用 |

**已复核：漂移仅为行号位置变化，上述问题的代码内容截至目前仍然存在，未被修复。**

### 已确认被其他进程修复的历史问题（无需再改）

| 原问题 | 状态 |
|---|---|
| `gestureActions` 声明 `lateinit` 却从未赋值 | ✅ 已在 `ReaderAccessibilityService.kt:457` 修复 |
| `k(node) && !k(node)` 恒假 | ✅ 已改为 `k(node) && !hasChildContent(node)`（但该行位于**不可达分支**，见问题 2） |
| `TatansTraversal.o()` 取父节点缺少「窗口根回退」 | 🔧 报告定稿时另一进程正在修复（`git diff` 可见），与本报告的**问题 4 不是同一处** |

---

*报告结束（含附一、附二）。本次审计未修改任何代码。*
