/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * AccessibilityEventInterpreter - 无障碍事件解释器接口
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;

/**
 * 所有解释器必须实现的接口
 * 
 * Pipeline架构核心接口之一
 */
public interface AccessibilityEventInterpreter {
    
    /**
     * 获取该解释器监听的事件类型掩码
     * @return 事件类型掩码
     */
    int getEventTypes();
    
    /**
     * 处理无障碍事件
     * 
     * Pipeline架构核心方法：
     * 1. 接收AccessibilityEvent
     * 2. 解析事件，提取语义信息
     * 3. 返回Interpretation（或者NO_CHANGE表示无变化）
     * 
     * @param event 无障碍事件
     * @param eventId 事件ID（用于性能追踪）
     * @return 解释结果，如果没有变化返回Interpretation.NO_CHANGE
     */
    Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId);
}
