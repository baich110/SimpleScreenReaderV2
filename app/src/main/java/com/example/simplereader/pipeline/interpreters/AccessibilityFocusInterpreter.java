package com.example.simplereader.pipeline.interpreters;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.pipeline.focus.AccessibilityFocusMonitor;
import com.example.simplereader.pipeline.focus.ScreenStateMonitor;

public class AccessibilityFocusInterpreter implements AccessibilityEventInterpreter {
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED;
    private final Context context;
    private Pipeline.InterpretationReceiver pipelineReceiver;
    
    public AccessibilityFocusInterpreter(Context context, AccessibilityFocusMonitor monitor, ScreenStateMonitor.State screenState, Analytics analytics) {
        this.context = context;
    }
    
    public void setPipeline(Pipeline.InterpretationReceiver receiver) { this.pipelineReceiver = receiver; }
    public void setActorState(com.example.simplereader.pipeline.ActorState actorState) {}
    
    @Override
    public int getEventTypes() { return MASK_EVENTS; }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        return Interpretation.NO_CHANGE;
    }
    
    public interface ScreenStateChangeListener {
        boolean onScreenStateChanged(com.example.simplereader.pipeline.focus.ScreenState screenState, Performance.EventId eventId);
    }
    
    public interface ManualScrollListener {
        void onManualScroll(ManualScrollInterpretation interpretation);
    }
    
    public interface TouchExplorationListener {
        boolean onTouchExplorationAction(TouchExplorationInterpreter.TouchExplorationAction action, Performance.EventId eventId);
    }
    
    public interface InputFocusListener {
        void onViewTargeted(Performance.EventId eventId, AccessibilityEvent event, androidx.core.view.accessibility.AccessibilityNodeInfoCompat targetedNode);
    }
}
