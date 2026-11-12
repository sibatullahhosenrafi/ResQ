package com.example.resq;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/**
 * Application class (set in the manifest).
 * Android does not deliver BATTERY_LOW / BATTERY_OKAY / AIRPLANE_MODE_CHANGED to receivers that
 * are declared in the manifest, so SystemEventReceiver is registered here in code.
 * It works for as long as the app process is alive.
 */
public class ResQApp extends Application {

    private SystemEventReceiver systemReceiver;

    @Override
    public void onCreate() {
        super.onCreate();

        // light / dark mode saved by the sun-moon toggle (default: light)
        ThemeHelper.apply(this);

        systemReceiver = new SystemEventReceiver();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_BATTERY_LOW);
        f.addAction(Intent.ACTION_BATTERY_OKAY);
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        // These are protected system broadcasts, only the system can send them,
        // so the receiver can safely be exported.
        ContextCompat.registerReceiver(this, systemReceiver, f, ContextCompat.RECEIVER_EXPORTED);
    }
}
