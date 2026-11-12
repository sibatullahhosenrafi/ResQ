package com.example.resq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.provider.Settings;

/**
 * Reacts to real system broadcasts (registered in ResQApp):
 *  - BATTERY_LOW            -> heads-up "Battery is low" with a SEND SOS... button
 *  - BATTERY_OKAY           -> removes that warning
 *  - AIRPLANE_MODE_CHANGED  -> warns that SOS text messages cannot be sent / removes the warning
 * Nothing is ever sent automatically: the SOS button only opens ResQ and asks first.
 */
public class SystemEventReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        switch (intent.getAction()) {
            case Intent.ACTION_BATTERY_LOW:
                SystemAlerts.showLowBattery(context, batteryPercent(context));
                break;

            case Intent.ACTION_BATTERY_OKAY:
                SystemAlerts.clearLowBattery(context);
                break;

            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                boolean on = intent.hasExtra("state")
                        ? intent.getBooleanExtra("state", false)
                        : isAirplaneOn(context);
                if (on) SystemAlerts.showAirplane(context);
                else SystemAlerts.clearAirplane(context);
                break;

            default:
                break;
        }
    }

    /** Battery level 0-100, or -1 if the phone does not report it. */
    static int batteryPercent(Context ctx) {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return -1;
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return (pct >= 0 && pct <= 100) ? pct : -1;
    }

    static boolean isAirplaneOn(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }
}
