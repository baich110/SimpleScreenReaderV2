/*
 * ScreenStateMonitor - 屏幕状态监控器
 */
package com.example.simplereader.pipeline.focus;

public class ScreenStateMonitor {
    public interface State {
        boolean areMainWindowsStable();
        ScreenState getStableScreenState();
    }
    
    public static class State implements ScreenStateMonitor.State {
        @Override
        public boolean areMainWindowsStable() {
            return true;
        }
        
        @Override
        public ScreenState getStableScreenState() {
            return new ScreenState();
        }
    }
}
