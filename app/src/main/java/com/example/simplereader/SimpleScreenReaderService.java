package com.example.simplereader;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Pipeline;
import com.example.simplereader.pipeline.interpreters.Interpreters;

public class SimpleScreenReaderService extends AccessibilityService {
    
    private static final String TAG = "SimpleScreenReader";
    
    private Pipeline pipeline;
    private Interpreters interpreters;
    private Performance performance;
    
    @Override
    public void onCreate() {
        super.onCreate();
        initializePipeline();
    }
    
    private void initializePipeline() {
        performance = Performance.getInstance();
        interpreters = new Interpreters(this);
        pipeline = new Pipeline(this, interpreters);
        pipeline.start();
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Performance.EventId eventId = performance.onEventReceived(event);
        if (pipeline != null) {
            pipeline.onAccessibilityEvent(event, eventId);
        }
    }
    
    @Override
    public void onInterrupt() {
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
