package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.AccessibilityNodeInfoUtils;
import com.example.simplereader.utils.FocusFinder;

public class InputFocusInterpreter implements AccessibilityEventInterpreter {
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_VIEW_FOCUSED | AccessibilityEvent.TYPE_VIEW_SELECTED;
    private final FocusFinder focusFinder;
    private TargetViewChangeListener targetViewChangeListener;
    
    public InputFocusInterpreter(FocusFinder focusFinder, com.example.simplereader.pipeline.GlobalVariables globalVariables, com.example.simplereader.pipeline.InterpreterDependencies dependencies) {
        this.focusFinder = focusFinder;
    }
    
    public void setTargetViewChangeListener(TargetViewChangeListener listener) { this.targetViewChangeListener = listener; }
    public void setActorState(com.example.simplereader.pipeline.ActorState actorState) {}
    
    @Override
    public int getEventTypes() { return MASK_EVENTS; }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return Interpretation.NO_CHANGE;
        }
        return Interpretation.NO_CHANGE;
    }
    
    public interface TargetViewChangeListener {
        void onViewTargeted(Performance.EventId eventId, AccessibilityEvent event, AccessibilityNodeInfoCompat targetedNode);
    }
}
