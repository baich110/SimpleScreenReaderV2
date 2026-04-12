/*
 * SoundActor - 音效反馈执行器
 * 基于TalkBack Feedback架构的高性能音效反馈
 */
package com.example.simplereader.pipeline.actors;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

public class SoundActor {
    
    private static final String TAG = "SoundActor";
    
    // 音效ID常量
    public static final int SOUND_TICK = 1;           // 点击音效
    public static final int SOUND_NAVIGATION = 2;    // 导航音效
    public static final int SOUND_ERROR = 3;          // 错误音效
    public static final int SOUND_SUCCESS = 4;       // 成功音效
    public static final int SOUND_LONG_PRESS = 5;    // 长按音效
    
    private Context context;
    private SoundPool soundPool;
    private Map<Integer, Integer> soundMap = new HashMap<>();
    private Map<Integer, Float> volumeMap = new HashMap<>();
    private boolean isEnabled = true;
    private boolean isLoaded = false;
    private float defaultVolume = 1.0f;
    
    public SoundActor(Context context) {
        this.context = context;
        initializeSoundPool();
    }
    
    private void initializeSoundPool() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        
        soundPool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(audioAttributes)
                .build();
        
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (status == 0) {
                Log.d(TAG, "音效加载成功: " + sampleId);
                isLoaded = true;
            } else {
                Log.e(TAG, "音效加载失败: " + sampleId);
            }
        });
        
        // 设置默认音量
        for (int i = 1; i <= 5; i++) {
            volumeMap.put(i, defaultVolume);
        }
        
        Log.d(TAG, "SoundPool初始化完成");
    }
    
    /**
     * 播放音效
     * 
     * @param soundId 音效ID
     */
    public void play(int soundId) {
        if (!isEnabled || soundPool == null) {
            return;
        }
        
        Integer loadedSoundId = soundMap.get(soundId);
        if (loadedSoundId == null) {
            // 使用默认音效ID
            loadedSoundId = soundId;
        }
        
        Float volume = volumeMap.get(soundId);
        if (volume == null) {
            volume = defaultVolume;
        }
        
        soundPool.play(loadedSoundId, volume, volume, 1, 0, 1.0f);
        Log.v(TAG, "播放音效: " + soundId);
    }
    
    /**
     * 播放点击音效
     */
    public void playTick() {
        play(SOUND_TICK);
    }
    
    /**
     * 播放导航音效
     */
    public void playNavigation() {
        play(SOUND_NAVIGATION);
    }
    
    /**
     * 播放错误音效
     */
    public void playError() {
        play(SOUND_ERROR);
    }
    
    /**
     * 播放成功音效
     */
    public void playSuccess() {
        play(SOUND_SUCCESS);
    }
    
    /**
     * 播放长按音效
     */
    public void playLongPress() {
        play(SOUND_LONG_PRESS);
    }
    
    /**
     * 设置音效音量
     * 
     * @param soundId 音效ID
     * @param volume 音量 0.0-1.0
     */
    public void setVolume(int soundId, float volume) {
        volumeMap.put(soundId, Math.max(0f, Math.min(1f, volume)));
    }
    
    /**
     * 设置默认音量
     */
    public void setDefaultVolume(float volume) {
        this.defaultVolume = Math.max(0f, Math.min(1f, volume));
    }
    
    /**
     * 设置是否启用音效
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }
    
    /**
     * 检查音效是否启用
     */
    public boolean isEnabled() {
        return isEnabled;
    }
    
    /**
     * 释放资源
     */
    public void shutdown() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        soundMap.clear();
        volumeMap.clear();
        isLoaded = false;
    }
}
