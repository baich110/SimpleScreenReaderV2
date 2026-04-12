/*
 * InputModeTracker - 输入模式追踪器
 */
package com.example.simplereader.utils.input;

public class InputModeTracker {
    public static final int INPUT_MODE_TOUCH = 0;
    public static final int INPUT_MODE_KEYBOARD = 1;
    
    private int currentMode = INPUT_MODE_TOUCH;
    
    public int getInputMode() {
        return currentMode;
    }
    
    public void setInputMode(int mode) {
        this.currentMode = mode;
    }
    
    public boolean isTouchMode() {
        return currentMode == INPUT_MODE_TOUCH;
    }
    
    public boolean isKeyboardMode() {
        return currentMode == INPUT_MODE_KEYBOARD;
    }
}
