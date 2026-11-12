package com.example.resq;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.resq.databinding.ActivityProfileBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

/**
 * Profile + medical info.
 * Save  -> Cloud Firestore (users/{uid}) + local copy (SharedPreferences).
 * Load  -> local copy first (instant, works offline), then Firestore (latest cloud copy).
 */
public class ProfileActivity extends AppCompatActivity {

    private static final String[] BLOOD_GROUPS =
            {"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};

    private ActivityProfileBinding binding;
    private FirebaseUser user;
    private FirebaseFirestore db;
    private SharedPreferences prefs;
    private String photoBase64 = "";     // my current profile picture (Base64 JPEG), "" = none

    /** Gallery picker. GetContent needs NO storage permission. */
    private final ActivityResultLauncher<String> pickPhoto =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPhotoPicked);

    /** Camera: the photo is written to a temporary file in our cache (no CAMERA permission needed). */
    private Uri cameraUri;
    private java.io.File cameraFile;
    private final ActivityResultLauncher<Uri> takePhoto =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (ok && cameraUri != null) onPhotoPicked(cameraUri);
                if (cameraFile != null) cameraFile.delete();     // the temporary file is not needed any more
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_profile, true);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {          // safety: not logged in
            goToLogin();
            return;
        }
        db = FirebaseFirestore.getInstance();
        prefs = getSharedPreferences("resq", Context.MODE_PRIVATE);

        binding.tvEmail.setText(user.getEmail());
        binding.actvBlood.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, BLOOD_GROUPS));

        // profile picture: show the local copy at once, the cloud copy arrives in loadFromCloud()
        photoBase64 = AvatarUtil.loadLocal(prefs);
        showPhoto();
        binding.ivAvatar.setOnClickListener(v -> showPhotoDialog());
        binding.btnCameraBadge.setOnClickListener(v -> showPhotoDialog());
        binding.btnRemovePhoto.setOnClickListener(v -> removePhoto());

        // 1) show the local copy immediately
        MedicalProfile local = MedicalProfile.load(prefs);
        if (local.name.isEmpty() && user.getDisplayName() != null) {
            local.name = user.getDisplayName();
        }
        show(local);
        showPhoto();       // again: the letter placeholder needs the name

        // 2) then ask Firestore for the latest cloud copy
        loadFromCloud();

        // Demo buttons: show the same notifications the real system events show
        binding.btnTestBattery.setOnClickListener(v -> {
            int pct = SystemEventReceiver.batteryPercent(this);
            SystemAlerts.showLowBattery(this, pct >= 0 ? pct : 14);
        });

        binding.cardReadiness.setOnClickListener(v ->
                startActivity(new Intent(this, KpiActivity.class)));

        binding.btnSave.setOnClickListener(v -> saveProfile());
        binding.btnLogout.setOnClickListener(v -> confirmLogout());

        // The user decides whether SOS also starts live location sharing (default: yes)
        binding.switchSosChat.setChecked(prefs.getBoolean(MainActivity.PREF_SOS_TO_CHAT, true));
        binding.switchSosChat.setOnCheckedChangeListener((button, on) ->
                prefs.edit().putBoolean(MainActivity.PREF_SOS_TO_CHAT, on).apply());
        binding.switchShareLive.setChecked(prefs.getBoolean(MainActivity.PREF_SHARE_ON_SOS, true));
        binding.switchShareLive.setOnCheckedChangeListener((button, on) ->
                prefs.edit().putBoolean(MainActivity.PREF_SHARE_ON_SOS, on).apply());

        binding.switchLockStop.setChecked(prefs.getBoolean(SosNotifier.PREF_LOCK_STOP, false));
        binding.switchLockStop.setOnCheckedChangeListener((button, on) ->
                prefs.edit().putBoolean(SosNotifier.PREF_LOCK_STOP, on).apply());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (user == null || prefs == null) return;     // not logged in
        updateReadinessCard();
        Readiness.refreshCloud(prefs, user.getUid(), this::updateReadinessCard);
    }

    /** Shows "70% ready - 5 of 7 steps" on the card that opens the KPI screen. */
    private void updateReadinessCard() {
        if (isFinishing() || isDestroyed()) return;
        java.util.List<Readiness.Step> steps = Readiness.compute(prefs, user.getUid());
        int pct = Readiness.percent(steps);
        binding.tvReadiness.setText(pct + "% ready  \u2022  "
                + Readiness.doneCount(steps) + " of " + steps.size() + " steps");
        binding.progressReadiness.setProgressCompat(pct, true);
    }

    /**
     * Live sharing needs the user to be logged in (the database only accepts updates from the
     * owner), so logging out ends it. Ask first, so stopping is always the user's choice.
     */
    private void confirmLogout() {
        if (!LocationShareService.isRunning()) {
            logout();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Live location sharing is on")
                .setMessage("You are sharing your live location. Logging out will stop the sharing, "
                        + "and the tracking code will stop working.")
                .setPositiveButton("Stop sharing and log out", (d, w) -> logout())
                .setNegativeButton("Keep sharing", null)
                .show();
    }

    private void show(MedicalProfile m) {
        binding.etName.setText(m.name);
        binding.etPhone.setText(m.phone);
        binding.actvBlood.setText(m.bloodGroup, false);   // false = don't filter the list
    }

    private void loadFromCloud() {
        binding.progress.setVisibility(View.VISIBLE);
        db.collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    binding.progress.setVisibility(View.GONE);
                    if (doc.exists()) {
                        MedicalProfile cloud = MedicalProfile.fromSnapshot(doc);
                        cloud.save(prefs);       // refresh the local copy
                        show(cloud);
                        String p = doc.getString("photoBase64");
                        photoBase64 = p == null ? "" : p;
                        AvatarUtil.saveLocal(prefs, photoBase64);
                        showPhoto();
                    }
                })
                .addOnFailureListener(e -> binding.progress.setVisibility(View.GONE));
    }

    private void saveProfile() {
        MedicalProfile m = new MedicalProfile();
        m.name = binding.etName.getText().toString().trim();
        m.phone = binding.etPhone.getText().toString().trim();
        m.bloodGroup = binding.actvBlood.getText().toString().trim();

        binding.tilName.setError(null);
        if (m.name.isEmpty()) {
            binding.tilName.setError("Enter your name");
            return;
        }

        // Local copy first: the SOS SMS can use it right now, even offline
        m.save(prefs);

        // Cloud copy: Firestore queues this write and syncs it when internet returns
        db.collection("users").document(user.getUid())
                .set(m.toMap(user.getEmail()), SetOptions.merge())
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Cloud save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());

        // Keep the Firebase Auth display name in sync too
        user.updateProfile(new UserProfileChangeRequest.Builder()
                .setDisplayName(m.name).build());

        Toast.makeText(this, "Saved (syncs to the cloud when online)",
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ profile picture

    private void showPhoto() {
        AvatarUtil.show(binding.ivAvatar, photoBase64, binding.etName.getText().toString());
        binding.btnRemovePhoto.setVisibility(photoBase64.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** "Update Profile Picture": Take Photo / Choose from Gallery. */
    private void showPhotoDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_photo_source, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Update Profile Picture")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .create();
        content.findViewById(R.id.rowTake).setOnClickListener(v -> {
            dialog.dismiss();
            openCamera();
        });
        content.findViewById(R.id.rowGallery).setOnClickListener(v -> {
            dialog.dismiss();
            pickPhoto.launch("image/*");
        });
        dialog.show();
    }

    private void openCamera() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            cameraFile = new java.io.File(dir, "profile_" + System.currentTimeMillis() + ".jpg");
            cameraUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", cameraFile);
            takePhoto.launch(cameraUri);
        } catch (Exception e) {      // e.g. no camera app on this phone
            Toast.makeText(this, "Could not open the camera", Toast.LENGTH_LONG).show();
        }
    }

    /** The user picked a picture: shrink it, turn it into text, save it. */
    private void onPhotoPicked(Uri uri) {
        if (uri == null) return;      // picker was cancelled
        String b64 = AvatarUtil.encodeFromUri(this, uri, AvatarUtil.FULL_SIZE);
        if (b64 == null) {
            Toast.makeText(this, "Could not read that picture", Toast.LENGTH_LONG).show();
            return;
        }
        savePhoto(b64);
    }

    private void removePhoto() {
        savePhoto("");
    }

    /** Saves locally (instant) and to Firestore users/{uid}.photoBase64 (merge = other fields untouched). */
    private void savePhoto(String b64) {
        photoBase64 = b64;
        AvatarUtil.saveLocal(prefs, b64);
        showPhoto();

        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("photoBase64", b64);
        map.put("updatedAt", System.currentTimeMillis());
        db.collection("users").document(user.getUid())
                .set(map, SetOptions.merge())
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Cloud save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());

    }

    private void logout() {
        stopService(new Intent(this, SirenService.class));
        ChatNotifyService.stop(this);     // stop chat notifications for this user
        LocationShareService.cleanupNow(prefs, user.getUid());   // remove the live alert first
        stopService(new Intent(this, LocationShareService.class));
        AvatarUtil.clearLocal(prefs);     // ...or this user's profile picture
        MedicalProfile.clear(prefs);      // don't leave this user's medical info on the phone
        CircleStore.clear(prefs, user.getUid());   // ...or this user's circle contacts
        Readiness.clear(prefs, user.getUid());     // ...or the remembered preparedness steps
        FirebaseAuth.getInstance().signOut();
        goToLogin();
    }

    private void goToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
