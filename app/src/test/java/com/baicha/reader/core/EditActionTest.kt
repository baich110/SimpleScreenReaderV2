package com.baicha.reader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑动作框架单测
 *
 * JVM 环境拿不到真实节点，所以这里验证：
 *   - 动作枚举定义完整性
 *   - ACTION 常量值正确性（对齐 Android + 天坦）
 *   - 无节点时的安全行为
 */
class EditActionTest {

    @Test
    fun action_count_is_8() {
        // 天坦 X6/k 定义了 8 个编辑动作
        assertEquals(8, EditAction.values().size)
    }

    @Test
    fun labels_are_chinese() {
        assertEquals("复制", EditAction.COPY.label)
        assertEquals("粘贴", EditAction.PASTE.label)
        assertEquals("剪切", EditAction.CUT.label)
        assertEquals("全选文本", EditAction.SELECT_ALL.label)
        assertEquals("清空文本", EditAction.CLEAR_TEXT.label)
        assertEquals("云粘贴", EditAction.CROSS_DEVICE_PASTE.label)
        assertEquals("光标跳转行首行尾", EditAction.CURSOR_JUMP.label)
        assertEquals("选择模式", EditAction.SELECT_MODE.label)
    }

    /**
     * ACTION 常量必须与 Android 真实值一致 —— 值错了动作会静默失效
     */
    @Test
    fun action_constants_match_android() {
        assertEquals(131072, NodeActions.SET_SELECTION)
        assertEquals(32768, NodeActions.PASTE)
        assertEquals(16384, NodeActions.COPY)
        assertEquals(65536, NodeActions.CUT)
        assertEquals(2097152, NodeActions.SET_TEXT)
    }

    @Test
    fun required_actions_are_correct() {
        assertEquals(NodeActions.COPY, EditAction.COPY.requiredAction)
        assertEquals(NodeActions.CUT, EditAction.CUT.requiredAction)
        assertEquals(NodeActions.PASTE, EditAction.PASTE.requiredAction)
        assertEquals(NodeActions.SET_TEXT, EditAction.CLEAR_TEXT.requiredAction)
        assertEquals(NodeActions.SET_SELECTION, EditAction.SELECT_ALL.requiredAction)
        // 云粘贴无 ACTION 前置
        assertEquals(0, EditAction.CROSS_DEVICE_PASTE.requiredAction)
    }

    @Test
    fun text_required_actions_are_marked() {
        assertTrue(EditAction.CLEAR_TEXT.needsText)
        assertTrue(EditAction.SELECT_ALL.needsText)
        assertFalse(EditAction.PASTE.needsText)
        assertFalse(EditAction.COPY.needsText)
    }

    @Test
    fun available_actions_null_returns_empty() {
        assertTrue(EditActionExecutor.availableActions(null).isEmpty())
    }

    @Test
    fun execute_with_null_returns_false() {
        EditAction.values().forEach { action ->
            assertFalse("$action 传 null 应返回 false", EditActionExecutor.execute(action, null))
        }
    }

    @Test
    fun select_mode_with_null_is_safe() {
        // 不应抛异常
        EditActionExecutor.isSelectMode(null)
    }

    @Test
    fun reset_is_safe() {
        EditActionExecutor.reset()
    }

    @Test
    fun label_of_select_mode_reflects_state() {
        // 无节点时用内部状态；初始应为"进入"
        val label = EditActionExecutor.labelOf(EditAction.SELECT_MODE, null)
        assertTrue(label == "进入选择模式" || label == "退出选择模式")
    }

    @Test
    fun label_of_normal_action_is_fixed() {
        assertEquals("复制", EditActionExecutor.labelOf(EditAction.COPY, null))
        assertEquals("粘贴", EditActionExecutor.labelOf(EditAction.PASTE, null))
    }

    @Test
    fun cross_device_hook_is_settable() {
        EditActionExecutor.crossDevicePasteAvailable = { true }
        assertTrue(EditActionExecutor.crossDevicePasteAvailable!!.invoke())
        EditActionExecutor.crossDevicePasteAvailable = null
        assertNull(EditActionExecutor.crossDevicePasteAvailable)
    }
}
