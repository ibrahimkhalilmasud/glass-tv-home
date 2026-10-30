package com.mytvhome.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/**
 * Keeps the LAN server (phone remote and photo upload) running while other apps are on screen. Started
 * by TV Hub and again after a reboot by BootReceiver.
 */
public class RemoteService extends Service {

    private static final String CHANNEL_ID = "remote";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "TvHubService";
    private static PhotoServer server;

    /** The one server shared by this service and the TV Hub screen. */
    static synchronized PhotoServer get(Context context) {
        if (server == null) server = new PhotoServer(context, MainActivity.PHOTO_PORT);
        return server;
    }

    static void start(Context context) {
        Intent i = new Intent(context, RemoteService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Phone remote", NotificationManager.IMPORTANCE_MIN));
        }
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        startForeground(NOTIFICATION_ID, b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Phone remote is on").build());
        try {
            get(this).start();
        } catch (IOException e) {
            Log.w(TAG, "server did not start", e);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
