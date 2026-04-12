/*
 * FocusActor - 焦点控制执行器
 * 基于TalkBack Feedback架构的焦点管理
 */
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
    
    /**
     * 设置无障碍焦点
     */
    public boolean setAccessibilityFocus(AccessibilityNodeInfoCompat node) {
        if (node == null) {
            return false;
        }
        
        boolean result = node.performAction(
                AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
        
        if (result) {
            currentFocus = node;
            Log.d(TAG, "设置无障碍焦点成功");
        } else {
            Log.w(TAG, "设置无障碍焦点失败");
        }
        
        return result;
    }
    
    /**
     * 清除无障碍焦点
     */
    public boolean clearAccessibilityFocus(AccessibilityNodeInfoCompat node) {
        if (node == null) {
            return false;
        }
        
        boolean result = node.performAction(
                AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_UNFOCUS);
        
        if (result) {
            if (currentFocus != null && currentFocus.equals(node)) {
                currentFocus = null;
            }
            Log.d(TAG, "清除无障碍焦点成功");
        }
        
        return result;
    }
    
    /**
     * 点击节点
     */
    public boolean click(AccessibilityNodeInfoCompat node) {
        if (node == null) {
            return false;
        }
        
        return node.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
    }
    
    /**
     * 长按节点
     */
    public boolean longClick(AccessibilityNodeInfoCompat node) {
        if (node == null) {
            return false;
        }
        
        return node.performAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
    }
    
    /**
     * 滚动节点
     */
    public boolean scroll(AccessibilityNodeInfoCompat node, boolean forward) {
        if (node == null) {
            return false;
        }
        
        int action = forward ? 
                AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD :
                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD;
        
        return node.performAction(action);
    }
    
    /**
     * 执行自定义操作
     */
    public boolean performAction(AccessibilityNodeInfoCompat node, int action, Bundle arguments) {
        if (node == null) {
            return false;
        }
        
        return node.performAction(action, arguments);
    }
    
    /**
     * 获取当前焦点节点
     */
    public AccessibilityNodeInfoCompat getCurrentFocus() {
        return currentFocus;
    }
    
    /**
     * 设置当前焦点节点
     */
    public void setCurrentFocus(AccessibilityNodeInfoCompat focus) {
        this.currentFocus = focus;
    }
    
    /**
     * 清除当前焦点
     */
    public void clearCurrentFocus() {
        if (currentFocus != null) {
            currentFocus.recycle();
            currentFocus = null;
        }
    }
    
    /**
     * 释放资源
     */
    public void shutdown() {
        clearCurrentFocus();
    }
}
