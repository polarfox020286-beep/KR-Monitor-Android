package ru.krmonitor.app;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){ AlarmScheduler.scheduleNext(c); }
}
