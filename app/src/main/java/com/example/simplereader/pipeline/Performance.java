package com.example.simplereader.pipeline;

import android.os.SystemClock;

/**
 * 性能监控 - 抄自TalkBack Performance
 * 
 * EventId用于追踪事件性能，便于优化
 */
public class Performance {
    
    /** 事件ID，用于追踪性能 */
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
        public int hashCode() {
            return count;
        }
    }
    
    /** 生成未跟踪的事件ID */
    public static final EventId EVENT_ID_UNTRACKED = null;
    
    /** 获取当前时间戳 */
    public static long currentTimeMs() {
        return SystemClock.uptimeMillis();
    }
    
    /** 创建事件ID */
    public static EventId getEventId(int eventType) {
        return new EventId(eventType);
    }
}
