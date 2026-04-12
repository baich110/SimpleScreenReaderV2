/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * SelectionEventInterpreter - 选择事件解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.AccessibilityNodeInfoUtils;

public class SelectionEventInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "SelectionEventInterpreter";
    private static final int MASK_EVENTS = 
            AccessibilityEvent.TYPE_VIEW_SELECTED |
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED;
    
    private AccessibilityNodeInfoCompat lastSelectedNode;
    private int lastFromIndex = -1;
    private int lastToIndex = -1;
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
                return handleViewSelected(event, eventId);
            case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED:
                return handleTextSelectionChanged(event, eventId);
            default:
                return Interpretation.NO_CHANGE;
        }
    }
    
    private Interpretation handleViewSelected(AccessibilityEvent event, Performance.EventId eventId) {
        AccessibilityNodeInfoCompat sourceNode = AccessibilityNodeInfoUtils.toCompat(event.getSource());
        if (sourceNode == null) return Interpretation.NO_CHANGE;
        
        boolean hasSelectedChild = event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE;
        
        if (lastSelectedNode != null && sourceNode.equals(lastSelectedNode)) {
            sourceNode.recycle();
            return Interpretation.NO_CHANGE;
        }
        
        Interpretation.Builder builder = Interpretation.Builder.ofType(Interpretation.Type.CONTENT_CHANGED)
                .setEvent(event)
                .setSource(sourceNode)
                .setSelectionChanged(hasSelectedChild);
        
        lastSelectedNode = sourceNode;
        return builder.build();
    }
    
    private Interpretation handleTextSelectionChanged(AccessibilityEvent event, Performance.EventId eventId) {
        AccessibilityNodeInfoCompat sourceNode = AccessibilityNodeInfoUtils.toCompat(event.getSource());
        if (sourceNode == null) return Interpretation.NO_CHANGE;
        
        int fromIndex = event.getFromIndex();
        int toIndex = event.getToIndex();
        
        boolean selectionMoved = (fromIndex != lastFromIndex) || (toIndex != lastToIndex);
        lastFromIndex = fromIndex;
        lastToIndex = toIndex;
        
        if (!selectionMoved) {
            sourceNode.recycle();
            return Interpretation.NO_CHANGE;
        }
        
        Interpretation.Builder builder = Interpretation.Builder.ofType(Interpretation.Type.EDIT_TEXT)
                .setEvent(event)
                .setSource(sourceNode)
                .setGranularitySelection(fromIndex)
                .setTextSelection(fromIndex, toIndex);
        
        sourceNode.recycle();
        return builder.build();
    }
    
    public void clearSelectionState() {
        lastFromIndex = -1;
        lastToIndex = -1;
        if (lastSelectedNode != null) {
            lastSelectedNode.recycle();
            lastSelectedNode = null;
        }
    }
}
