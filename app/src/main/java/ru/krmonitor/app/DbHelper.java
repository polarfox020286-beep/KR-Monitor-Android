package ru.krmonitor.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class DbHelper extends SQLiteOpenHelper {
    private static final String DB = "kr.db";
    private static final int VER = 4;
    public DbHelper(Context c) { super(c, DB, null, VER); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recs(base_id TEXT PRIMARY KEY,current_id TEXT NOT NULL,title TEXT NOT NULL,filename TEXT NOT NULL,mkb_codes TEXT NOT NULL DEFAULT '',last_seen TEXT,added_at TEXT)");
        db.execSQL("CREATE TABLE history(base_id TEXT PRIMARY KEY,viewed_at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) {
        if(oldV<2) db.execSQL("ALTER TABLE recs ADD COLUMN added_at TEXT");
        if(oldV<3) db.execSQL("CREATE TABLE IF NOT EXISTS history(base_id TEXT PRIMARY KEY,viewed_at INTEGER NOT NULL)");
        if(oldV<4) db.execSQL("ALTER TABLE recs ADD COLUMN mkb_codes TEXT NOT NULL DEFAULT ''");
    }

    public void upsert(Recommendation r, String lastSeen) { upsert(r,lastSeen,null); }
    public void upsert(Recommendation r, String lastSeen, String addedAt) {
        ContentValues v=new ContentValues();
        v.put("base_id",r.baseId); v.put("current_id",r.id); v.put("title",r.title);
        v.put("filename",r.filename); v.put("mkb_codes",r.mkbCodes); v.put("last_seen",lastSeen);
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
    public void clearAll() { getWritableDatabase().delete("recs",null,null); }
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

    public void markViewed(String baseId) {
        ContentValues v=new ContentValues();
        v.put("base_id",baseId);
        v.put("viewed_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("history",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<Recommendation> history(int limit) {
        ArrayList<Recommendation> out=new ArrayList<>();
        String sql="SELECT r.* FROM history h JOIN recs r ON r.base_id=h.base_id ORDER BY h.viewed_at DESC LIMIT ?";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{Integer.toString(limit)})) {
            while(c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    public void clearHistory() { getWritableDatabase().delete("history",null,null); }

    private Recommendation fromCursor(Cursor c) {
        int mkbIndex=c.getColumnIndex("mkb_codes");
        String mkb=mkbIndex>=0 && !c.isNull(mkbIndex)?c.getString(mkbIndex):"";
        return new Recommendation(c.getString(c.getColumnIndexOrThrow("base_id")),c.getString(c.getColumnIndexOrThrow("current_id")),c.getString(c.getColumnIndexOrThrow("title")),c.getString(c.getColumnIndexOrThrow("filename")),mkb);
    }
}
