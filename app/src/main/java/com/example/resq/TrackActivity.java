package com.example.resq;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.resq.databinding.ActivityTrackBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.Locale;

/**
 * Track a friend: enter the code from their SOS SMS and watch their location update live.
 * Realtime Database: sos_codes/{code} -> uid, then sos_alerts/{uid} (live listener).
 */
public class TrackActivity extends AppCompatActivity {

    private static final String KEY_CODE = "state_code";
    private static final long STALE_MS = 90_000L;     // no update for 90 s = maybe offline

    private ActivityTrackBinding binding;
    private DatabaseReference alertRef;
    private ValueEventListener listener;
    private String trackedUid = null;

    // last values received
    private boolean hasData = false;
    private boolean ended = false;
    private long updatedAt = 0;
    private double lat = Double.NaN;
    private double lng = Double.NaN;
    private String who = "";
    private String phone = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTrackBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_circle, false);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {   // safety: not logged in
            finish();
            return;
        }

        binding.btnStart.setOnClickListener(v -> startTracking());
        binding.btnStopTrack.setOnClickListener(v -> stopTracking());
        binding.btnPaste.setOnClickListener(v -> {
            String code = CodeClipboard.readCode(this);
            if (code == null) {
                Toast.makeText(this, "No tracking code copied yet. Copy it from the SOS message first.",
                        Toast.LENGTH_LONG).show();
            } else {
                binding.etCode.setText(code);
                startTracking();            // paste and start in one tap
            }
        });
        binding.btnMaps.setOnClickListener(v -> openMaps());
        binding.btnCallFriend.setOnClickListener(v -> callFriend());

        // after screen rotation: continue with the same code
        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(KEY_CODE);
            if (saved != null && !saved.isEmpty()) {
                binding.etCode.setText(saved);
                startTracking();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (trackedUid != null) {
            outState.putString(KEY_CODE, binding.etCode.getText().toString().trim());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (trackedUid != null) attachListener();
    }

    @Override
    protected void onStop() {
        super.onStop();
        detachListener();
    }

    // ---------- steps ----------
    private void startTracking() {
        String code = binding.etCode.getText().toString().trim().toUpperCase(Locale.ROOT);
        binding.tilCode.setError(null);
        if (!TrackCode.isValid(code)) {
            binding.tilCode.setError("Enter the " + TrackCode.LENGTH + "-character code from the SOS message");
            return;
        }
        if (!NetUtil.isOnline(this)) {
            Toast.makeText(this, "Tracking needs an internet connection", Toast.LENGTH_LONG).show();
            return;
        }
        hideKeyboard();
        binding.progress.setVisibility(View.VISIBLE);

        Rtdb.get().getReference("sos_codes").child(code).get()
                .addOnSuccessListener(snap -> {
                    binding.progress.setVisibility(View.GONE);
                    String uid = snap.getValue(String.class);
                    if (!snap.exists() || uid == null) {
                        binding.tilCode.setError("Code not found. The sender may have stopped sharing, logged out, or the 2-hour limit ended.");
                        return;
                    }
                    trackedUid = uid;
                    hasData = false;
                    ended = false;
                    binding.cardTrack.setVisibility(View.VISIBLE);
                    binding.tvStatus.setText("Connecting...");
                    attachListener();
                    handler.removeCallbacks(ticker);
                    handler.post(ticker);
                })
                .addOnFailureListener(e -> {
                    binding.progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Could not check the code: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void attachListener() {
        detachListener();
        alertRef = Rtdb.get().getReference("sos_alerts").child(trackedUid);
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) {
                    ended = true;
                    refreshStatus();
                    return;
                }
                ended = false;
                hasData = true;
                String n = s.child("name").getValue(String.class);
                String p = s.child("phone").getValue(String.class);
                who = n == null ? "" : n;
                phone = p == null ? "" : p;
                Double la = s.child("lat").getValue(Double.class);
                Double lo = s.child("lng").getValue(Double.class);
                lat = la == null ? Double.NaN : la;
                lng = lo == null ? Double.NaN : lo;
                Long up = s.child("updatedAt").getValue(Long.class);
                updatedAt = up == null ? 0 : up;
                Long bat = s.child("battery").getValue(Long.class);
                binding.tvBattery.setText(bat != null && bat >= 0 ? "Battery: " + bat + "%" : "");
                refreshStatus();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(TrackActivity.this, "Tracking error: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        };
        alertRef.addValueEventListener(listener);
    }

    private void detachListener() {
        if (alertRef != null && listener != null) {
            alertRef.removeEventListener(listener);
        }
        listener = null;
    }

    private void stopTracking() {
        detachListener();
        handler.removeCallbacks(ticker);
        trackedUid = null;
        hasData = false;
        binding.cardTrack.setVisibility(View.GONE);
    }

    /** Runs every second: updates the text and the "x seconds ago" part. */
    private void refreshStatus() {
        if (trackedUid == null) return;
        boolean canMap = hasData && !Double.isNaN(lat) && !Double.isNaN(lng) && !ended;
        binding.btnMaps.setEnabled(canMap);
        binding.btnCallFriend.setEnabled(hasData && !phone.isEmpty());

        if (ended) {
            binding.tvStatus.setText("Sharing has ended");
            binding.tvWho.setText(who);
            binding.tvCoords.setText("");
            binding.tvUpdated.setText("");
            return;
        }
        if (!hasData) {
            binding.tvStatus.setText("Connecting...");
            return;
        }

        binding.tvWho.setText((who.isEmpty() ? "Your contact" : who)
                + (phone.isEmpty() ? "" : "  \u2022  " + phone));

        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            binding.tvStatus.setText("Waiting for the first location...");
            binding.tvCoords.setText("");
            binding.tvUpdated.setText("");
            return;
        }

        long ageMs = Math.max(0, System.currentTimeMillis() - updatedAt);
        boolean stale = updatedAt > 0 && ageMs > STALE_MS;
        binding.tvStatus.setText(stale ? "No recent update (their phone may be offline)" : "LIVE");
        binding.tvCoords.setText(String.format(Locale.US, "%.5f, %.5f", lat, lng));
        binding.tvUpdated.setText(updatedAt > 0 ? "Updated " + ago(ageMs) : "");
    }

    private String ago(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + " s ago";
        long min = sec / 60;
        if (min < 60) return min + " min ago";
        return (min / 60) + " h ago";
    }

    private void openMaps() {
        if (Double.isNaN(lat) || Double.isNaN(lng)) return;
        Uri geo = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                lat, lng, lat, lng, Uri.encode(who.isEmpty() ? "SOS" : who)));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, geo));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    String.format(Locale.US, "https://maps.google.com/?q=%f,%f", lat, lng))));
        }
    }

    private void callFriend() {
        if (phone.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)));
        } catch (Exception e) {
            Toast.makeText(this, "No phone app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        detachListener();
    }
}
