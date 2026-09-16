package ru.krmonitor.app;

public class Recommendation {
    public final String baseId;
    public final String id;
    public final String title;
    public final String filename;

    public Recommendation(String baseId, String id, String title, String filename) {
        this.baseId = baseId; this.id = id; this.title = title; this.filename = filename;
    }
}
