package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.ActorState;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.pipeline.GlobalVariables;
import com.example.simplereader.utils.input.InputModeTracker;
import com.example.simplereader.utils.input.TouchMonitor;

public class Interpreters {
    private final InputFocusInterpreter inputFocusInterpreter;
    private final AccessibilityFocusInterpreter accessibilityFocusInterpreter;
    private final ScrollEventInterpreter scrollEventInterpreter;
    private final SelectionEventInterpreter selectionInterpreter;
    private final StateChangeEventInterpreter stateChangeEventInterpreter;
    private final SubtreeChangeEventInterpreter subtreeChangeEventInterpreter;
    private final TouchExplorationInterpreter touchExplorationInterpreter;
    private final UiChangeEventInterpreter uiChangeEventInterpreter;
    
    private final int eventTypeMask;
    private Pipeline.InterpretationReceiver pipelineReceiver;
    
    public Interpreters(android.content.Context context) {
        GlobalVariables globalVariables = new GlobalVariables();
        com.example.simplereader.pipeline.InterpreterDependencies dependencies = new com.example.simplereader.pipeline.InterpreterDependencies();
        
        TouchMonitor touchMonitor = new TouchMonitor();
        InputModeTracker inputModeTracker = new InputModeTracker();
        com.example.simplereader.utils.FocusFinder focusFinder = new com.example.simplereader.utils.FocusFinder();
        
        inputFocusInterpreter = new InputFocusInterpreter(focusFinder, globalVariables, dependencies);
        accessibilityFocusInterpreter = new AccessibilityFocusInterpreter(context, null, null, null);
        scrollEventInterpreter = new ScrollEventInterpreter(touchMonitor, false);
        selectionInterpreter = new SelectionEventInterpreter();
        stateChangeEventInterpreter = new StateChangeEventInterpreter();
        subtreeChangeEventInterpreter = new SubtreeChangeEventInterpreter();
        touchExplorationInterpreter = new TouchExplorationInterpreter(inputModeTracker);
        uiChangeEventInterpreter = new UiChangeEventInterpreter();
        
        eventTypeMask = inputFocusInterpreter.getEventTypes() |
                accessibilityFocusInterpreter.getEventTypes() |
                scrollEventInterpreter.getEventTypes() |
                selectionInterpreter.getEventTypes() |
                stateChangeEventInterpreter.getEventTypes() |
                subtreeChangeEventInterpreter.getEventTypes() |
                touchExplorationInterpreter.getEventTypes() |
                uiChangeEventInterpreter.getEventTypes();
    }
    
    public void setPipelineInterpretationReceiver(Pipeline.InterpretationReceiver receiver) {
        this.pipelineReceiver = receiver;
        accessibilityFocusInterpreter.setPipeline(receiver);
        scrollEventInterpreter.addListener((event, interpretation, eventId) -> {});
    }
    
    public void setActorState(ActorState actorState) {
        inputFocusInterpreter.setActorState(actorState);
        accessibilityFocusInterpreter.setActorState(actorState);
    }
    
    public int getEventTypes() { return eventTypeMask; }
    
    public void onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        inputFocusInterpreter.onAccessibilityEvent(event, eventId);
        accessibilityFocusInterpreter.onAccessibilityEvent(event, eventId);
        scrollEventInterpreter.onAccessibilityEvent(event, eventId);
        selectionInterpreter.onAccessibilityEvent(event, eventId);
        stateChangeEventInterpreter.onAccessibilityEvent(event, eventId);
        subtreeChangeEventInterpreter.onAccessibilityEvent(event, eventId);
        touchExplorationInterpreter.onAccessibilityEvent(event, eventId);
        uiChangeEventInterpreter.onAccessibilityEvent(event, eventId);
    }
    
    public void onIdle() {}
    
    public void interpret(Performance.EventId eventId, com.example.simplereader.pipeline.Pipeline.SyntheticEvent event) {}
}
