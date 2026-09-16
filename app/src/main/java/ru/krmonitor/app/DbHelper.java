package ru.krmonitor.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class DbHelper extends SQLiteOpenHelper {
    private static final String DB = "kr.db";
    private static final int VER = 2;
    public DbHelper(Context c) { super(c, DB, null, VER); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recs(base_id TEXT PRIMARY KEY,current_id TEXT NOT NULL,title TEXT NOT NULL,filename TEXT NOT NULL,last_seen TEXT,added_at TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) {
        if(oldV<2) db.execSQL("ALTER TABLE recs ADD COLUMN added_at TEXT");
    }

    public void upsert(Recommendation r, String lastSeen) { upsert(r,lastSeen,null); }
    public void upsert(Recommendation r, String lastSeen, String addedAt) {
        ContentValues v=new ContentValues();
        v.put("base_id",r.baseId); v.put("current_id",r.id); v.put("title",r.title);
        v.put("filename",r.filename); v.put("last_seen",lastSeen);
        if(addedAt!=null) v.put("added_at",addedAt);
        else {
            String old=getAddedAt(r.baseId);
            if(old!=null) v.put("added_at",old);
        }
        getWritableDatabase().insertWithOnConflict("recs",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private String getAddedAt(String base) {
        try(Cursor c=getReadableDatabase().query("recs",new String[]{"added_at"},"base_id=?",new String[]{base},null,null,null)) {
            if(c.moveToFirst()) return c.isNull(0)?null:c.getString(0); return null;
        }
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
    public List<Recommendation> recentAdded(int limit) {
        ArrayList<Recommendation> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("recs",null,"added_at IS NOT NULL",null,null,null,"added_at DESC",Integer.toString(limit))) {
            while(c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }
    private Recommendation fromCursor(Cursor c) {
        return new Recommendation(c.getString(c.getColumnIndexOrThrow("base_id")),c.getString(c.getColumnIndexOrThrow("current_id")),c.getString(c.getColumnIndexOrThrow("title")),c.getString(c.getColumnIndexOrThrow("filename")));
    }
}
