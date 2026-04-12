/*
 * ScreenState - 屏幕状态
 */
package com.example.simplereader.pipeline.focus;

public class ScreenState {
    public boolean areMainWindowsStable() {
        return true;
    }
    
    public ScreenState getStableScreenState() {
        return this;
    }
}
