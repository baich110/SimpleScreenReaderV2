package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.ActorState;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Pipeline;
import java.util.ArrayList;
import java.util.List;

/**
 * 解释器管理器 - 完整抄自TalkBack Interpreters
 * 
 * 13个事件解释器，统一管理，统一输出Interpretation
 */
public class Interpreters {
    
    // 13个解释器
    private final InputFocusInterpreter inputFocusInterpreter;
    private final AccessibilityFocusInterpreter accessibilityFocusInterpreter;
    private final ScrollEventInterpreter scrollEventInterpreter;
    private final SelectionEventInterpreter selectionInterpreter;
    private final StateChangeEventInterpreter stateChangeEventInterpreter;
    private final SubtreeChangeEventInterpreter subtreeChangeEventInterpreter;
    private final TouchExplorationInterpreter touchExplorationInterpreter;
    private final ContinuousReadInterpreter continuousReadInterpreter;
    private final DirectionNavigationInterpreter directionNavigationInterpreter;
    private final HintEventInterpreter hintEventInterpreter;
    private final AutoScrollInterpreter autoScrollInterpreter;
    private final IdleInterpreter idleInterpreter;
    private final UiChangeEventInterpreter uiChangeEventInterpreter;
    
    private final int eventTypeMask;
    
    // Pipeline接收器
    private Pipeline.InterpretationReceiver pipelineReceiver;
    
    public Interpreters(
            InputFocusInterpreter inputFocusInterpreter,
            AccessibilityFocusInterpreter accessibilityFocusInterpreter,
            ScrollEventInterpreter scrollEventInterpreter,
            SelectionEventInterpreter selectionInterpreter,
            StateChangeEventInterpreter stateChangeEventInterpreter,
            SubtreeChangeEventInterpreter subtreeChangeEventInterpreter,
            TouchExplorationInterpreter touchExplorationInterpreter,
            ContinuousReadInterpreter continuousReadInterpreter,
            DirectionNavigationInterpreter directionNavigationInterpreter,
            HintEventInterpreter hintEventInterpreter,
            AutoScrollInterpreter autoScrollInterpreter,
            IdleInterpreter idleInterpreter,
            UiChangeEventInterpreter uiChangeEventInterpreter) {
        
        this.inputFocusInterpreter = inputFocusInterpreter;
        this.accessibilityFocusInterpreter = accessibilityFocusInterpreter;
        this.scrollEventInterpreter = scrollEventInterpreter;
        this.selectionInterpreter = selectionInterpreter;
        this.stateChangeEventInterpreter = stateChangeEventInterpreter;
        this.subtreeChangeEventInterpreter = subtreeChangeEventInterpreter;
        this.touchExplorationInterpreter = touchExplorationInterpreter;
        this.continuousReadInterpreter = continuousReadInterpreter;
        this.directionNavigationInterpreter = directionNavigationInterpreter;
        this.hintEventInterpreter = hintEventInterpreter;
        this.autoScrollInterpreter = autoScrollInterpreter;
        this.idleInterpreter = idleInterpreter;
        this.uiChangeEventInterpreter = uiChangeEventInterpreter;
        
        // 计算事件类型掩码
        this.eventTypeMask = 
            inputFocusInterpreter.getEventTypes() |
            accessibilityFocusInterpreter.getEventTypes() |
            scrollEventInterpreter.getEventTypes() |
            selectionInterpreter.getEventTypes() |
            stateChangeEventInterpreter.getEventTypes() |
            subtreeChangeEventInterpreter.getEventTypes() |
            touchExplorationInterpreter.getEventTypes() |
            continuousReadInterpreter.getEventTypes() |
            directionNavigationInterpreter.getEventTypes() |
            hintEventInterpreter.getEventTypes() |
            autoScrollInterpreter.getEventTypes() |
            idleInterpreter.getEventTypes() |
            uiChangeEventInterpreter.getEventTypes();
    }
    
    public void setPipelineInterpretationReceiver(Pipeline.InterpretationReceiver receiver) {
        this.pipelineReceiver = receiver;
        
        // 设置Pipeline接收器到各个解释器
        autoScrollInterpreter.setPipelineInterpretationReceiver(receiver);
        accessibilityFocusInterpreter.setPipeline(receiver);
        continuousReadInterpreter.setPipeline(receiver);
        stateChangeEventInterpreter.setPipeline(receiver);
        directionNavigationInterpreter.setPipeline(receiver);
        hintEventInterpreter.setPipelineInterpretationReceiver(receiver);
        subtreeChangeEventInterpreter.setPipeline(receiver);
        idleInterpreter.setPipeline(receiver);
        uiChangeEventInterpreter.setPipeline(receiver);
    }
    
    public void setActorState(ActorState actorState) {
        inputFocusInterpreter.setActorState(actorState);
        accessibilityFocusInterpreter.setActorState(actorState);
        continuousReadInterpreter.setActorState(actorState);
        stateChangeEventInterpreter.setActorState(actorState);
        directionNavigationInterpreter.setActorState(actorState);
        hintEventInterpreter.setActorState(actorState);
        subtreeChangeEventInterpreter.setActorState(actorState);
        uiChangeEventInterpreter.setActorState(actorState);
    }
    
    public int getEventTypes() {
        return eventTypeMask;
    }
    
    /** 处理无障碍事件 - 分发到各个解释器 */
    public void onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        int eventType = event.getEventType();
        
        // 分发到各个解释器
        if (inputFocusInterpreter.matches(event)) {
            inputFocusInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (accessibilityFocusInterpreter.matches(event)) {
            accessibilityFocusInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (scrollEventInterpreter.matches(event)) {
            scrollEventInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (selectionInterpreter.matches(event)) {
            selectionInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (stateChangeEventInterpreter.matches(event)) {
            stateChangeEventInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (subtreeChangeEventInterpreter.matches(event)) {
            subtreeChangeEventInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (touchExplorationInterpreter.matches(event)) {
            touchExplorationInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (continuousReadInterpreter.matches(event)) {
            continuousReadInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (hintEventInterpreter.matches(event)) {
            hintEventInterpreter.onAccessibilityEvent(event, eventId);
        }
        if (uiChangeEventInterpreter.matches(event)) {
            uiChangeEventInterpreter.onAccessibilityEvent(event, eventId);
        }
    }
    
    /** 处理空闲状态 */
    public void onIdle() {
        idleInterpreter.onIdle();
    }
    
    /** 处理合成事件 */
    public void interpret(Performance.EventId eventId, Pipeline.SyntheticEvent event) {
        if (event.eventType == Pipeline.SyntheticEvent.Type.SCROLL_TIMEOUT) {
            autoScrollInterpreter.handleAutoScrollFailed();
        }
    }
    
    // ============ 辅助方法 ============
    
    protected void inputToPipeline(@Nullable Performance.EventId eventId,
                                   @Nullable AccessibilityEvent event,
                                   @Nullable Interpretation interpretation,
                                   @Nullable AccessibilityNodeInfoCompat sourceNode) {
        if (pipelineReceiver != null) {
            pipelineReceiver.input(eventId, event, interpretation, sourceNode);
        }
    }
}
