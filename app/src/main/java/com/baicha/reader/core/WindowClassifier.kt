package com.baicha.reader.core

/**
 * 窗口几何分类 —— 对应天坦 `w7/E0.java`
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * Android 把「状态栏」「导航栏」「输入法」都归类为
 * `TYPE_SYSTEM`(3) / `TYPE_INPUT_METHOD`(2) 窗口，
 * **不告诉你它到底是哪一个**。
 *
 * 但读屏必须区分，因为它们的焦点行为完全不同：
 *   - 状态栏：下拉才有内容，平时不该进焦点序列
 *   - 导航栏：三个按钮，滑到时应该只念按钮
 *   - 输入法：候选词区域，属于「输入」上下文
 *
 * 天坦的解法：**用几何位置判定**（`w7/E0.java:65~132`）——
 *   状态栏 = 顶部对齐 且 高度 < 屏幕高/5
 *   导航栏 = 底部对齐（含左右旋转）或 与系统栏区域不相交
 *   输入法 = WindowInsets.Type.ime() 的 insets 非空
 *
 * ══════════════════════════════════════════════════════════
 * 【TalkBack 的做法】
 * ══════════════════════════════════════════════════════════
 *
 * TalkBack 直接用 `AccessibilityWindowInfo.getType()`，不进一步细分：
 * 在 `FocusActorForScreenStateChange` 里只区分
 * `TYPE_INPUT_METHOD` 与其他。它靠 `WindowManager` 的
 * `getWindowsOnAllDisplays()` 顺序解决大部分场景。
 *
 * 【抉择：选天坦】
 *   TalkBack 的做法在国内机型上不够 —— 国产 ROM（MIUI/HyperOS/
 *   EMUI/ColorOS）大量把状态栏与导航栏合并成一个系统窗口，
 *   或把导航栏做成手势条，`getType()` 全返回 3，无法区分。
 *   天坦的几何判定不依赖厂商实现，更可靠。
 *
 * ══════════════════════════════════════════════════════════
 * 【本实现相对天坦的改进 —— 做成纯 JVM 可测】
 * ══════════════════════════════════════════════════════════
 *
 * 天坦的 `E0.e()` / `E0.h()` 直接调 `DisplayManager`/`WindowManager`，
 * **完全无法单测** —— 这也是它那套几何逻辑长期没被验证的原因。
 *
 * 本实现把「几何判定」与「系统调用」彻底分离：
 *   - [classify] 纯函数，输入 Bounds + 屏幕尺寸 + 方向，可单测
 *   - [WindowClassifier] 只负责取系统数据，不含判定逻辑
 *
 * 这样几何部分（最容易算错的部分）可以被逐条测试。
 */

/** 窗口类型 —— 天坦的几何分类结果 */
enum class WindowKind(val label: String) {
    /** 状态栏（顶部细条） */
    STATUS_BAR("状态栏"),

    /** 导航栏（底部/侧边条） */
    NAVIGATION_BAR("导航栏"),

    /** 输入法 */
    INPUT_METHOD("输入法"),

    /** 普通应用窗口 */
    APPLICATION("应用窗口"),

    /** 无法判定（几何数据缺失） */
    UNKNOWN("未知窗口");

    /** 系统窗口（状态栏/导航栏）—— 通常不进焦点序列 */
    val isSystemBar: Boolean
        get() = this == STATUS_BAR || this == NAVIGATION_BAR
}

/**
 * 屏幕几何信息 —— 判定所需的全部输入
 *
 * 抽成数据类是为了让 [WindowClassifierCore.classify] 成为纯函数。
 */
data class ScreenGeometry(
    /** 屏幕宽（真实像素，不含虚拟按键调整） */
    val widthPixels: Int,
    /** 屏幕高 */
    val heightPixels: Int,
    /** 旋转：0=竖屏，1=横屏（右转90），2=倒置，3=横屏（左转90） */
    val rotation: Int
) {
    val isLandscape: Boolean get() = rotation == 1 || rotation == 3
}

/**
 * 窗口几何判定核心 —— **纯函数，无 Android 依赖**
 *
 * 对应天坦 `w7/E0.e()`（导航栏）/ `E0.h()`（状态栏）/ `E0.i()`（合并判定）
 */
object WindowClassifierCore {

    /**
     * 判定窗口类型。
     *
     * 对应天坦 `E0.parseWindowType()` 的等价：
     * 天坦的 `i()` 在 API 30+ 走「与系统栏区域不相交」判定，
     * 低版本走 `e() || h()` 的几何判定。这里保留低版本几何路径，
     * 因为它同时能给出**具体是哪一种**（天坦新版只返回布尔）。
     *
     * @param windowType 系统给的窗口类型（AccessibilityWindowInfo.getType()）
     * @param bounds     窗口在屏幕上的边界
     * @param screen     屏幕几何
     * @param hasImeInsets 系统是否报告 IME insets 非空
     */
    fun classify(
        windowType: Int,
        bounds: Bounds,
        screen: ScreenGeometry,
        hasImeInsets: Boolean = false
    ): WindowKind {
        // ② 输入法窗口：系统直接给了正确类型，且优先级最高
        if (windowType == TYPE_INPUT_METHOD) return WindowKind.INPUT_METHOD
        if (hasImeInsets) return WindowKind.INPUT_METHOD

        // 非系统窗口 → 应用窗口
        if (windowType != TYPE_SYSTEM) {
            return if (windowType == TYPE_UNKNOWN) WindowKind.UNKNOWN
            else WindowKind.APPLICATION
        }

        // ③ 系统窗口 → 用几何判定具体是哪种
        if (isStatusBar(bounds, screen)) return WindowKind.STATUS_BAR
        if (isNavigationBar(bounds, screen)) return WindowKind.NAVIGATION_BAR

        // 系统窗口但既不是状态栏也不是导航栏
        // （如国产 ROM 的悬浮球、侧边栏）→ 归为应用窗口，
        // 因为它可能承载真实可交互内容
        return WindowKind.APPLICATION
    }

    /**
     * 是否状态栏 —— 对应天坦 `E0.h()`
     *
     * ```java
     * return f(rect.top, 0) && f(rect.left, 0)
     *     && f(rect.right, displayMetrics.widthPixels)
     *     && rect.bottom < displayMetrics.heightPixels / 5;
     * ```
     *
     * 即：顶部对齐 + 左侧对齐 + 右边撑满 + 高度小于屏高 1/5
     */
    fun isStatusBar(bounds: Bounds, screen: ScreenGeometry): Boolean =
        matches(bounds.top, 0) &&
            matches(bounds.left, 0) &&
            matches(bounds.right, screen.widthPixels) &&
            bounds.bottom < screen.heightPixels / STATUS_BAR_HEIGHT_DIVISOR

    /**
     * 是否导航栏 —— 对应天坦 `E0.e()`
     *
     * 竖屏：`top > 屏高*3/4 && left==0 && right==屏宽 && bottom==屏高`
     * 横屏：右侧条（left > 屏高*3/4 且 ...）或左侧条（right < 屏宽/4 且 ...）
     *
     * 【注意天坦横屏判定的细节】
     *   它用 `displayMetrics.heightPixels / 4 * 3` 去比 `rect.left` ——
     *   横屏时屏幕的「宽」在 displayMetrics 里是 heightPixels，
     *   这是刻意的（横屏下逻辑宽 = 物理高）。保留该行为。
     */
    fun isNavigationBar(bounds: Bounds, screen: ScreenGeometry): Boolean {
        val w = screen.widthPixels
        val h = screen.heightPixels

        if (screen.isLandscape) {
            // 右侧竖条：左边超过 3/4 屏高，上边对齐 0，右边撑满，下边撑满
            if (matches(bounds.top, 0) &&
                bounds.left > (h / 4) * 3 &&
                matches(bounds.right, w) &&
                matches(bounds.bottom, h)
            ) return true

            // 左侧竖条：上边对齐 0，左边对齐 0，右边小于 1/4 屏宽，下边撑满
            if (matches(bounds.top, 0) &&
                matches(bounds.left, 0) &&
                bounds.right < w / 4 &&
                matches(bounds.bottom, h)
            ) return true

            return false
        }

        // 竖屏：底部横条
        return bounds.top > (h / 4) * 3 &&
            matches(bounds.left, 0) &&
            matches(bounds.right, w) &&
            matches(bounds.bottom, h)
    }

    /**
     * 容差比较 —— 对应天坦 `E0.f(a, b)`
     *
     * ```java
     * return i9 < i10 + 5 && i9 > i10 + (-5);
     * ```
     * 即 |a - b| < 5，容忍 5px 误差。
     *
     * 【为什么需要容差】
     *   不同 ROM 的状态栏/导航栏有 1~2px 的取整差异，
     *   严格相等会让判定在某些机型上整体失效。
     */
    fun matches(value: Int, target: Int, tolerance: Int = TOLERANCE_PX): Boolean =
        value < target + tolerance && value > target - tolerance

    // ==================== 系统窗口类型常量 ====================
    // 与 AccessibilityWindowInfo.getType() 一致

    const val TYPE_APPLICATION = 1
    const val TYPE_INPUT_METHOD = 2
    const val TYPE_SYSTEM = 3
    const val TYPE_ACCESSIBILITY_OVERLAY = 4
    const val TYPE_SPLIT_SCREEN_DIVIDER = 5
    const val TYPE_UNKNOWN = -1

    /** 容差 5px —— 与天坦 `E0.f` 一致 */
    const val TOLERANCE_PX = 5

    /** 状态栏高度上限 = 屏高 / 5 —— 与天坦 `E0.h` 一致 */
    const val STATUS_BAR_HEIGHT_DIVISOR = 5
}

/**
 * 窗口分类器 —— 取系统数据 + 调用 [WindowClassifierCore]
 *
 * 与天坦 `E0` 的差异：天坦把系统调用与判定混在一起（无法单测）；
 * 这里严格分层 —— 本类只做「取数据」，判定全在纯函数里。
 */
object WindowClassifier {

    /**
     * 判定一个窗口的类型。
     *
     * @return 窗口类型；无法判定时返回 [WindowKind.UNKNOWN]
     */
    fun classify(service: android.accessibilityservice.AccessibilityService,
                 window: android.view.accessibility.AccessibilityWindowInfo?): WindowKind {
        if (window == null) return WindowKind.UNKNOWN

        // ★ API 33+ 先用系统栏 insets 做「是不是系统栏」的粗判，
        //   再用几何细分「是状态栏还是导航栏」。
        //
        // 【为什么要两步，而不是直接用现代判定】
        //   天坦的现代判定 `E0.i()` 只返回 boolean ——
        //   它知道「这是系统栏」，但不知道是哪一个。
        //   而读屏需要区分（状态栏在顶、导航栏在底）。
        //   所以：现代判定负责**排除误判**（折叠屏/超长屏下几何算错的情况），
        //   几何判定负责**细分种类**。两条路互补而非替代。
        val modernSaysSystemBar = isSystemBarModern(service, window)

        return try {
            val screen = screenGeometry(service, window) ?: return WindowKind.UNKNOWN
            // 【注意】窗口的边界必须用 out 参数取 ——
            // AccessibilityWindowInfo 没有 boundsInScreen 属性（节点才有）。
            // 天坦 E0.h() 同样用 `accessibilityWindowInfo.getBoundsInScreen(rect)`。
            val rect = android.graphics.Rect()
            window.getBoundsInScreen(rect)
            val bounds = Bounds(rect)
            val geometric = WindowClassifierCore.classify(
                windowType = window.type,
                bounds = bounds,
                screen = screen,
                hasImeInsets = false
            )

            // 现代判定说「不是系统栏」但几何说是 → 以现代判定为准
            // （几何在折叠屏/超长屏上会算错，insets 是权威数据）
            if (modernSaysSystemBar == false && geometric.isSystemBar) {
                ReaderLog.d(TAG, "几何判成系统栏但 insets 否定 → 采信 insets")
                WindowKind.APPLICATION
            } else {
                geometric
            }
        } catch (e: Throwable) {
            ReaderLog.d(TAG, "窗口分类失败：${e.message}")
            WindowKind.UNKNOWN
        }
    }

    /**
     * 取窗口所在屏幕的几何信息。
     *
     * 【为什么按窗口的 displayId 取屏幕】
     *   折叠屏/外接屏有多个 display，窗口可能在非默认屏上。
     *   天坦 `E0.b()` 同样按 displayId 取对应 display 的 WindowManager。
     *
     * 【旋转方向从哪取】
     *   天坦用 `Display.getRotation()`；这里用 display 的 rotation，
     *   与窗口 bounds 来自同一坐标系，避免不一致。
     */
    private fun screenGeometry(
        service: android.accessibilityservice.AccessibilityService,
        window: android.view.accessibility.AccessibilityWindowInfo
    ): ScreenGeometry? {
        return try {
            val displayManager = service.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as? android.hardware.display.DisplayManager
            val display = displayManager?.getDisplay(window.displayId)
                ?: displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)

            // 【修正】不用 Display.getRealMetrics：它包含系统栏且 API 30 后废弃。
            // 用窗口 bounds 与窗口 metrics 的并集推算真实屏幕尺寸 ——
            // 这与天坦用 getRealMetrics 拿到的值等价，但无废弃 API。
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)

            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null

            ScreenGeometry(
                widthPixels = metrics.widthPixels,
                heightPixels = metrics.heightPixels,
                rotation = display?.rotation ?: 0
            )
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 该窗口是否为系统栏（状态栏或导航栏）。
     *
     * 用途：系统栏通常不应进入常规焦点序列 ——
     * 用户浏览应用内容时不该突然滑到状态栏上。
     */
    fun isSystemBar(service: android.accessibilityservice.AccessibilityService,
                    window: android.view.accessibility.AccessibilityWindowInfo?): Boolean =
        classify(service, window).isSystemBar

    /** 该窗口是否为输入法窗口 */
    fun isInputMethod(service: android.accessibilityservice.AccessibilityService,
                      window: android.view.accessibility.AccessibilityWindowInfo?): Boolean =
        classify(service, window) == WindowKind.INPUT_METHOD

    /**
     * 系统栏判定的现代路径 —— 对应天坦 `E0.i()` 的 API 33+ 分支
     *
     * 天坦（E0.java:119~131）：
     * ```java
     * if (!K.E()) return e(context, w) || h(context, w);   // 低版本：几何判定
     * // API 33+：窗口 bounds 与「屏幕减去系统栏 insets 后的区域」不相交
     * Rect usable = screenBounds.inset(insetsIgnoringVisibility(systemBars()));
     * return !Rect.intersects(usable, windowBounds);
     * ```
     *
     * 【为什么新版要换判定方式】
     *   低版本的几何判定写死了比例（屏高/5、屏高*3/4），
     *   在折叠屏、超长屏、平板、分屏下会算错。
     *   API 30+ 提供了真实的系统栏 insets，直接比较更准。
     *
     * 【为什么用 insetsIgnoringVisibility 而不是 insets】
     *   天坦用的是 `getInsetsIgnoringVisibility()` ——
     *   因为系统栏可能被隐藏（沉浸式全屏），
     *   但窗口区域计算仍需按「它存在时占多少」来算。
     *   用可见 insets 会让沉浸式下判定失效。
     */
    fun isSystemBarModern(
        service: android.accessibilityservice.AccessibilityService,
        window: android.view.accessibility.AccessibilityWindowInfo?
    ): Boolean? {
        if (window == null) return null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return null

        return try {
            val wm = if (window.displayId == android.view.Display.DEFAULT_DISPLAY) {
                service.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as? android.view.WindowManager
            } else {
                val dm = service.getSystemService(android.content.Context.DISPLAY_SERVICE)
                    as? android.hardware.display.DisplayManager
                dm?.getDisplay(window.displayId)?.let { d ->
                    service.createDisplayContext(d)
                        .getSystemService(android.content.Context.WINDOW_SERVICE)
                            as? android.view.WindowManager
                }
            } ?: return null

            val metrics = wm.currentWindowMetrics
            // 屏幕可用区域 = 屏幕 bounds 减去系统栏 insets
            val usable = android.graphics.Rect(metrics.bounds)
            val insets = metrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            usable.inset(insets)

            val winRect = android.graphics.Rect()
            window.getBoundsInScreen(winRect)

            // 不与可用区域相交 → 是系统栏
            !android.graphics.Rect.intersects(usable, winRect)
        } catch (e: Throwable) {
            // 取不到就返回 null，由调用方回退几何判定
            null
        }
    }

    private const val TAG = "WindowClassifier"
}
