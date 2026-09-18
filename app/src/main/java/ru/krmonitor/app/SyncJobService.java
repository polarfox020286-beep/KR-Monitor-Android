package ru.krmonitor.app;

import android.app.job.*;

public class SyncJobService extends JobService {
    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> {\n            SyncEngine.sync(getApplicationContext());\n            sendBroadcast(new android.content.Intent("ru.krmonitor.app.SYNC_COMPLETE").setPackage(getPackageName()));\n            jobFinished(p,false);\n        },"kr-sync").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters p) { return true; }
}
