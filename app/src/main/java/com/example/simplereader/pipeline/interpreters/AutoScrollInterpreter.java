/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * AutoScrollInterpreter - 自动滚动解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.traversal.TraversalStrategy;

public class AutoScrollInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "AutoScrollInterpreter";
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_VIEW_SCROLLED;
    
    private boolean autoScrollEnabled = true;
    private int autoScrollDelayMs = 1000;
    private int lastScrollInstanceId = -1;
    
    public interface AutoScrollHandler {
        void onAutoScroll(int direction, Performance.EventId eventId);
    }
    
    private AutoScrollHandler handler;
    
    public void setAutoScrollHandler(AutoScrollHandler handler) {
        this.handler = handler;
    }
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        if (!autoScrollEnabled) return Interpretation.NO_CHANGE;
        
        AccessibilityNodeInfoCompat node = androidx.core.view.accessibility.AccessibilityNodeInfoUtils.toCompat(event.getSource());
        if (node == null) return Interpretation.NO_CHANGE;
        
        int direction = TraversalStrategy.SEARCH_FOCUS_FORWARD;
        node.recycle();
        
        if (handler != null) {
            handler.onAutoScroll(direction, eventId);
        }
        
        return Interpretation.NO_CHANGE;
    }
    
    public void setAutoScrollEnabled(boolean enabled) {
        this.autoScrollEnabled = enabled;
    }
    
    public void setAutoScrollDelay(int delayMs) {
        this.autoScrollDelayMs = delayMs;
    }
}
