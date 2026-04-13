/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * ContinuousReadInterpreter - 连续阅读解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.traversal.TraversalStrategy;

public class ContinuousReadInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "ContinuousReadInterpreter";
    private static final int MASK_EVENTS = AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
    
    private boolean isReading = false;
    private int currentGranularity = TraversalStrategy.MOVEMENT_GRANULARITY_LINE;
    private AccessibilityNodeInfoCompat readingNode;
    
    public interface ContinuousReadHandler {
        void onContinuousReadStart(int granularity);
        void onContinuousReadStop();
        void onContinuousReadPause();
    }
    
    private ContinuousReadHandler handler;
    
    public void setContinuousReadHandler(ContinuousReadHandler handler) {
        this.handler = handler;
    }
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        if (isReading) {
            AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(event.getSource());
            if (node != null) {
                Interpretation.Builder builder = Interpretation.builder().ofType(Interpretation.Type.CONTINUOUS_READ)
                        .setEvent(event)
                        .setSource(node)
                        .setGranularitySelection(currentGranularity);
                node.recycle();
                return builder.build();
            }
        }
        return Interpretation.NO_CHANGE;
    }
    
    public void startContinuousRead(int granularity) {
        isReading = true;
        currentGranularity = granularity;
        if (handler != null) {
            handler.onContinuousReadStart(granularity);
        }
    }
    
    public void stopContinuousRead() {
        isReading = false;
        if (handler != null) {
            handler.onContinuousReadStop();
        }
    }
    
    public void pauseContinuousRead() {
        if (handler != null) {
            handler.onContinuousReadPause();
        }
    }
    
    public boolean isReading() {
        return isReading;
    }
    
    public void setGranularity(int granularity) {
        this.currentGranularity = granularity;
    }
    
    public int getGranularity() {
        return currentGranularity;
    }
}
