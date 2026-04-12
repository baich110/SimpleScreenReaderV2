/*
 * FocusFinder - 焦点查找器
 */
package com.example.simplereader.utils;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class FocusFinder {
    
    private android.view.accessibility.AccessibilityNodeInfo findFocusCompat(int direction) {
        return null;
    }
    
    public AccessibilityNodeInfoCompat findFocusCompat(int focusType) {
        android.view.accessibility.AccessibilityNodeInfo info = findFocusCompat(focusType);
        if (info != null) {
            return AccessibilityNodeInfoCompat.wrap(info);
        }
        return null;
    }
}
