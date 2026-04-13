package com.example.simplereader.pipeline.actors;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

public class SoundActor {
    
    private static final String TAG = "SoundActor";
    
    public static final int SOUND_TICK = 1;
    public static final int SOUND_NAVIGATION = 2;
    public static final int SOUND_ERROR = 3;
    
    private Context context;
    private SoundPool soundPool;
    private Map<Integer, Integer> soundMap = new HashMap<>();
    private boolean isEnabled = true;
    
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
        
        Log.d(TAG, "SoundPool初始化完成");
    }
    
    public void play(int soundId) {
        if (!isEnabled || soundPool == null) return;
        soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
        Log.v(TAG, "播放音效: " + soundId);
    }
    
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }
    
    public boolean isEnabled() { return isEnabled; }
    
    public void shutdown() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        soundMap.clear();
    }
}
