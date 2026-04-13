package com.example.simplereader.pipeline;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashMap;
import java.util.Map;

public class ActorState {
    private final FocusHistory focusHistory = new FocusHistory();
    private final LabelManagerState labelManagerState = new LabelManagerState();

    public FocusHistory getFocusHistory() { return focusHistory; }
    public LabelManagerState getLabelManagerState() { return labelManagerState; }

    public static class FocusHistory {
        private final Map<Integer, FocusActionInfo> focusActionInfoMap = new HashMap<>();
        public void addFocusActionInfo(int eventId, FocusActionInfo info) { focusActionInfoMap.put(eventId, info); }
        public FocusActionInfo getFocusActionInfoFromEvent(AccessibilityEvent event) { return null; }
    }

    public static class FocusActionInfo {
        public static final int TOUCH_EXPLORATION = 1;
        public static final int LOGICAL_NAVIGATION = 2;
        public static final int DIRECTIONAL_NAVIGATION = 3;
        public static final int SCREEN_STATE_CHANGE = 4;
        public final int sourceAction;
        public final boolean forceMuteFeedback;
        public final boolean forceFeedbackEvenIfAudioPlaybackActive;
        public final boolean forceFeedbackEvenIfMicrophoneActive;
        public final boolean forceFeedbackEvenIfSsbActive;

        public FocusActionInfo(int sourceAction, boolean forceMuteFeedback,
                boolean forceFeedbackEvenIfAudioPlaybackActive,
                boolean forceFeedbackEvenIfMicrophoneActive,
                boolean forceFeedbackEvenIfSsbActive) {
            this.sourceAction = sourceAction;
            this.forceMuteFeedback = forceMuteFeedback;
            this.forceFeedbackEvenIfAudioPlaybackActive = forceFeedbackEvenIfAudioPlaybackActive;
            this.forceFeedbackEvenIfMicrophoneActive = forceFeedbackEvenIfMicrophoneActive;
            this.forceFeedbackEvenIfSsbActive = forceFeedbackEvenIfSsbActive;
        }
    }

    public static class LabelManagerState {
        public int getLabelIdForNode(AccessibilityNodeInfoCompat node) { return Label.NO_ID; }
    }
}
