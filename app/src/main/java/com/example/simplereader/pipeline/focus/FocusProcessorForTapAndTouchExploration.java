/*
 * FocusProcessorForTapAndTouchExploration - 触摸探索焦点处理器
 */
package com.example.simplereader.pipeline.focus;

import android.content.Context;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Pipeline;
import com.example.simplereader.pipeline.interpreters.TouchExplorationInterpreter;

public class FocusProcessorForTapAndTouchExploration {
    
    private final Context context;
    private Pipeline.InterpretationReceiver interpretationReceiver;
    private ActorState actorState;
    private Analytics analytics;
    private boolean singleTapEnabled = true;
    
    public FocusProcessorForTapAndTouchExploration(Context context, Analytics analytics) {
        this.context = context;
        this.analytics = analytics;
    }
    
    public void setInterpretationReceiver(Pipeline.InterpretationReceiver receiver) {
        this.interpretationReceiver = receiver;
    }
    
    public void setActorState(ActorState actorState) {
        this.actorState = actorState;
    }
    
    public boolean onTouchExplorationAction(TouchExplorationInterpreter.TouchExplorationAction action, 
                                            Performance.EventId eventId) {
        switch (action.actionType) {
            case TouchExplorationInterpreter.TouchExplorationAction.TOUCH_INTERACTION_START:
                return handleTouchInteractionStart(eventId);
            case TouchExplorationInterpreter.TouchExplorationAction.TOUCH_INTERACTION_END:
                return handleTouchInteractionEnd(action, eventId);
            case TouchExplorationInterpreter.TouchExplorationAction.HOVER_ENTER:
                return handleHoverEnter(action, eventId);
            default:
                return false;
        }
    }
    
    private boolean handleTouchInteractionStart(Performance.EventId eventId) {
        return true;
    }
    
    private boolean handleTouchInteractionEnd(TouchExplorationInterpreter.TouchExplorationAction action,
                                             Performance.EventId eventId) {
        if (action.touchedFocusableNode != null) {
            if (singleTapEnabled) {
                return performSingleTap(action.touchedFocusableNode, eventId);
            }
        }
        return false;
    }
    
    private boolean handleHoverEnter(TouchExplorationInterpreter.TouchExplorationAction action,
                                    Performance.EventId eventId) {
        if (action.touchedFocusableNode != null) {
            return requestAccessibilityFocus(action.touchedFocusableNode, eventId);
        }
        return false;
    }
    
    private boolean performSingleTap(AccessibilityNodeInfoCompat node, Performance.EventId eventId) {
        if (node != null && node.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK)) {
            if (analytics != null) {
                analytics.onSingleTap(eventId);
            }
            return true;
        }
        return false;
    }
    
    private boolean requestAccessibilityFocus(AccessibilityNodeInfoCompat node, Performance.EventId eventId) {
        if (node != null && node.performAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)) {
            return true;
        }
        return false;
    }
    
    public boolean performSplitTap(Performance.EventId eventId) {
        return false;
    }
    
    public void setSingleTapEnabled(boolean enabled) {
        this.singleTapEnabled = enabled;
    }
    
    public boolean getSingleTapEnabled() {
        return singleTapEnabled;
    }
    
    public void setTypingMethod(int type) {}
    public void setTypingLongPressDurationMs(int duration) {}
    
    public void shutdown() {
        interpretationReceiver = null;
        actorState = null;
    }
}
