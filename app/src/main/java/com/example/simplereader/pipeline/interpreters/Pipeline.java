package com.example.simplereader.pipeline.interpreters;

import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class Pipeline {
    public interface InterpretationReceiver {
        boolean input(@Nullable Performance.EventId eventId, @Nullable AccessibilityEvent event,
                      @Nullable Interpretation interpretation, @Nullable AccessibilityNodeInfoCompat sourceNode);
    }
}
