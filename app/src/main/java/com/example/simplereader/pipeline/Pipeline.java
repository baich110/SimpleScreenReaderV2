package com.example.simplereader.pipeline;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.feedback.Feedback;
import com.example.simplereader.pipeline.interpreters.Interpreters;

public class Pipeline implements AccessibilityEventListener, AccessibilityEventIdleListener {
    
    public static final String LOG = "Pipeline";
    
    private final Context context;
    private final Interpreters interpreters;
    private final Actors actors;
    private final Mappers mappers;
    private final Monitors monitors;
    
    public Pipeline(Context context, Interpreters interpreters) {
        this.context = context;
        this.interpreters = interpreters;
        this.actors = new Actors(context);
        this.mappers = new Mappers(context);
        this.monitors = new Monitors(context);
        
        monitors.setPipelineInterpretationReceiver((eventId, event, interpretation, source) -> {
            return inputInterpretation(eventId, event, interpretation, source);
        });
        
        interpreters.setPipelineInterpretationReceiver((eventId, event, interpretation, source) -> {
            return inputInterpretation(eventId, event, interpretation, source);
        });
    }
    
    public void start() {
        Log.d(LOG, "Pipeline started");
    }
    
    public void stop() {
        actors.shutdown();
        Log.d(LOG, "Pipeline stopped");
    }
    
    @Override
    public int getEventTypes() {
        return interpreters.getEventTypes() | monitors.getEventTypes();
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        monitors.onAccessibilityEvent(event);
        interpreters.onAccessibilityEvent(event, eventId);
    }
    
    @Override
    public void onIdle() {
        interpreters.onIdle();
    }
    
    private boolean inputInterpretation(@Nullable Performance.EventId eventId,
            @Nullable AccessibilityEvent event,
            @Nullable Interpretation interpretation,
            @Nullable AccessibilityNodeInfoCompat sourceNode) {
        
        Feedback feedback = mappers.mapToFeedback(eventId, event, interpretation, sourceNode);
        if (feedback == null) return false;
        return execute(feedback);
    }
    
    boolean execute(Feedback feedback) {
        Log.v(LOG, "execute() feedback=" + feedback);
        for (Feedback.Part part : feedback.getParts()) {
            if (actors.act(feedback.eventId(), part)) {
                return true;
            }
        }
        return false;
    }
    
    public Actors getActors() { return actors; }
    public Interpreters getInterpreters() { return interpreters; }
    public Mappers getMappers() { return mappers; }
    public Monitors getMonitors() { return monitors; }
    
    public interface InterpretationReceiver {
        boolean input(@Nullable Performance.EventId eventId, @Nullable AccessibilityEvent event,
                      @Nullable Interpretation interpretation, @Nullable AccessibilityNodeInfoCompat sourceNode);
    }
    
    public static class SyntheticEvent {
        public enum Type { SCROLL_TIMEOUT, TEXT_TRAVERSAL }
        public final Type eventType;
        public SyntheticEvent(Type eventType) { this.eventType = eventType; }
    }
    
    public class EventReceiver {
        public void input(SyntheticEvent.Type eventType) {
            Pipeline.this.inputEvent(Performance.EVENT_ID_UNTRACKED, new SyntheticEvent(eventType));
        }
    }
    
    private void inputEvent(Performance.EventId eventId, SyntheticEvent event) {
        interpreters.interpret(eventId, event);
    }
}
