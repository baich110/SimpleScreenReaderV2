package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;

public class UiChangeEventInterpreter implements AccessibilityEventInterpreter {
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    
    @Override
    public int getEventTypes() { return MASK_EVENTS; }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        return Interpretation.NO_CHANGE;
    }
}
