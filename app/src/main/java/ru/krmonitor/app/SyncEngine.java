package ru.krmonitor.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class SyncEngine {
    public static final class UpdateInfo {
        public final Recommendation recommendation;
        public final String previousId;
        UpdateInfo(Recommendation r,String previousId){this.recommendation=r;this.previousId=previousId;}
    }

    public static final class Result {
        public final int added,updated,titleFixed,downloaded,downloadFailed;
        public final String message;
        public final List<Recommendation> newRecommendations;
        public final List<UpdateInfo> updatedRecommendations;
        Result(int a,int u,int t,int d,int f,String m,List<Recommendation> n,List<UpdateInfo> up){
            added=a;updated=u;titleFixed=t;downloaded=d;downloadFailed=f;
            message=m;newRecommendations=n;updatedRecommendations=up;
        }
        Result(int a,int u,int t,int d,int f,String m,List<Recommendation> n){
            this(a,u,t,d,f,m,n,Collections.emptyList());
        }
    }

    private static final int ABSOLUTE_MIN_CATALOG=500;
    private static final double MIN_RELATIVE_SIZE=0.75;
    private static final double MAX_MISSING_SHARE=0.30;

    private SyncEngine() {}

    public static Result sync(Context context) {
        SharedPreferences prefs=context.getSharedPreferences("prefs",Context.MODE_PRIVATE);
        String attemptDate=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date());

        try {
            SeedImporter.ensureSeeded(context);
            Map<String,Recommendation> online=CatalogFetcher.fetch();
            DbHelper db=new DbHelper(context);
            List<Recommendation> localBefore=db.all();

            validateCatalogIntegrity(online,localBefore);

            int added=0,updated=0,titleFixed=0,downloaded=0,downloadFailed=0;
            int technicalRemaps=0,ignoredDowngrades=0;
            ArrayList<Recommendation> newlyAdded=new ArrayList<>();
            ArrayList<UpdateInfo> newlyUpdated=new ArrayList<>();
            LinkedHashMap<String,Recommendation> toDownload=new LinkedHashMap<>();
            String date=attemptDate;

            LinkedHashMap<String,Recommendation> localByBase=new LinkedHashMap<>();
            LinkedHashMap<String,ArrayList<Recommendation>> localByIdentity=new LinkedHashMap<>();
            for(Recommendation r:localBefore) {
                localByBase.put(r.baseId,r);
                String key=identityKey(r.title);
                if(!key.isEmpty()) localByIdentity.computeIfAbsent(key,k->new ArrayList<>()).add(r);
            }
            HashSet<String> remapConsumed=new HashSet<>();

            for(Recommendation cur:online.values()) {
                Recommendation old=localByBase.get(cur.baseId);

                if(old==null) {
                    Recommendation equivalent=findUniqueEquivalent(cur,localByIdentity,remapConsumed);
                    if(equivalent!=null && db.remapBase(equivalent.baseId,cur,date)) {
                        technicalRemaps++;
                        remapConsumed.add(equivalent.baseId);
                        localByBase.remove(equivalent.baseId);
                        localByBase.put(cur.baseId,cur);
                        continue;
                    }

                    db.upsert(cur,date,date);
                    db.recordChange("NEW",cur,null,date);
                    added++;
                    newlyAdded.add(cur);
                    toDownload.put(cur.id,cur);
                    localByBase.put(cur.baseId,cur);
                    continue;
                }

                int oldVersion=version(old.id);
                int newVersion=version(cur.id);

                // A stale fallback or transient API inconsistency must never
                // downgrade a recommendation that is already newer locally.
                if(newVersion<oldVersion) {
                    ignoredDowngrades++;
                    continue;
                }

                if(newVersion>oldVersion) {
                    File oldFile=PdfManager.file(context,old);
                    if(PdfManager.isPdf(oldFile)) {
                        File target=new File(PdfManager.archiveDir(context),oldFile.getName());
                        if(target.exists()) {
                            target=new File(PdfManager.archiveDir(context),
                                    oldFile.getName().replace(".pdf","_"+System.currentTimeMillis()+".pdf"));
                        }
                        oldFile.renameTo(target);
                    }
                    db.upsert(cur,date);
                    db.recordChange("UPDATED",cur,old.id,date);
                    updated++;
                    newlyUpdated.add(new UpdateInfo(cur,old.id));
                    toDownload.put(cur.id,cur);
                    localByBase.put(cur.baseId,cur);
                } else if(!sameText(old.title,cur.title) || !sameText(old.mkbCodes,cur.mkbCodes)) {
                    // Metadata/title correction with the same version is not a
                    // clinical update and must not generate a notification.
                    db.upsert(cur,date);
                    if(!sameText(old.title,cur.title)) titleFixed++;
                    localByBase.put(cur.baseId,cur);
                }
            }

            Set<String> pending=new LinkedHashSet<>(
                    prefs.getStringSet("pending_pdf_ids",Collections.emptySet())
            );
            for(String id:pending) {
                Recommendation r=db.getById(id);
                if(r!=null && !PdfManager.isPdf(PdfManager.file(context,r))) {
                    toDownload.put(r.id,r);
                }
            }

            LinkedHashSet<String> stillPending=new LinkedHashSet<>();
            for(Recommendation r:toDownload.values()) {
                if(PdfManager.download(context,r)) downloaded++;
                else {
                    downloadFailed++;
                    stillPending.add(r.id);
                }
            }

            prefs.edit()
                    .putStringSet("pending_pdf_ids",stillPending)
                    .putString("last_sync",date)
                    .putString("last_sync_attempt",date)
                    .putString("last_sync_status","OK")
                    .putInt("last_sync_count",online.size())
                    .remove("last_sync_error")
                    .apply();

            if(added+updated>0) {
                notifyUpdates(context,added,updated,newlyAdded,newlyUpdated);
            }

            String msg;
            if(added==0 && updated==0) {
                msg="Новых или обновлённых клинических рекомендаций не найдено.";
            } else {
                msg="Проверка завершена. Новых КР: "+added+", обновлено: "+updated+".";
            }
            if(technicalRemaps>0) msg+="\nТехнических перенумераций без уведомления: "+technicalRemaps+".";
            if(ignoredDowngrades>0) msg+="\nИгнорировано устаревших версий: "+ignoredDowngrades+".";
            if(downloaded>0) msg+="\nPDF скачано: "+downloaded+".";
            if(downloadFailed>0) msg+="\nНе удалось скачать PDF: "+downloadFailed+". Повтор будет при следующей проверке.";

            return new Result(added,updated,titleFixed,downloaded,downloadFailed,
                    msg,newlyAdded,newlyUpdated);
        } catch(Exception e) {
            String detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            if(detail.length()>300) detail=detail.substring(0,300);

            prefs.edit()
                    .putString("last_sync_attempt",attemptDate)
                    .putString("last_sync_status","ERROR")
                    .putString("last_sync_error",detail)
                    .apply();

            return new Result(
                    0,0,0,0,0,
                    "Не удалось проверить обновления. Сохранённый локальный каталог оставлен без изменений.\n"+detail,
                    Collections.emptyList(),Collections.emptyList()
            );
        }
    }

    private static void validateCatalogIntegrity(Map<String,Recommendation> online,
                                                 List<Recommendation> local) throws IOException {
        int onlineCount=online==null?0:online.size();
        int localCount=local==null?0:local.size();

        if(onlineCount<ABSOLUTE_MIN_CATALOG) {
            throw new IOException("Получен неполный каталог: "+onlineCount+
                    " КР. Локальная база сохранена без изменений.");
        }

        if(localCount>=ABSOLUTE_MIN_CATALOG) {
            int relativeFloor=(int)Math.floor(localCount*MIN_RELATIVE_SIZE);
            if(onlineCount<relativeFloor) {
                throw new IOException("Размер нового каталога подозрительно уменьшился: "+
                        localCount+" → "+onlineCount+" КР. Обновление отклонено.");
            }

            HashSet<String> onlineBases=new HashSet<>(online.keySet());
            HashSet<String> onlineIdentities=new HashSet<>();
            for(Recommendation r:online.values()) {
                String key=identityKey(r.title);
                if(!key.isEmpty()) onlineIdentities.add(key);
            }

            int preserved=0;
            for(Recommendation old:local) {
                if(onlineBases.contains(old.baseId) || onlineIdentities.contains(identityKey(old.title))) {
                    preserved++;
                }
            }

            int missing=localCount-preserved;
            int maxMissing=Math.max(40,(int)Math.ceil(localCount*MAX_MISSING_SHARE));
            if(missing>maxMissing) {
                throw new IOException("Новый каталог не прошёл проверку целостности: отсутствуют "+
                        missing+" из "+localCount+" ранее известных КР. Локальная база сохранена.");
            }
        }

        HashSet<String> uniqueTitles=new HashSet<>();
        for(Recommendation r:online.values()) {
            String key=identityKey(r.title);
            if(!key.isEmpty()) uniqueTitles.add(key);
        }
        if(uniqueTitles.size()<Math.max(400,(int)(onlineCount*0.65))) {
            throw new IOException("Каталог содержит подозрительно много повторяющихся/повреждённых названий. Обновление отклонено.");
        }
    }

    private static Recommendation findUniqueEquivalent(
            Recommendation cur,
            Map<String,ArrayList<Recommendation>> localByIdentity,
            Set<String> consumed) {

        String key=identityKey(cur.title);
        if(key.isEmpty()) return null;
        List<Recommendation> candidates=localByIdentity.get(key);
        if(candidates==null || candidates.isEmpty()) return null;

        Recommendation found=null;
        for(Recommendation candidate:candidates) {
            if(candidate==null || consumed.contains(candidate.baseId)) continue;
            if(candidate.baseId.equals(cur.baseId)) continue;
            if(!mkbCompatible(candidate.mkbCodes,cur.mkbCodes)) continue;

            if(found!=null) {
                // Ambiguous identical titles: do not guess. Treat as a normal
                // new record instead of silently merging two recommendations.
                return null;
            }
            found=candidate;
        }
        return found;
    }

    private static boolean mkbCompatible(String a,String b) {
        Set<String> left=mkbSet(a);
        Set<String> right=mkbSet(b);
        if(left.isEmpty() || right.isEmpty()) return true;
        for(String code:left) if(right.contains(code)) return true;
        return false;
    }

    private static Set<String> mkbSet(String raw) {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        if(raw==null) return out;
        for(String part:raw.toUpperCase(Locale.ROOT).split("[,;\\s]+")) {
            String code=part.replace(".","").trim();
            if(!code.isEmpty()) out.add(code);
        }
        return out;
    }

    private static String identityKey(String s) {
        if(s==null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('ё','е')
                .replaceAll("[^а-яa-z0-9]+"," ")
                .replaceAll("\\s+"," ")
                .trim();
    }

    private static boolean sameText(String a,String b) {
        return (a==null?"":a.trim()).equals(b==null?"":b.trim());
    }

    private static int version(String id) {
        if(id==null) return 0;
        int p=id.indexOf('_');
        if(p<0 || p+1>=id.length()) return 0;
        try { return Integer.parseInt(id.substring(p+1)); }
        catch(Exception e) { return 0; }
    }

    private static void notifyUpdates(Context c,int a,int u,
                                      List<Recommendation> fresh,
                                      List<UpdateInfo> changed) {
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) {
            nm.createNotificationChannel(new NotificationChannel(
                    "updates","Обновления КР",NotificationManager.IMPORTANCE_DEFAULT
            ));
        }

        Intent intent=new Intent(c,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(
                c,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder b=Build.VERSION.SDK_INT>=26
                ? new Notification.Builder(c,"updates")
                : new Notification.Builder(c);

        String text="Новых: "+a+", обновлено: "+u;
        if(!fresh.isEmpty()) {
            text="Новая КР: "+fresh.get(0).title+
                    (fresh.size()>1?" и ещё "+(fresh.size()-1):"");
        } else if(!changed.isEmpty()) {
            UpdateInfo x=changed.get(0);
            text="Обновлена КР: "+x.recommendation.title+
                    " ("+x.previousId+" → "+x.recommendation.id+")"+
                    (changed.size()>1?" и ещё "+(changed.size()-1):"");
        }

        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Изменения клинических рекомендаций")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true);

        try {
            nm.notify(1001,b.build());
        } catch(SecurityException ignored) {
            c.getSharedPreferences("prefs",Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("notification_blocked",true)
                    .apply();
        }
    }
}
