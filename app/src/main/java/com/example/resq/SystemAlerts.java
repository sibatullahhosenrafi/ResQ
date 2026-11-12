package com.example.resq;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Builds the notifications for system events (low battery, airplane mode).
 * The low-battery button only OPENS ResQ with a question: nothing is sent until the user confirms.
 */
public final class SystemAlerts {

    /** Extra on the Intent that opens MainActivity: "ask the user if they want to send an SOS". */
    public static final String EXTRA_ASK_SOS = "com.example.resq.EXTRA_ASK_SOS";

    private static final String CHANNEL_ID = "system_alerts_channel";
    private static final int ID_BATTERY = 3301;
    private static final int ID_AIRPLANE = 3302;

    // Separate, fresh channel for the airplane alert. A channel's importance is frozen after it is
    // first created (and the user may have lowered it), so a new id guarantees pop-up (heads-up).
    private static final String AIRPLANE_CHANNEL_ID = "airplane_alert_channel_v2";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingAirplane;

    private SystemAlerts() { }

    // ================= low battery =================
    public static void showLowBattery(Context ctx, int percent) {
        ensureChannel(ctx);
        String title = percent >= 0 ? "Battery is low (" + percent + "%)" : "Battery is low";
        String text = "Your phone may switch off soon. You can tell your circle now.";

        // Opens ResQ and asks "Send an SOS message to your circle now?" - sends nothing by itself
        Intent open = new Intent(ctx, MainActivity.class)
                .putExtra(EXTRA_ASK_SOS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent sosPi = PendingIntent.getActivity(ctx, 3310, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(sosPi)
                .addAction(R.drawable.ic_stat_sos, "SEND SOS...", sosPi)
                .build();
        post(ctx, ID_BATTERY, n);
    }

    public static void clearLowBattery(Context ctx) {
        NotificationManagerCompat.from(ctx).cancel(ID_BATTERY);
    }

    // ================= airplane mode =================
    public static void showAirplane(Context ctx) {
        final Context app = ctx.getApplicationContext();
        cancelPendingAirplane();
        // Post a moment later: if airplane mode was toggled from the quick-settings shade, the
        // system hides pop-ups while the shade is still open / closing.
        pendingAirplane = () -> {
            pendingAirplane = null;
            postAirplane(app);
        };
        MAIN.postDelayed(pendingAirplane, 700);
    }

    private static void postAirplane(Context ctx) {
        ensureAirplaneChannel(ctx);
        String title = "Airplane mode is ON";
        String text = "Airplane mode is ON. SOS text messages cannot be sent while it is on.";

        // Apps cannot switch airplane mode themselves, so the button opens its settings screen
        PendingIntent settingsPi = PendingIntent.getActivity(ctx, 3320,
                new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(ctx, AIRPLANE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(settingsPi)
                .addAction(R.drawable.ic_stat_sos, "TURN OFF", settingsPi)
                .build();
        post(ctx, ID_AIRPLANE, n);
    }

    private static void cancelPendingAirplane() {
        if (pendingAirplane != null) {
            MAIN.removeCallbacks(pendingAirplane);
            pendingAirplane = null;
        }
    }

    public static void clearAirplane(Context ctx) {
        cancelPendingAirplane();
        NotificationManagerCompat.from(ctx).cancel(ID_AIRPLANE);
    }

    // ================= helpers =================
    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Phone status alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Warns about low battery and airplane mode, which can stop an SOS");
            ch.enableVibration(true);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static void ensureAirplaneChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    AIRPLANE_CHANNEL_ID, "Airplane mode alert", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Pops up when airplane mode is switched on, because it blocks SOS texts");
            ch.enableVibration(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /** Shows the notification (silently does nothing if notifications are switched off). */
    @SuppressLint("MissingPermission")
    private static void post(Context ctx, int id, Notification n) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        if (!nm.areNotificationsEnabled()) return;
        try {
            nm.notify(id, n);
        } catch (SecurityException ignored) {
            // notification permission missing
        }
    }
}
