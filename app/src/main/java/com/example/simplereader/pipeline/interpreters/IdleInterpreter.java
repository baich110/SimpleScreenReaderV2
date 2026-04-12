/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * IdleInterpreter - 空闲检测解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;

public class IdleInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "IdleInterpreter";
    private static final int MASK_EVENTS = 0; // 监听所有事件来检测空闲
    
    private long lastEventTime = 0;
    private long idleThresholdMs = 3000; // 3秒空闲阈值
    
    public interface IdleListener {
        void onIdle();
        void onActivity();
    }
    
    private IdleListener listener;
    
    public IdleInterpreter(long idleThresholdMs) {
        this.idleThresholdMs = idleThresholdMs;
    }
    
    public void setIdleListener(IdleListener listener) {
        this.listener = listener;
    }
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        long currentTime = System.currentTimeMillis();
        
        if (lastEventTime > 0) {
            long idleTime = currentTime - lastEventTime;
            if (idleTime >= idleThresholdMs) {
                if (listener != null) {
                    listener.onIdle();
                }
            }
        }
        
        if (listener != null) {
            listener.onActivity();
        }
        
        lastEventTime = currentTime;
        return Interpretation.NO_CHANGE;
    }
    
    public long getIdleTime() {
        return System.currentTimeMillis() - lastEventTime;
    }
    
    public void resetIdleTimer() {
        lastEventTime = System.currentTimeMillis();
    }
    
    public void setIdleThreshold(long thresholdMs) {
        this.idleThresholdMs = thresholdMs;
    }
}
