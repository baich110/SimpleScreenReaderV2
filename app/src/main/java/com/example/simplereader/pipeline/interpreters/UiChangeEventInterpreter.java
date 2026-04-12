/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * UiChangeEventInterpreter - UI变化解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.AccessibilityNodeInfoUtils;

public class UiChangeEventInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "UiChangeEventInterpreter";
    private static final int MASK_EVENTS = 
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    
    private long lastChangeTime = 0;
    private static final long DEBOUNCE_TIME_MS = 100;
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        long currentTime = System.currentTimeMillis();
        
        // 防抖处理
        if (currentTime - lastChangeTime < DEBOUNCE_TIME_MS) {
            return Interpretation.NO_CHANGE;
        }
        
        lastChangeTime = currentTime;
        
        AccessibilityNodeInfoCompat sourceNode = AccessibilityNodeInfoUtils.toCompat(event.getSource());
        if (sourceNode == null) return Interpretation.NO_CHANGE;
        
        int eventType = event.getEventType();
        Interpretation.Type type = Interpretation.Type.CONTENT_CHANGED;
        
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            type = Interpretation.Type.WINDOW_CHANGED;
        }
        
        Interpretation.Builder builder = Interpretation.Builder.ofType(type)
                .setEvent(event)
                .setSource(sourceNode);
        
        sourceNode.recycle();
        return builder.build();
    }
    
    public void clearLastChangeTime() {
        lastChangeTime = 0;
    }
}
