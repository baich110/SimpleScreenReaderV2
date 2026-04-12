/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * SubtreeChangeEventInterpreter - 子树变化解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.AccessibilityNodeInfoUtils;

public class SubtreeChangeEventInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "SubtreeChangeEventInterpreter";
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
    
    private AccessibilityNodeInfoCompat lastFocusedNode;
    private long lastChangeTime = 0;
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) {
                AccessibilityNodeInfoCompat sourceNode = AccessibilityNodeInfoUtils.toCompat(event.getSource());
                if (sourceNode != null) {
                    long currentTime = System.currentTimeMillis();
                    boolean isRapidChange = (currentTime - lastChangeTime) < 100;
                    lastChangeTime = currentTime;
                    
                    Interpretation.Builder builder = Interpretation.Builder.ofType(Interpretation.Type.CONTENT_CHANGED)
                            .setEvent(event)
                            .setSource(sourceNode);
                    
                    sourceNode.recycle();
                    return builder.build();
                }
            }
        }
        return Interpretation.NO_CHANGE;
    }
    
    public void clearState() {
        lastChangeTime = 0;
        if (lastFocusedNode != null) {
            lastFocusedNode.recycle();
            lastFocusedNode = null;
        }
    }
}
