/*
 * ActorState - 执行器状态
 */
package com.example.simplereader.pipeline.actor;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class ActorState {
    
    private FocusHistory focusHistory = new FocusHistory();
    private LabelManagerState labelManagerState = new LabelManagerState();
    
    public FocusHistory getFocusHistory() {
        return focusHistory;
    }
    
    public LabelManagerState getLabelManagerState() {
        return labelManagerState;
    }
    
    public InputFocusActionRecord getInputFocusActionRecord() {
        return null;
    }
    
    public static class FocusHistory {
        public Object getFocusActionInfoFromEvent(Object event) {
            return null;
        }
    }
    
    public static class LabelManagerState {
        public int getLabelIdForNode(AccessibilityNodeInfoCompat node) {
            return 0;
        }
    }
    
    public static class InputFocusActionRecord {
        public long actionTime = 0;
        public AccessibilityNodeInfoCompat inputFocusedNode;
    }
}
