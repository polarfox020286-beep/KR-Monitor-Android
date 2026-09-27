package ru.krmonitor.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.util.*;

public final class AlarmScheduler {
    private AlarmScheduler() {}

    public static long nextWeekday12() {
        Calendar now=Calendar.getInstance();
        Calendar next=(Calendar)now.clone();
        next.set(Calendar.HOUR_OF_DAY,12);
        next.set(Calendar.MINUTE,0);
        next.set(Calendar.SECOND,0);
        next.set(Calendar.MILLISECOND,0);

        int dow=next.get(Calendar.DAY_OF_WEEK);
        boolean weekday=dow>=Calendar.MONDAY && dow<=Calendar.FRIDAY;
        if(!weekday || !next.after(now)) next.add(Calendar.DAY_OF_MONTH,1);
        while(next.get(Calendar.DAY_OF_WEEK)==Calendar.SATURDAY || next.get(Calendar.DAY_OF_WEEK)==Calendar.SUNDAY) {
            next.add(Calendar.DAY_OF_MONTH,1);
        }
        next.set(Calendar.HOUR_OF_DAY,12);
        next.set(Calendar.MINUTE,0);
        next.set(Calendar.SECOND,0);
        next.set(Calendar.MILLISECOND,0);
        return next.getTimeInMillis();
    }

    public static void scheduleNext(Context c) {
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am==null) return;

        // Cancel alarms used by older versions so an upgrade does not leave
        // the old 07:00 or historical 19:00 schedule active.
        cancelIfExists(c,am,1900);
        cancelIfExists(c,am,700);

        Intent i=new Intent(c,AlarmReceiver.class);
        PendingIntent pi=PendingIntent.getBroadcast(
                c,1200,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
        );
        long when=nextWeekday12();

        // Background catalog refresh does not need to fire at an exact minute.
        // Use an inexact idle-capable alarm so Android can batch work for
        // battery efficiency and the app does not need SCHEDULE_EXACT_ALARM.
        if(Build.VERSION.SDK_INT>=23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP,when,pi);
        }
        c.getSharedPreferences("prefs",Context.MODE_PRIVATE)
                .edit()
                .putLong("next_alarm",when)
                .apply();
    }

    private static void cancelIfExists(Context c,AlarmManager am,int requestCode) {
        Intent intent=new Intent(c,AlarmReceiver.class);
        PendingIntent old=PendingIntent.getBroadcast(
                c,requestCode,intent,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
        );
        if(old!=null) {
            am.cancel(old);
            old.cancel();
        }
    }
}
