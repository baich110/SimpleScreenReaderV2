package com.example.simplereader.pipeline.feedback;

import com.example.simplereader.pipeline.Performance;
import java.util.ArrayList;
import java.util.List;

public class Feedback {
    
    public static final int DEFAULT = 0;
    public static final String FLAG_NO_QUEUE = "FLAG_NO_QUEUE";
    
    private final Performance.EventId eventId;
    private final List<Part> failovers;
    
    public static Feedback empty() {
        return new Feedback(null, new ArrayList<>());
    }
    
    public Feedback(Part part) {
        this.eventId = null;
        this.failovers = new ArrayList<>();
        if (part != null) {
            this.failovers.add(part);
        }
    }
    
    public Feedback(Performance.EventId eventId, List<Part> failovers) {
        this.eventId = eventId;
        this.failovers = failovers;
    }
    
    public Performance.EventId eventId() { return eventId; }
    public List<Part> failovers() { return failovers; }
    public List<Part> getParts() { return failovers; }
    
    public static class Part {
        public static final int SPEECH = 1;
        public static final int VIBRATION = 2;
        public static final int SOUND = 3;
        public static final int FOCUS = 4;
        
        private final int type;
        private String text;
        private int duration;
        private int soundId;
        private String utteranceId;
        private List<String> flags;
        private int interruptGroup = DEFAULT;
        private int interruptLevel = DEFAULT;
        private boolean interruptAllFeedback = false;
        private boolean interruptSoundAndVibration = false;
        private boolean interruptGentle = false;
        private boolean stopTts = false;
        private String senderName;
        
        public Part(int type) {
            this.type = type;
            this.flags = new ArrayList<>();
        }
        
        public int getType() { return type; }
        public String getText() { return text; }
        public int getDuration() { return duration; }
        public int getSoundId() { return soundId; }
        public String getUtteranceId() { return utteranceId; }
        public List<String> getFlags() { return flags; }
        public boolean getInterruptAllFeedback() { return interruptAllFeedback; }
        public boolean getInterruptSoundAndVibration() { return interruptSoundAndVibration; }
        public int getInterruptGroup() { return interruptGroup; }
        public int getInterruptLevel() { return interruptLevel; }
        public String getSenderName() { return senderName; }
        
        public static class Builder {
            private final Part part;
            
            public Builder(int type) {
                this.part = new Part(type);
            }
            
            public Builder setText(String text) { part.text = text; return this; }
            public Builder setDuration(int duration) { part.duration = duration; return this; }
            public Builder setSoundId(int soundId) { part.soundId = soundId; return this; }
            public Builder setUtteranceId(String utteranceId) { part.utteranceId = utteranceId; return this; }
            public Builder setInterruptGroup(int group) { part.interruptGroup = group; return this; }
            public Builder setInterruptLevel(int level) { part.interruptLevel = level; return this; }
            public Builder setInterruptAllFeedback(boolean interrupt) { part.interruptAllFeedback = interrupt; return this; }
            public Builder setStopTts(boolean stop) { part.stopTts = stop; return this; }
            public Builder setSenderName(String name) { part.senderName = name; return this; }
            
            public Part build() { return part; }
        }
    }
}
