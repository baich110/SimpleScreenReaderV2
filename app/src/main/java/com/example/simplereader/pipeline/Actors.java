package com.example.simplereader.pipeline;

import android.content.Context;
import com.example.simplereader.pipeline.actors.SpeechActor;
import com.example.simplereader.pipeline.actors.VibrationActor;
import com.example.simplereader.pipeline.actors.SoundActor;
import com.example.simplereader.pipeline.actors.FocusActor;
import com.example.simplereader.pipeline.feedback.Feedback;

public class Actors {
    private final SpeechActor speechActor;
    private final VibrationActor vibrationActor;
    private final SoundActor soundActor;
    private FocusActor focusActor;
    private final ActorState actorState;
    private Pipeline.EventReceiver eventReceiver;
    private Pipeline.FeedbackReturner feedbackReturner;

    public Actors(Context context) {
        this.speechActor = new SpeechActor(context);
        this.vibrationActor = new VibrationActor(context);
        this.soundActor = new SoundActor(context);
        this.focusActor = null;
        this.actorState = new ActorState();
    }

    public void setAccessibilityService(android.accessibilityservice.AccessibilityService service) {
        this.focusActor = new FocusActor(service);
    }

    public void setPipelineEventReceiver(Pipeline.EventReceiver receiver) {
        this.eventReceiver = receiver;
    }

    public void setPipelineFeedbackReturner(Pipeline.FeedbackReturner returner) {
        this.feedbackReturner = returner;
    }

    public ActorState getState() { return actorState; }

    public boolean act(Performance.EventId eventId, Feedback.Part part) {
        if (part == null) return false;
        switch (part.getType()) {
            case Feedback.Part.SPEECH: return actSpeech(part);
            case Feedback.Part.VIBRATION: return actVibration(part);
            case Feedback.Part.SOUND: return actSound(part);
            case Feedback.Part.FOCUS: return actFocus(part);
            default: return false;
        }
    }

    private boolean actSpeech(Feedback.Part part) {
        if (part.getText() == null || part.getText().isEmpty()) return false;
        String utteranceId = part.getUtteranceId() != null ? part.getUtteranceId() : "feedback_" + System.currentTimeMillis();
        int queueMode = part.getFlags().contains(Feedback.FLAG_NO_QUEUE) ? android.speech.tts.TextToSpeech.QUEUE_FLUSH : android.speech.tts.TextToSpeech.QUEUE_ADD;
        speechActor.speak(part.getText(), utteranceId, queueMode);
        return true;
    }

    private boolean actVibration(Feedback.Part part) {
        if (part.getDuration() <= 0) return false;
        vibrationActor.vibrate(part.getDuration());
        return true;
    }

    private boolean actSound(Feedback.Part part) {
        if (part.getSoundId() <= 0) return false;
        soundActor.play(part.getSoundId());
        return true;
    }

    private boolean actFocus(Feedback.Part part) { return true; }

    public void interruptAllFeedback(boolean stopTts) {
        if (stopTts) speechActor.stop();
        vibrationActor.cancel();
    }

    public void interruptSoundAndVibration() { vibrationActor.cancel(); }
    public void interruptGentle(Performance.EventId eventId) {}
    public void clearHintUtteranceCompleteAction(int interruptGroup, int interruptLevel) {}

    public void shutdown() {
        speechActor.shutdown();
        vibrationActor.shutdown();
        soundActor.shutdown();
        if (focusActor != null) focusActor.shutdown();
    }
}
