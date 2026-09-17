package ru.krmonitor.app;

public class Recommendation {
    public final String baseId;
    public final String id;
    public final String title;
    public final String filename;
    public final String mkbCodes;

    public Recommendation(String baseId, String id, String title, String filename) {
        this(baseId,id,title,filename,"");
    }

    public Recommendation(String baseId, String id, String title, String filename, String mkbCodes) {
        this.baseId = baseId;
        this.id = id;
        this.title = title;
        this.filename = filename;
        this.mkbCodes = mkbCodes == null ? "" : mkbCodes.trim();
    }
}
