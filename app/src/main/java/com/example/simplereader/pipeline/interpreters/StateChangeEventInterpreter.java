/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * StateChangeEventInterpreter - 状态变化解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.AccessibilityNodeInfoUtils;

public class StateChangeEventInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "StateChangeEventInterpreter";
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            AccessibilityNodeInfoCompat sourceNode = AccessibilityNodeInfoUtils.toCompat(event.getSource());
            if (sourceNode != null) {
                Bundle bundle = event.getBundle();
                boolean hasStateDescription = bundle != null && 
                        bundle.containsKey("android:stateDescription");
                
                Interpretation.Builder builder = Interpretation.Builder.ofType(Interpretation.Type.STATE_CHANGED)
                        .setEvent(event)
                        .setSource(sourceNode);
                
                sourceNode.recycle();
                return builder.build();
            }
        }
        return Interpretation.NO_CHANGE;
    }
}
