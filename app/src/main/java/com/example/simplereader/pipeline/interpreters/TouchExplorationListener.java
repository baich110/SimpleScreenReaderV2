package com.example.simplereader.pipeline.interpreters;

import com.example.simplereader.pipeline.Performance;

public interface TouchExplorationListener {
    boolean onTouchExplorationAction(TouchExplorationAction action, Performance.EventId eventId);
}

enum TouchExplorationAction {
    TOUCH_DOWN,
    TOUCH_UP,
    HOVER_ENTER,
    HOVER_EXIT,
    GESTURE_START,
    GESTURE_END
}
