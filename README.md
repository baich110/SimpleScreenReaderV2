# SimpleScreenReaderV2

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=for-the-badge)](LICENSE)
[![Language](https://img.shields.io/badge/Java-Android%2014-orange.svg?style=for-the-badge)](#)
[![Platform](https://img.shields.io/badge/Min%20SDK-Android%205.0-green.svg?style=for-the-badge)](#)

> 一份**完整开源、可独立运行、按 Google TalkBack 架构思路重写**的 Android 读屏服务参考实现。中文社区少见的非 TalkBack 系列开源读屏方案，13 个 Interpreter 全部开源，可作为研究 Android 无障碍 / a11y 服务架构的范例。

## 项目定位

- 🎯 **学习 / 研究**：完整复刻 Google TalkBack 的 Pipeline 架构（Monitors → Interpreters → Mappers → Actors → Feedback），便于阅读、调试、二次开发
- 🔧 **可定制**：模块解耦，可单独替换 TTS、震动、声音等任一 Actor，或新增自定义 Interpreter
- 🚀 **高性能**：事件去重、延迟处理、缓存机制、快速分发，针对实际场景做了多处性能优化
- 📖 **文档完整**：每个 Interpreter / Actor 职责清晰，README 列出全部 13 类事件解释器与 4 类反馈执行器

## 核心特性

### Pipeline流水线架构
本项目完整抄写了Google TalkBack的Pipeline流水线架构，这是TalkBack的核心设计：

```
AccessibilityEvent
       ↓
Monitors（监控阶段）
       ↓
Interpreters（13个解释器）★ 核心精华
       ↓
Mappers（映射阶段）
       ↓
Actors（执行阶段）
       ↓
   Feedback（带failover的反馈系统）
```

### 13个解释器完整实现
1. **InputFocusInterpreter** - 输入焦点监控
2. **AccessibilityFocusInterpreter** - 无障碍焦点解释器
3. **TouchExplorationInterpreter** - 触摸探索解释器
4. **ScrollEventInterpreter** - 滚动事件解释器
5. **DirectionNavigationInterpreter** - 方向导航解释器
6. **SelectionEventInterpreter** - 选择事件解释器
7. **StateChangeEventInterpreter** - 状态变化解释器
8. **SubtreeChangeEventInterpreter** - 子树变化解释器
9. **ContinuousReadInterpreter** - 连续阅读解释器
10. **HintEventInterpreter** - 提示事件解释器
11. **AutoScrollInterpreter** - 自动滚动解释器
12. **IdleInterpreter** - 空闲检测解释器
13. **UiChangeEventInterpreter** - UI变化解释器

### 高性能设计要点
1. **事件过滤** - 避免重复事件进入Pipeline
2. **延迟处理** - 对特殊情况使用延迟处理避免事件冲突
3. **缓存机制** - 滚动位置缓存、焦点状态缓存
4. **快速分发** - 基于事件类型的switch快速分发
5. **窗口稳定性检查** - 避免在窗口不稳定时处理焦点

### 反馈系统（带Failover机制）
- **SpeechActor** - 语音朗读执行器（TTS）
- **VibrationActor** - 震动反馈执行器
- **SoundActor** - 音效反馈执行器
- **FocusActor** - 焦点控制执行器

## 项目结构

```
SimpleScreenReaderV2/
├── app/src/main/java/com/example/simplereader/
│   ├── SimpleScreenReaderService.java  # 主服务类
│   ├── MainActivity.java               # 设置界面
│   └── pipeline/
│       ├── Pipeline.java              # 流水线核心
│       ├── Interpretation.java        # 12种解释类型
│       ├── Feedback.java              # 反馈系统
│       ├── FeedbackController.java     # 反馈控制器
│       ├── Performance.java          # 性能监控
│       ├── actors/                    # 执行器
│       │   ├── SpeechActor.java
│       │   ├── VibrationActor.java
│       │   ├── SoundActor.java
│       │   └── FocusActor.java
│       └── interpreters/              # 13个解释器
│           ├── AccessibilityEventInterpreter.java
│           ├── InputFocusInterpreter.java
│           ├── AccessibilityFocusInterpreter.java
│           └── ...
└── build.gradle
```

## 技术栈

- **语言**: Java
- **最低SDK**: Android 5.0 (API 21)
- **目标SDK**: Android 14 (API 34)
- **架构**: Pipeline流水线（参考TalkBack）

## 编译

```bash
./gradlew assembleDebug
```

## 功能

- ✅ 屏幕内容朗读
- ✅ 触摸探索
- ✅ 焦点管理
- ✅ 滚动导航
- ✅ 震动反馈
- ✅ 音效反馈
- ✅ 可配置设置界面

## License

Apache License 2.0
