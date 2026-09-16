package ru.krmonitor.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class SyncEngine {
    public static final class Result {
        public final int added,updated,titleFixed; public final String message;
        Result(int a,int u,int t,String m){added=a;updated=u;titleFixed=t;message=m;}
    }
    private SyncEngine() {}
    public static Result sync(Context context) {
        try {
            SeedImporter.ensureSeeded(context);
            Map<String,Recommendation> online=CatalogFetcher.fetch();
            DbHelper db=new DbHelper(context);
            int added=0,updated=0,titleFixed=0;
            String date=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date());
            for(Recommendation cur:online.values()) {
                Recommendation old=db.getByBase(cur.baseId);
                if(old==null) {
                    db.upsert(cur,date); added++;
                    PdfManager.download(context,cur);
                } else if(!old.id.equals(cur.id)) {
                    File oldFile=PdfManager.file(context,old);
                    if(PdfManager.isPdf(oldFile)) {
                        File target=new File(PdfManager.archiveDir(context),oldFile.getName());
                        if(target.exists()) target=new File(PdfManager.archiveDir(context),oldFile.getName().replace(".pdf","_"+System.currentTimeMillis()+".pdf"));
                        oldFile.renameTo(target);
                    }
                    db.upsert(cur,date); updated++;
                    PdfManager.download(context,cur);
                } else if(!old.title.equals(cur.title)) {
                    db.upsert(cur,date); titleFixed++;
                }
            }
            context.getSharedPreferences("prefs",Context.MODE_PRIVATE).edit().putString("last_sync",date).apply();
            if(added+updated>0) notifyUpdates(context,added,updated);
            return new Result(added,updated,titleFixed,"Готово. Новых: "+added+", обновлено: "+updated);
        } catch(Exception e) {
            return new Result(0,0,0,"Ошибка проверки: "+e.getMessage());
        }
    }
    private static void notifyUpdates(Context c,int a,int u) {
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel("updates","Обновления КР",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(c,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,"updates"):new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Обновления клинических рекомендаций")
         .setContentText("Новых: "+a+", обновлено: "+u).setContentIntent(pi).setAutoCancel(true);
        try { nm.notify(1001,b.build()); } catch(SecurityException ignored) {}
    }
}
