package com.example.simplereader.pipeline.focus;

import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.ActorState;
import com.example.simplereader.pipeline.interpreters.Pipeline;

public class FocusProcessorForScreenStateChange {
    private AccessibilityFocusMonitor accessibilityFocusMonitor;
    private Pipeline.InterpretationReceiver interpretationReceiver;
    private ActorState actorState;
    
    public FocusProcessorForScreenStateChange(AccessibilityFocusMonitor monitor) {
        this.accessibilityFocusMonitor = monitor;
    }
    
    public void setPipeline(Pipeline.InterpretationReceiver receiver) { this.interpretationReceiver = receiver; }
    public void setActorState(ActorState actorState) { this.actorState = actorState; }
    
    public boolean onScreenStateChanged(ScreenState screenState, Performance.EventId eventId) { return true; }
    public void shutdown() { interpretationReceiver = null; actorState = null; }
}
