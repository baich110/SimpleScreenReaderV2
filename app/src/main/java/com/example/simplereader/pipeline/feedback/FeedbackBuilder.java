package com.example.simplereader.pipeline.feedback;

import android.content.Context;

public class FeedbackBuilder {
    private final Context context;
    private Feedback.Part.Builder currentBuilder;

    public FeedbackBuilder(Context context) {
        this.context = context;
    }

    public FeedbackBuilder buildFeedback(int type, String text) {
        currentBuilder = new Feedback.Part.Builder();
        switch (type) {
            case Feedback.Part.SPEECH:
                currentBuilder.setSpeech(text, null);
                break;
            default:
                break;
        }
        return this;
    }

    public FeedbackBuilder buildFeedback(int type, int duration) {
        currentBuilder = new Feedback.Part.Builder();
        switch (type) {
            case Feedback.Part.VIBRATION:
                currentBuilder.setVibration(duration);
                break;
            default:
                break;
        }
        return this;
    }

    public FeedbackBuilder buildFeedback(int type, int soundId, String text) {
        currentBuilder = new Feedback.Part.Builder();
        switch (type) {
            case Feedback.Part.SOUND:
                currentBuilder.setSound(soundId, 1.0f, 1.0f);
                break;
            default:
                break;
        }
        return this;
    }

    public Feedback build() {
        if (currentBuilder == null) {
            return Feedback.empty();
        }
        Feedback.Part part = currentBuilder.build();
        return Feedback.create(null, part);
    }
}
