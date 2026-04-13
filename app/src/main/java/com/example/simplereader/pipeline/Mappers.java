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
            case "Touch": return mapTouch(interpretation);
            case "Scroll": return Feedback.create(eventId, Feedback.part().build());
            case "Hint": return mapHint(interpretation);
            case "StateChanged": return mapStateChanged(interpretation);
            case "ContentChanged": return mapContentChanged(sourceNode);
            default: return null;
        }
    }
    
    private Feedback mapAccessibilityFocused(Interpretation interpretation, AccessibilityNodeInfoCompat node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text == null || text.length() == 0) text = node.getContentDescription();
        if (text == null || text.length() == 0) return null;
        return Feedback.create(null, Feedback.part().build());
    }
    
    private Feedback mapTouch(Interpretation interpretation) {
        return Feedback.create(null, Feedback.part().build());
    }
    
    private Feedback mapHint(Interpretation interpretation) {
        if (interpretation instanceof Interpretation.Hint) {
            CharSequence hintText = ((Interpretation.Hint) interpretation).hintText();
            if (hintText != null && hintText.length() > 0) {
                return Feedback.create(null, Feedback.part().build());
            }
        }
        return null;
    }
    
    private Feedback mapStateChanged(Interpretation interpretation) {
        if (interpretation instanceof Interpretation.StateChanged) {
            return Feedback.create(null, Feedback.part().build());
        }
        return null;
    }
    
    private Feedback mapContentChanged(AccessibilityNodeInfoCompat node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return Feedback.create(null, Feedback.part().build());
        }
        return null;
    }
}
