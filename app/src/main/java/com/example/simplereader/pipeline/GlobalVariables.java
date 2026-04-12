/*
 * GlobalVariables - 全局变量
 */
package com.example.simplereader.pipeline;

public class GlobalVariables {
    private boolean lastTextEditIsPassword = false;
    
    public void setLastTextEditIsPassword(boolean isPassword) {
        this.lastTextEditIsPassword = isPassword;
    }
    
    public boolean isLastTextEditPassword() {
        return lastTextEditIsPassword;
    }
}
