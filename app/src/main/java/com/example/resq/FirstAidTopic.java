package com.example.resq;

public class FirstAidTopic {
    private final String title;
    private final String steps;

    public FirstAidTopic(String title, String steps) {
        this.title = title;
        this.steps = steps;
    }

    public String getTitle() {
        return title;
    }

    public String getSteps() {
        return steps;
    }
}
