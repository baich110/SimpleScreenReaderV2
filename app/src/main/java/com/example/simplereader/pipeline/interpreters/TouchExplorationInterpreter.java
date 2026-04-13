package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.input.InputModeTracker;
import java.util.ArrayList;
import java.util.List;

public class TouchExplorationInterpreter implements AccessibilityEventInterpreter {
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_TOUCH_INTERACTION_START | AccessibilityEvent.TYPE_TOUCH_INTERACTION_END | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER;
    private final InputModeTracker inputModeTracker;
    private final List<TouchExplorationActionListener> listeners = new ArrayList<>();
    
    public TouchExplorationInterpreter(InputModeTracker inputModeTracker) {
        this.inputModeTracker = inputModeTracker;
    }
    
    public void addTouchExplorationActionListener(TouchExplorationActionListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }
    
    @Override
    public int getEventTypes() { return MASK_EVENTS; }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        return Interpretation.NO_CHANGE;
    }
    
    public interface TouchExplorationActionListener {
        boolean onTouchExplorationAction(TouchExplorationAction action, Performance.EventId eventId);
    }
    
    public static class TouchExplorationAction {
        public static final int TOUCH_INTERACTION_START = 1;
        public static final int TOUCH_INTERACTION_END = 2;
        public static final int HOVER_ENTER = 3;
        public final int actionType;
        public final AccessibilityNodeInfoCompat touchedFocusableNode;
        public TouchExplorationAction(int actionType, AccessibilityNodeInfoCompat touchedFocusableNode) {
            this.actionType = actionType;
            this.touchedFocusableNode = touchedFocusableNode;
        }
    }
}
