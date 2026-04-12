/*
 * SimpleScreenReaderService - 简单读屏服务主类
 * 基于TalkBack Pipeline架构的高性能无障碍服务
 */
package com.example.simplereader;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Pipeline;
import com.example.simplereader.pipeline.interpreters.Interpreters;

public class SimpleScreenReaderService extends AccessibilityService {
    
    private static final String TAG = "SimpleScreenReader";
    
    // Pipeline核心组件
    private Pipeline pipeline;
    private Interpreters interpreters;
    private Performance performance;
    
    @Override
    public void onCreate() {
        super.onCreate();
        initializePipeline();
    }
    
    private void initializePipeline() {
        // 初始化性能监控
        performance = Performance.getInstance();
        
        // 初始化解释器管理器
        interpreters = new Interpreters(this);
        
        // 初始化Pipeline
        pipeline = new Pipeline(this, interpreters);
        pipeline.start();
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 记录事件性能
        Performance.EventId eventId = performance.onEventReceived(event);
        
        // 通过Pipeline处理事件
        if (pipeline != null) {
            pipeline.onAccessibilityEvent(event, eventId);
        }
    }
    
    @Override
    public void onInterrupt() {
        // 服务中断处理
        if (pipeline != null) {
            pipeline.stop();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (pipeline != null) {
            pipeline.stop();
        }
    }
    
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // 服务连接成功
    }
    
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
    
    @Override
    public void onGesture(int gestureId) {
        super.onGesture(gestureId);
    }
    
    @Override
    public boolean onKeyEvent(android.view.KeyEvent event) {
        return false;
    }
}
