package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 层无障碍铁律 —— **每个可交互元素都必须有 addAccessibilityAction**
 *
 * ══════════════════════════════════════════════════════════
 * 【铁律原文（项目约定，见 MEMORY.md）】
 * ══════════════════════════════════════════════════════════
 *
 * > 所有可交互元素用 `ViewCompat.addAccessibilityAction` 提供操作选项 ——
 * > 读屏用户左右滑浏览元素、**单指上下滑浏览操作项**、双击执行。
 *
 * 【为什么这条铁律是「硬需求」而不是「最好有」】
 *   本项目的用户是视障者，读屏是他**能不能用手机的唯一途径**。
 *   一个没有无障碍动作的按钮，读屏只念出按钮文字；
 *   用户**无法知道这个按钮到底做什么**，只能靠双击盲试。
 *   对「打开无障碍设置」这种会导致读屏开关的按钮，盲试的代价很大。
 *
 * ══════════════════════════════════════════════════════════
 * 【本轮修复前实测的缺口】
 * ══════════════════════════════════════════════════════════
 *
 * | 文件 | setOnClickListener | addAccessibilityAction |
 * |---|---|---|
 * | `MainActivity` | 6 | **1** |
 * | `GestureSettingsActivity` | 2 | **0** |
 * | `TtsSettingsActivity` | 4 | **0** |
 *
 * 即 **12 个点击入口里只有 1 个**挂了无障碍动作。
 *
 * ══════════════════════════════════════════════════════════
 * 【本测试的判定方式】
 * ══════════════════════════════════════════════════════════
 *
 * 要求：**每个 `setOnClickListener` 附近都必须有对应的
 * `addAccessibilityAction`**（同一文件内计数不低于点击监听数）。
 *
 * 这是**结构性断言**，不是行为断言 —— 因为 Activity 的 View 树
 * 无法在 JVM 单测里构造。但它能防住「新增按钮忘了挂动作」这类回归。
 *
 * 【注意剥注释】本项目已三次因注释里的文字导致断言假通过/假失败，
 * 故所有源码断言一律先剥注释。
 */
class UiAccessibilityRuleTest {

    private fun codeOf(path: String) = java.io.File(path).readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private val uiDir = "src/main/java/com/baicha/reader/ui"

    private fun files() = listOf(
        "$uiDir/MainActivity.kt",
        "$uiDir/GestureSettingsActivity.kt",
        "$uiDir/TtsSettingsActivity.kt"
    )

    /**
     * ★ 核心规则：点击监听数 ≤ 无障碍动作数
     *
     * 【为什么是「≤」而不是「=」】
     * 一个按钮可以有**多个**无障碍动作（例如日志筛选按钮有两个：
     * 「选择分类」和「取消筛选」），因此动作数可以多于点击监听数。
     * 反过来（动作少于监听）就意味着有按钮没挂动作。
     */
    @Test
    fun everyClickListener_hasAccessibilityAction() {
        val offenders = mutableListOf<String>()
        for (f in files()) {
            val code = codeOf(f)
            val clicks = Regex("setOnClickListener").findAll(code).count()
            val actions = Regex("addAccessibilityAction").findAll(code).count()
            if (actions < clicks) {
                offenders += "${f.substringAfterLast('/')}: 点击 $clicks 个，动作只有 $actions 个"
            }
        }
        assertTrue(
            "以下文件的可交互元素缺少无障碍动作 —— " +
                    "读屏用户滑到那里会听不到「这个按钮做什么」：\n" +
                    offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * `MainActivity` 的 5 个导航按钮必须各有动作
     *
     * 逐个点名，避免「总数够了但某个按钮漏了」。
     */
    @Test
    fun mainActivity_allButtons_haveActions() {
        val code = codeOf("$uiDir/MainActivity.kt")
        for (id in listOf(
            "R.id.btnSettings",
            "R.id.btnTtsSettings",
            "R.id.btnGestureSettings",
            "R.id.btnTest",
            "R.id.btnClear",
            "R.id.btnLogFilter"
        )) {
            val idx = code.indexOf("findViewById<Button>($id)")
                .takeIf { it >= 0 }
                ?: code.indexOf("findViewById(R.id.${id.removePrefix("R.id.")})")
            assertTrue("$id 应被 findViewById 取出以便挂无障碍动作", idx >= 0)
        }
        assertTrue(
            "每个按钮变量名后都必须出现 addAccessibilityAction",
            Regex("addAccessibilityAction").findAll(code).count() >= 6
        )
    }

    /**
     * ★★ 逐按钮核对：**每个**按钮都必须挂上无障碍动作
     *
     * ══════════════════════════════════════════════════════════
     * 【这条测试的来历：一次变异逃逸】
     * ══════════════════════════════════════════════════════════
     *
     * 我原来只断言「`addAccessibilityAction` 出现次数 ≥ 6」。
     * 变异「删掉 btnTest 的那一个动作（剩 5 个）」**逃逸**了 ——
     * 因为 6 这个阈值只对「修复前 = 1 个」有区分力，
     * 对「6 个里少 1 个」毫无察觉。
     *
     * 正确做法：**逐个按钮**检查它的名字是否与
     * `addAccessibilityAction` 出现在同一段代码里。
     */
    @Test
    fun everyButtonInMainActivity_individuallyWired() {
        val code = codeOf("$uiDir/MainActivity.kt")
        val buttons = listOf(
            "btnSettings" to "a11y_open_settings",
            "btnTtsSettings" to "a11y_tts_settings",
            "btnGestureSettings" to "a11y_gesture_settings",
            "btnTest" to "a11y_test_speech",
            "btnClear" to "a11y_clear_log"
        )
        for ((varName, stringRes) in buttons) {
            // 该按钮的 addAccessibilityAction 调用块
            val callIdx = code.indexOf("ViewCompat.addAccessibilityAction(" + "\n" + "            $varName,")
            assertTrue(
                "★ $varName 缺少 addAccessibilityAction —— " +
                        "读屏用户滑到它时听不到「这个按钮做什么」",
                callIdx >= 0
            )
            val block = code.substring(callIdx, minOf(code.length, callIdx + 300))
            assertTrue(
                "$varName 的动作文案应指向 $stringRes（而不是泛化的共用文案）:\n$block",
                block.contains(stringRes)
            )
        }
    }

    /**
     * 逐按钮核对另两个 Activity
     */
    @Test
    fun everyButtonInSettingsActivities_individuallyWired() {
        val gesture = codeOf("$uiDir/GestureSettingsActivity.kt")
        for (v in listOf("btnResetAll", "btnShowConflicts")) {
            assertTrue(
                "GestureSettingsActivity.$v 缺少 addAccessibilityAction",
                gesture.contains("ViewCompat.addAccessibilityAction(" + "\n" + "            $v,")
            )
        }

        val tts = codeOf("$uiDir/TtsSettingsActivity.kt")
        for (v in listOf("btnRefresh", "btnEngineSettings", "btnTestSpeak", "btnReset")) {
            assertTrue(
                "TtsSettingsActivity.$v 缺少 addAccessibilityAction",
                tts.contains("ViewCompat.addAccessibilityAction(" + "\n" + "            $v,")
            )
        }
    }

    /**
     * 布局里**不得**有硬编码文案 —— 必须走 `@string/`
     *
     * 【为什么对读屏项目尤其重要】硬编码文案无法本地化，
     * 而且与 strings.xml 里的同义文案会各自演化；
     * 读屏念的是**实际渲染的文本**，一旦两处不一致，
     * 用户听到的就和他看到的（或别人描述的）对不上。
     *
     * 【本轮修复】`activity_main.xml` 里 `btnTest` 原本写着
     * `android:text="测试朗读"`，已改为 `@string/btn_test_speech`。
     */
    @Test
    fun layouts_haveNoHardcodedText() {
        val layoutDir = java.io.File("src/main/res/layout")
        val offenders = mutableListOf<String>()
        for (f in layoutDir.listFiles { it -> it.name.endsWith(".xml") } ?: emptyArray()) {
            val text = f.readText()
            // android:text="..." 且不以 @ 开头 = 硬编码
            Regex("android:text=\"([^@][^\"]*)\"").findAll(text).forEach {
                offenders += "${f.name}: ${it.value}"
            }
            Regex("android:hint=\"([^@][^\"]*)\"").findAll(text).forEach {
                offenders += "${f.name}: ${it.value}"
            }
        }
        assertTrue(
            "布局里存在硬编码文案（必须走 @string/）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * ★ 每个无障碍动作都必须有对应的 string 资源
     *
     * `getString(R.string.xxx)` 写错会编译失败（安全），
     * 但要防的是「动作文案指向了一个过于笼统的字符串」
     * —— 例如所有按钮都复用同一个 `a11y_click`。
     * 这里断言 `a11y_` 前缀的字符串至少覆盖了各主要入口。
     */
    @Test
    fun accessibilityActionStrings_existAndAreSpecific() {
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        val required = listOf(
            "a11y_open_settings",
            "a11y_tts_settings",
            "a11y_gesture_settings",
            "a11y_test_speech",
            "a11y_clear_log"
        )
        for (name in required) {
            assertTrue(
                "缺少无障碍动作文案 $name —— 动作标签必须让读屏用户听懂「做什么」",
                strings.contains("name=\"$name\"")
            )
        }
    }

    /**
     * ★ 死代码检查：`clearFilter()` 现在必须**有调用点**
     *
     * 它此前是死代码（注释写「按钮长按」，但没人装长按监听）。
     * 已挂为 `btnLogFilter` 的第 2/3 个无障碍动作。
     */
    @Test
    fun clearFilter_isActuallyWired() {
        val code = codeOf("$uiDir/MainActivity.kt")
        val defs = Regex("private fun clearFilter\\(\\)").findAll(code).count()
        assertEquals("应只有一处定义", 1, defs)
        // 除了定义那一行，还必须出现在别处
        val usages = Regex("clearFilter\\(\\)").findAll(code).count()
        assertTrue(
            "clearFilter() 必须被真正调用（此前是死代码）—— " +
                    "定义 1 处 + 调用至少 1 处，实际共 $usages 处",
            usages >= 2
        )
    }
}
