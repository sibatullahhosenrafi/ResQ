package com.example.resq;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Live location sharing (Realtime Database).
 *  sos_alerts/{uid}  = name, phone, lat, lng, battery, updatedAt ...  (updated every 10 seconds)
 *  sos_codes/{code}  = uid   (lets a contact find the alert with the code from the SOS SMS)
 * It runs as a foreground service so it keeps working when the screen is locked.
 */
public class LocationShareService extends Service {

    public static final String EXTRA_CODE = "extra_code";
    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_PHONE = "extra_phone";
    public static final String EXTRA_LAT = "extra_lat";
    public static final String EXTRA_LNG = "extra_lng";
    public static final String ACTION_STOP = "com.example.resq.STOP_SHARING";
    /** Stop sharing AND tell My Circle and the chat rooms "I am safe". */
    public static final String ACTION_SAFE = "com.example.resq.SAFE_STOP";

    private static final String TAG = "ResQ_Share";
    private static final String CHANNEL_ID = "share_channel";
    private static final int NOTIF_ID = 2;
    private static final long UPDATE_MS = 10_000L;               // location every 10 seconds
    private static final long MAX_SHARE_MS = 2L * 60 * 60 * 1000; // stop by itself after 2 hours
    private static final String KEY_CODE = "share_code";

    private static volatile boolean running = false;
    private static volatile String activeCode = "";
    private static Runnable stateListener;

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private DatabaseReference alertRef;
    private String uid;
    private boolean started = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---------- state for the UI ----------
    public static boolean isRunning() {
        return running;
    }

    public static String getActiveCode() {
        return activeCode;
    }

    /** MainActivity listens here to show / hide the "Sharing live location" card. */
    public static void setStateListener(Runnable listener) {
        stateListener = listener;
    }

    private static void notifyState() {
        final Runnable l = stateListener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l);
    }

    // ---------- cleanup helpers (also used by logout and app start) ----------
    /** Deletes this user's live alert + code from the database. */
    public static void cleanupNow(SharedPreferences prefs, String uid) {
        try {
            FirebaseDatabase db = Rtdb.get();
            db.getReference("sos_alerts").child(uid).removeValue();
            String code = prefs.getString(KEY_CODE, "");
            if (code != null && !code.isEmpty()) {
                db.getReference("sos_codes").child(code).removeValue();
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanup failed", e);
        }
        prefs.edit().remove(KEY_CODE).apply();
    }

    /** If the app was killed while sharing, remove the old alert the next time the app opens. */
    public static void cleanupLeftovers(SharedPreferences prefs) {
        if (running) return;
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        String code = prefs.getString(KEY_CODE, "");
        if (code != null && !code.isEmpty()) cleanupNow(prefs, u.getUid());
    }

    // ---------- service ----------
    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SAFE.equals(intent.getAction())) {
            SafeMessage.send(this);       // SMS + chat, with its own progress notification
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;      // already sharing

        String code = intent == null ? null : intent.getStringExtra(EXTRA_CODE);

        // A foreground service must show its notification straight away
        createChannel();
        try {
            Notification n = buildNotification(code == null ? "" : code);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start foreground service", e);
            Toast.makeText(this, "Could not start live location sharing", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !TrackCode.isValid(code)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        started = true;
        running = true;
        activeCode = code;
        uid = user.getUid();
        getSharedPreferences("resq", Context.MODE_PRIVATE).edit().putString(KEY_CODE, code).apply();

        String name = intent.getStringExtra(EXTRA_NAME);
        String phone = intent.getStringExtra(EXTRA_PHONE);
        double lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN);
        double lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN);

        // 1) create the alert + the code that points to it
        FirebaseDatabase db = Rtdb.get();
        alertRef = db.getReference("sos_alerts").child(uid);
        Map<String, Object> first = new HashMap<>();
        first.put("active", true);
        first.put("code", code);
        first.put("name", name == null ? "" : name);
        first.put("phone", phone == null ? "" : phone);
        first.put("battery", batteryPercent());
        first.put("startedAt", ServerValue.TIMESTAMP);
        first.put("updatedAt", ServerValue.TIMESTAMP);
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            first.put("lat", lat);
            first.put("lng", lng);
        }
        alertRef.setValue(first).addOnFailureListener(this::showError);
        db.getReference("sos_codes").child(code).setValue(uid).addOnFailureListener(this::showError);

        // 2) keep the location fresh
        fused = LocationServices.getFusedLocationProviderClient(this);
        callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location l = result.getLastLocation();
                if (l == null || alertRef == null) return;
                Map<String, Object> update = new HashMap<>();
                update.put("lat", l.getLatitude());
                update.put("lng", l.getLongitude());
                update.put("accuracy", (double) l.getAccuracy());
                update.put("battery", batteryPercent());
                update.put("active", true);
                update.put("updatedAt", ServerValue.TIMESTAMP);
                alertRef.updateChildren(update);
            }
        };
        try {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_MS)
                    .setMinUpdateIntervalMillis(5_000L)
                    .build();
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Toast.makeText(this, "Location permission is needed for live sharing", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 3) never share forever
        handler.postDelayed(this::stopSelf, MAX_SHARE_MS);

        notifyState();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (fused != null && callback != null) {
            fused.removeLocationUpdates(callback);
        }
        if (started && uid != null) {
            cleanupNow(getSharedPreferences("resq", Context.MODE_PRIVATE), uid);
        }
        running = false;
        activeCode = "";
        notifyState();
        super.onDestroy();
    }

    private void showError(Exception e) {
        Log.e(TAG, "Database error", e);
        Toast.makeText(this, "Live sharing error: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }

    private int batteryPercent() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm == null) return -1;
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Live location sharing", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String code) {
        Intent stop = new Intent(this, LocationShareService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent safePi = PendingIntent.getService(this, 4,
                new Intent(this, LocationShareService.class).setAction(ACTION_SAFE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sharing your live location")
                .setContentText("Your contacts can follow you. Tap to open ResQ.")   // no tracking code here
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentIntent(openPi)
                .addAction(SosNotifier.stopAction(this, "I'M SAFE", safePi))
                .addAction(SosNotifier.stopAction(this, "STOP SHARING", stopPi))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // shown on the lock screen
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
