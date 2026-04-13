package com.example.simplereader.utils;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class FocusFinder {
    private AccessibilityNodeInfo findFocusInternal(int direction) { return null; }
    
    public AccessibilityNodeInfoCompat findFocusCompat(int focusType) {
        AccessibilityNodeInfo info = findFocusInternal(focusType);
        if (info != null) return AccessibilityNodeInfoCompat.wrap(info);
        return null;
    }
}
