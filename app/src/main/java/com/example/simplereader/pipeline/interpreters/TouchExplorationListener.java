package com.example.simplereader.pipeline.interpreters;

import com.example.simplereader.pipeline.Performance;

public interface TouchExplorationListener {
    boolean onTouchExplorationAction(TouchExplorationAction action, Performance.EventId eventId);
}
