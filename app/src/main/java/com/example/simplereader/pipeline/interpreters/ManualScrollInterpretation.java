package com.example.simplereader.pipeline.interpreters;

import com.example.simplereader.pipeline.Performance;
import android.view.accessibility.AccessibilityEvent;
import com.example.simplereader.pipeline.focus.ScreenState;

public class ManualScrollInterpretation {
    private final int direction;
    private final ScreenState screenState;
    private final Performance.EventId eventId;
    private final AccessibilityEvent event;

    public ManualScrollInterpretation(int direction, ScreenState screenState, 
            Performance.EventId eventId, AccessibilityEvent event) {
        this.direction = direction;
        this.screenState = screenState;
        this.eventId = eventId;
        this.event = event;
    }

    public int direction() { return direction; }
    public ScreenState screenState() { return screenState; }
    public Performance.EventId eventId() { return eventId; }
    public AccessibilityEvent event() { return event; }

    public static class Builder {
        private int direction;
        private ScreenState screenState;
        private Performance.EventId eventId;
        private AccessibilityEvent event;

        public Builder setDirection(int direction) { this.direction = direction; return this; }
        public Builder setScreenState(ScreenState screenState) { this.screenState = screenState; return this; }
        public Builder setEventId(Performance.EventId eventId) { this.eventId = eventId; return this; }
        public Builder setEvent(AccessibilityEvent event) { this.event = event; return this; }
        public ManualScrollInterpretation build() {
            return new ManualScrollInterpretation(direction, screenState, eventId, event);
        }
    }

    public static Builder builder() { return new Builder(); }
}
