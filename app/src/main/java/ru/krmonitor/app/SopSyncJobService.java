package ru.krmonitor.app;

import android.app.job.*;
import android.content.*;

public class SopSyncJobService extends JobService {
    @Override public boolean onStartJob(JobParameters p){
        new Thread(() -> {
            SopSyncEngine.sync(getApplicationContext());
            Intent done=new Intent(SopMainActivity.ACTION_SYNC_COMPLETE).setPackage(getPackageName());
            sendBroadcast(done);
            jobFinished(p,false);
        },"sop-sync").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters p){ return true; }
}
