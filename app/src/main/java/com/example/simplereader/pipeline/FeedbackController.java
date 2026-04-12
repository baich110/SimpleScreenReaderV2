/*
 * FeedbackController - 反馈控制器
 * 基于TalkBack Feedback架构，整合语音、震动、音效、焦点等所有反馈
 */
package com.example.simplereader.pipeline;

import android.content.Context;
import com.example.simplereader.pipeline.actors.SpeechActor;
import com.example.simplereader.pipeline.actors.VibrationActor;
import com.example.simplereader.pipeline.actors.SoundActor;
import com.example.simplereader.pipeline.actors.FocusActor;
import com.example.simplereader.pipeline.feedback.Feedback;

public class FeedbackController {
    
    private static final String TAG = "FeedbackController";
    
    private Context context;
    private SpeechActor speechActor;
    private VibrationActor vibrationActor;
    private SoundActor soundActor;
    private FocusActor focusActor;
    
    // 反馈配置
    private boolean speechEnabled = true;
    private boolean vibrationEnabled = true;
    private boolean soundEnabled = false;
    
    public FeedbackController(Context context) {
        this.context = context;
        initializeActors();
    }
    
    private void initializeActors() {
        speechActor = new SpeechActor(context);
        vibrationActor = new VibrationActor(context);
        soundActor = new SoundActor(context);
    }
    
    public void setAccessibilityService(android.accessibilityservice.AccessibilityService service) {
        focusActor = new FocusActor(service);
    }
    
    /**
     * 执行反馈
     * 
     * 基于TalkBack的failover机制：
     * 尝试执行反馈，如果失败则尝试下一个
     */
    public void execute(Feedback feedback) {
        if (feedback == null) {
            return;
        }
        
        for (Feedback.Part part : feedback.getParts()) {
            if (!executePart(part)) {
                // 如果执行失败，尝试下一个Part（failover机制）
                continue;
            }
            // 执行成功，跳出循环
            break;
        }
    }
    
    private boolean executePart(Feedback.Part part) {
        switch (part.getType()) {
            case Feedback.Part.SPEECH:
                return executeSpeech(part);
            case Feedback.Part.VIBRATION:
                return executeVibration(part);
            case Feedback.Part.SOUND:
                return executeSound(part);
            case Feedback.Part.FOCUS:
                return executeFocus(part);
            default:
                return false;
        }
    }
    
    private boolean executeSpeech(Feedback.Part part) {
        if (!speechEnabled || speechActor == null) {
            return false;
        }
        
        String text = part.getText();
        if (text != null && !text.isEmpty()) {
            boolean flush = part.getFlags().contains(Feedback.FLAG_NO_QUEUE);
            int queueMode = flush ? 
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH :
                    android.speech.tts.TextToSpeech.QUEUE_ADD;
            
            speechActor.speak(text, part.getUtteranceId(), queueMode);
            return true;
        }
        return false;
    }
    
    private boolean executeVibration(Feedback.Part part) {
        if (!vibrationEnabled || vibrationActor == null) {
            return false;
        }
        
        int duration = part.getDuration();
        if (duration > 0) {
            vibrationActor.vibrate(duration);
            return true;
        }
        return false;
    }
    
    private boolean executeSound(Feedback.Part part) {
        if (!soundEnabled || soundActor == null) {
            return false;
        }
        
        int soundId = part.getSoundId();
        if (soundId > 0) {
            soundActor.play(soundId);
            return true;
        }
        return false;
    }
    
    private boolean executeFocus(Feedback.Part part) {
        if (focusActor == null) {
            return false;
        }
        
        // Focus反馈处理
        return true;
    }
    
    // ==================== 配置方法 ====================
    
    public void setSpeechEnabled(boolean enabled) {
        this.speechEnabled = enabled;
        if (!enabled) {
            speechActor.stop();
        }
    }
    
    public boolean isSpeechEnabled() {
        return speechEnabled;
    }
    
    public void setVibrationEnabled(boolean enabled) {
        this.vibrationEnabled = enabled;
    }
    
    public boolean isVibrationEnabled() {
        return vibrationEnabled;
    }
    
    public void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
    }
    
    public boolean isSoundEnabled() {
        return soundEnabled;
    }
    
    public SpeechActor getSpeechActor() {
        return speechActor;
    }
    
    public VibrationActor getVibrationActor() {
        return vibrationActor;
    }
    
    public SoundActor getSoundActor() {
        return soundActor;
    }
    
    public FocusActor getFocusActor() {
        return focusActor;
    }
    
    public void stopAll() {
        if (speechActor != null) {
            speechActor.stop();
        }
        if (vibrationActor != null) {
            vibrationActor.cancel();
        }
    }
    
    public void shutdown() {
        if (speechActor != null) {
            speechActor.shutdown();
        }
        if (vibrationActor != null) {
            vibrationActor.shutdown();
        }
        if (soundActor != null) {
            soundActor.shutdown();
        }
        if (focusActor != null) {
            focusActor.shutdown();
        }
    }
}
