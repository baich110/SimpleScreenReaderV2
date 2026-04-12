/*
 * SimpleScreenReaderV2 - 高性能读屏服务
 * 基于TalkBack Pipeline架构
 * ScrollEventInterpreter - 滚动事件解释器
 */
package com.example.simplereader.pipeline.interpreters;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.Interpretation;
import com.example.simplereader.utils.AccessibilityNodeInfoUtils;
import com.example.simplereader.utils.traversal.TraversalStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class ScrollEventInterpreter implements AccessibilityEventInterpreter {
    
    private static final String TAG = "ScrollEventInterpreter";
    private static final int SCROLL_NOISE_RANGE = 15;
    private static final int INDEX_UNDEFINED = -1;
    private static final int SCROLL_INSTANCE_ID_UNDEFINED = -1;
    
    private static final int MASK_EVENTS =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
    
    public interface ScrollEventHandler {
        void onScrollEvent(AccessibilityEvent event, ScrollEventInterpretation interpretation, Performance.EventId eventId);
    }
    
    public static class ScrollEventInterpretation {
        public static final ScrollEventInterpretation DEFAULT = new ScrollEventInterpretation(
                ScrollActionRecord.ACTION_UNKNOWN, TraversalStrategy.SEARCH_FOCUS_UNKNOWN,
                false, false, false, false, SCROLL_INSTANCE_ID_UNDEFINED);
        
        public final int userAction;
        public final int scrollDirection;
        public final boolean hasValidIndex;
        public final boolean isDuplicateEvent;
        public final boolean isFromScrollable;
        public final boolean isMediaPlayerAutoScroll;
        public final int scrollInstanceId;
        
        public ScrollEventInterpretation(int userAction, int scrollDirection,
                boolean hasValidIndex, boolean isDuplicateEvent,
                boolean isFromScrollable, boolean isMediaPlayerAutoScroll,
                int scrollInstanceId) {
            this.userAction = userAction;
            this.scrollDirection = scrollDirection;
            this.hasValidIndex = hasValidIndex;
            this.isDuplicateEvent = isDuplicateEvent;
            this.isFromScrollable = isFromScrollable;
            this.isMediaPlayerAutoScroll = isMediaPlayerAutoScroll;
            this.scrollInstanceId = scrollInstanceId;
        }
        
        @Override public String toString() {
            return "ScrollEventInterpretation{userAction=" + userAction + 
                   ", direction=" + scrollDirection + 
                   ", isDuplicate=" + isDuplicateEvent + "}";
        }
    }
    
    public static class ScrollActionRecord {
        public static final int ACTION_UNKNOWN = 0;
        public static final int ACTION_USER = 1;
        public static final int ACTION_AUTO = 2;
        public static final int ACTION_INITIAL = 3;
        
        public final int actionType;
        public final long actionTime;
        public final int scrollInstanceId;
        
        public ScrollActionRecord(int actionType, long actionTime, int scrollInstanceId) {
            this.actionType = actionType;
            this.actionTime = actionTime;
            this.scrollInstanceId = scrollInstanceId;
        }
    }
    
    private static class NodeIdentifier {
        public final int windowId;
        public final int viewId;
        
        public NodeIdentifier(int windowId, int viewId) {
            this.windowId = windowId;
            this.viewId = viewId;
        }
        
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NodeIdentifier)) return false;
            NodeIdentifier that = (NodeIdentifier) o;
            return windowId == that.windowId && viewId == that.viewId;
        }
        
        @Override public int hashCode() {
            return Objects.hash(windowId, viewId);
        }
    }
    
    private static class PositionInfo {
        public int fromIndex = INDEX_UNDEFINED;
        public int toIndex = INDEX_UNDEFINED;
        public int scrollX = INDEX_UNDEFINED;
        public int scrollY = INDEX_UNDEFINED;
        
        public PositionInfo() {}
        public PositionInfo(int fromIndex, int toIndex) {
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }
    }
    
    private final TouchMonitor touchMonitor;
    private final boolean supportMultipleAutoScroll;
    private ScrollActionRecord lastScrollActionRecord;
    private int handledScrollInstanceId = SCROLL_INSTANCE_ID_UNDEFINED;
    private final HashMap<NodeIdentifier, PositionInfo> cachedPositionInfo = new HashMap<>();
    private final List<ScrollEventHandler> listeners = new ArrayList<>();
    
    public ScrollEventInterpreter(TouchMonitor touchMonitor, boolean supportMultipleAutoScroll) {
        this.touchMonitor = touchMonitor;
        this.supportMultipleAutoScroll = supportMultipleAutoScroll;
    }
    
    public void addListener(ScrollEventHandler listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(ScrollEventHandler listener) {
        listeners.remove(listener);
    }
    
    @Override
    public int getEventTypes() {
        return MASK_EVENTS;
    }
    
    @Override
    public Interpretation onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                cachedPositionInfo.clear();
                break;
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                return handleViewScrolled(event, eventId);
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                handleWindowContentChanged(event);
                break;
        }
        return Interpretation.NO_CHANGE;
    }
    
    private Interpretation handleViewScrolled(AccessibilityEvent event, Performance.EventId eventId) {
        AccessibilityNodeInfoCompat sourceNode = AccessibilityNodeInfoUtils.toCompat(event.getSource());
        if (sourceNode == null) return Interpretation.NO_CHANGE;
        
        boolean isFromScrollable = AccessibilityNodeInfoUtils.isScrollableNode(sourceNode);
        PositionInfo positionInfo = getPositionInfo(event);
        PositionInfo cachedInfo = getCachedPositionInfo(sourceNode);
        boolean isDuplicate = isDuplicateScrollEvent(positionInfo, cachedInfo);
        int scrollDirection = calculateScrollDirection(positionInfo, cachedInfo, isFromScrollable);
        
        updateCachedPositionInfo(sourceNode, positionInfo);
        sourceNode.recycle();
        
        ScrollEventInterpretation interpretation = new ScrollEventInterpretation(
                getUserAction(event), scrollDirection,
                positionInfo.fromIndex != INDEX_UNDEFINED,
                isDuplicate, isFromScrollable, false,
                getScrollInstanceId(event));
        
        dispatchScrollEvent(event, interpretation, eventId);
        
        if (interpretation.userAction == ScrollActionRecord.ACTION_USER) {
            return Interpretation.Builder.ofType(Interpretation.Type.SCROLL)
                    .setEvent(event)
                    .setDirection(scrollDirection)
                    .build();
        }
        return Interpretation.NO_CHANGE;
    }
    
    private void handleWindowContentChanged(AccessibilityEvent event) {
        AccessibilityNodeInfoCompat sourceNode = AccessibilityNodeInfoUtils.toCompat(event.getSource());
        if (sourceNode != null) {
            cachedPositionInfo.remove(new NodeIdentifier(sourceNode.getWindowId(), sourceNode.getSourceNodeId()));
            sourceNode.recycle();
        }
    }
    
    private PositionInfo getPositionInfo(AccessibilityEvent event) {
        PositionInfo info = new PositionInfo();
        if (event.getFromIndex() != INDEX_UNDEFINED && event.getToIndex() != INDEX_UNDEFINED) {
            info.fromIndex = event.getFromIndex();
            info.toIndex = event.getToIndex();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.scrollX = event.getScrollX();
            info.scrollY = event.getScrollY();
        }
        return info;
    }
    
    private PositionInfo getCachedPositionInfo(AccessibilityNodeInfoCompat node) {
        return cachedPositionInfo.get(new NodeIdentifier(node.getWindowId(), node.getSourceNodeId()));
    }
    
    private void updateCachedPositionInfo(AccessibilityNodeInfoCompat node, PositionInfo info) {
        cachedPositionInfo.put(new NodeIdentifier(node.getWindowId(), node.getSourceNodeId()), info);
    }
    
    private boolean isDuplicateScrollEvent(PositionInfo current, PositionInfo cached) {
        if (cached == null) return false;
        if (current.fromIndex != INDEX_UNDEFINED) {
            return current.fromIndex == cached.fromIndex && current.toIndex == cached.toIndex;
        }
        if (current.scrollX != INDEX_UNDEFINED) {
            return Math.abs(current.scrollX - cached.scrollX) <= SCROLL_NOISE_RANGE &&
                   Math.abs(current.scrollY - cached.scrollY) <= SCROLL_NOISE_RANGE;
        }
        return false;
    }
    
    private int calculateScrollDirection(PositionInfo current, PositionInfo cached, boolean isFromScrollable) {
        if (current.fromIndex != INDEX_UNDEFINED && cached != null && cached.fromIndex != INDEX_UNDEFINED) {
            return current.toIndex > cached.fromIndex ? 
                    TraversalStrategy.SEARCH_FOCUS_FORWARD : TraversalStrategy.SEARCH_FOCUS_BACKWARD;
        }
        if (current.scrollY != INDEX_UNDEFINED && cached != null && cached.scrollY != INDEX_UNDEFINED) {
            return current.scrollY < cached.scrollY ?
                    TraversalStrategy.SEARCH_FOCUS_FORWARD : TraversalStrategy.SEARCH_FOCUS_BACKWARD;
        }
        return TraversalStrategy.SEARCH_FOCUS_UNKNOWN;
    }
    
    private int getUserAction(AccessibilityEvent event) {
        if (lastScrollActionRecord != null && 
                (event.getEventTime() - lastScrollActionRecord.actionTime) < 1000) {
            return lastScrollActionRecord.actionType;
        }
        return ScrollActionRecord.ACTION_USER;
    }
    
    private int getScrollInstanceId(AccessibilityEvent event) {
        if (lastScrollActionRecord != null && 
                (event.getEventTime() - lastScrollActionRecord.actionTime) < 1000) {
            return lastScrollActionRecord.scrollInstanceId;
        }
        return SCROLL_INSTANCE_ID_UNDEFINED;
    }
    
    private void dispatchScrollEvent(AccessibilityEvent event, 
                                    ScrollEventInterpretation interpretation,
                                    Performance.EventId eventId) {
        for (ScrollEventHandler handler : listeners) {
            handler.onScrollEvent(event, interpretation, eventId);
        }
    }
    
    public void shutdown() {
        listeners.clear();
        cachedPositionInfo.clear();
    }
}
