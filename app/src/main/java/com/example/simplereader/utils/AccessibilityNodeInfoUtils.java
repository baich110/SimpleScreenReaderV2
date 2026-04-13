package com.example.simplereader.utils;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class AccessibilityNodeInfoUtils {
    public static AccessibilityNodeInfoCompat toCompat(@Nullable AccessibilityNodeInfo node) {
        if (node == null) return null;
        return AccessibilityNodeInfoCompat.wrap(node);
    }
    
    public static boolean isScrollableNode(AccessibilityNodeInfoCompat node) {
        if (node == null) return false;
        return node.isScrollable();
    }
    
    public static boolean shouldFocusNode(AccessibilityNodeInfoCompat node) {
        if (node == null) return false;
        return node.isVisibleToUser() && node.isEnabled();
    }
    
    public static AccessibilityNodeInfoCompat findFocusFromHover(AccessibilityNodeInfoCompat node) {
        return node;
    }
    
    public static void recycle(AccessibilityNodeInfoCompat node) {
        if (node != null) node.recycle();
    }
}
