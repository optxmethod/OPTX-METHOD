package com.optiverse.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the process alive (screen off / app in background) while a video is being processed. */
public class KeepAliveService extends Service {
    @Override public int onStartCommand(Intent i, int flags, int id) {
        String ch = "optiverse_jobs";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(ch, "Video processing", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle("OPTIVERSE X").setContentText("Processing your video...")
         .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
