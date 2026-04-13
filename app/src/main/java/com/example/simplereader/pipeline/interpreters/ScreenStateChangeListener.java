package com.example.simplereader.pipeline.interpreters;

import com.example.simplereader.pipeline.Performance;
import com.example.simplereader.pipeline.focus.ScreenState;

public interface ScreenStateChangeListener {
    boolean onScreenStateChanged(ScreenState screenState, Performance.EventId eventId);
}
