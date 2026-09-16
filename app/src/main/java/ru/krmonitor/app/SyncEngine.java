package ru.krmonitor.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class SyncEngine {
    public static final class Result {
        public final int added,updated,titleFixed;
        public final String message;
        public final List<Recommendation> newRecommendations;
        Result(int a,int u,int t,String m,List<Recommendation> n){added=a;updated=u;titleFixed=t;message=m;newRecommendations=n;}
    }
    private SyncEngine() {}

    public static Result sync(Context context) {
        try {
            SeedImporter.ensureSeeded(context);
            Map<String,Recommendation> online=CatalogFetcher.fetch();
            DbHelper db=new DbHelper(context);
            int added=0,updated=0,titleFixed=0;
            ArrayList<Recommendation> newlyAdded=new ArrayList<>();
            String date=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date());
            for(Recommendation cur:online.values()) {
                Recommendation old=db.getByBase(cur.baseId);
                if(old==null) {
                    db.upsert(cur,date,date);
                    added++;
                    newlyAdded.add(cur);
                    PdfManager.download(context,cur);
                } else if(!old.id.equals(cur.id)) {
                    File oldFile=PdfManager.file(context,old);
                    if(PdfManager.isPdf(oldFile)) {
                        File target=new File(PdfManager.archiveDir(context),oldFile.getName());
                        if(target.exists()) target=new File(PdfManager.archiveDir(context),oldFile.getName().replace(".pdf","_"+System.currentTimeMillis()+".pdf"));
                        oldFile.renameTo(target);
                    }
                    db.upsert(cur,date);
                    updated++;
                    PdfManager.download(context,cur);
                } else if(!old.title.equals(cur.title)) {
                    db.upsert(cur,date);
                    titleFixed++;
                }
            }
            context.getSharedPreferences("prefs",Context.MODE_PRIVATE).edit().putString("last_sync",date).apply();
            if(added+updated>0) notifyUpdates(context,added,updated,newlyAdded);
            String msg;
            if(added==0 && updated==0) msg="Новых клинических рекомендаций не найдено.";
            else msg="Проверка завершена. Новых КР: "+added+", обновлено: "+updated+".";
            return new Result(added,updated,titleFixed,msg,newlyAdded);
        } catch(Exception e) {
            return new Result(0,0,0,"Не удалось проверить обновления. Локальный реестр сохранён.\n"+e.getMessage(),Collections.emptyList());
        }
    }

    private static void notifyUpdates(Context c,int a,int u,List<Recommendation> fresh) {
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel("updates","Обновления КР",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(c,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,"updates"):new Notification.Builder(c);
        String text="Новых: "+a+", обновлено: "+u;
        if(!fresh.isEmpty()) text="Новая КР: "+fresh.get(0).title+(fresh.size()>1?" и ещё "+(fresh.size()-1):"");
        b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Обновления клинических рекомендаций")
         .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true);
        try { nm.notify(1001,b.build()); } catch(SecurityException ignored) {}
    }
}
