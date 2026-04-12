package com.example.simplereader.pipeline;

import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/**
 * 解释系统 - 完整抄自TalkBack Interpretation
 * 
 * Interpretation是Interpreters输出的语义化事件
 * Pipeline将其传递给Mappers转换为Feedback
 */
public class Interpretation {
    
    /** 空解释 */
    public static final Interpretation EMPTY = new Interpretation();
    
    // 解释类型标签
    private final Object tag;
    
    // 原始数据
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
    
    @Nullable public Object tag() { return tag; }
    @Nullable public AccessibilityEvent event() { return event; }
    @Nullable public AccessibilityNodeInfoCompat sourceNode() { return sourceNode; }
    @Nullable public EventId eventId() { return eventId; }
    
    // ============ 解释类型 ============
    
    /** 无障碍焦点解释 */
    public static class AccessibilityFocused extends Interpretation {
        private final Object focusActionInfo;
        
        public AccessibilityFocused(EventId eventId, AccessibilityEvent event, Object focusActionInfo) {
            super.builder()
                .setTag("AccessibilityFocused")
                .setEventId(eventId)
                .setEvent(event);
            this.focusActionInfo = focusActionInfo;
        }
        
        public Object focusActionInfo() { return focusActionInfo; }
    }
    
    /** 触摸解释 */
    public static class Touch extends Interpretation {
        public enum Action { TOUCH_START, TOUCH_END, TAP, LONG_PRESS, HOVER_ENTER, HOVER_EXIT, LIFT }
        
        private final Action action;
        private final AccessibilityNodeInfoCompat touchedNode;
        
        public Touch(Action action, AccessibilityNodeInfoCompat touchedNode) {
            this.action = action;
            this.touchedNode = touchedNode;
        }
        
        public Action action() { return action; }
        public AccessibilityNodeInfoCompat touchedNode() { return touchedNode; }
    }
    
    /** 触摸交互解释 */
    public static class TouchInteraction extends Interpretation {
        private final boolean interactionStart;
        
        public TouchInteraction(boolean interactionStart) {
            this.interactionStart = interactionStart;
        }
        
        public boolean isInteractionStart() { return interactionStart; }
    }
    
    /** 方向导航解释 */
    public static class DirectionNavigation extends Interpretation {
        private final int searchDirection;
        private final Object granularity;
        private final AccessibilityNodeInfoCompat moveToNode;
        
        public DirectionNavigation(int searchDirection, Object granularity, AccessibilityNodeInfoCompat moveToNode) {
            this.searchDirection = searchDirection;
            this.granularity = granularity;
            this.moveToNode = moveToNode;
        }
        
        public int searchDirection() { return searchDirection; }
        public Object granularity() { return granularity; }
        public AccessibilityNodeInfoCompat moveToNode() { return moveToNode; }
    }
    
    /** 滚动解释 */
    public static class Scroll extends Interpretation {
        private final int scrollDeltaX;
        private final int scrollDeltaY;
        
        public Scroll(int scrollDeltaX, int scrollDeltaY) {
            this.scrollDeltaX = scrollDeltaX;
            this.scrollDeltaY = scrollDeltaY;
        }
        
        public int scrollDeltaX() { return scrollDeltaX; }
        public int scrollDeltaY() { return scrollDeltaY; }
    }
    
    /** 连续阅读解释 */
    public static class ContinuousRead extends Interpretation {
        public enum Action { START_AT_TOP, START_AT_CURSOR, INTERRUPT }
        
        private final Action action;
        
        public ContinuousRead(Action action) {
            this.action = action;
        }
        
        public Action action() { return action; }
    }
    
    /** 编辑文本解释 */
    public static class EditText extends Interpretation {
        public enum Action { SELECT_ALL, COPY, CUT, PASTE }
        
        private final Action action;
        private final AccessibilityNodeInfoCompat node;
        
        public EditText(Action action, AccessibilityNodeInfoCompat node) {
            this.action = action;
            this.node = node;
        }
        
        public Action action() { return action; }
        public AccessibilityNodeInfoCompat node() { return node; }
    }
    
    /** 焦点变化解释 */
    public static class FocusChanged extends Interpretation {
        private final AccessibilityNodeInfoCompat focusedNode;
        
        public FocusChanged(AccessibilityNodeInfoCompat focusedNode) {
            this.focusedNode = focusedNode;
        }
        
        public AccessibilityNodeInfoCompat focusedNode() { return focusedNode; }
    }
    
    /** 窗口变化解释 */
    public static class WindowChanged extends Interpretation {
        private final int windowId;
        
        public WindowChanged(int windowId) {
            this.windowId = windowId;
        }
        
        public int windowId() { return windowId; }
    }
    
    /** 状态变化解释 */
    public static class StateChanged extends Interpretation {
        private final String stateKey;
        private final boolean enabled;
        
        public StateChanged(String stateKey, boolean enabled) {
            this.stateKey = stateKey;
            this.enabled = enabled;
        }
        
        public String stateKey() { return stateKey; }
        public boolean isEnabled() { return enabled; }
    }
    
    /** 内容变化解释 */
    public static class ContentChanged extends Interpretation {
        private final CharSequence text;
        
        public ContentChanged(CharSequence text) {
            this.text = text;
        }
        
        public CharSequence text() { return text; }
    }
    
    /** 提示解释 */
    public static class Hint extends Interpretation {
        private final CharSequence hintText;
        
        public Hint(CharSequence hintText) {
            this.hintText = hintText;
        }
        
        public CharSequence hintText() { return hintText; }
    }
    
    /** ID解释 */
    public static class ID extends Interpretation {
        private final int eventId;
        
        public ID(int eventId) {
            this.eventId = eventId;
        }
        
        public int eventId() { return eventId; }
    }
    
    // ============ Builder ============
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Object tag;
        private AccessibilityEvent event;
        private AccessibilityNodeInfoCompat sourceNode;
        private EventId eventId;
        
        public Builder setTag(Object tag) { this.tag = tag; return this; }
        public Builder setEvent(AccessibilityEvent event) { this.event = event; return this; }
        public Builder setSourceNode(AccessibilityNodeInfoCompat sourceNode) { this.sourceNode = sourceNode; return this; }
        public Builder setEventId(EventId eventId) { this.eventId = eventId; return this; }
        
        public Interpretation build() {
            return new Interpretation(this);
        }
    }
}
