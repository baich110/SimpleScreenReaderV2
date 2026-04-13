package com.example.simplereader.pipeline;

import android.view.accessibility.AccessibilityEvent;

/**
 * AccessibilityEventListener - 无障碍事件监听器接口
 */
public interface AccessibilityEventListener {
    int getEventTypes();
    void onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId);
}
