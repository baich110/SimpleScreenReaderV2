package com.example.simplereader.pipeline.feedback;

import android.content.Context;

public class FeedbackBuilder {
    private final Context context;
    private Feedback.Part.Builder currentBuilder;

    public FeedbackBuilder(Context context) {
        this.context = context;
    }

    public FeedbackBuilder buildFeedback(int type, String text) {
        currentBuilder = new Feedback.Part.Builder(type);
        currentBuilder.setText(text);
        return this;
    }

    public FeedbackBuilder buildFeedback(int type, int duration) {
        currentBuilder = new Feedback.Part.Builder(type);
        currentBuilder.setDuration(duration);
        return this;
    }

    public FeedbackBuilder buildFeedback(int type, int soundId, String text) {
        currentBuilder = new Feedback.Part.Builder(type);
        currentBuilder.setSoundId(soundId);
        currentBuilder.setText(text);
        return this;
    }

    public Feedback build() {
        if (currentBuilder == null) {
            return Feedback.empty();
        }
        Feedback.Part part = currentBuilder.build();
        return new Feedback(part);
    }
}
