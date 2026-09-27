package ru.krmonitor.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class DbHelper extends SQLiteOpenHelper {
    private static final String DB = "kr.db";
    private static final int VER = 8;
    public DbHelper(Context c) { super(c, DB, null, VER); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recs(base_id TEXT PRIMARY KEY,current_id TEXT NOT NULL,title TEXT NOT NULL,filename TEXT NOT NULL,mkb_codes TEXT NOT NULL DEFAULT '',last_seen TEXT,added_at TEXT)");
        db.execSQL("CREATE TABLE history(base_id TEXT PRIMARY KEY,viewed_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE changes(id INTEGER PRIMARY KEY AUTOINCREMENT,base_id TEXT NOT NULL,event_type TEXT NOT NULL,old_id TEXT,new_id TEXT NOT NULL,title TEXT NOT NULL,changed_at TEXT NOT NULL,UNIQUE(base_id,event_type,new_id))");
        db.execSQL("CREATE TABLE profile_custom(base_id TEXT PRIMARY KEY,mode TEXT NOT NULL,profiles TEXT NOT NULL)");
        db.execSQL("CREATE TABLE favorites(base_id TEXT PRIMARY KEY,added_at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) {
        if(oldV<2) db.execSQL("ALTER TABLE recs ADD COLUMN added_at TEXT");
        if(oldV<3) db.execSQL("CREATE TABLE IF NOT EXISTS history(base_id TEXT PRIMARY KEY,viewed_at INTEGER NOT NULL)");
        if(oldV<4) db.execSQL("ALTER TABLE recs ADD COLUMN mkb_codes TEXT NOT NULL DEFAULT ''");
        if(oldV<6) ensureChangesTable(db);
        if(oldV<7) ensureProfileCustomTable(db);
        if(oldV<8) ensureFavoritesTable(db);
    }

    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Defensive repair for 1.1.9: that build could mark the DB as v5
        // without creating the changes table.
        ensureChangesTable(db);
        ensureProfileCustomTable(db);
        ensureFavoritesTable(db);
    }

    private void ensureFavoritesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites(base_id TEXT PRIMARY KEY,added_at INTEGER NOT NULL)");
    }

    private void ensureProfileCustomTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS profile_custom(base_id TEXT PRIMARY KEY,mode TEXT NOT NULL,profiles TEXT NOT NULL)");
    }

    private void ensureChangesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS changes(id INTEGER PRIMARY KEY AUTOINCREMENT,base_id TEXT NOT NULL,event_type TEXT NOT NULL,old_id TEXT,new_id TEXT NOT NULL,title TEXT NOT NULL,changed_at TEXT NOT NULL,UNIQUE(base_id,event_type,new_id))");
        // Restore events already detected by older builds. Seed rows have empty
        // last_seen/added_at, so they are not incorrectly shown as changes.
        db.execSQL("INSERT OR IGNORE INTO changes(base_id,event_type,old_id,new_id,title,changed_at) " +
                "SELECT base_id,CASE WHEN added_at IS NOT NULL AND TRIM(added_at)<>'' THEN 'NEW' ELSE 'UPDATED' END,NULL,current_id,title," +
                "CASE WHEN last_seen IS NOT NULL AND TRIM(last_seen)<>'' THEN last_seen ELSE added_at END " +
                "FROM recs WHERE (last_seen IS NOT NULL AND TRIM(last_seen)<>'') OR (added_at IS NOT NULL AND TRIM(added_at)<>'')");
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
    public void clearAll() {
        getWritableDatabase().delete("recs",null,null);
        try { getWritableDatabase().delete("changes",null,null); } catch(Exception ignored) {}
    }
    public boolean remapBase(String oldBase,Recommendation replacement,String lastSeen) {
        if(oldBase==null || replacement==null || replacement.baseId==null) return false;
        if(oldBase.equals(replacement.baseId)) {
            upsert(replacement,lastSeen);
            return true;
        }

        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            // The new base must not already exist; otherwise this is not a
            // technical renumbering and should be handled as a normal record.
            try(Cursor existing=db.query("recs",new String[]{"base_id"},"base_id=?",
                    new String[]{replacement.baseId},null,null,null)) {
                if(existing.moveToFirst()) return false;
            }

            ContentValues rec=new ContentValues();
            rec.put("base_id",replacement.baseId);
            rec.put("current_id",replacement.id);
            rec.put("title",replacement.title);
            rec.put("filename",replacement.filename);
            rec.put("mkb_codes",replacement.mkbCodes);
            rec.put("last_seen",lastSeen);
            int changed=db.update("recs",rec,"base_id=?",new String[]{oldBase});
            if(changed!=1) return false;

            ContentValues history=new ContentValues();
            history.put("base_id",replacement.baseId);
            db.update("history",history,"base_id=?",new String[]{oldBase});

            ContentValues profile=new ContentValues();
            profile.put("base_id",replacement.baseId);
            db.update("profile_custom",profile,"base_id=?",new String[]{oldBase});

            ContentValues favorite=new ContentValues();
            favorite.put("base_id",replacement.baseId);
            db.update("favorites",favorite,"base_id=?",new String[]{oldBase});

            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
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

    public static final class ChangeEvent {
        public final String type,baseId,oldId,newId,title,changedAt;
        ChangeEvent(String type,String baseId,String oldId,String newId,String title,String changedAt) {
            this.type=type; this.baseId=baseId; this.oldId=oldId; this.newId=newId; this.title=title; this.changedAt=changedAt;
        }
    }

    public void recordChange(String type,Recommendation r,String oldId,String changedAt) {
        ContentValues v=new ContentValues();
        v.put("base_id",r.baseId); v.put("event_type",type); v.put("old_id",oldId);
        v.put("new_id",r.id); v.put("title",r.title); v.put("changed_at",changedAt);
        getWritableDatabase().insertWithOnConflict("changes",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<ChangeEvent> recentChanges(int limit) {
        return recentChangesWithinHours(Integer.MAX_VALUE,limit);
    }

    public List<ChangeEvent> recentChangesWithinHours(int hours,int limit) {
        String now=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date());
        return recentChangesWithinHoursAt(now,hours,limit);
    }

    public List<ChangeEvent> recentChangesWithinHoursAt(String referenceTime,int hours,int limit) {
        ArrayList<ChangeEvent> out=new ArrayList<>();
        String selection=null;
        String[] args=null;
        if(hours!=Integer.MAX_VALUE) {
            try {
                SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);
                Date ref=f.parse(referenceTime);
                long refMs=ref==null?System.currentTimeMillis():ref.getTime();
                String cutoffText=f.format(new Date(refMs-hours*60L*60L*1000L));
                selection="changed_at>=? AND changed_at<=?";
                args=new String[]{cutoffText,referenceTime};
            } catch(Exception e) {
                long cutoff=System.currentTimeMillis()-hours*60L*60L*1000L;
                String cutoffText=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(cutoff));
                selection="changed_at>=?";
                args=new String[]{cutoffText};
            }
        }
        try(Cursor c=getReadableDatabase().query("changes",null,selection,args,null,null,"id DESC",Integer.toString(limit))) {
            while(c.moveToNext()) out.add(new ChangeEvent(
                    c.getString(c.getColumnIndexOrThrow("event_type")),
                    c.getString(c.getColumnIndexOrThrow("base_id")),
                    c.isNull(c.getColumnIndexOrThrow("old_id"))?null:c.getString(c.getColumnIndexOrThrow("old_id")),
                    c.getString(c.getColumnIndexOrThrow("new_id")),
                    c.getString(c.getColumnIndexOrThrow("title")),
                    c.getString(c.getColumnIndexOrThrow("changed_at"))));
        }
        return out;
    }

    public static final class ProfileRule {
        public final String mode;
        public final LinkedHashSet<String> profiles;
        ProfileRule(String mode,Collection<String> profiles) {
            this.mode=mode==null?"":mode;
            this.profiles=new LinkedHashSet<>();
            if(profiles!=null) this.profiles.addAll(profiles);
        }
    }

    private String encodeProfiles(Collection<String> profiles) {
        StringBuilder out=new StringBuilder();
        if(profiles!=null) {
            for(String p:profiles) {
                if(p==null) continue;
                String value=p.trim().replace("\n"," ").replace("\r"," ");
                if(value.isEmpty()) continue;
                if(out.length()>0) out.append("\n");
                out.append(value);
            }
        }
        return out.toString();
    }

    private LinkedHashSet<String> decodeProfiles(String raw) {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        if(raw==null || raw.trim().isEmpty()) return out;
        for(String p:raw.split("\\n")) {
            String value=p.trim();
            if(!value.isEmpty()) out.add(value);
        }
        return out;
    }

    public void saveProfileRule(String baseId,String mode,Collection<String> profiles) {
        if(baseId==null || baseId.trim().isEmpty()) return;
        LinkedHashSet<String> clean=new LinkedHashSet<>();
        if(profiles!=null) {
            for(String p:profiles) if(ProfileClassifier.PROFILES.contains(p)) clean.add(p);
        }
        if(clean.isEmpty()) {
            clearProfileRule(baseId);
            return;
        }
        String safeMode="REPLACE".equals(mode)?"REPLACE":"ADD";
        ContentValues v=new ContentValues();
        v.put("base_id",baseId);
        v.put("mode",safeMode);
        v.put("profiles",encodeProfiles(clean));
        getWritableDatabase().insertWithOnConflict("profile_custom",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void clearProfileRule(String baseId) {
        if(baseId==null) return;
        getWritableDatabase().delete("profile_custom","base_id=?",new String[]{baseId});
    }

    public ProfileRule getProfileRule(String baseId) {
        if(baseId==null) return null;
        try(Cursor c=getReadableDatabase().query("profile_custom",null,"base_id=?",new String[]{baseId},null,null,null)) {
            if(c.moveToFirst()) return new ProfileRule(
                    c.getString(c.getColumnIndexOrThrow("mode")),
                    decodeProfiles(c.getString(c.getColumnIndexOrThrow("profiles"))));
        }
        return null;
    }

    public Map<String,ProfileRule> allProfileRules() {
        LinkedHashMap<String,ProfileRule> out=new LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().query("profile_custom",null,null,null,null,null,"base_id")) {
            while(c.moveToNext()) {
                String base=c.getString(c.getColumnIndexOrThrow("base_id"));
                out.put(base,new ProfileRule(
                        c.getString(c.getColumnIndexOrThrow("mode")),
                        decodeProfiles(c.getString(c.getColumnIndexOrThrow("profiles")))));
            }
        }
        return out;
    }

    public boolean isFavorite(String baseId) {
        if(baseId==null || baseId.trim().isEmpty()) return false;
        try(Cursor c=getReadableDatabase().query(
                "favorites",new String[]{"base_id"},"base_id=?",
                new String[]{baseId},null,null,null)) {
            return c.moveToFirst();
        }
    }

    public boolean setFavorite(String baseId,boolean favorite) {
        if(baseId==null || baseId.trim().isEmpty()) return false;
        SQLiteDatabase db=getWritableDatabase();
        if(!favorite) {
            db.delete("favorites","base_id=?",new String[]{baseId});
            return false;
        }
        ContentValues v=new ContentValues();
        v.put("base_id",baseId);
        v.put("added_at",System.currentTimeMillis());
        db.insertWithOnConflict("favorites",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public boolean toggleFavorite(String baseId) {
        return setFavorite(baseId,!isFavorite(baseId));
    }

    public int favoriteCount() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM favorites",null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    public Set<String> favoriteBaseIds() {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        try(Cursor c=getReadableDatabase().query(
                "favorites",new String[]{"base_id"},null,null,null,null,"added_at DESC")) {
            while(c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public List<Recommendation> favorites() {
        ArrayList<Recommendation> out=new ArrayList<>();
        String sql="SELECT r.* FROM favorites f JOIN recs r ON r.base_id=f.base_id ORDER BY f.added_at DESC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,null)) {
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

    public void removeFromHistory(String baseId) {
        if(baseId==null || baseId.trim().isEmpty()) return;
        getWritableDatabase().delete("history","base_id=?",new String[]{baseId});
    }

    public void clearHistory() { getWritableDatabase().delete("history",null,null); }

    private Recommendation fromCursor(Cursor c) {
        int mkbIndex=c.getColumnIndex("mkb_codes");
        String mkb=mkbIndex>=0 && !c.isNull(mkbIndex)?c.getString(mkbIndex):"";
        return new Recommendation(c.getString(c.getColumnIndexOrThrow("base_id")),c.getString(c.getColumnIndexOrThrow("current_id")),c.getString(c.getColumnIndexOrThrow("title")),c.getString(c.getColumnIndexOrThrow("filename")),mkb);
    }
}
