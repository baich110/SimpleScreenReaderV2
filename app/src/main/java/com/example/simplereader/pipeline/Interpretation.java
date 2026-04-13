package com.example.simplereader.pipeline;

import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class Interpretation {
    
    public static final Interpretation NO_CHANGE = new Interpretation();
    
    public static enum Type {
        ACCESSIBILITY_FOCUSED, TOUCH, SCROLL, DIRECTION_NAVIGATION, 
        CONTINUOUS_READ, FOCUS_CHANGED, EDIT_TEXT, WINDOW_CHANGED,
        STATE_CHANGED, CONTENT_CHANGED, HINT
    }
    
    private final Object tag;
    private final AccessibilityEvent event;
    private final AccessibilityNodeInfoCompat sourceNode;
    private final EventId eventId;
    
    private Interpretation() {
        this.tag = null;
        this.event = null;
        this.sourceNode = null;
        this.eventId = null;
    }
    
    private Interpretation(Builder builder) {
        this.tag = builder.tag;
        this.event = builder.event;
        this.sourceNode = builder.sourceNode;
        this.eventId = builder.eventId;
    }
    
    public Object tag() { return tag; }
    public AccessibilityEvent event() { return event; }
    public AccessibilityNodeInfoCompat sourceNode() { return sourceNode; }
    public EventId eventId() { return eventId; }
    
    public static class AccessibilityFocused extends Interpretation {
        public AccessibilityFocused() { super(); }
    }
    
    public static class Touch extends Interpretation {
        public enum Action { TOUCH_START, TOUCH_END, TAP, LONG_PRESS, HOVER_ENTER, HOVER_EXIT, LIFT }
        private final Action action;
        private final AccessibilityNodeInfoCompat touchedNode;
        public Touch(Action action, AccessibilityNodeInfoCompat touchedNode) { this.action = action; this.touchedNode = touchedNode; }
        public Action action() { return action; }
        public AccessibilityNodeInfoCompat touchedNode() { return touchedNode; }
    }
    
    public static class Scroll extends Interpretation {
        private final int scrollDeltaX, scrollDeltaY;
        public Scroll(int scrollDeltaX, int scrollDeltaY) { this.scrollDeltaX = scrollDeltaX; this.scrollDeltaY = scrollDeltaY; }
        public int scrollDeltaX() { return scrollDeltaX; }
        public int scrollDeltaY() { return scrollDeltaY; }
    }
    
    public static class Hint extends Interpretation {
        private final CharSequence hintText;
        public Hint(CharSequence hintText) { this.hintText = hintText; }
        public CharSequence hintText() { return hintText; }
    }
    
    public static class StateChanged extends Interpretation {
        private final String stateKey;
        private final boolean enabled;
        public StateChanged(String stateKey, boolean enabled) { this.stateKey = stateKey; this.enabled = enabled; }
        public String stateKey() { return stateKey; }
        public boolean isEnabled() { return enabled; }
    }
    
    public static class ContentChanged extends Interpretation {
        private final CharSequence text;
        public ContentChanged(CharSequence text) { this.text = text; }
        public CharSequence text() { return text; }
    }
    
    public static class InputFocus extends Interpretation {
        private final AccessibilityNodeInfoCompat targetedNode;
        public InputFocus(AccessibilityNodeInfoCompat targetedNode) { this.targetedNode = targetedNode; }
        public AccessibilityNodeInfoCompat targetedNode() { return targetedNode; }
    }
    
    public static Builder builder() { return new Builder(); }
    
    public static class Builder {
        private Object tag;
        private AccessibilityEvent event;
        private AccessibilityNodeInfoCompat sourceNode;
        private EventId eventId;
        private Type type;
        
        public Builder ofType(Type type) { this.type = type; this.tag = type.name(); return this; }
        public Builder setTag(Object tag) { this.tag = tag; return this; }
        public Builder setEvent(AccessibilityEvent event) { this.event = event; return this; }
        public Builder setSource(AccessibilityNodeInfoCompat sourceNode) { this.sourceNode = sourceNode; return this; }
        public Builder setEventId(EventId eventId) { this.eventId = eventId; return this; }
        public Builder setAccessibilityFocused(boolean focused) { return this; }
        public Builder setDirection(int direction) { return this; }
        public Builder setGranularitySelection(int granularity) { return this; }
        public Builder setTextSelection(int from, int to) { return this; }
        public Builder setSelectionChanged(boolean changed) { return this; }
        
        public Interpretation build() { return new Interpretation(this); }
    }
}
