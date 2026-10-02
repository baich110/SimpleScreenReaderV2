package com.baicha.reader.core

/**
 * 测试用最小 Context 替身
 *
 * 【为什么需要】多个测试类需要构造依赖 Context 的组件，
 * 但这些组件在 JVM 里不会真正访问系统服务（`getSystemService`
 * 返回 null 时它们都有 null 保护）。
 *
 * 抽成共享类避免每个测试文件各写一份导致重复声明。
 *
 * 【注意】它不是 mock 框架的替代品 —— 只用于「构造参数占位」，
 * 任何真正调用 Context 方法（如 `contentResolver`）的路径
 * 仍会失败，那类路径本就无法在 JVM 覆盖。
 */
class NoopContext : android.content.ContextWrapper(null)
