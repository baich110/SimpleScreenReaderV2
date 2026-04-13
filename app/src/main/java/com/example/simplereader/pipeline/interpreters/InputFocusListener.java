package com.example.simplereader.pipeline.interpreters;

import com.example.simplereader.pipeline.Performance;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface InputFocusListener {
    void onViewTargeted(@Nullable Performance.EventId eventId, AccessibilityEvent event, AccessibilityNodeInfoCompat targetedNode);
}
