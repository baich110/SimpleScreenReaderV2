/*
 * VibrationActor - 震动反馈执行器
 * 基于TalkBack Feedback架构的高性能震动反馈
 */
package com.example.simplereader.pipeline.actors;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

public class VibrationActor {
    
    private static final String TAG = "VibrationActor";
    
    // 震动模式常量
    public static final int PATTERN_SHORT = 50;      // 短震动 - 50ms
    public static final int PATTERN_NORMAL = 100;   // 普通震动 - 100ms
    public static final int PATTERN_LONG = 200;     // 长震动 - 200ms
    public static final int PATTERN_DOUBLE = 300;   // 双震动 - 300ms
    
    // 震动反馈类型
    public static final int FEEDBACK_CLICK = PATTERN_SHORT;
    public static final int FEEDBACK_LONG_PRESS = PATTERN_LONG;
    public static final int FEEDBACK_NAVIGATION = PATTERN_NORMAL;
    public static final int FEEDBACK_ERROR = PATTERN_DOUBLE;
    
    private final Context context;
    private Vibrator vibrator;
    private boolean isEnabled = true;
    private int amplitude = 255; // 震动强度 0-255
    private int defaultDuration = PATTERN_NORMAL;
    
    public VibrationActor(Context context) {
        this.context = context;
        initializeVibrator();
    }
    
    private void initializeVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = 
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                vibrator = vibratorManager.getDefaultVibrator();
            }
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        
        if (vibrator != null && vibrator.hasVibrator()) {
            Log.d(TAG, "震动器初始化成功");
        } else {
            Log.w(TAG, "设备不支持震动");
        }
    }
    
    /**
     * 执行震动
     * 
     * @param duration 震动持续时间（毫秒）
     */
    public void vibrate(int duration) {
        if (!isEnabled || vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createOneShot(
                        duration, 
                        amplitude > 0 ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE);
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(duration);
            }
            Log.v(TAG, "震动: " + duration + "ms");
        } catch (Exception e) {
            Log.e(TAG, "震动失败", e);
        }
    }
    
    /**
     * 执行震动模式
     * 
     * @param pattern 震动模式数组 [暂停时长, 震动时长, 暂停时长, 震动时长, ...]
     * @param repeat 重复次数，-1表示不重复
     */
    public void vibratePattern(long[] pattern, int repeat) {
        if (!isEnabled || vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createWaveform(
                        pattern, 
                        repeat);
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(pattern, repeat);
            }
            Log.v(TAG, "震动模式: " + pattern.length + " 步");
        } catch (Exception e) {
            Log.e(TAG, "震动模式失败", e);
        }
    }
    
    /**
     * 执行点击震动反馈
     */
    public void vibrateClick() {
        vibrate(FEEDBACK_CLICK);
    }
    
    /**
     * 执行长按震动反馈
     */
    public void vibrateLongPress() {
        vibrate(FEEDBACK_LONG_PRESS);
    }
    
    /**
     * 执行导航震动反馈
     */
    public void vibrateNavigation() {
        vibrate(FEEDBACK_NAVIGATION);
    }
    
    /**
     * 执行错误震动反馈
     */
    public void vibrateError() {
        vibrate(FEEDBACK_ERROR);
    }
    
    /**
     * 执行双击震动
     */
    public void vibrateDouble() {
        long[] pattern = {0, PATTERN_SHORT, 100, PATTERN_SHORT};
        vibratePattern(pattern, -1);
    }
    
    /**
     * 停止震动
     */
    public void cancel() {
        if (vibrator != null) {
            vibrator.cancel();
        }
    }
    
    /**
     * 设置是否启用震动
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) {
            cancel();
        }
    }
    
    /**
     * 检查震动是否启用
     */
    public boolean isEnabled() {
        return isEnabled;
    }
    
    /**
     * 设置震动强度
     * 
     * @param amplitude 强度值 0-255
     */
    public void setAmplitude(int amplitude) {
        this.amplitude = Math.max(1, Math.min(255, amplitude));
    }
    
    /**
     * 获取震动强度
     */
    public int getAmplitude() {
        return amplitude;
    }
    
    /**
     * 设置默认震动时长
     */
    public void setDefaultDuration(int duration) {
        this.defaultDuration = duration;
    }
    
    /**
     * 获取默认震动时长
     */
    public int getDefaultDuration() {
        return defaultDuration;
    }
    
    /**
     * 检查设备是否支持震动
     */
    public boolean hasVibrator() {
        return vibrator != null && vibrator.hasVibrator();
    }
    
    /**
     * 检查设备是否支持特定震动效果
     */
    public boolean hasAmplitudeControl() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return vibrator != null && vibrator.hasAmplitudeControl();
        }
        return false;
    }
    
    /**
     * 释放资源
     */
    public void shutdown() {
        cancel();
        vibrator = null;
    }
}
