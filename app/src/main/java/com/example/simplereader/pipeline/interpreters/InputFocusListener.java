package com.example.simplereader.pipeline.interpreters;

import com.example.simplereader.pipeline.Performance;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public interface InputFocusListener {
    void onViewTargeted(@Nullable Performance.EventId eventId, AccessibilityEvent event, AccessibilityNodeInfoCompat targetedNode);
}
