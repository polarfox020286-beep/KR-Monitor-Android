package ru.krmonitor.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class DbHelper extends SQLiteOpenHelper {
    private static final String DB = "kr.db";
    private static final int VER = 1;
    public DbHelper(Context c) { super(c, DB, null, VER); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recs(base_id TEXT PRIMARY KEY,current_id TEXT NOT NULL,title TEXT NOT NULL,filename TEXT NOT NULL,last_seen TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) {}

    public void upsert(Recommendation r, String lastSeen) {
        ContentValues v=new ContentValues();
        v.put("base_id",r.baseId); v.put("current_id",r.id); v.put("title",r.title);
        v.put("filename",r.filename); v.put("last_seen",lastSeen);
        getWritableDatabase().insertWithOnConflict("recs",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public Recommendation getByBase(String base) {
        try(Cursor c=getReadableDatabase().query("recs",null,"base_id=?",new String[]{base},null,null,null)) {
            if(c.moveToFirst()) return fromCursor(c); return null;
        }
    }
    public Recommendation getById(String id) {
        try(Cursor c=getReadableDatabase().query("recs",null,"current_id=?",new String[]{id},null,null,null)) {
            if(c.moveToFirst()) return fromCursor(c); return null;
        }
    }
    public int count() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM recs",null)) { c.moveToFirst(); return c.getInt(0); }
    }
    public List<Recommendation> all() {
        ArrayList<Recommendation> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("recs",null,null,null,null,null,"title COLLATE NOCASE")) {
            while(c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }
    private Recommendation fromCursor(Cursor c) {
        return new Recommendation(c.getString(c.getColumnIndexOrThrow("base_id")),c.getString(c.getColumnIndexOrThrow("current_id")),c.getString(c.getColumnIndexOrThrow("title")),c.getString(c.getColumnIndexOrThrow("filename")));
    }
}
