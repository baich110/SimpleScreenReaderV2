package com.example.simplereader.pipeline.focus;

import android.content.Context;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.interpreters.Pipeline;
import com.example.simplereader.pipeline.interpreters.TouchExplorationInterpreter;

public class FocusProcessorForTapAndTouchExploration {
    private final Context context;
    private Pipeline.InterpretationReceiver interpretationReceiver;
    private ActorState actorState;
    private boolean singleTapEnabled = true;
    
    public FocusProcessorForTapAndTouchExploration(Context context) {
        this.context = context;
    }
    
    public void setInterpretationReceiver(Pipeline.InterpretationReceiver receiver) { this.interpretationReceiver = receiver; }
    public void setActorState(ActorState actorState) { this.actorState = actorState; }
    
    public boolean onTouchExplorationAction(TouchExplorationInterpreter.TouchExplorationAction action, Performance.EventId eventId) {
        return true;
    }
    
    public boolean performSplitTap(Performance.EventId eventId) { return false; }
    public void setSingleTapEnabled(boolean enabled) { this.singleTapEnabled = enabled; }
    public boolean getSingleTapEnabled() { return singleTapEnabled; }
    public void setTypingMethod(int type) {}
    public void setTypingLongPressDurationMs(int duration) {}
    public void shutdown() { interpretationReceiver = null; actorState = null; }
}
