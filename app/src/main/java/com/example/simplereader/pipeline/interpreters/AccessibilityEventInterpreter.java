package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;

public interface AccessibilityEventInterpreter {
    int getEventTypes();
    Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId);
    default boolean matches(AccessibilityEvent event) { return true; }
}
