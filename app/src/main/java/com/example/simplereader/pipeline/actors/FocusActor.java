package com.example.simplereader.pipeline.actors;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class FocusActor {
    
    private static final String TAG = "FocusActor";
    
    private final AccessibilityService service;
    private AccessibilityNodeInfoCompat currentFocus;
    
    public FocusActor(AccessibilityService service) {
        this.service = service;
    }
    
    public boolean setAccessibilityFocus(AccessibilityNodeInfoCompat node) {
        if (node == null) return false;
        boolean result = node.performAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
        if (result) {
            currentFocus = node;
            Log.d(TAG, "设置无障碍焦点成功");
        }
        return result;
    }
    
    public boolean clearAccessibilityFocus(AccessibilityNodeInfoCompat node) {
        if (node == null) return false;
        boolean result = node.performAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_UNFOCUS);
        if (result && currentFocus != null && currentFocus.equals(node)) {
            currentFocus = null;
        }
        return result;
    }
    
    public boolean click(AccessibilityNodeInfoCompat node) {
        if (node == null) return false;
        return node.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
    }
    
    public boolean performAction(AccessibilityNodeInfoCompat node, int action, Bundle arguments) {
        if (node == null) return false;
        return node.performAction(action, arguments);
    }
    
    public AccessibilityNodeInfoCompat getCurrentFocus() { return currentFocus; }
    public void setCurrentFocus(AccessibilityNodeInfoCompat focus) { this.currentFocus = focus; }
    
    public void clearCurrentFocus() {
        if (currentFocus != null) {
            currentFocus.recycle();
            currentFocus = null;
        }
    }
    
    public void shutdown() {
        clearCurrentFocus();
    }
}
