package com.example.simplereader.pipeline.actors;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.Locale;

public class SpeechActor implements TextToSpeech.OnInitListener {
    
    private static final String TAG = "SpeechActor";
    
    private final Context context;
    private TextToSpeech tts;
    private boolean isTtsReady = false;
    
    private float speechRate = 1.0f;
    private float pitch = 1.0f;
    private float volume = 1.0f;
    private Locale locale = Locale.CHINESE;
    
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
                tts.setLanguage(Locale.getDefault());
            }
            
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {}
                @Override public void onError(String utteranceId) {}
            });
            
            isTtsReady = true;
            Log.d(TAG, "TTS初始化成功");
        } else {
            Log.e(TAG, "TTS初始化失败");
        }
    }
    
    public void speak(String text, String utteranceId, int queueMode) {
        if (!isTtsReady || text == null || text.isEmpty()) return;
        
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        
        tts.setSpeechRate(speechRate);
        tts.setPitch(pitch);
        tts.speak(text, queueMode, params, utteranceId);
        Log.v(TAG, "开始朗读: " + text);
    }
    
    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }
    
    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        if (tts != null) tts.setSpeechRate(rate);
    }
    
    public void setPitch(float pitch) {
        this.pitch = pitch;
        if (tts != null) tts.setPitch(pitch);
    }
    
    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }
    
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        isTtsReady = false;
    }
}
