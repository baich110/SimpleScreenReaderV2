package com.example.simplereader.pipeline.interpreters;

import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.pipeline.focus.ScreenStateMonitor;
import android.view.accessibility.AccessibilityEvent;

public class ScreenStateChangeInterpreter implements AccessibilityEventInterpreter {
    private Pipeline.InterpretationReceiver receiver;
    private ScreenStateMonitor.State screenState;

    public void setPipelineInterpretationReceiver(Pipeline.InterpretationReceiver receiver) { this.receiver = receiver; }
    public void setScreenStateMonitor(ScreenStateMonitor.State state) { this.screenState = state; }

    @Override
    public int getEventTypes() { return AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED; }

    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        return Interpretation.NO_CHANGE;
    }

    public void setActorState(com.example.simplereader.pipeline.ActorState actorState) {}
}
