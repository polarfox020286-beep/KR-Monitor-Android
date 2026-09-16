package ru.krmonitor.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.util.*;

public final class AlarmScheduler {
    private AlarmScheduler() {}
    public static long nextMonday19() {
        Calendar now=Calendar.getInstance();
        Calendar next=(Calendar)now.clone();
        next.set(Calendar.HOUR_OF_DAY,19); next.set(Calendar.MINUTE,0); next.set(Calendar.SECOND,0); next.set(Calendar.MILLISECOND,0);
        int dow=now.get(Calendar.DAY_OF_WEEK);
        int days=(Calendar.MONDAY-dow+7)%7;
        if(days==0 && now.after(next)) days=7;
        next.add(Calendar.DAY_OF_MONTH,days);
        return next.getTimeInMillis();
    }
    public static void scheduleNext(Context c) {
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        Intent i=new Intent(c,AlarmReceiver.class);
        PendingIntent pi=PendingIntent.getBroadcast(c,1900,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        long when=nextMonday19();
        if(Build.VERSION.SDK_INT>=31 && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP,when,15*60*1000L,pi);
        } else if(Build.VERSION.SDK_INT>=23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        } else am.setExact(AlarmManager.RTC_WAKEUP,when,pi);
        c.getSharedPreferences("prefs",Context.MODE_PRIVATE).edit().putLong("next_alarm",when).apply();
    }
}
