package ru.krmonitor.app;

import android.app.job.*;

public class SyncJobService extends JobService {
    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> { SyncEngine.sync(getApplicationContext()); jobFinished(p,false); },"kr-sync").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters p) { return true; }
}
