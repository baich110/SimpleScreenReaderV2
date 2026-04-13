package com.example.simplereader.pipeline;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.feedback.Feedback;
import com.example.simplereader.pipeline.focus.ScreenStateMonitor;

public class Mappers {
    private final Context context;
    private ScreenStateMonitor.State screenState;
    
    public Mappers(Context context) {
        this.context = context;
    }
    
    public void setMonitors(ScreenStateMonitor.State state) { this.screenState = state; }
    
    public Feedback mapToFeedback(@Nullable Performance.EventId eventId,
            @Nullable AccessibilityEvent event,
            @Nullable Interpretation interpretation,
            @Nullable AccessibilityNodeInfoCompat sourceNode) {
        if (interpretation == null || interpretation == Interpretation.NO_CHANGE) return null;
        Object tag = interpretation.tag();
        if (tag == null) return null;
        String tagStr = tag.toString();
        switch (tagStr) {
            case "AccessibilityFocused": return mapAccessibilityFocused(interpretation, sourceNode);
            case "Touch": return Feedback.create(eventId);
            case "Scroll": return Feedback.create(eventId);
            case "Hint": return mapHint(interpretation);
            case "StateChanged": return Feedback.create(eventId);
            case "ContentChanged": return mapContentChanged(sourceNode);
            default: return null;
        }
    }
    
    private Feedback mapAccessibilityFocused(Interpretation interpretation, AccessibilityNodeInfoCompat node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text == null || text.length() == 0) text = node.getContentDescription();
        if (text == null || text.length() == 0) return null;
        return Feedback.create(null);
    }
    
    private Feedback mapHint(Interpretation interpretation) {
        if (interpretation instanceof Interpretation.Hint) {
            return Feedback.create(null);
        }
        return null;
    }
    
    private Feedback mapContentChanged(AccessibilityNodeInfoCompat node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return Feedback.create(null);
        }
        return null;
    }
}
