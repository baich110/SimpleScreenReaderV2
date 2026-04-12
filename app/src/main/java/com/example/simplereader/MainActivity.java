/*
 * MainActivity - 设置界面
 */
package com.example.simplereader;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.content.Intent;
import android.provider.Settings;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import android.content.ComponentName;
import android.widget.Toast;
import java.util.List;

public class MainActivity extends PreferenceActivity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
        
        // 检查无障碍服务是否启用
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
        }
    }
    
    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        ComponentName componentName = new ComponentName(this, SimpleScreenReaderService.class);
        
        List<AccessibilityServiceInfo> enabledServices = 
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        
        for (AccessibilityServiceInfo service : enabledServices) {
            if (service.getComponentName().equals(componentName)) {
                return true;
            }
        }
        return false;
    }
    
    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
