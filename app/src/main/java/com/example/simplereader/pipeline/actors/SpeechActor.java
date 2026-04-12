/*
 * SpeechActor - 语音朗读执行器
 * 基于TalkBack Feedback架构的高性能语音反馈
 */
package com.example.simplereader.pipeline.actors;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class SpeechActor implements TextToSpeech.OnInitListener {
    
    private static final String TAG = "SpeechActor";
    
    private final Context context;
    private TextToSpeech tts;
    private boolean isTtsReady = false;
    
    // 朗读队列
    private final ConcurrentHashMap<String, SpeechRequest> pendingSpeech = new ConcurrentHashMap<>();
    
    // 配置参数
    private float speechRate = 1.0f;
    private float pitch = 1.0f;
    private float volume = 1.0f;
    private Locale locale = Locale.CHINESE;
    
    // 回调接口
    public interface SpeechCallback {
        void onStart(String utteranceId);
        void onDone(String utteranceId);
        void onError(String utteranceId, int errorCode);
    }
    
    private SpeechCallback callback;
    
    public SpeechActor(Context context) {
        this.context = context;
        tts = new TextToSpeech(context, this);
    }
    
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "语言不支持");
                // 尝试使用默认语言
                tts.setLanguage(Locale.getDefault());
            }
            
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    if (callback != null) {
                        callback.onStart(utteranceId);
                    }
                }
                
                @Override
                public void onDone(String utteranceId) {
                    pendingSpeech.remove(utteranceId);
                    if (callback != null) {
                        callback.onDone(utteranceId);
                    }
                }
                
                @Override
                public void onError(String utteranceId) {
                    pendingSpeech.remove(utteranceId);
                    if (callback != null) {
                        callback.onError(utteranceId, -1);
                    }
                }
            });
            
            isTtsReady = true;
            Log.d(TAG, "TTS初始化成功");
        } else {
            Log.e(TAG, "TTS初始化失败");
        }
    }
    
    /**
     * 朗读文本
     * 
     * @param text 要朗读的文本
     * @param utteranceId 唯一标识符
     * @param queueMode 队列模式 - QUEUE_ADD(追加) 或 QUEUE_FLUSH(打断当前朗读)
     */
    public void speak(String text, String utteranceId, int queueMode) {
        if (!isTtsReady || text == null || text.isEmpty()) {
            return;
        }
        
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f);
        
        HashMap<String, String> speakParams = new HashMap<>();
        speakParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
        
        tts.setSpeechRate(speechRate);
        tts.setPitch(pitch);
        
        int result = tts.speak(text, queueMode, params, utteranceId);
        
        if (result == TextToSpeech.SUCCESS) {
            pendingSpeech.put(utteranceId, new SpeechRequest(text, utteranceId));
            Log.v(TAG, "开始朗读: " + text);
        }
    }
    
    /**
     * 停止朗读
     */
    public void stop() {
        if (tts != null) {
            tts.stop();
            pendingSpeech.clear();
        }
    }
    
    /**
     * 暂停朗读
     */
    public void pause() {
        // TTS不支持暂停，使用停止代替
        stop();
    }
    
    /**
     * 设置语速
     */
    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        if (tts != null) {
            tts.setSpeechRate(rate);
        }
    }
    
    /**
     * 设置音调
     */
    public void setPitch(float pitch) {
        this.pitch = pitch;
        if (tts != null) {
            tts.setPitch(pitch);
        }
    }
    
    /**
     * 设置音量
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
    }
    
    /**
     * 设置语言
     */
    public boolean setLanguage(Locale locale) {
        this.locale = locale;
        if (tts != null) {
            int result = tts.setLanguage(locale);
            return result != TextToSpeech.LANG_MISSING_DATA && 
                   result != TextToSpeech.LANG_NOT_SUPPORTED;
        }
        return false;
    }
    
    /**
     * 获取可用的语言列表
     */
    public java.util.Set<Locale> getAvailableLanguages() {
        if (tts != null) {
            return new java.util.HashSet<>(tts.getAvailableLanguages());
        }
        return new java.util.HashSet<>();
    }
    
    /**
     * 设置回调
     */
    public void setCallback(SpeechCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 检查是否正在朗读
     */
    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }
    
    /**
     * 获取待朗读队列大小
     */
    public int getPendingCount() {
        return pendingSpeech.size();
    }
    
    /**
     * 释放资源
     */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        pendingSpeech.clear();
        isTtsReady = false;
    }
    
    /**
     * 语音请求
     */
    private static class SpeechRequest {
        public final String text;
        public final String utteranceId;
        public final long timestamp;
        
        public SpeechRequest(String text, String utteranceId) {
            this.text = text;
            this.utteranceId = utteranceId;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
