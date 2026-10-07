# 白茶读屏 v2 —— 项目交接文档

> 写给下一个接手这个项目的 AI / 开发者。**先读完这一份，再动代码。**
> 最后更新：2026-10-07

---

## 一、这是什么

**白茶读屏（baicha-reader）** —— 一个从零自研的 Android 无障碍读屏服务，目标是做一个能替代天坦/TalkBack 的读屏。

- **包名**：`com.baicha.reader`
- **服务类**：`com.baicha.reader.core.ReaderAccessibilityService`
- **项目路径**：`/home/baicha-reader/`（proot Ubuntu 环境）
- **当前版本**：v18（最后部署 2026-10-03）
- **规模**：172 个 Kotlin 文件 / 52485 行
- **状态**：⚠️ **已暂停**（用户主动停用，见第四节）

---

## 二、当前状态（重要）

### 2.1 项目已暂停

2026-10-03，用户实测后决定停用：

> 「把白茶先停用了吧，我不想玩了。等我哪天把 TalkBack 编辑一下，做自做自己的功能框架，就就用 TalkBack 的，太难了」

**已执行**：
```bash
settings put secure enabled_accessibility_services "com.android.tback/net.tatans.soundback.SoundBackService"
am force-stop com.baicha.reader
```

**当前系统无障碍服务**：只有天坦（`com.android.tback`），白茶已停用。

### 2.2 下一步路线（用户已定）

**放弃自建 `com.baicha.reader`，转向直接修改 TalkBack 源码做自定义框架。**

TalkBack 源码位置：`/home/talkback/`
- 关键路径：`/home/talkback/utils/src/main/java/com/google/android/accessibility/utils/traversal/`
- 判定逻辑：`/home/talkback/utils/src/main/java/com/google/android/accessibility/utils/AccessibilityNodeInfoUtils.java`

---

## 三、项目结构

```
/home/baicha-reader/
├── app/src/main/java/com/baicha/reader/
│   ├── core/        # 核心框架（70+ 个 Kotlin 文件）
│   └── ui/          # 设置界面（MainActivity / TtsSettingsActivity / GestureSettingsActivity）
├── app/src/test/    # 单元测试（739 个测试，全绿）
├── docs/            # 26 份工作文档（审查报告、核查记录、待办总表）
├── HANDOVER.md      # 本文件
└── 踩坑记录/         # 从记忆库导出的经验教训
```

### 3.1 core 分层

| 层 | 文件 |
|---|---|
| **入口** | `ReaderAccessibilityService.kt`（73KB，主服务） |
| **事件链路** | `EventDispatcher.kt` / `EventQueue.kt`（环形缓冲，零 GC） |
| **焦点遍历** | `FocusManager.kt`(53KB) / `FocusSearch.kt` / `TraversalStrategy.kt` / `SpatialTraversal.kt` / `TatansTraversal.kt` / `TalkBackTraversal.kt` / `ShouldFocusNode.kt`(117KB) / `TalkBackShouldFocus.kt` / `NodeOrderComparator.kt` |
| **导航** | `NavigationMode.kt`（27 种模式）/ `GranularityNavigator.kt` |
| **朗读** | `SpeechCompositor.kt` / `SpeechEngine.kt` / `SpeechQueue.kt` / `ReadReplacement.kt` / `TtsEngineManager.kt` |
| **手势** | `GestureChain.kt` / `GestureActionHandler.kt` / `Gestures.kt` |
| **支撑** | `NodeCache.kt` / `TextDiff.kt` / `AutoScrollManager.kt` / `AudioFocusManager.kt` / `SoundManager.kt` / `HapticFeedback.kt` |

---

## 四、核心教训（最重要的一节）

### 4.1 四大根因（反复踩的坑）

| 坑 | 本质 | 正确做法 |
|---|---|---|
| **`IdentityHashMap` 做节点映射** | `AccessibilityNodeInfo` 每次查询返回**新对象**，identity 比较 100% 失效 | 用 `HashMap`（内容 equals）。对齐 TalkBack 的 `OrderedTraversalController.nodeTreeMap` |
| **`searchFocus` 只走一步** | 遍历器返回的是**未过滤**节点，第一步可能不可聚焦 | 照抄 TalkBack `TraversalStrategyUtils.searchFocus()` 的 `do-while` + `seenNodes` 防环 |
| **播报去重用 `identityHashCode`** | 同一节点不同对象，去重失效 → 一会儿播一会儿不播 | 用**内容指纹**（`NodePath.fingerprintOf`） |
| **抄天坦的私有补丁** | 天坦 `shouldFocusNode` 2000+ 行是**特定版本的补丁**，脱离上下文抄必错 | 抄 TalkBack 官方 `AccessibilityNodeInfoUtils.shouldFocusNode()` 七步链 |

### 4.2 最核心的结论

> **TalkBack 的组件不能拆一个塞进自己的项目。**
> `Compositor` / `OrderedTraversalController` / `AccessibilityNodeInfoUtils` 是**一整套互相咬合的设计**，拆开用必崩。

**这就是项目暂停的根本原因** —— 抄了一半 + 自己发明，导致问题永远修不完。

### 4.3 其他踩过的坑

1. **`pm install -r` 不杀进程** —— 必须 `am force-stop` 才会跑新代码
2. **`ScreenStateRecovery` 与 `applyFocus` 抢焦点** —— 无防回声机制会死循环，音效狂响
3. **`NodeFilter.CachedFilter` 污染 `focusCache`** —— 组合过滤器的结果会覆盖 `ShouldFocusNode` 的判定
4. **Activity 属性初始化器用 `this` 当 Context** —— attach() 前执行，base context 为 null → NPE，必须 `by lazy`
5. **`isVisibleToUser` 不能作为焦点判定依据** —— 滚动/动画中会返回 false，但焦点已设上

---

## 五、编译与部署

### 5.1 编译环境

```bash
ANDROID_HOME=/opt/android-sdk
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
```

**⚠️ ARM64 关键坑**：官方 build-tools 自带的 `aapt2` 是 x86_64，不可用。
必须用 `/opt/aapt2-arm64/aapt2`，`gradle.properties` 里配：
```
android.aapt2FromMavenOverride=/opt/aapt2-arm64/aapt2
```

### 5.2 编译

```bash
cd /home/baicha-reader
nohup bash gradlew assembleDebug --no-daemon > /tmp/build.log 2>&1 &
# 约 90-100 秒
tail -20 /tmp/build.log
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

### 5.3 装 APK（铁律）

**禁用 `system_tools:install_app` 直装 `/sdcard`**（system_server 读不了 FUSE 视图，必报错）。

正确流程：
```bash
# 1. 编译产物拷到 Download（proot 可直接写）
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/baicha-reader-vXX.apk

# 2. shell 侧：拷到 /data/local/tmp 再装
cp /sdcard/Download/baicha-reader-vXX.apk /data/local/tmp/
pm install -r /data/local/tmp/baicha-reader-vXX.apk

# 3. 必须 force-stop 才会跑新代码
am force-stop com.baicha.reader
settings put secure enabled_accessibility_services "com.baicha.reader/com.baicha.reader.core.ReaderAccessibilityService"
```

**⚠️ 副作用**：手动 enable 白茶会顶掉天坦，验证完记得切回：
```bash
settings put secure enabled_accessibility_services "com.android.tback/net.tatans.soundback.SoundBackService"
```

### 5.4 日志

```bash
# 应用日志文件
/sdcard/Android/data/com.baicha.reader/files/logs/baicha-reader.log

# 关键 grep
grep -E '✅|❌|searchFocus|建树|播报' /sdcard/Android/data/com.baicha.reader/files/logs/baicha-reader.log | tail -30
```

---

## 六、docs/ 目录说明

| 文件 | 内容 |
|---|---|
| `验证债台账.md` | ⭐ **最重要**，47KB，所有待验证项 + 已知问题台账 |
| `待办总表.md` | 30KB，全部待办 + 已完成记录 |
| `全项目Bug排查.md` | 99KB，全项目 bug 排查记录 |
| `遍历排序忠实度核查.md` | 73KB，遍历算法与 TalkBack 的逐项对比 |
| `焦点判定忠实度核查-第三轮.md` | 38KB，shouldFocus 判定链对比 |
| `焦点框架对标与缺失清单-2026-10-02.md` | 39KB，与 TalkBack 的完整差异清单 |
| `框架架构总览.md` | 14KB，架构说明 |
| `日志系统设计.md` | 26KB，日志系统设计 |
| `Bug报告-2026-10-02.md` | 22KB，P0/P1 分级 bug 清单 |

**读的顺序建议**：`框架架构总览` → `验证债台账` → `待办总表` → 按需查其他。

---

## 七、Git 历史

130 个 commit，完整记录了从 v1 到 v18 的演进。关键节点：

```
0ed91dd  feat(focus): 遍历器改 TalkBack 模型 + shouldFocus 1:1 移植 + 播报去重改内容指纹  ← 最后
f898517  fix(focus): 用天坦参考实现在真机 dump 上做逐节点对比，修 4 处判定偏差
ed58d72  fix(focus): 三方对照 TalkBack/天坦，修复「有子无内容容器被放行」P0
b9ccd3c  P0 真机根因定位并修复：左右滑动找不到焦点（可见性兜底缺失）
094aa6e  P0 真机验证：服务启动即崩溃（lateinit 时序）—— 修复 + 静态防线
```

---

## 八、给下一个 AI 的建议

1. **别急着改代码**。先读 `docs/框架架构总览.md` + `docs/验证债台账.md`，理解现状。
2. **别信"抄一半"**。要抄就抄完整的、配套的。TalkBack 的组件必须整套用。
3. **实测 > 推理**。用户明确要求：出问题就用真机日志/curl 验证，别靠猜。
4. **用户偏好**：简短、直白、不 AI 腔。方案定了直接执行，别反复确认。
5. **如果继续这条路线**：先解决"焦点生命周期管理"（`ScreenStateRecovery` ↔ `applyFocus` 抢焦点），这是所有焦点问题的根源。
6. **如果转 TalkBack 路线**：`/home/talkback/` 已有完整源码，直接改。

---

## 九、用户背景（协作须知）

- 23 岁视障开发者（盲人），用**天坦读屏**日常操作
- 沟通风格：**简短、硬核、直白、不 AI 腔**，烦躁时会爆粗口，别介意
- 核心诉求：**要能真正跑通的读屏**，不是"看起来对"的代码
- 讨厌：啰嗦、空话、套话、反复确认、发散式排查
- 喜欢：一针见血、直接给结果、实测验证

---

*本文档由 AI 生成于 2026-10-07，基于 `/home/baicha-reader/` 项目现状与记忆库导出内容。*
