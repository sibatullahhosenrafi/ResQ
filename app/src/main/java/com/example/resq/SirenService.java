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
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

public class SirenService extends Service {

    private static final String TAG = "ResQ_Siren";
    private static final String CHANNEL_ID = "sos_channel";
    public static final String ACTION_STOP = "com.example.resq.STOP_SIREN";

    // the alarm volume the user had before the siren made it louder
    private static final String PREF_PREV_VOLUME = "siren_prev_volume";

    private static volatile boolean running = false;

    private MediaPlayer player;          // plays res/raw/siren.wav (or the default alarm tone as fallback)

    // flashing camera light (torch)
    private static final long FLASH_INTERVAL_MS = 250;
    private final Handler flashHandler = new Handler(Looper.getMainLooper());
    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn = false;
    private boolean flashing = false;
    private final Runnable flashRunnable = new Runnable() {
        @Override
        public void run() {
            if (!flashing) return;
            setTorch(!torchOn);
            flashHandler.postDelayed(this, FLASH_INTERVAL_MS);
        }
    };

    public static boolean isRunning() {
        return running;
    }

    @SuppressLint("ForegroundServiceType")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. Handle quick cancel from the notification button
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 2. Set up the Notification Channel for Android 8.0+
        createNotificationChannel();

        // 3. Create a PendingIntent to stop the service directly from the notification
        Intent stopIntent = new Intent(this, SirenService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // 4. Create a PendingIntent to open MainActivity if they tap the notification body
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        // 5. Build the persistent Notification
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Siren is ON")
                .setContentText("Tap to open ResQ.")
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentIntent(mainPendingIntent)
                .addAction(SosNotifier.stopAction(this, "STOP SIREN", stopPendingIntent))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // shown on the lock screen
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true) // Cannot be swiped away
                .build();

        // 6. Upgrade standard Service to Foreground Service
        startForeground(1, notification);

        // 7. Siren sound (res/raw/siren.wav) + flashing light
        if (!running) {
            try {
                startSirenSound();
                raiseVolume();          // make the siren as loud as possible
                running = true;
                Log.d(TAG, "Siren started (siren.wav)");
            } catch (Exception e) {
                Log.e(TAG, "Siren sound failed, using the default alarm tone", e);
                releasePlayer();
                startFallbackTone();
            }
            startFlashing();
        }
        ResQWidget.refresh(this);       // widget turns red while the siren is ON

        // Tells Android to restart the service if it is killed for memory reasons
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "SOS Siren Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        stopFlashing();
        running = false;
        ResQWidget.refresh(this);       // widget goes back to normal
        restoreVolume(this);            // give the user's old volume back
        Log.d(TAG, "Siren stopped");
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }

    // ================= siren sound (res/raw/siren.wav) =================

    /**
     * Plays res/raw/siren.wav in a loop on the ALARM audio stream.
     * Because it uses USAGE_ALARM, the phone's volume buttons control the ALARM volume while the
     * siren is ringing (also from the lock screen / home screen), so the user can make it
     * louder or quieter at any time.
     */
    private void startSirenSound() throws Exception {
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.siren);
        try {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        } finally {
            afd.close();
        }
        player.setLooping(true);
        player.setVolume(1f, 1f);   // full player gain; loudness is then set by the ALARM volume
        player.prepare();
        player.start();
    }

    /** Old behaviour (the phone's default alarm tone), used only if siren.wav fails. */
    private void startFallbackTone() {
        try {
            Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarm == null) {
                alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            player = new MediaPlayer();
            player.setDataSource(this, alarm);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setLooping(true);
            player.prepare();
            raiseVolume();
            player.start();
            running = true;
            Log.d(TAG, "Siren started (fallback tone)");
        } catch (Exception e) {
            Toast.makeText(this, "Could not start siren: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            releasePlayer();
            stopSelf();
        }
    }

    // ================= flashing camera light =================

    /** Starts blinking the camera flash (torch). Does nothing on phones without a flash. */
    private void startFlashing() {
        try {
            if (!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)) {
                return;
            }
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) return;
            torchCameraId = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (hasFlash != null && hasFlash && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchCameraId = id;
                    break;
                }
            }
            if (torchCameraId == null) return;
            flashing = true;
            flashHandler.removeCallbacks(flashRunnable);
            flashHandler.post(flashRunnable);
        } catch (Exception e) {
            Log.e(TAG, "Could not start the flashing light", e);
        }
    }

    /** Stops blinking and makes sure the light is switched off. */
    private void stopFlashing() {
        flashing = false;
        flashHandler.removeCallbacks(flashRunnable);
        if (torchCameraId != null) setTorch(false);
    }

    private void setTorch(boolean on) {
        try {
            if (cameraManager != null && torchCameraId != null) {
                cameraManager.setTorchMode(torchCameraId, on);
                torchOn = on;
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Could not switch the torch", e);
        }
    }

    /** Sets the alarm volume to maximum (and remembers the old volume). */
    private void raiseVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            // never blast full volume into headphones: it can hurt the ears
            if (headphonesConnected(am)) return;
            SharedPreferences prefs = getSharedPreferences("resq", Context.MODE_PRIVATE);
            if (!prefs.contains(PREF_PREV_VOLUME)) {   // keep the ORIGINAL volume if the service restarts
                prefs.edit().putInt(PREF_PREV_VOLUME, am.getStreamVolume(AudioManager.STREAM_ALARM)).apply();
            }
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception e) {
            Log.e(TAG, "Could not raise the volume", e);
        }
    }

    /** Puts the alarm volume back to what the user had. Safe to call at any time. */
    public static void restoreVolume(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("resq", Context.MODE_PRIVATE);
            if (!prefs.contains(PREF_PREV_VOLUME)) return;
            int previous = prefs.getInt(PREF_PREV_VOLUME, -1);
            prefs.edit().remove(PREF_PREV_VOLUME).apply();
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && previous >= 0) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not restore the volume", e);
        }
    }

    private boolean headphonesConnected(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true;
            }
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}