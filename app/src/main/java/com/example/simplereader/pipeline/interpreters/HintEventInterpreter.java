/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * HintEventInterpreter - 提示事件解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.AccessibilityNodeInfoUtils;

public class HintEventInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "HintEventInterpreter";
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_VIEW_FOCUSED;
    
    private boolean hintsEnabled = true;
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        if (!hintsEnabled) return Interpretation.NO_CHANGE;
        
        AccessibilityNodeInfoCompat sourceNode = AccessibilityNodeInfoUtils.toCompat(event.getSource());
        if (sourceNode == null) return Interpretation.NO_CHANGE;
        
        CharSequence hintText = sourceNode.getHintText();
        if (hintText != null && hintText.length() > 0) {
            Interpretation.Builder builder = Interpretation.Builder.ofType(Interpretation.Type.HINT)
                    .setEvent(event)
                    .setSource(sourceNode);
            sourceNode.recycle();
            return builder.build();
        }
        
        sourceNode.recycle();
        return Interpretation.NO_CHANGE;
    }
    
    public void setHintsEnabled(boolean enabled) {
        this.hintsEnabled = enabled;
    }
    
    public boolean isHintsEnabled() {
        return hintsEnabled;
    }
}
