package com.example.simplereader.pipeline;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.focus.AccessibilityFocusMonitor;
import com.example.simplereader.pipeline.focus.ScreenStateMonitor;
import com.example.simplereader.utils.input.TouchMonitor;
import com.example.simplereader.utils.input.InputModeTracker;

public class Monitors {
    public final ScreenStateMonitor.State state;
    private final AccessibilityFocusMonitor accessibilityFocusMonitor;
    private final TouchMonitor touchMonitor;
    private final InputModeTracker inputModeTracker;
    private Pipeline.InterpretationReceiver interpretationReceiver;
    
    public Monitors(Context context) {
        this.state = new ScreenStateMonitor.ScreenStateImpl();
        this.accessibilityFocusMonitor = new AccessibilityFocusMonitor();
        this.touchMonitor = new TouchMonitor();
        this.inputModeTracker = new InputModeTracker();
    }
    
    public void setPipelineInterpretationReceiver(Pipeline.InterpretationReceiver receiver) {
        this.interpretationReceiver = receiver;
    }
    
    public int getEventTypes() {
        return AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
    }
    
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 处理窗口状态变化
    }
    
    public AccessibilityFocusMonitor getAccessibilityFocusMonitor() { return accessibilityFocusMonitor; }
    public TouchMonitor getTouchMonitor() { return touchMonitor; }
    public InputModeTracker getInputModeTracker() { return inputModeTracker; }
    
    public void shutdown() {}
}
