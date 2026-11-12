package com.example.resq;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;

/**
 * ResQ Safety Panel: the home-screen widget.
 *
 *  - big SOS button          -> opens ResQ and asks "Send an SOS message to your circle now?"
 *  - Siren tile              -> turns the siren ON / OFF right from the home screen
 *  - Hospital tile           -> navigates to the nearest hospital (same logic as the app button)
 *  - Call 999 tile           -> opens the dialer with 999
 *  - First aid tile          -> opens the First Aid guides
 *
 * Two looks: calm (white / pink) and ALARM (the whole widget turns crimson while the siren is ON).
 * SirenService calls {@link #refresh(Context)} whenever it starts or stops, so the look stays correct.
 */
public class ResQWidget extends AppWidgetProvider {

    private static final String TAG = "ResQ_Widget";
    public static final String ACTION_TOGGLE_SIREN = "com.example.resq.WIDGET_TOGGLE_SIREN";

    private static final int FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        RemoteViews views = build(context);
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, views);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);       // handles the normal widget update messages
        if (intent != null && ACTION_TOGGLE_SIREN.equals(intent.getAction())) {
            toggleSiren(context);
        }
    }

    /** Siren tile pressed: stop it if it is playing, otherwise start it. */
    private void toggleSiren(Context context) {
        try {
            Intent siren = new Intent(context, SirenService.class);
            if (SirenService.isRunning()) {
                context.stopService(siren);
            } else {
                // a widget tap is allowed to start a foreground service from the home screen
                ContextCompat.startForegroundService(context, siren);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not toggle the siren", e);
            Toast.makeText(context, "Could not start the siren. Open ResQ and try again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Redraws every ResQ widget on the home screen. Safe to call from anywhere, never throws. */
    public static void refresh(Context context) {
        try {
            Context app = context.getApplicationContext();
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            int[] ids = manager.getAppWidgetIds(new ComponentName(app, ResQWidget.class));
            if (ids == null || ids.length == 0) return;
            manager.updateAppWidget(ids, build(app));
        } catch (Exception e) {
            Log.e(TAG, "Could not refresh the widget", e);
        }
    }

    // ================= drawing =================

    private static RemoteViews build(Context c) {
        final boolean alarm = SirenService.isRunning();
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_resq);

        final int crimson = ContextCompat.getColor(c, R.color.brand_primary);
        final int white = ContextCompat.getColor(c, R.color.white);
        final int dark = ContextCompat.getColor(c, R.color.widget_text_primary);
        final int grey = ContextCompat.getColor(c, R.color.widget_text_secondary);

        // ---- colours / backgrounds for the two looks ----
        v.setInt(R.id.w_root, "setBackgroundResource",
                alarm ? R.drawable.widget_bg_alarm : R.drawable.widget_bg_calm);

        v.setTextColor(R.id.w_title, alarm ? white : dark);
        v.setTextViewText(R.id.w_status, c.getString(alarm
                ? R.string.widget_siren_on_status : R.string.widget_ready));
        v.setTextColor(R.id.w_status, alarm ? white : grey);

        // SOS button and its rings
        v.setInt(R.id.w_ring_outer, "setBackgroundResource",
                alarm ? R.drawable.widget_ring_outer_alarm : R.drawable.bg_sos_ring_outer);
        v.setInt(R.id.w_ring_inner, "setBackgroundResource",
                alarm ? R.drawable.widget_ring_inner_alarm : R.drawable.bg_sos_ring_inner);
        v.setInt(R.id.w_sos_text, "setBackgroundResource",
                alarm ? R.drawable.widget_sos_btn_alarm : R.drawable.widget_sos_btn);
        v.setTextColor(R.id.w_sos_text, alarm ? crimson : white);

        // the three static tiles
        int tileBg = alarm ? R.drawable.widget_tile_alarm : R.drawable.widget_tile_calm;
        int fg = alarm ? white : dark;
        int icon = alarm ? white : crimson;
        v.setInt(R.id.w_tile_hosp, "setBackgroundResource", tileBg);
        v.setInt(R.id.w_tile_call, "setBackgroundResource", tileBg);
        v.setInt(R.id.w_tile_aid, "setBackgroundResource", tileBg);
        v.setTextColor(R.id.w_tv_hosp, fg);
        v.setTextColor(R.id.w_tv_call, fg);
        v.setTextColor(R.id.w_tv_aid, fg);
        v.setInt(R.id.w_ic_hosp, "setColorFilter", icon);
        v.setInt(R.id.w_ic_call, "setColorFilter", icon);
        v.setInt(R.id.w_ic_aid, "setColorFilter", icon);

        // the siren tile: normal = "Siren"; while ringing = a white "Stop" tile
        if (alarm) {
            v.setInt(R.id.w_tile_siren, "setBackgroundResource", R.drawable.widget_tile_siren_on);
            v.setImageViewResource(R.id.w_ic_siren, R.drawable.ic_siren_off);
            v.setInt(R.id.w_ic_siren, "setColorFilter", crimson);
            v.setTextViewText(R.id.w_tv_siren, c.getString(R.string.widget_stop));
            v.setTextColor(R.id.w_tv_siren, crimson);
        } else {
            v.setInt(R.id.w_tile_siren, "setBackgroundResource", R.drawable.widget_tile_calm);
            v.setImageViewResource(R.id.w_ic_siren, R.drawable.ic_siren_on);
            v.setInt(R.id.w_ic_siren, "setColorFilter", crimson);
            v.setTextViewText(R.id.w_tv_siren, c.getString(R.string.widget_siren));
            v.setTextColor(R.id.w_tv_siren, dark);
        }

        // ---- taps ----
        v.setOnClickPendingIntent(R.id.w_sos, sosIntent(c));

        Intent toggle = new Intent(c, ResQWidget.class).setAction(ACTION_TOGGLE_SIREN);
        v.setOnClickPendingIntent(R.id.w_tile_siren,
                PendingIntent.getBroadcast(c, 21, toggle, FLAGS));

        Intent hospital = new Intent(c, WidgetHospitalActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.w_tile_hosp,
                PendingIntent.getActivity(c, 22, hospital, FLAGS));

        Intent call = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:999"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.w_tile_call,
                PendingIntent.getActivity(c, 23, call, FLAGS));

        Intent aid = new Intent(c, FirstAidActivity.class)
                .putExtra(FirstAidActivity.EXTRA_SOURCE, "Widget")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.w_tile_aid,
                PendingIntent.getActivity(c, 24, aid, FLAGS));

        return v;
    }

    /** SOS: signed in -> the "Send an SOS message?" question inside ResQ; signed out -> login. */
    private static PendingIntent sosIntent(Context c) {
        boolean signedIn = false;
        try {
            signedIn = FirebaseAuth.getInstance().getCurrentUser() != null;
        } catch (Exception ignored) {
        }
        Intent i;
        if (signedIn) {
            i = new Intent(c, MainActivity.class)
                    .putExtra(SystemAlerts.EXTRA_ASK_SOS, true)
                    .putExtra(MainActivity.EXTRA_ASK_TITLE, c.getString(R.string.widget_ask_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } else {
            i = new Intent(c, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return PendingIntent.getActivity(c, 20, i, FLAGS);
    }
}
