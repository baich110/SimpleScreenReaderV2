package com.example.simplereader.pipeline.focus;

public class ScreenStateMonitor {
    
    public interface State {
        boolean areMainWindowsStable();
        ScreenState getStableScreenState();
    }
    
    public static class ScreenStateImpl implements ScreenStateMonitor.State {
        @Override
        public boolean areMainWindowsStable() { return true; }
        
        @Override
        public ScreenState getStableScreenState() { return new ScreenState(); }
    }
}
