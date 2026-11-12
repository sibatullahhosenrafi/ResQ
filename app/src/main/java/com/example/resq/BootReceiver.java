package com.example.resq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts chat notifications again after the phone restarts or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            ChatNotifyService.start(context);
        }
    }
}
