/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * 基于TalkBack Pipeline架构
 * 
 * Copyright (C) 2024
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.example.simplereader.pipeline.interpreters;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.pipeline.feedback.Feedback;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * AccessibilityFocusInterpreter - 无障碍焦点解释器
 * 
 * 核心职责：
 * 1. 消费来自其他解释器的半解释事件
 * 2. 管理无障碍焦点的变化
 * 3. 处理触摸探索、滚动等交互
 * 4. 生成完整的Interpretation发送到Pipeline
 * 
 * Pipeline架构中的作用：
 * - 作为解释器的协调者，整合多个输入源
 * - 维护焦点状态，与ActorState交互
 * - 生成AccessibilityFocused类型的Interpretation
 * 
 * 高性能设计：
 * 1. 事件预处理 - 在事件进入Pipeline前进行预处理
 * 2. 状态缓存 - 避免重复的状态查询
 * 3. 条件检查 - 通过early return避免不必要的处理
 */
public class AccessibilityFocusInterpreter 
        implements AccessibilityEventInterpreter,
                   ScreenStateChangeListener,
                   ManualScrollListener,
                   TouchExplorationListener,
                   InputFocusListener {
    
    private static final String TAG = "AccessibilityFocusInterpreter";
    
    // 事件类型掩码
    private static final int EVENT_MASK = 
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED;
    
    // 手表设备焦点边界比率
    private static final float PARTIAL_INVISIBLE_TOP_RATIO = 0.15f;
    private static final float PARTIAL_INVISIBLE_MID_RATIO = 0.5f;
    private static final float PARTIAL_INVISIBLE_BOTTOM_RATIO = 0.85f;
    
    // 成员变量
    private final Context context;
    private final AccessibilityFocusMonitor accessibilityFocusMonitor;
    private final ScreenStateMonitor.State screenState;
    private final FocusProcessorForTapAndTouchExploration focusProcessorForTapAndTouchExploration;
    private final FocusProcessorForScreenStateChange focusProcessorForScreenStateChange;
    
    private Pipeline.InterpretationReceiver pipelineInterpretations;
    private ActorState actorState;
    
    // 配置标志
    private boolean singleTapEnabled = true;
    private @TypingMethod int typingMethod = TypingMethod.DOUBLE_TAP;
    private int typingLongPressDurationMs = 500;
    
    /**
     * 构造函数
     */
    public AccessibilityFocusInterpreter(
            Context context,
            AccessibilityFocusMonitor accessibilityFocusMonitor,
            ScreenStateMonitor.State screenState,
            Analytics analytics) {
        this.context = context;
        this.accessibilityFocusMonitor = accessibilityFocusMonitor;
        this.screenState = screenState;
        
        // 初始化焦点处理器
        this.focusProcessorForTapAndTouchExploration = 
                new FocusProcessorForTapAndTouchExploration(context, analytics);
        this.focusProcessorForScreenStateChange = 
                new FocusProcessorForScreenStateChange(accessibilityFocusMonitor);
    }
    
    /**
     * 设置Pipeline解释器接收器
     */
    public void setPipeline(Pipeline.InterpretationReceiver pipeline) {
        this.pipelineInterpretations = pipeline;
        this.focusProcessorForTapAndTouchExploration.setInterpretationReceiver(pipeline);
        this.focusProcessorForScreenStateChange.setPipeline(pipeline);
    }
    
    /**
     * 设置Actor状态
     */
    public void setActorState(ActorState actorState) {
        this.actorState = actorState;
        this.focusProcessorForTapAndTouchExploration.setActorState(actorState);
        this.focusProcessorForScreenStateChange.setActorState(actorState);
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
     * Pipeline入口点：
     * 1. 处理TYPE_VIEW_ACCESSIBILITY_FOCUSED事件
     * 2. 获取焦点动作信息
     * 3. 判断是否需要图像描述
     * 4. 生成Interpretation发送到Pipeline
     */
    @Override
    public Interpretation onAccessibilityEvent(
            AccessibilityEvent event, 
            Performance.EventId eventId) {
        
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            return handleAccessibilityFocusedEvent(event, eventId);
        }
        
        return Interpretation.NO_CHANGE;
    }
    
    /**
     * 处理无障碍焦点获得事件
     * 
     * 高性能关键点：
     * 1. 快速获取事件源节点
     * 2. 从ActorState获取焦点动作信息
     * 3. 检查是否需要图像描述
     * 4. 生成Interpretation并发送到Pipeline
     */
    private Interpretation handleAccessibilityFocusedEvent(
            AccessibilityEvent event, 
            Performance.EventId eventId) {
        
        // 获取事件源节点
        AccessibilityNodeInfoCompat node = 
                AccessibilityNodeInfoCompat.wrap(event.getSource());
        
        if (node == null) {
            return Interpretation.NO_CHANGE;
        }
        
        // 获取焦点动作信息
        FocusActionInfo info = actorState.getFocusHistory().getFocusActionInfoFromEvent(event);
        
        // 判断是否需要强制静音反馈
        boolean forceMuteFeedback = (info != null) && info.forceMuteFeedback;
        
        // 检查是否需要图像描述
        boolean needsCaption = checkNeedsCaption(node, forceMuteFeedback);
        
        // 创建无障碍焦点解释
        Interpretation.AccessibilityFocused interpretation = 
                Interpretation.AccessibilityFocused.create(info, needsCaption);
        
        // 发送到Pipeline
        if (pipelineInterpretations != null) {
            pipelineInterpretations.input(eventId, event, interpretation, node);
        }
        
        // 生成Interpretation返回
        return Interpretation.Builder
                .ofType(Interpretation.Type.ACCESSIBILITY_FOCUSED)
                .setEvent(event)
                .setSource(node)
                .setAccessibilityFocused(true)
                .build();
    }
    
    /**
     * 检查是否需要图像描述
     * 
     * 条件：
     * 1. 不强制静音反馈
     * 2. 支持图像描述
     * 3. 节点需要自动描述
     * 4. 节点没有被标记
     */
    private boolean checkNeedsCaption(
            AccessibilityNodeInfoCompat node, 
            boolean forceMuteFeedback) {
        
        if (forceMuteFeedback) {
            return false;
        }
        
        // 检查是否支持图像描述
        if (!ImageCaptioner.supportsImageCaption(context)) {
            return false;
        }
        
        // 检查节点是否需要描述
        if (!ImageCaptioner.needAutomaticCaptioning(context, node)) {
            return false;
        }
        
        // 检查节点是否已被标记
        if (actorState.getLabelManagerState().getLabelIdForNode(node) != Label.NO_ID) {
            return false;
        }
        
        return true;
    }
    
    // ==================== 触摸探索操作 ====================
    
    /**
     * 处理触摸探索动作
     * 
     * 由TouchExplorationInterpreter调用
     * 
     * @param action 触摸探索动作
     * @param eventId 事件ID
     * @return 是否成功处理
     */
    @Override
    public boolean onTouchExplorationAction(
            TouchExplorationAction action, 
            Performance.EventId eventId) {
        
        LogUtils.d(TAG, "User action: %s", action);
        return focusProcessorForTapAndTouchExploration.onTouchExplorationAction(action, eventId);
    }
    
    /**
     * 执行分屏点击
     * 
     * 分屏点击是一种无障碍功能，
     * 用户可以同时触摸两个位置来执行点击操作
     */
    public void performSplitTap(Performance.EventId eventId) {
        if (!focusProcessorForTapAndTouchExploration.performSplitTap(eventId)) {
            // 如果分屏点击失败，检查是否支持在所有地方执行分屏点击
            if (FeatureSupport.supportSplitTapEverywhere() 
                    && FeatureFlagReader.splitTapEverywhere(context)) {
                performClick(eventId);
            }
        }
    }
    
    /**
     * 执行点击
     */
    private void performClick(Performance.EventId eventId) {
        AccessibilityNodeInfoCompat currentA11yFocusedNode =
                accessibilityFocusMonitor.getAccessibilityFocus(false);
        
        if (currentA11yFocusedNode != null) {
            Interpretation.Touch touch = 
                    Interpretation.Touch.create(Interpretation.Touch.Action.LIFT, 
                                               currentA11yFocusedNode);
            pipelineInterpretations.input(eventId, null, touch);
        }
    }
    
    // ==================== 屏幕状态变化 ====================
    
    /**
     * 屏幕状态变化时调用
     * 
     * 由ScreenStateMonitor调用
     * 
     * 场景：
     * 1. 屏幕旋转
     * 2. 窗口状态变化
     * 3. 应用切换
     */
    @Override
    public boolean onScreenStateChanged(
            ScreenState screenState, 
            Performance.EventId eventId) {
        
        return focusProcessorForScreenStateChange.onScreenStateChanged(screenState, eventId);
    }
    
    // ==================== 手动滚动处理 ====================
    
    /**
     * 处理手动滚动解释
     * 
     * 由ManualScrollInterpreter调用
     * 
     * 高性能设计：
     * 1. 检查窗口稳定性（不稳定时不处理，避免焦点冲突）
     * 2. 检查是否需要移动焦点
     * 3. 生成ManualScroll Interpretation
     */
    @Override
    public void onManualScroll(ManualScrollInterpretation interpretation) {
        
        // 检查主窗口是否稳定
        if (!screenState.areMainWindowsStable()) {
            LogUtils.w(TAG, "窗口未稳定，延迟处理焦点");
            return;
        }
        
        // 获取当前无障碍焦点
        AccessibilityNodeInfoCompat currentA11yFocusedNode =
                accessibilityFocusMonitor.getAccessibilityFocus(false);
        
        // 检查是否需要移动焦点
        if (shouldMoveFocus(currentA11yFocusedNode, interpretation.direction())) {
            
            // 构建ManualScroll解释
            ManualScroll.Builder builder = ManualScroll.builder()
                    .setDirection(interpretation.direction())
                    .setScreenState(screenState.getStableScreenState());
            
            // 手表设备特殊处理
            if (FormFactorUtils.isAndroidWear()) {
                builder.setCurrentFocusedNode(currentA11yFocusedNode);
            }
            
            // 发送到Pipeline
            pipelineInterpretations.input(
                    interpretation.eventId(), 
                    interpretation.event(), 
                    builder.build());
        }
    }
    
    /**
     * 检查是否需要移动焦点
     * 
     * 对于手表设备，需要在节点即将不可见前移动焦点
     * 对于其他设备，只要当前焦点不可用就移动
     */
    private boolean shouldMoveFocus(
            AccessibilityNodeInfoCompat currentA11yFocusedNode, 
            @SearchDirection int direction) {
        
        if (FormFactorUtils.isAndroidWear()) {
            // 手表设备特殊逻辑
            return shouldFocusNextNodeForWatch(currentA11yFocusedNode, direction);
        } else {
            // 普通设备：当前焦点不可用时移动
            return !AccessibilityNodeInfoUtils.shouldFocusNode(currentA11yFocusedNode);
        }
    }
    
    /**
     * 检查手表设备是否需要聚焦到下一个节点
     * 
     * 手表设备的屏幕较小，需要提前移动焦点以避免滚动冲突
     */
    private boolean shouldFocusNextNodeForWatch(
            AccessibilityNodeInfoCompat node, 
            @SearchDirection int direction) {
        
        if (node == null) {
            return true;
        }
        
        // 获取节点在屏幕上的边界
        Rect nodeRect = new Rect();
        node.getBoundsInScreen(nodeRect);
        
        // 获取屏幕尺寸
        Point screenPxSize = DisplayUtils.getScreenPixelSizeWithoutWindowDecor(context);
        
        // 检查是否接近边界，并且有足够空间容纳下一个节点
        return closeToBorder(direction, nodeRect, screenPxSize)
                && hasEnoughSpaceForNextNode(direction, nodeRect, screenPxSize);
    }
    
    /**
     * 检查节点是否接近屏幕边界
     */
    private boolean closeToBorder(
            @SearchDirection int direction, 
            Rect nodeRect, 
            Point screenPxSize) {
        
        switch (direction) {
            case TraversalStrategy.SEARCH_FOCUS_FORWARD:
                // 向上滚动时，检查节点是否接近顶部
                return (float) nodeRect.top < (float) screenPxSize.y * PARTIAL_INVISIBLE_TOP_RATIO;
                
            case TraversalStrategy.SEARCH_FOCUS_BACKWARD:
                // 向下滚动时，检查节点是否接近底部
                return (float) nodeRect.bottom > (float) screenPxSize.y * PARTIAL_INVISIBLE_BOTTOM_RATIO;
                
            default:
                return false;
        }
    }
    
    /**
     * 检查是否有足够空间容纳下一个节点
     */
    private boolean hasEnoughSpaceForNextNode(
            @SearchDirection int direction, 
            Rect nodeRect, 
            Point screenPxSize) {
        
        switch (direction) {
            case TraversalStrategy.SEARCH_FOCUS_FORWARD:
                // 向上滚动时，检查节点底部是否在屏幕中上部
                return (float) nodeRect.bottom < (float) screenPxSize.y * PARTIAL_INVISIBLE_MID_RATIO;
                
            case TraversalStrategy.SEARCH_FOCUS_BACKWARD:
                // 向下滚动时，检查节点顶部是否在屏幕中下部
                return (float) nodeRect.top > (float) screenPxSize.y * PARTIAL_INVISIBLE_MID_RATIO;
                
            default:
                return false;
        }
    }
    
    // ==================== 输入焦点处理 ====================
    
    /**
     * 处理输入焦点目标
     * 
     * 由InputFocusInterpreter调用
     * 
     * 场景：用户使用D-pad或键盘导航时，输入焦点变化
     */
    @Override
    public void onViewTargeted(
            @Nullable Performance.EventId eventId,
            AccessibilityEvent event,
            AccessibilityNodeInfoCompat targetedNode) {
        
        // 检查窗口稳定性
        if (!screenState.areMainWindowsStable()) {
            LogUtils.w(TAG, "窗口未稳定，延迟处理焦点同步");
            return;
        }
        
        // 创建输入焦点解释并发送到Pipeline
        Interpretation.InputFocus inputFocus = new Interpretation.InputFocus(targetedNode);
        pipelineInterpretations.input(eventId, event, inputFocus);
    }
    
    // ==================== 配置方法 ====================
    
    /**
     * 设置单指激活是否启用
     */
    public void setSingleTapEnabled(boolean enabled) {
        this.singleTapEnabled = enabled;
        focusProcessorForTapAndTouchExploration.setSingleTapEnabled(enabled);
    }
    
    /**
     * 获取单指激活是否启用
     */
    public boolean getSingleTapEnabled() {
        return singleTapEnabled;
    }
    
    /**
     * 设置键盘确认方式
     */
    public void setTypingMethod(@TypingMethod int type) {
        this.typingMethod = type;
        focusProcessorForTapAndTouchExploration.setTypingMethod(type);
    }
    
    /**
     * 获取键盘确认方式
     */
    @TypingMethod
    public int getTypingMethod() {
        return typingMethod;
    }
    
    /**
     * 设置长按持续时间（毫秒）
     * 仅在键盘确认方式不是双击时适用
     */
    public void setTypingLongPressDurationMs(int duration) {
        this.typingLongPressDurationMs = duration;
        focusProcessorForTapAndTouchExploration.setTypingLongPressDurationMs(duration);
    }
    
    /**
     * 获取长按持续时间
     */
    public int getTypingLongPressDurationMs() {
        return typingLongPressDurationMs;
    }
    
    /**
     * 释放资源
     */
    public void shutdown() {
        focusProcessorForTapAndTouchExploration.shutdown();
        focusProcessorForScreenStateChange.shutdown();
        pipelineInterpretations = null;
        actorState = null;
    }
    
    // ==================== 内部类定义 ====================
    
    /**
     * 焦点动作信息
     * 记录焦点的来源和特征
     */
    public static class FocusActionInfo {
        public static final int TOUCH_EXPLORATION = 1;
        public static final int LOGICAL_NAVIGATION = 2;
        public static final int DIRECTIONAL_NAVIGATION = 3;
        public static final int SCREEN_STATE_CHANGE = 4;
        
        public final int sourceAction;
        public final boolean forceMuteFeedback;
        public final boolean forceFeedbackEvenIfAudioPlaybackActive;
        public final boolean forceFeedbackEvenIfMicrophoneActive;
        public final boolean forceFeedbackEvenIfSsbActive;
        
        public FocusActionInfo(
                int sourceAction,
                boolean forceMuteFeedback,
                boolean forceFeedbackEvenIfAudioPlaybackActive,
                boolean forceFeedbackEvenIfMicrophoneActive,
                boolean forceFeedbackEvenIfSsbActive) {
            this.sourceAction = sourceAction;
            this.forceMuteFeedback = forceMuteFeedback;
            this.forceFeedbackEvenIfAudioPlaybackActive = forceFeedbackEvenIfAudioPlaybackActive;
            this.forceFeedbackEvenIfMicrophoneActive = forceFeedbackEvenIfMicrophoneActive;
            this.forceFeedbackEvenIfSsbActive = forceFeedbackEvenIfSsbActive;
        }
    }
    
    /**
     * 键盘确认方式常量
     */
    @interface TypingMethod {
        int DOUBLE_TAP = 1;
        int LONG_PRESS = 2;
    }
}
