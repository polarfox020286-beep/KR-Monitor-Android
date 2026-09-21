package ru.krmonitor.app;

import android.app.job.*;
import android.content.*;

public class SopAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo job=new JobInfo.Builder(2711,new ComponentName(c,SopSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(60*60*1000L).build();
        js.schedule(job);
        SopAlarmScheduler.scheduleNext(c);
    }
}
