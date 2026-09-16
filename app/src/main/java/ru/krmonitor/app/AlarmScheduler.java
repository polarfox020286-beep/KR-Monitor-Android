package ru.krmonitor.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.util.*;

public final class AlarmScheduler {
    private AlarmScheduler() {}

    public static long nextWeekday7() {
        Calendar now=Calendar.getInstance();
        Calendar next=(Calendar)now.clone();
        next.set(Calendar.HOUR_OF_DAY,7);
        next.set(Calendar.MINUTE,0);
        next.set(Calendar.SECOND,0);
        next.set(Calendar.MILLISECOND,0);

        int dow=next.get(Calendar.DAY_OF_WEEK);
        boolean weekday=dow>=Calendar.MONDAY && dow<=Calendar.FRIDAY;
        if(!weekday || !next.after(now)) next.add(Calendar.DAY_OF_MONTH,1);
        while(next.get(Calendar.DAY_OF_WEEK)==Calendar.SATURDAY || next.get(Calendar.DAY_OF_WEEK)==Calendar.SUNDAY) {
            next.add(Calendar.DAY_OF_MONTH,1);
        }
        next.set(Calendar.HOUR_OF_DAY,7);
        next.set(Calendar.MINUTE,0);
        next.set(Calendar.SECOND,0);
        next.set(Calendar.MILLISECOND,0);
        return next.getTimeInMillis();
    }

    public static void scheduleNext(Context c) {
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);

        // Cancel alarm used by older versions (Monday 19:00).
        Intent legacyIntent=new Intent(c,AlarmReceiver.class);
        PendingIntent legacy=PendingIntent.getBroadcast(c,1900,legacyIntent,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
        if(legacy!=null) { am.cancel(legacy); legacy.cancel(); }

        Intent i=new Intent(c,AlarmReceiver.class);
        PendingIntent pi=PendingIntent.getBroadcast(c,700,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        long when=nextWeekday7();
        if(Build.VERSION.SDK_INT>=31 && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP,when,15*60*1000L,pi);
        } else if(Build.VERSION.SDK_INT>=23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP,when,pi);
        }
        c.getSharedPreferences("prefs",Context.MODE_PRIVATE).edit().putLong("next_alarm",when).apply();
    }
}
