package ru.krmonitor.app;

import android.app.job.*;

public class SyncJobService extends JobService {
    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> {
            SyncEngine.sync(getApplicationContext());
            sendBroadcast(new android.content.Intent("ru.krmonitor.app.SYNC_COMPLETE").setPackage(getPackageName()));
            jobFinished(p,false);
        },"kr-sync").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters p) { return true; }
}
