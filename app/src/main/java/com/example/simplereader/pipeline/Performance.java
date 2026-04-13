package com.example.simplereader.pipeline;

import android.os.SystemClock;

public class Performance {
    
    private static Performance instance;
    
    public static Performance getInstance() {
        if (instance == null) {
            instance = new Performance();
        }
        return instance;
    }
    
    public static class EventId {
        private final long uptimeMs;
        private final int eventType;
        private final int count;
        
        private static int globalCount = 0;
        
        public EventId(int eventType) {
            this.uptimeMs = SystemClock.uptimeMillis();
            this.eventType = eventType;
            this.count = globalCount++;
        }
        
        public long uptimeMs() { return uptimeMs; }
        public int eventType() { return eventType; }
        public int count() { return count; }
        
        @Override
        public String toString() {
            return String.format("EventId[type=%d, count=%d, uptime=%d]", eventType, count, uptimeMs);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            EventId eventId = (EventId) obj;
            return count == eventId.count;
        }
        
        @Override
        public int hashCode() { return count; }
    }
    
    public static final EventId EVENT_ID_UNTRACKED = null;
    
    public static long currentTimeMs() {
        return SystemClock.uptimeMillis();
    }
    
    public static EventId getEventId(int eventType) {
        return new EventId(eventType);
    }
    
    public EventId onEventReceived(android.view.accessibility.AccessibilityEvent event) {
        return new EventId(event.getEventType());
    }
}
