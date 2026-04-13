/*
 * Feedback - 反馈数据类
 * 基于TalkBack Feedback架构
 */
package com.example.simplereader.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Feedback {
    
    public static final int SPEECH = 0;
    public static final int VIBRATION = 1;
    public static final int SOUND = 2;
    public static final int FOCUS = 3;
    
    public static final String FLAG_NO_QUEUE = "FLAG_NO_QUEUE";
    
    private final List<Part> parts = new ArrayList<>();
    
    public static class Part {
        public static final int SPEECH = 0;
        public static final int VIBRATION = 1;
        public static final int SOUND = 2;
        public static final int FOCUS = 3;
        
        private final int type;
        private String text;
        private int duration;
        private int soundId;
        private int utteranceId;
        private List<String> flags = new ArrayList<>();
        
        public Part(int type) {
            this.type = type;
        }
        
        public int getType() {
            return type;
        }
        
        public String getText() {
            return text;
        }
        
        public void setText(String text) {
            this.text = text;
        }
        
        public int getDuration() {
            return duration;
        }
        
        public void setDuration(int duration) {
            this.duration = duration;
        }
        
        public int getSoundId() {
            return soundId;
        }
        
        public void setSoundId(int soundId) {
            this.soundId = soundId;
        }
        
        public int getUtteranceId() {
            return utteranceId;
        }
        
        public void setUtteranceId(int utteranceId) {
            this.utteranceId = utteranceId;
        }
        
        public List<String> getFlags() {
            return flags;
        }
        
        public void addFlag(String flag) {
            this.flags.add(flag);
        }
    }
    
    public void addPart(Part part) {
        parts.add(part);
    }
    
    public List<Part> getParts() {
        return Collections.unmodifiableList(parts);
    }
    
    public static Feedback speech(String text) {
        Feedback feedback = new Feedback();
        Part part = new Part(SPEECH);
        part.setText(text);
        feedback.addPart(part);
        return feedback;
    }
    
    public static Feedback vibration(int duration) {
        Feedback feedback = new Feedback();
        Part part = new Part(VIBRATION);
        part.setDuration(duration);
        feedback.addPart(part);
        return feedback;
    }
}
