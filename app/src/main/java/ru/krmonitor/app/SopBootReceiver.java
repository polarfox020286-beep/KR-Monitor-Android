package ru.krmonitor.app;

import android.content.*;

public class SopBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){ SopAlarmScheduler.scheduleNext(c); }
}
