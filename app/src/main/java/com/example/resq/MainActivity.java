package com.example.resq;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.location.Location;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.os.Looper;
import android.os.Handler;
import android.os.Build;
import android.view.View;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.resq.databinding.ActivityMainBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private FusedLocationProviderClient fused;
    private SharedPreferences prefs;

    private static final String TAG = "ResQ_Lifecycle";
    public static final String PREF_SHARE_ON_SOS = "share_on_sos";
    /** Profile switch: also post the SOS into my chat rooms (default: on). */
    public static final String PREF_SOS_TO_CHAT = "sos_to_chat";

    // saved when the screen rotates
    private static final String S_CHIP = "state_chip_id";
    private static final String S_LOCATION = "state_location_text";
    private static final String S_ASK_OPEN = "state_ask_sos_open";
    private static final String PREF_NOTIF_ASKED = "notif_permission_asked";
    private static final String S_ASK_TITLE = "state_ask_sos_title";

    /** Optional title for the SOS question (the home-screen widget sends its own). */
    public static final String EXTRA_ASK_TITLE = "com.example.resq.EXTRA_ASK_TITLE";
    private String askSosTitle = "Low battery";

    /** The "Send an SOS message to your circle now?" dialog (null when it is not on screen). */
    private AlertDialog askSosDialog;

    /** Android 13+: asks once, when the app opens, so the system alerts can show. */
    private final ActivityResultLauncher<String> notifLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });


    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    (Map<String, Boolean> result) -> {
                        boolean sms = Boolean.TRUE.equals(result.get(Manifest.permission.SEND_SMS));
                        boolean fine = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                        boolean coarse = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                        if (sms && (fine || coarse)) {
                            updateLocationUI();
                            sendSos();
                        } else {
                            toast("Location and SMS permissions are needed for SOS");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = getSharedPreferences("resq", MODE_PRIVATE);
        fused = LocationServices.getFusedLocationProviderClient(this);

        // Rotation: bring back what Android does not restore by itself
        boolean locationRestored = false;
        if (savedInstanceState != null) {
            int chip = savedInstanceState.getInt(S_CHIP, View.NO_ID);
            if (chip != View.NO_ID) binding.chipGroupType.check(chip);
            String loc = savedInstanceState.getString(S_LOCATION);
            if (loc != null && !loc.isEmpty()) {
                binding.tvLocation.setText(loc);
                locationRestored = true;
            }
        }

        // Fetch location for the UI header if permissions exist (not again right after a rotation)
        if (hasPermissions() && !locationRestored) {
            updateLocationUI();
        }

        askNotificationPermissionOnce();

        binding.btnSiren.setOnClickListener(v -> {
            startService(new Intent(this, SirenService.class));
        });

        binding.btnStopSiren.setOnClickListener(v -> {
            stopService(new Intent(this, SirenService.class));
        });

        binding.btnSos.setOnClickListener(v -> {
            playSosWave();
            startSosFlow();
        });

        binding.btnCall.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:999"))));

        // One tap: navigate to the nearest hospital (saved list first, Google Maps as backup)
        binding.btnHospitals.setOnClickListener(v -> NearestHospital.go(this));

        binding.btnNumbers.setOnClickListener(v -> startActivity(new Intent(this, EmergencyNumbersActivity.class)));

        binding.btnFirstAid.setOnClickListener(v -> {
            Intent i = new Intent(this, FirstAidActivity.class);
            i.putExtra(FirstAidActivity.EXTRA_SOURCE, "ResQ Home");
            startActivity(i);
        });

        syncMedicalProfile();
        syncCircle();
        LocationShareService.cleanupLeftovers(prefs);
        if (!SirenService.isRunning()) SirenService.restoreVolume(this);   // after a crash

        binding.btnCopyCode.setOnClickListener(v -> {
            String c = LocationShareService.getActiveCode();
            if (c != null && !c.isEmpty()) {
                CodeClipboard.copy(this, "ResQ tracking code", c, "Tracking code copied: " + c);
            }
        });
        binding.btnStopShare.setOnClickListener(v -> confirmStopSharing());

        // Bottom navigation: the same bar is on every main screen
        NavHelper.setup(this, R.id.nav_home, true);

        // The low-battery question: from the notification button, or still open before a rotation
        boolean wasOpen = savedInstanceState != null && savedInstanceState.getBoolean(S_ASK_OPEN, false);
        if (savedInstanceState != null && savedInstanceState.getString(S_ASK_TITLE) != null) {
            askSosTitle = savedInstanceState.getString(S_ASK_TITLE);
        }
        if (wasOpen || getIntent().getBooleanExtra(SystemAlerts.EXTRA_ASK_SOS, false)) {
            if (getIntent().getStringExtra(EXTRA_ASK_TITLE) != null) {
                askSosTitle = getIntent().getStringExtra(EXTRA_ASK_TITLE);
                getIntent().removeExtra(EXTRA_ASK_TITLE);
            }
            // consume the extra so a rotation (which re-delivers this intent) never asks again
            getIntent().removeExtra(SystemAlerts.EXTRA_ASK_SOS);
            showAskSosDialog();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // MainActivity was already open and the "SEND SOS..." button was tapped
        if (intent.getBooleanExtra(SystemAlerts.EXTRA_ASK_SOS, false)) {
            intent.removeExtra(SystemAlerts.EXTRA_ASK_SOS);
            String t = intent.getStringExtra(EXTRA_ASK_TITLE);
            askSosTitle = t != null ? t : "Low battery";
            intent.removeExtra(EXTRA_ASK_TITLE);
            showAskSosDialog();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(S_CHIP, binding.chipGroupType.getCheckedChipId());
        out.putString(S_LOCATION, binding.tvLocation.getText().toString());
        out.putBoolean(S_ASK_OPEN, askSosDialog != null && askSosDialog.isShowing());
        out.putString(S_ASK_TITLE, askSosTitle);
    }

    @Override
    protected void onDestroy() {
        if (askSosDialog != null) {
            askSosDialog.dismiss();      // avoids a leaked window when the screen rotates
            askSosDialog = null;
        }
        super.onDestroy();
    }

    /** Android 13+: ask for the notification permission one time only. */
    private void askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        if (prefs.getBoolean(PREF_NOTIF_ASKED, false)) return;
        prefs.edit().putBoolean(PREF_NOTIF_ASKED, true).apply();
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /** Asks first. Nothing is sent unless the user confirms. */
    private void showAskSosDialog() {
        if (askSosDialog != null && askSosDialog.isShowing()) return;
        SystemAlerts.clearLowBattery(this);
        askSosDialog = new AlertDialog.Builder(this)
                .setTitle(askSosTitle)
                .setMessage("Send an SOS message to your circle now?")
                .setPositiveButton("Send SOS", (d, w) -> startSosFlow())
                .setNegativeButton("Cancel", null)
                .create();
        askSosDialog.show();
    }

    /** Same path as the big SOS button: check permissions, then send. */
    private void startSosFlow() {
        if (hasPermissions()) {
            sendSos();
        } else {
            permLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.POST_NOTIFICATIONS
            });
        }
    }

    /** Pull the latest emergency circle from Firestore into the local copy (used by SOS). */
    private void syncCircle() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        CircleStore.syncFromCloud(prefs, u.getUid());
    }

    /** Pull the latest medical info from Firestore into the local copy (used by the SOS SMS). */
    private void syncMedicalProfile() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        FirebaseFirestore.getInstance().collection("users").document(u.getUid()).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) MedicalProfile.fromSnapshot(doc).save(prefs);
                });
    }

    @SuppressLint("MissingPermission")
    private void updateLocationUI() {
        TextView tvLocation = findViewById(R.id.tvLocation);
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        try {
                            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                            List<Address> addresses = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                            if (addresses != null && !addresses.isEmpty()) {
                                String city = addresses.get(0).getLocality();
                                String country = addresses.get(0).getCountryName();
                                tvLocation.setText("Current location\n" + city + ", " + country);
                            } else {
                                tvLocation.setText("Current location\nCoordinates found");
                            }
                        } catch (Exception e) {
                            tvLocation.setText("Current location\n" + loc.getLatitude() + ", " + loc.getLongitude());
                        }
                    } else {
                        tvLocation.setText("Current location\nUnavailable");
                    }
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        ChatNotifyService.start(this);       // chat notifications (no-op if logged out)
        LocationShareService.setStateListener(this::updateShareCard);
        updateShareCard();
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocationShareService.setStateListener(null);
    }

    /**
     * Stop was pressed. The sharing only ever starts from an SOS, so ask whether the emergency is over:
     * "Yes, I'm safe" also tells My Circle and the chat rooms (a wrong "safe" message could be dangerous,
     * so it is never sent without this question).
     */
    private void confirmStopSharing() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        boolean hasContacts = u != null && !CircleStore.load(prefs, u.getUid()).isEmpty();
        boolean chatOn = prefs.getBoolean(PREF_SOS_TO_CHAT, true);
        if (!hasContacts && !chatOn) {            // nobody to tell
            stopSharingOnly();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Are you safe now?")
                .setMessage("Tell your contacts and chat rooms that you are safe? "
                        + "They get an SMS and a chat message, and live location sharing stops.")
                .setPositiveButton("Yes, I'm safe", (d, w) ->
                        startService(new Intent(this, LocationShareService.class)
                                .setAction(LocationShareService.ACTION_SAFE)))
                .setNegativeButton("Stop sharing only", (d, w) -> stopSharingOnly())
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void stopSharingOnly() {
        startService(new Intent(this, LocationShareService.class)
                .setAction(LocationShareService.ACTION_STOP));
    }

    /** Shows the "Sharing live location" card while the service is running. */
    private void updateShareCard() {
        boolean on = LocationShareService.isRunning();
        binding.cardSharing.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            binding.tvShareInfo.setText("Sharing your live location.\nTracking code: "
                    + LocationShareService.getActiveCode());
        }
    }

    private boolean hasPermissions() {
        boolean sms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return sms && (fine || coarse);
    }

    /** Reads the emergency type chip the user selected ("" if none). */
    /** Wave that spreads out from the round SOS button when it is tapped. */
    private void playSosWave() {
        final View[] waves = {binding.sosWave1, binding.sosWave2};
        for (int i = 0; i < waves.length; i++) {
            final View w = waves[i];
            w.animate().cancel();
            w.setScaleX(1f);
            w.setScaleY(1f);
            w.setAlpha(0.8f);
            w.animate()
                    .scaleX(1.5f).scaleY(1.5f).alpha(0f)
                    .setStartDelay(i * 220L)
                    .setDuration(900)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private String getSelectedType() {
        int id = binding.chipGroupType.getCheckedChipId();
        if (id == R.id.chipMedical) return "Medical";
        if (id == R.id.chipFire) return "Fire";
        if (id == R.id.chipAccident) return "Accident";
        if (id == R.id.chipDisaster) return "Disaster";
        return "";
    }

    /** Battery level 0-100, or -1 if the phone does not report it. */
    private int getBatteryPercent() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm == null) return -1;
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return (pct >= 0 && pct <= 100) ? pct : -1;
    }

    private String nowText() {
        return new SimpleDateFormat("h:mm a, dd MMM", Locale.ENGLISH).format(new Date());
    }

    /** Everyone who should get the SOS: the whole My Circle list. */
    private List<CircleContact> getSosTargets() {
        List<CircleContact> list = new ArrayList<>();
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) list.addAll(CircleStore.load(prefs, u.getUid()));
        return list;
    }

    /** Shown when SOS is pressed but nobody is in My Circle yet. */
    private void askToAddContact() {
        new AlertDialog.Builder(this)
                .setTitle("Your circle is empty")
                .setMessage("SOS needs at least one emergency contact. Add someone you trust in My Circle.")
                .setPositiveButton("Open My Circle", (d, w) ->
                        startActivity(new Intent(this, CircleActivity.class)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void sendSos() {
        final List<CircleContact> targets = getSosTargets();
        if (targets.isEmpty()) {
            askToAddContact();
            return;
        }
        warnIfNoSim();

        // The SOS must ALWAYS go out, even if the location is slow or unavailable.
        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> lastKnownThenSend(done, targets);
        handler.postDelayed(timeout, 8000);      // wait at most 8 seconds for a fresh location

        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    handler.removeCallbacks(timeout);
                    if (loc != null) sendOnce(done, targets, loc);
                    else lastKnownThenSend(done, targets);
                })
                .addOnFailureListener(e -> {
                    handler.removeCallbacks(timeout);
                    lastKnownThenSend(done, targets);
                });
    }

    /** No fresh location: use the last known one, or send without a location. */
    @SuppressLint("MissingPermission")
    private void lastKnownThenSend(AtomicBoolean done, List<CircleContact> targets) {
        if (done.get()) return;
        fused.getLastLocation().addOnCompleteListener(t ->
                sendOnce(done, targets, t.isSuccessful() ? t.getResult() : null));
    }

    /** Makes sure the SOS is sent only one time, whichever location answer arrives first. */
    private void sendOnce(AtomicBoolean done, List<CircleContact> targets, Location loc) {
        if (!done.compareAndSet(false, true)) return;
        proceedWithSos(targets, loc);
    }

    /** loc can be null: the message then says "Location unavailable". */
    private void proceedWithSos(List<CircleContact> targets, Location loc) {
        String where = (loc != null) ? "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude() : "Location unavailable";
        MedicalProfile med = MedicalProfile.load(prefs);
        FirebaseUser cu = FirebaseAuth.getInstance().getCurrentUser();
        if (med.name.isEmpty() && cu != null && cu.getDisplayName() != null) {
            med.name = cu.getDisplayName();
        }
        String gps = (loc != null)
                ? String.format(Locale.US, "%.5f, %.5f", loc.getLatitude(), loc.getLongitude())
                : "";
        // reuse the code if we are already sharing, otherwise make a new one
        boolean alreadySharing = LocationShareService.isRunning();
        // the user can switch live sharing off in Profile; if it is already running we keep the same code
        boolean shareLive = alreadySharing || prefs.getBoolean(PREF_SHARE_ON_SOS, true);
        String code = !shareLive ? "" : (alreadySharing ? LocationShareService.getActiveCode() : TrackCode.generate());
        String message = med.buildSosMessage(
                getSelectedType(), getBatteryPercent(), nowText(), gps, where, code);
        // ONE live progress notification for My Circle (SMS) and the chat rooms
        boolean chatOn = prefs.getBoolean(PREF_SOS_TO_CHAT, true);
        long sosId = SosNotifier.begin(this, targets.size(), chatOn);
        SmsSender.sendToAll(this, targets, message, sosId);

        // also post the SOS into every chat room I joined (no medical details, rooms can be shared)
        if (chatOn) {
            StringBuilder chat = new StringBuilder("SOS! I need help.");
            if (!getSelectedType().isEmpty()) chat.append("\nType: ").append(getSelectedType());
            if (!med.name.isEmpty()) chat.append("\nName: ").append(med.name);
            int bat = getBatteryPercent();
            if (bat >= 0) chat.append("\nBattery: ").append(bat).append("%");
            chat.append("\nTime: ").append(nowText());
            chat.append("\nLocation: ").append(where);
            if (!code.isEmpty()) chat.append("\nTrack live in ResQ: ").append(code);
            ChatSos.post(this, sosId, chat.toString());
        }

        // keep sharing the live location after the SMS is sent
        if (shareLive && !alreadySharing) {
            Intent share = new Intent(this, LocationShareService.class);
            share.putExtra(LocationShareService.EXTRA_CODE, code);
            share.putExtra(LocationShareService.EXTRA_NAME, med.name);
            share.putExtra(LocationShareService.EXTRA_PHONE, med.phone);
            if (loc != null) {
                share.putExtra(LocationShareService.EXTRA_LAT, loc.getLatitude());
                share.putExtra(LocationShareService.EXTRA_LNG, loc.getLongitude());
            }
            try {
                ContextCompat.startForegroundService(this, share);
            } catch (Exception e) {
                toast("SOS sent, but live sharing could not start: " + e.getMessage());
            }
        }
    }

    private void warnIfNoSim() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null || tm.getSimState() != TelephonyManager.SIM_STATE_READY) {
            toast("No SIM card is ready. The SOS SMS cannot be sent from this device.");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}