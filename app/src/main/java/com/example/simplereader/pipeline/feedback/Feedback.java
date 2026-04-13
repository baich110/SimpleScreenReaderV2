package com.example.simplereader.pipeline.feedback;

import com.example.simplereader.pipeline.Performance;
import java.util.ArrayList;
import java.util.List;

public class Feedback {
    public static final int DEFAULT = 0;
    private final Performance.EventId eventId;
    private final List<Part> parts;

    private Feedback(Builder builder) {
        this.eventId = builder.eventId;
        this.parts = builder.parts;
    }

    public static Builder builder(Performance.EventId eventId) {
        return new Builder(eventId);
    }

    public static Feedback create(Performance.EventId eventId, Part... parts) {
        Builder builder = builder(eventId);
        for (Part part : parts) {
            builder.addPart(part);
        }
        return builder.build();
    }

    @Nullable
    public Performance.EventId eventId() { return eventId; }

    public List<Part> failovers() { return parts; }

    public static Part.Builder part() { return new Part.Builder(); }

    public static Part.Builder speech(CharSequence text, Object options) {
        return part().setSpeech(text, options);
    }

    public static Part.Builder speech(CharSequence text) {
        return speech(text, null);
    }

    public static class Part {
        private final int delayMs;
        private final int interruptGroup;
        private final int interruptLevel;
        private final boolean interruptAllFeedback;
        private final boolean interruptSoundAndVibration;
        private final boolean interruptGentle;
        private final boolean stopTts;
        private final String senderName;
        
        private final SpeechPart speech;
        private final SoundPart sound;
        private final VibrationPart vibration;
        private final FocusPart focus;
        private final EditPart edit;
        private final GranularityPart granularity;
        private final NodeActionPart nodeAction;
        private final ScrollPart scroll;
        private final ContinuousReadPart continuousRead;
        private final DimScreenPart dimScreen;
        
        private Part(Builder builder) {
            this.delayMs = builder.delayMs;
            this.interruptGroup = builder.interruptGroup;
            this.interruptLevel = builder.interruptLevel;
            this.interruptAllFeedback = builder.interruptAllFeedback;
            this.interruptSoundAndVibration = builder.interruptSoundAndVibration;
            this.interruptGentle = builder.interruptGentle;
            this.stopTts = builder.stopTts;
            this.senderName = builder.senderName;
            this.speech = builder.speech;
            this.sound = builder.sound;
            this.vibration = builder.vibration;
            this.focus = builder.focus;
            this.edit = builder.edit;
            this.granularity = builder.granularity;
            this.nodeAction = builder.nodeAction;
            this.scroll = builder.scroll;
            this.continuousRead = builder.continuousRead;
            this.dimScreen = builder.dimScreen;
        }
        
        public int delayMs() { return delayMs; }
        public int interruptGroup() { return interruptGroup; }
        public int interruptLevel() { return interruptLevel; }
        public boolean interruptAllFeedback() { return interruptAllFeedback; }
        public boolean interruptSoundAndVibration() { return interruptSoundAndVibration; }
        public boolean interruptGentle() { return interruptGentle; }
        public boolean stopTts() { return stopTts; }
        public String senderName() { return senderName; }
        
        @Nullable public SpeechPart speech() { return speech; }
        @Nullable public SoundPart sound() { return sound; }
        @Nullable public VibrationPart vibration() { return vibration; }
        @Nullable public FocusPart focus() { return focus; }
        @Nullable public EditPart edit() { return edit; }
        @Nullable public GranularityPart granularity() { return granularity; }
        @Nullable public NodeActionPart nodeAction() { return nodeAction; }
        @Nullable public ScrollPart scroll() { return scroll; }
        @Nullable public ContinuousReadPart continuousRead() { return continuousRead; }
        @Nullable public DimScreenPart dimScreen() { return dimScreen; }
        
        public static class Builder {
            private final Performance.EventId eventId;
            private int delayMs = 0;
            private int interruptGroup = DEFAULT;
            private int interruptLevel = DEFAULT;
            private boolean interruptAllFeedback = false;
            private boolean interruptSoundAndVibration = false;
            private boolean interruptGentle = false;
            private boolean stopTts = false;
            private String senderName = "";
            
            private SpeechPart speech = null;
            private SoundPart sound = null;
            private VibrationPart vibration = null;
            private FocusPart focus = null;
            private EditPart edit = null;
            private GranularityPart granularity = null;
            private NodeActionPart nodeAction = null;
            private ScrollPart scroll = null;
            private ContinuousReadPart continuousRead = null;
            private DimScreenPart dimScreen = null;
            
            public Builder(Performance.EventId eventId) { this.eventId = eventId; }
            public Builder setDelayMs(int delayMs) { this.delayMs = delayMs; return this; }
            public Builder setInterruptGroup(int group) { this.interruptGroup = group; return this; }
            public Builder setInterruptLevel(int level) { this.interruptLevel = level; return this; }
            public Builder setInterruptAllFeedback(boolean interrupt) { this.interruptAllFeedback = interrupt; return this; }
            public Builder setInterruptSoundAndVibration(boolean interrupt) { this.interruptSoundAndVibration = interrupt; return this; }
            public Builder setStopTts(boolean stop) { this.stopTts = stop; return this; }
            public Builder setSenderName(String name) { this.senderName = name; return this; }
            public Builder setSpeech(CharSequence text, Object options) { this.speech = new SpeechPart(text, options); return this; }
            public Builder setSound(int resourceId, float rate, float volume) { this.sound = new SoundPart(resourceId, rate, volume); return this; }
            public Builder setVibration(int resourceId) { this.vibration = new VibrationPart(resourceId); return this; }
            public Builder setFocus(FocusPart focus) { this.focus = focus; return this; }
            public Builder setEdit(EditPart edit) { this.edit = edit; return this; }
            public Builder setGranularity(GranularityPart granularity) { this.granularity = granularity; return this; }
            public Builder setNodeAction(NodeActionPart nodeAction) { this.nodeAction = nodeAction; return this; }
            public Builder setScroll(ScrollPart scroll) { this.scroll = scroll; return this; }
            public Builder setContinuousRead(ContinuousReadPart continuousRead) { this.continuousRead = continuousRead; return this; }
            public Builder setDimScreen(DimScreenPart dimScreen) { this.dimScreen = dimScreen; return this; }
            public Part build() { return new Part(this); }
        }
    }
    
    public static class Builder {
        private final Performance.EventId eventId;
        private final List<Part> parts = new ArrayList<>();
        public Builder(@Nullable Performance.EventId eventId) { this.eventId = eventId; }
        public Builder addPart(Part part) { parts.add(part); return this; }
        public Feedback build() { return new Feedback(this); }
    }
    
    public static class SpeechPart {
        private final CharSequence text;
        private final Object options;
        private final Action action;
        
        public enum Action { SPEAK, PAUSE_OR_RESUME, SILENCE, UNSILENCE, TOGGLE_VOICE_FEEDBACK, SAVE_LAST, COPY_SAVED, REPEAT_SAVED, SPELL_SAVED }
        
        public SpeechPart(CharSequence text, Object options) { this.text = text; this.options = options; this.action = Action.SPEAK; }
        public SpeechPart(Action action) { this.text = null; this.options = null; this.action = action; }
        public CharSequence text() { return text; }
        public Object options() { return options; }
        public Action action() { return action; }
    }
    
    public static class SoundPart {
        private final int resourceId;
        private final float rate;
        private final float volume;
        public SoundPart(int resourceId, float rate, float volume) { this.resourceId = resourceId; this.rate = rate; this.volume = volume; }
        public int resourceId() { return resourceId; }
        public float rate() { return rate; }
        public float volume() { return volume; }
    }
    
    public static class VibrationPart {
        private final int resourceId;
        public VibrationPart(int resourceId) { this.resourceId = resourceId; }
        public int resourceId() { return resourceId; }
    }
    
    public static class FocusPart {
        public enum Action { CLICK_NODE, CLEAR_FOCUS, SET_FOCUS, LONG_CLICK }
        private final Action action;
        private final Object node;
        public FocusPart(Action action, Object node) { this.action = action; this.node = node; }
        public Action action() { return action; }
        public Object node() { return node; }
    }
    
    public static class EditPart {
        public enum Action { SELECT_ALL, COPY, CUT, PASTE, START_SELECT, END_SELECT }
        private final Action action;
        private final Object node;
        public EditPart(Action action, Object node) { this.action = action; this.node = node; }
        public Action action() { return action; }
        public Object node() { return node; }
    }
    
    public static class GranularityPart {
        private final Object granularity;
        public GranularityPart(Object granularity) { this.granularity = granularity; }
        public Object granularity() { return granularity; }
    }
    
    public static class NodeActionPart {
        private final Object node;
        private final int actionId;
        public NodeActionPart(Object node, int actionId) { this.node = node; this.actionId = actionId; }
        public Object node() { return node; }
        public int actionId() { return actionId; }
    }
    
    public static class ScrollPart {
        private final Object node;
        private final int action;
        public ScrollPart(Object node, int action) { this.node = node; this.action = action; }
        public Object node() { return node; }
        public int action() { return action; }
    }
    
    public static class ContinuousReadPart {
        public enum Action { START_AT_TOP, START_AT_CURSOR, READ_FOCUSED_CONTENT, INTERRUPT, IGNORE, PAUSE_OR_RESUME }
        private final Action action;
        public ContinuousReadPart(Action action) { this.action = action; }
        public Action action() { return action; }
    }
    
    public static class DimScreenPart {
        public enum Action { DIM, BRIGHTEN }
        private final Action action;
        public DimScreenPart(Action action) { this.action = action; }
        public Action action() { return action; }
    }
}
