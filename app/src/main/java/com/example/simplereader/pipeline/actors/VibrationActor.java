package com.example.simplereader.pipeline.actors;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

public class VibrationActor {
    
    private static final String TAG = "VibrationActor";
    
    public static final int PATTERN_SHORT = 50;
    public static final int PATTERN_NORMAL = 100;
    public static final int PATTERN_LONG = 200;
    
    private final Context context;
    private Vibrator vibrator;
    private boolean isEnabled = true;
    private int amplitude = 255;
    
    public VibrationActor(Context context) {
        this.context = context;
        initializeVibrator();
    }
    
    private void initializeVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) vibrator = vibratorManager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            Log.d(TAG, "震动器初始化成功");
        }
    }
    
    public void vibrate(int duration) {
        if (!isEnabled || vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createOneShot(duration, amplitude > 0 ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE);
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(duration);
            }
            Log.v(TAG, "震动: " + duration + "ms");
        } catch (Exception e) {
            Log.e(TAG, "震动失败", e);
        }
    }
    
    public void vibratePattern(long[] pattern, int repeat) {
        if (!isEnabled || vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, repeat);
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(pattern, repeat);
            }
        } catch (Exception e) {
            Log.e(TAG, "震动模式失败", e);
        }
    }
    
    public void cancel() {
        if (vibrator != null) vibrator.cancel();
    }
    
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) cancel();
    }
    
    public boolean isEnabled() { return isEnabled; }
    
    public void setAmplitude(int amplitude) {
        this.amplitude = Math.max(1, Math.min(255, amplitude));
    }
    
    public boolean hasVibrator() {
        return vibrator != null && vibrator.hasVibrator();
    }
    
    public void shutdown() {
        cancel();
        vibrator = null;
    }
}
