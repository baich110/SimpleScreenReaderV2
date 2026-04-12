/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * 基于TalkBack Pipeline架构
 * 
 * Copyright (C) 2024
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.feedback.Feedback;
import com.example.simplereader.pipeline.feedback.Feedback.Part;
import com.example.simplereader.pipeline.feedback.FeedbackBuilder;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * InputFocusInterpreter - 输入焦点监控解释器
 * 
 * 监控输入焦点变化事件，同步无障碍焦点到输入焦点目标。
 * 
 * Pipeline架构中的作用：
 * - 接收TYPE_VIEW_FOCUSED和TYPE_VIEW_SELECTED事件
 * - 解析输入焦点变化，确定需要同步的目标节点
 * - 生成AccessibilityFocused解释
 * 
 * 核心功能：
 * 1. 监听系统输入焦点变化
 * 2. 处理TYPE_VIEW_FOCUSED事件（视图获得输入焦点）
 * 3. 处理TYPE_VIEW_SELECTED事件（适配器视图中的项被选中）
 * 4. 同步无障碍焦点到输入焦点目标
 * 5. 管理焦点冲突避免（处理快速导航时的焦点跳回）
 * 
 * 高性能设计：
 * - 基于事件类型的快速分发（switch语句）
 * - 节点缓存复用（避免重复查找）
 * - 超时机制避免焦点冲突
 */
public class InputFocusInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "InputFocusInterpreter";
    
    /** 监听的目标视图变化回调接口 */
    public interface TargetViewChangeListener {
        void onViewTargeted(
                @Nullable Performance.EventId eventId,
                AccessibilityEvent event,
                AccessibilityNodeInfoCompat targetedNode);
    }
    
    /** 焦点操作记录，用于追踪焦点动作 */
    public static class FocusActionRecord {
        public final long actionTime;
        public final AccessibilityNodeInfoCompat inputFocusedNode;
        public final int actionType;
        
        public FocusActionRecord(long actionTime, 
                                 AccessibilityNodeInfoCompat inputFocusedNode, 
                                 int actionType) {
            this.actionTime = actionTime;
            this.inputFocusedNode = inputFocusedNode;
            this.actionType = actionType;
        }
    }
    
    // 成员变量
    private final FocusFinder focusFinder;
    private final GlobalVariables globalVariables;
    private final InterpreterDependencies dependencies;
    
    private TargetViewChangeListener targetViewChangeListener;
    private FocusActionRecord lastFocusActionRecord;
    private long lastFocusActionHandleUptimeMs = 0;
    
    // 事件类型掩码
    private static final int EVENT_MASK =
            AccessibilityEvent.TYPE_VIEW_FOCUSED 
            | AccessibilityEvent.TYPE_VIEW_SELECTED;
    
    /** 输入焦点动作超时时间（毫秒） */
    private static final long INPUT_FOCUS_ACTION_TIMEOUT = 1000;
    
    /**
     * 构造函数
     */
    public InputFocusInterpreter(
            FocusFinder focusFinder,
            GlobalVariables globalVariables,
            InterpreterDependencies dependencies) {
        this.focusFinder = focusFinder;
        this.globalVariables = globalVariables;
        this.dependencies = dependencies;
    }
    
    /**
     * 设置目标视图变化监听器
     */
    public void setTargetViewChangeListener(TargetViewChangeListener listener) {
        this.targetViewChangeListener = listener;
    }
    
    /**
     * 记录焦点动作（用于冲突检测）
     */
    public void recordFocusAction(AccessibilityNodeInfoCompat node, int actionType) {
        lastFocusActionRecord = new FocusActionRecord(
                System.currentTimeMillis(), 
                node, 
                actionType);
    }
    
    /**
     * 获取监听的事件类型
     */
    @Override
    public int getEventTypes() {
        return EVENT_MASK;
    }
    
    /**
     * 处理无障碍事件
     * 
     * Pipeline架构核心方法：
     * 1. 根据事件类型分发到不同的处理方法
     * 2. 处理TYPE_VIEW_FOCUSED - 输入焦点变化
     * 3. 处理TYPE_VIEW_SELECTED - 适配器选中变化
     */
    @Override
    public Interpretation onAccessibilityEvent(
            AccessibilityEvent event, 
            Performance.EventId eventId) {
        
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                return handleViewInputFocusedEvent(event, eventId);
                
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
                return handleViewSelectedEvent(event, eventId);
                
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED:
                // 更新已编辑的焦点状态，以防屏幕变化事件来得太晚
                initLastEditableFocusForGlobalVariables();
                break;
                
            default:
                break;
        }
        
        return Interpretation.NO_CHANGE;
    }
    
    /**
     * 处理视图获得输入焦点事件
     * 
     * 高性能关键点：
     * 1. 快速检查事件有效性
     * 2. 避免不必要的焦点同步（通过冲突检测）
     * 3. 正确处理集合容器的特殊情况
     */
    private Interpretation handleViewInputFocusedEvent(
            AccessibilityEvent event, 
            Performance.EventId eventId) {
        
        // 获取事件源节点
        AccessibilityNodeInfoCompat sourceNode = 
                AccessibilityNodeInfoCompat.wrap(event.getSource());
        
        if (sourceNode == null) {
            // 无效的TYPE_VIEW_FOCUSED事件
            return Interpretation.NO_CHANGE;
        }
        
        // 更新全局变量中的输入焦点
        updateInputFocusedNodeInGlobalVariables(sourceNode);
        
        // 检查焦点冲突
        if (isFromSavedFocusAction(event)) {
            // 来自保存的焦点动作，清除记录
            clearFocusActionRecord();
        } else if (!conflictWithFocusActionRecord(event)) {
            // 不冲突，执行焦点同步
            
            // 获取可获得无障碍焦点的节点
            AccessibilityNodeInfoCompat a11yFocusableNode = 
                    getA11yFocusableNodeFromInputFocusedNode(sourceNode);
            
            if (a11yFocusableNode != null && targetViewChangeListener != null) {
                // 通知监听器处理焦点同步
                targetViewChangeListener.onViewTargeted(eventId, event, a11yFocusableNode);
            }
        }
        
        // 如果节点有效，生成解释
        if (sourceNode.isEditable() || isTextInputField(sourceNode)) {
            return Interpretation.Builder
                    .ofType(Interpretation.Type.FOCUS_CHANGED)
                    .setEvent(event)
                    .setSource(sourceNode)
                    .setGranularitySelection(getGranularityFromNode(sourceNode))
                    .build();
        }
        
        return Interpretation.NO_CHANGE;
    }
    
    /**
     * 处理视图被选中事件
     * 
     * 场景：适配器视图（如ListView、GridView）中的项被选中
     */
    private Interpretation handleViewSelectedEvent(
            AccessibilityEvent event, 
            Performance.EventId eventId) {
        
        AccessibilityNodeInfoCompat selectedNode = getSelectedItemFromAdapterView(event);
        
        if (selectedNode != null) {
            // 检查节点是否应该获得焦点
            if (shouldFocusNode(selectedNode)) {
                // 通知监听器
                if (targetViewChangeListener != null) {
                    targetViewChangeListener.onViewTargeted(eventId, event, selectedNode);
                }
                
                // 生成选中解释
                return Interpretation.Builder
                        .ofType(Interpretation.Type.CONTENT_CHANGED)
                        .setEvent(event)
                        .setSource(selectedNode)
                        .build();
            }
        }
        
        return Interpretation.NO_CHANGE;
    }
    
    /**
     * 从输入焦点节点获取可获得无障碍焦点的节点
     * 
     * 特殊处理：
     * - 忽略集合容器（ListView/GridView）的事件，避免干扰初始焦点功能
     * - 检查节点是否应该获得焦点
     */
    private @Nullable AccessibilityNodeInfoCompat getA11yFocusableNodeFromInputFocusedNode(
            AccessibilityNodeInfoCompat eventSourceNode) {
        
        // 忽略集合容器的焦点事件
        // 原因：Android TV设备上，使用D-pad导航时，
        // 打开新窗口时集合会获得输入焦点，这会干扰初始焦点功能
        int role = Role.getRole(eventSourceNode);
        if (role == Role.ROLE_LIST || role == Role.ROLE_GRID) {
            LogUtils.d(TAG, "忽略集合容器的TYPE_VIEW_FOCUSED事件");
            return null;
        }
        
        // 检查节点是否应该获得焦点
        if (!shouldFocusNode(eventSourceNode)) {
            return null;
        }
        
        return eventSourceNode;
    }
    
    /**
     * 从适配器视图获取选中的项
     * 
     * 过滤条件：
     * 1. 事件源节点不为空
     * 2. 节点处于选中状态
     * 3. 节点是顶级滚动项
     */
    private @Nullable AccessibilityNodeInfoCompat getSelectedItemFromAdapterView(
            AccessibilityEvent event) {
        
        AccessibilityNodeInfoCompat sourceNode = 
                AccessibilityNodeInfoCompat.wrap(event.getSource());
        
        if (sourceNode == null) {
            return null;
        }
        
        // 检查是否为选中的顶级滚动项
        if (!sourceNode.isSelected() || !isTopLevelScrollItem(sourceNode)) {
            return null;
        }
        
        return sourceNode;
    }
    
    /**
     * 检查焦点动作记录是否冲突
     * 
     * 场景：Android TV设备上快速导航时，
     * TYPE_VIEW_FOCUSED事件可能延迟到达，
     * 需要通过时间戳判断是否与缓存的导航动作冲突
     */
    private boolean conflictWithFocusActionRecord(AccessibilityEvent event) {
        if (lastFocusActionRecord == null) {
            return false;
        }
        
        long timeDiff = event.getEventTime() - lastFocusActionRecord.actionTime;
        return timeDiff >= 0 && timeDiff < INPUT_FOCUS_ACTION_TIMEOUT;
    }
    
    /**
     * 检查事件是否来自保存的焦点动作
     */
    private boolean isFromSavedFocusAction(AccessibilityEvent event) {
        if (lastFocusActionRecord == null) {
            return false;
        }
        
        AccessibilityNodeInfoCompat node = 
                AccessibilityNodeInfoCompat.wrap(event.getSource());
        
        if (node == null) {
            return false;
        }
        
        long timeDiff = event.getEventTime() - lastFocusActionRecord.actionTime;
        boolean isFromFocusAction =
                (timeDiff >= 0L) && (timeDiff < INPUT_FOCUS_ACTION_TIMEOUT)
                && node.equals(lastFocusActionRecord.inputFocusedNode);
        
        return isFromFocusAction;
    }
    
    /**
     * 检查上一个焦点动作是否已处理
     */
    private boolean isLastFocusActionHandled() {
        return (lastFocusActionRecord == null)
                || (lastFocusActionRecord.actionTime <= lastFocusActionHandleUptimeMs);
    }
    
    /**
     * 清除焦点动作记录（标记为已处理）
     */
    private void clearFocusActionRecord() {
        if (lastFocusActionRecord != null) {
            lastFocusActionHandleUptimeMs = lastFocusActionRecord.actionTime;
            // 释放节点引用
            if (lastFocusActionRecord.inputFocusedNode != null) {
                lastFocusActionRecord.inputFocusedNode.recycle();
            }
            lastFocusActionRecord = null;
        }
    }
    
    /**
     * 初始化全局变量中的最后一个可编辑焦点
     * 
     * 场景：
     * 1. TalkBack打开时，已有编辑框获得输入焦点
     * 2. 带输入焦点编辑框的窗口被带到前台
     */
    public void initLastEditableFocusForGlobalVariables() {
        AccessibilityNodeInfoCompat currentInputFocus = 
                focusFinder.findFocusCompat(AccessibilityNodeInfo.FOCUS_INPUT);
        
        LogUtils.v(TAG, "initLastEditableFocusForGlobalVariables: %s", currentInputFocus);
        updateInputFocusedNodeInGlobalVariables(currentInputFocus);
        
        // 释放临时引用
        if (currentInputFocus != null) {
            currentInputFocus.recycle();
        }
    }
    
    /**
     * 更新全局变量中的输入焦点信息
     * 
     * 只在编辑框获得输入焦点时更新lastTextEditIsPassword标志
     * 某些键盘按键获得输入焦点时也要保持最后一个文本编辑的状态
     */
    private void updateInputFocusedNodeInGlobalVariables(
            @Nullable AccessibilityNodeInfoCompat inputFocusedNode) {
        
        if (inputFocusedNode != null) {
            boolean isEditable = inputFocusedNode.isEditable();
            int role = Role.getRole(inputFocusedNode);
            boolean isEditText = (role == Role.ROLE_EDIT_TEXT);
            
            if (isEditable || isEditText) {
                globalVariables.setLastTextEditIsPassword(inputFocusedNode.isPassword());
            }
        }
        // 如果非编辑框获得输入焦点，不更新字段
        // 这样可以让软键盘获得焦点时保持最后一个文本编辑的状态
    }
    
    /**
     * 屏幕状态变化时调用
     * 
     * 场景：屏幕状态变化后，输入焦点可能改变，
     * 但TalkBack可能收不到TYPE_VIEW_FOCUSED事件
     */
    public void onScreenStateChanged(ScreenState screenState, Performance.EventId eventId) {
        initLastEditableFocusForGlobalVariables();
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 检查节点是否应该获得焦点
     */
    private static boolean shouldFocusNode(AccessibilityNodeInfoCompat node) {
        if (node == null) {
            return false;
        }
        
        // 检查节点是否可点击
        boolean isClickable = node.isClickable();
        
        // 检查节点是否可见
        boolean isVisible = node.isVisibleToUser();
        
        // 检查节点是否启用
        boolean isEnabled = node.isEnabled();
        
        // 检查节点是否可聚焦
        boolean isFocusable = node.isFocusable();
        
        return isClickable || (isFocusable && isVisible && isEnabled);
    }
    
    /**
     * 检查节点是否为顶级滚动项
     */
    private static boolean isTopLevelScrollItem(AccessibilityNodeInfoCompat node) {
        // 简化实现：检查节点是否为列表项或网格项
        int role = Role.getRole(node);
        return (role == Role.ROLE_LIST_ITEM) 
                || (role == Role.ROLE_GRID_ITEM)
                || (role == Role.ROLE_LIST)
                || (role == Role.ROLE_GRID);
    }
    
    /**
     * 检查节点是否为文本输入字段
     */
    private static boolean isTextInputField(AccessibilityNodeInfoCompat node) {
        if (node == null) {
            return false;
        }
        int role = Role.getRole(node);
        return (role == Role.ROLE_EDIT_TEXT);
    }
    
    /**
     * 从节点获取阅读粒度
     */
    private static int getGranularityFromNode(AccessibilityNodeInfoCompat node) {
        if (node == null) {
            return AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE;
        }
        
        // 根据角色返回合适的粒度
        int role = Role.getRole(node);
        if (role == Role.ROLE_EDIT_TEXT) {
            return AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER;
        }
        
        return AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD;
    }
    
    /**
     * 释放资源
     */
    public void shutdown() {
        clearFocusActionRecord();
        targetViewChangeListener = null;
    }
    
    /**
     * 清除焦点动作记录
     */
    public void clearLastFocusAction() {
        lastFocusActionRecord = null;
    }
    
    /**
     * 获取最后一个焦点动作记录
     */
    public @Nullable FocusActionRecord getLastFocusActionRecord() {
        return lastFocusActionRecord;
    }
}
