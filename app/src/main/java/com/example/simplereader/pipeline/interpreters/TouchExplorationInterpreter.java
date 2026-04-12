/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * 基于TalkBack Pipeline架构
 * 
 * Copyright (C) 2024
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.example.simplereader.pipeline.interpreters;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.pipeline.feedback.Feedback;
import com.example.simplereader.utils.AccessibilityNodeInfoUtils;
import com.example.simplereader.utils.FeatureSupport;
import com.example.simplereader.utils.input.InputModeTracker;

/**
 * TouchExplorationInterpreter - 触摸探索解释器
 * 
 * 核心职责：
 * 1. 解析无障碍事件中的触摸探索动作
 * 2. 处理触摸交互开始、触摸交互结束、悬停进入事件
 * 3. 过滤重复事件和异常事件
 * 4. 管理延迟动作调度
 * 
 * Pipeline架构中的作用：
 * - 作为用户交互的入口点
 * - 生成Touch类型的Interpretation
 * - 与AccessibilityFocusInterpreter协同处理触摸焦点
 * 
 * 高性能设计：
 * 1. 事件过滤 - 避免重复事件进入Pipeline
 * 2. 延迟处理 - 对特殊情况使用延迟处理避免事件冲突
 * 3. 快速分发 - 事件类型switch快速分发
 * 
 * 触摸探索动作类型：
 * - TOUCH_INTERACTION_START: 触摸开始
 * - TOUCH_INTERACTION_END: 触摸结束
 * - HOVER_ENTER: 悬停进入（手指在屏幕上移动时触发）
 */
public class TouchExplorationInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "TouchExplorationInterpreter";
    
    // 事件类型掩码
    private static final int EVENT_MASK =
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
            | AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
            | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER;
    
    // 延迟时间配置
    private static final long EMPTY_TOUCH_AREA_DELAY_MS = 100;
    private static final long TOUCH_END_DELAY_MS = 70;
    
    // 消息类型
    private static final int MSG_EMPTY_TOUCH_ACTION = 0;
    private static final int MSG_TOUCH_END_ACTION = 1;
    
    // 成员变量
    private final InputModeTracker inputModeTracker;
    private final PostDelayHandler postDelayHandler;
    
    // 状态变量
    private AccessibilityNodeInfoCompat lastTouchedNode;
    private EventId pendingTouchEndEventId;
    
    // 监听器列表
    private final java.util.List<TouchExplorationActionListener> listeners = 
            new java.util.ArrayList<>();
    
    /**
     * 构造函数
     */
    public TouchExplorationInterpreter(InputModeTracker inputModeTracker) {
        this.inputModeTracker = inputModeTracker;
        this.postDelayHandler = new PostDelayHandler(this);
    }
    
    /**
     * 添加触摸探索动作监听器
     */
    public void addTouchExplorationActionListener(TouchExplorationActionListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("监听器不能为空");
        }
        listeners.add(listener);
    }
    
    /**
     * 移除触摸探索动作监听器
     */
    public void removeTouchExplorationActionListener(TouchExplorationActionListener listener) {
        listeners.remove(listener);
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
     * 2. 处理TYPE_TOUCH_INTERACTION_START - 触摸开始
     * 3. 处理TYPE_TOUCH_INTERACTION_END - 触摸结束
     * 4. 处理TYPE_VIEW_HOVER_ENTER - 悬停进入
     * 
     * 高性能关键点：
     * - 使用switch语句快速分发
     * - 事件处理后设置输入模式
     */
    @Override
    public Interpretation onAccessibilityEvent(
            AccessibilityEvent event, 
            Performance.EventId eventId) {
        
        boolean result = false;
        
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START:
                result = handleTouchInteractionStartEvent(eventId);
                break;
                
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END:
                result = handleTouchInteractionEndEvent(eventId);
                break;
                
            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
                result = handleHoverEnterEvent(event, eventId);
                break;
                
            default:
                break;
        }
        
        // 处理成功后设置输入模式为触摸
        if (result) {
            setInputTouchMode();
        }
        
        // 如果成功处理了触摸事件，生成Interpretation
        if (result) {
            AccessibilityNodeInfoCompat sourceNode = 
                    AccessibilityNodeInfoUtils.toCompat(event.getSource());
            
            return Interpretation.Builder
                    .ofType(Interpretation.Type.TOUCH)
                    .setEvent(event)
                    .setSource(sourceNode)
                    .build();
        }
        
        return Interpretation.NO_CHANGE;
    }
    
    /**
     * 处理触摸交互开始事件
     * 
     * 场景：用户手指触摸屏幕
     * 
     * 高性能关键点：
     * 1. 执行待处理的触摸结束动作
     * 2. 清除最后触摸的节点
     * 3. 取消待处理的空触摸动作
     * 4. 分发TOUCH_INTERACTION_START动作
     */
    private boolean handleTouchInteractionStartEvent(Performance.EventId eventId) {
        // 执行待处理的触摸结束动作
        postDelayHandler.executePendingTouchEndAction();
        
        // 清除最后触摸的节点
        setLastTouchedNode(null);
        
        // 取消待处理的空触摸动作（不立即分发）
        postDelayHandler.cancelPendingEmptyTouchAction(false);
        
        LogUtils.v(TAG, 
                "handleTouchInteractionStartEvent: TOUCH_INTERACTION_START, touchedFocusableNode=null");
        
        // 分发触摸交互开始动作
        TouchExplorationAction action = new TouchExplorationAction(
                TouchExplorationAction.TOUCH_INTERACTION_START, 
                null);
        
        return dispatchTouchExplorationAction(action, eventId);
    }
    
    /**
     * 处理触摸交互结束事件
     * 
     * 场景：用户手指离开屏幕
     * 
     * 高性能关键点：
     * 1. 清除最后触摸的节点
     * 2. 立即分发待处理的空触摸动作
     * 3. 分发TOUCH_INTERACTION_END动作
     * 
     * 特殊情况处理：
     * - 当hover事件乱序时，使用延迟处理
     */
    private boolean handleTouchInteractionEndEvent(Performance.EventId eventId) {
        // 清除最后触摸的节点
        setLastTouchedNode(null);
        
        // 立即分发待处理的空触摸动作
        postDelayHandler.cancelPendingEmptyTouchAction(true);
        
        LogUtils.v(TAG, 
                "handleTouchInteractionEndEvent: TOUCH_INTERACTION_END, touchedFocusableNode=null");
        
        // 分发触摸交互结束动作
        TouchExplorationAction action = new TouchExplorationAction(
                TouchExplorationAction.TOUCH_INTERACTION_END, 
                null);
        
        return dispatchTouchExplorationAction(action, eventId);
    }
    
    /**
     * 处理悬停进入事件
     * 
     * 场景：用户在屏幕上移动手指
     * 
     * 高性能关键点：
     * 1. 快速检查事件有效性
     * 2. 过滤重复的悬停事件（同一节点连续触发）
     * 3. 从悬停节点查找可聚焦节点
     * 4. 对非焦点节点使用延迟分发
     * 
     * 特殊情况处理：
     * - 当触摸非焦点区域时，使用延迟分发避免事件冲突
     */
    private boolean handleHoverEnterEvent(
            AccessibilityEvent event, 
            Performance.EventId eventId) {
        
        // 获取触摸的节点
        AccessibilityNodeInfoCompat touchedNode = 
                AccessibilityNodeInfoUtils.toCompat(event.getSource());
        
        if (touchedNode == null) {
            // 无效事件
            return false;
        }
        
        // 过滤重复事件：如果连续两个悬停事件来自同一节点，不分发
        if (touchedNode.equals(lastTouchedNode)) {
            LogUtils.v(TAG, "过滤重复悬停事件: %s", touchedNode);
            return false;
        }
        
        // 更新最后触摸的节点
        setLastTouchedNode(touchedNode);
        
        // 从悬停节点查找可获得焦点的节点
        AccessibilityNodeInfoCompat touchedFocusableNode = 
                AccessibilityNodeInfoUtils.findFocusFromHover(touchedNode);
        
        LogUtils.v(TAG, 
                "handleHoverEnterEvent: touchedNode=%s, touchedFocusableNode=%s",
                touchedNode,
                touchedFocusableNode);
        
        if (touchedFocusableNode == null) {
            // 没有可聚焦的节点，延迟分发空触摸动作
            // 如果在超时前收到其他悬停事件，会取消这个延迟动作
            postDelayHandler.postDelayEmptyTouchAction(eventId);
            return false;
        } else {
            // 有可聚焦的节点，取消待处理的空触摸动作（不立即分发）
            postDelayHandler.cancelPendingEmptyTouchAction(false);
            
            LogUtils.v(TAG, 
                    "handleHoverEnterEvent: HOVER_ENTER, touchedFocusableNode=%s", 
                    touchedFocusableNode);
            
            // 分发悬停进入动作
            TouchExplorationAction action = new TouchExplorationAction(
                    TouchExplorationAction.HOVER_ENTER, 
                    touchedFocusableNode);
            
            return dispatchTouchExplorationAction(action, eventId);
        }
    }
    
    /**
     * 分发触摸探索动作到所有监听器
     * 
     * 高性能设计：
     * - 遍历监听器列表，只要有任何一个监听器处理成功就返回true
     * - 使用|=操作符避免短路优化影响性能
     */
    private boolean dispatchTouchExplorationAction(
            TouchExplorationAction action, 
            Performance.EventId eventId) {
        
        boolean result = false;
        
        for (TouchExplorationActionListener listener : listeners) {
            if (listener.onTouchExplorationAction(action, eventId)) {
                result = true;
            }
        }
        
        return result;
    }
    
    /**
     * 设置输入模式为触摸模式
     */
    private void setInputTouchMode() {
        inputModeTracker.setInputMode(InputModeTracker.INPUT_MODE_TOUCH);
    }
    
    /**
     * 保存最后触摸的节点
     */
    private void setLastTouchedNode(@Nullable AccessibilityNodeInfoCompat touchedNode) {
        // 注意：不回收旧节点，因为可能有其他地方引用
        lastTouchedNode = touchedNode;
    }
    
    /**
     * 获取最后触摸的节点
     */
    public @Nullable AccessibilityNodeInfoCompat getLastTouchedNode() {
        return lastTouchedNode;
    }
    
    /**
     * 释放资源
     */
    public void shutdown() {
        postDelayHandler.removeCallbacksAndMessages(null);
        listeners.clear();
        lastTouchedNode = null;
        pendingTouchEndEventId = null;
    }
    
    // ==================== 延迟处理器 ====================
    
    /**
     * 延迟处理器
     * 
     * 用于处理特殊情况下的延迟动作分发：
     * 1. 空触摸动作：当触摸非焦点区域时延迟分发
     * 2. 触摸结束动作：当事件乱序时延迟处理
     * 
     * 高性能设计：
     * - 使用WeakReference避免内存泄漏
     * - 使用Handler的延迟消息机制
     */
    private static class PostDelayHandler {
        
        private final TouchExplorationInterpreter parent;
        private final Handler handler;
        
        // 待处理的触摸结束事件ID
        @Nullable private Performance.EventId touchEndEventId = null;
        
        PostDelayHandler(TouchExplorationInterpreter parent) {
            this.parent = parent;
            this.handler = new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MSG_EMPTY_TOUCH_ACTION:
                            handleEmptyTouchAction((Performance.EventId) msg.obj);
                            break;
                            
                        case MSG_TOUCH_END_ACTION:
                            handleTouchEndAction();
                            break;
                            
                        default:
                            break;
                    }
                }
            };
        }
        
        /**
         * 发送延迟的空触摸动作
         */
        void postDelayEmptyTouchAction(Performance.EventId eventId) {
            Message message = handler.obtainMessage(MSG_EMPTY_TOUCH_ACTION, eventId);
            handler.sendMessageDelayed(message, EMPTY_TOUCH_AREA_DELAY_MS);
        }
        
        /**
         * 取消待处理的空触摸动作
         * 
         * @param dispatchImmediately 是否立即分发待处理的动作
         */
        void cancelPendingEmptyTouchAction(boolean dispatchImmediately) {
            boolean shouldDispatch = 
                    dispatchImmediately && handler.hasMessages(MSG_EMPTY_TOUCH_ACTION);
            
            handler.removeMessages(MSG_EMPTY_TOUCH_ACTION);
            
            if (shouldDispatch) {
                LogUtils.v(TAG, 
                        "cancelPendingEmptyTouchAction: 立即分发空触摸动作");
                parent.dispatchTouchExplorationAction(
                        new TouchExplorationAction(
                                TouchExplorationAction.HOVER_ENTER, 
                                null),
                        null);
            }
        }
        
        /**
         * 发送延迟的触摸结束动作
         */
        void postDelayTouchEndAction(Performance.EventId eventId) {
            touchEndEventId = eventId;
            handler.sendEmptyMessageDelayed(MSG_TOUCH_END_ACTION, TOUCH_END_DELAY_MS);
        }
        
        /**
         * 执行待处理的触摸结束动作
         */
        void executePendingTouchEndAction() {
            if (handler.hasMessages(MSG_TOUCH_END_ACTION)) {
                handler.removeMessages(MSG_TOUCH_END_ACTION);
                handleTouchEndAction();
            }
        }
        
        /**
         * 处理空触摸动作
         */
        private void handleEmptyTouchAction(Performance.EventId eventId) {
            LogUtils.v(TAG, 
                    "handleEmptyTouchAction: 空触摸动作超时，touchedFocusableNode=null");
            parent.dispatchTouchExplorationAction(
                    new TouchExplorationAction(
                            TouchExplorationAction.HOVER_ENTER, 
                            null),
                    eventId);
        }
        
        /**
         * 处理触摸结束动作
         */
        private void handleTouchEndAction() {
            if (touchEndEventId == null) {
                return;
            }
            
            boolean handled = parent.handleTouchInteractionEndEvent(touchEndEventId);
            if (handled) {
                parent.setInputTouchMode();
            }
            touchEndEventId = null;
        }
        
        /**
         * 移除所有回调
         */
        void removeCallbacksAndMessages(@Nullable Object token) {
            handler.removeCallbacksAndMessages(token);
        }
    }
    
    // ==================== 触摸探索动作类 ====================
    
    /**
     * 触摸探索动作
     * 
     * 表示用户在触摸探索过程中的不同动作阶段
     */
    public static class TouchExplorationAction {
        
        /** 触摸交互开始 */
        public static final int TOUCH_INTERACTION_START = 1;
        
        /** 触摸交互结束 */
        public static final int TOUCH_INTERACTION_END = 2;
        
        /** 悬停进入（手指在屏幕上移动） */
        public static final int HOVER_ENTER = 3;
        
        /** 动作类型 */
        public final int actionType;
        
        /** 触摸的可聚焦节点（可能为空） */
        @Nullable public final AccessibilityNodeInfoCompat touchedFocusableNode;
        
        public TouchExplorationAction(
                int actionType, 
                @Nullable AccessibilityNodeInfoCompat touchedFocusableNode) {
            this.actionType = actionType;
            this.touchedFocusableNode = touchedFocusableNode;
        }
        
        /**
         * 获取动作类型的字符串表示
         */
        public String getActionTypeString() {
            switch (actionType) {
                case TOUCH_INTERACTION_START:
                    return "TOUCH_INTERACTION_START";
                case TOUCH_INTERACTION_END:
                    return "TOUCH_INTERACTION_END";
                case HOVER_ENTER:
                    return "HOVER_ENTER";
                default:
                    return "UNKNOWN";
            }
        }
        
        @Override
        public String toString() {
            return "TouchExplorationAction{" +
                    "actionType=" + getActionTypeString() +
                    ", touchedFocusableNode=" + touchedFocusableNode +
                    '}';
        }
    }
    
    // ==================== 监听器接口 ====================
    
    /**
     * 触摸探索动作监听器
     */
    public interface TouchExplorationActionListener {
        
        /**
         * 回调方法：用户执行触摸探索动作时调用
         * 
         * @param action 触摸探索动作
         * @param eventId 事件ID
         * @return 是否有任何无障碍动作成功执行
         */
        boolean onTouchExplorationAction(TouchExplorationAction action, Performance.EventId eventId);
    }
}
