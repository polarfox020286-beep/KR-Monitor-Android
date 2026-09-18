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
        Result(int a,int u,int t,int d,int f,String m,List<Recommendation> n,List<UpdateInfo> up){added=a;updated=u;titleFixed=t;downloaded=d;downloadFailed=f;message=m;newRecommendations=n;updatedRecommendations=up;}
        Result(int a,int u,int t,int d,int f,String m,List<Recommendation> n){this(a,u,t,d,f,m,n,Collections.emptyList());}
    }
    private SyncEngine() {}

    public static Result sync(Context context) {
        try {
            SeedImporter.ensureSeeded(context);
            Map<String,Recommendation> online=CatalogFetcher.fetch();
            DbHelper db=new DbHelper(context);
            int added=0,updated=0,titleFixed=0,downloaded=0,downloadFailed=0;
            ArrayList<Recommendation> newlyAdded=new ArrayList<>();
            ArrayList<UpdateInfo> newlyUpdated=new ArrayList<>();
            LinkedHashMap<String,Recommendation> toDownload=new LinkedHashMap<>();
            String date=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date());

            for(Recommendation cur:online.values()) {
                Recommendation old=db.getByBase(cur.baseId);
                if(old==null) {
                    db.upsert(cur,date,date);
                    db.recordChange("NEW",cur,null,date);
                    added++;
                    newlyAdded.add(cur);
                    toDownload.put(cur.id,cur);
                } else if(!old.id.equals(cur.id)) {
                    File oldFile=PdfManager.file(context,old);
                    if(PdfManager.isPdf(oldFile)) {
                        File target=new File(PdfManager.archiveDir(context),oldFile.getName());
                        if(target.exists()) target=new File(PdfManager.archiveDir(context),oldFile.getName().replace(".pdf","_"+System.currentTimeMillis()+".pdf"));
                        oldFile.renameTo(target);
                    }
                    db.upsert(cur,date);
                    db.recordChange("UPDATED",cur,old.id,date);
                    updated++;
                    newlyUpdated.add(new UpdateInfo(cur,old.id));
                    toDownload.put(cur.id,cur);
                } else if(!old.title.equals(cur.title)) {
                    db.upsert(cur,date);
                    titleFixed++;
                }
            }

            SharedPreferences prefs=context.getSharedPreferences("prefs",Context.MODE_PRIVATE);
            Set<String> pending=new LinkedHashSet<>(prefs.getStringSet("pending_pdf_ids",Collections.emptySet()));
            for(String id:pending) {
                Recommendation r=db.getById(id);
                if(r!=null && !PdfManager.isPdf(PdfManager.file(context,r))) toDownload.put(r.id,r);
            }

            LinkedHashSet<String> stillPending=new LinkedHashSet<>();
            for(Recommendation r:toDownload.values()) {
                if(PdfManager.download(context,r)) downloaded++;
                else { downloadFailed++; stillPending.add(r.id); }
            }
            prefs.edit().putStringSet("pending_pdf_ids",stillPending).putString("last_sync",date).apply();

            if(added+updated>0) notifyUpdates(context,added,updated,newlyAdded,newlyUpdated);

            String msg;
            if(added==0 && updated==0) msg="Новых или обновлённых клинических рекомендаций не найдено.";
            else msg="Проверка завершена. Новых КР: "+added+", обновлено: "+updated+".";
            if(downloaded>0) msg += "\nPDF скачано: "+downloaded+".";
            if(downloadFailed>0) msg += "\nНе удалось скачать PDF: "+downloadFailed+". Повтор будет при следующей проверке.";
            return new Result(added,updated,titleFixed,downloaded,downloadFailed,msg,newlyAdded,newlyUpdated);
        } catch(Exception e) {
            return new Result(0,0,0,0,0,"Не удалось проверить обновления. Локальный реестр сохранён.\n"+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),Collections.emptyList(),Collections.emptyList());
        }
    }

    private static void notifyUpdates(Context c,int a,int u,List<Recommendation> fresh,List<UpdateInfo> changed) {
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel("updates","Обновления КР",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(c,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,"updates"):new Notification.Builder(c);
        String text="Новых: "+a+", обновлено: "+u;
        if(!fresh.isEmpty()) {
            text="Новая КР: "+fresh.get(0).title+(fresh.size()>1?" и ещё "+(fresh.size()-1):"");
        } else if(!changed.isEmpty()) {
            UpdateInfo x=changed.get(0);
            text="Обновлена КР: "+x.recommendation.title+" ("+x.previousId+" → "+x.recommendation.id+")"+(changed.size()>1?" и ещё "+(changed.size()-1):"");
        }
        b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Изменения клинических рекомендаций")
         .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true);
        try { nm.notify(1001,b.build()); }
        catch(SecurityException ignored) { c.getSharedPreferences("prefs",Context.MODE_PRIVATE).edit().putBoolean("notification_blocked",true).apply(); }
    }
}
