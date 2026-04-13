package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.input.TouchMonitor;
import com.example.simplereader.utils.traversal.TraversalStrategy;
import java.util.ArrayList;
import java.util.List;

public class ScrollEventInterpreter implements AccessibilityEventInterpreter {
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_VIEW_SCROLLED;
    private final TouchMonitor touchMonitor;
    private final List<ScrollEventHandler> listeners = new ArrayList<>();
    
    public ScrollEventInterpreter(TouchMonitor touchMonitor, boolean supportMultipleAutoScroll) {
        this.touchMonitor = touchMonitor;
    }
    
    public void addListener(ScrollEventHandler listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(ScrollEventHandler listener) { listeners.remove(listener); }
    
    @Override
    public int getEventTypes() { return MASK_EVENTS; }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        return Interpretation.NO_CHANGE;
    }
    
    public void handleAutoScrollFailed() {}
    
    public interface ScrollEventHandler {
        void onScrollEvent(AccessibilityEvent event, ScrollEventInterpretation interpretation, Performance.EventId eventId);
    }
    
    public static class ScrollEventInterpretation {
        public ScrollEventInterpretation(int userAction, int scrollDirection, boolean hasValidIndex, boolean isDuplicate, boolean isFromScrollable, boolean isMediaPlayerAutoScroll, int scrollInstanceId) {}
    }
}
