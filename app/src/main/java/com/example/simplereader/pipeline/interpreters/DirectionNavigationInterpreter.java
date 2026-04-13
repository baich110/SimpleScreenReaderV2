/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * DirectionNavigationInterpreter - 方向导航解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.traversal.TraversalStrategy;

public class DirectionNavigationInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "DirectionNavigationInterpreter";
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
    
    public interface DirectionNavigationHandler {
        void onDirectionNavigation(int direction, Performance.EventId eventId);
    }
    
    private DirectionNavigationHandler handler;
    private int lastDirection = TraversalStrategy.SEARCH_FOCUS_UNKNOWN;
    
    public void setDirectionNavigationHandler(DirectionNavigationHandler handler) {
        this.handler = handler;
    }
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(event.getSource());
            if (node != null) {
                Interpretation.Builder builder = Interpretation.Builder.ofType(Interpretation.Type.DIRECTION_NAVIGATION)
                        .setEvent(event)
                        .setSource(node)
                        .setDirection(lastDirection);
                node.recycle();
                return builder.build();
            }
        }
        return Interpretation.NO_CHANGE;
    }
    
    public void setLastDirection(int direction) {
        this.lastDirection = direction;
    }
    
    public int getLastDirection() {
        return lastDirection;
    }
}
