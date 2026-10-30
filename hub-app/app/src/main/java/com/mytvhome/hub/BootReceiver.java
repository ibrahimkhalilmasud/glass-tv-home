package com.mytvhome.hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the phone remote after the box boots, so it works without opening TV Hub first. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        RemoteService.start(context);
        context.startActivity(new Intent(context, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
