package com.example.resq;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One tap: start navigation to the nearest hospital.
 *
 *  1) If a saved hospital (shared list + the user's own) is close, navigate to the nearest one.
 *     Hospitals marked "24h emergency" come first when there are any.
 *  2) Otherwise (empty list, far away, no location, slow internet) Google Maps chooses the
 *     nearest hospital itself, so the button always does something useful.
 */
public final class NearestHospital {

    /** If the nearest saved hospital is further than this, the list does not cover the area. */
    private static final float MAX_SAVED_DISTANCE_M = 10_000f;
    /** Never keep a person waiting longer than this. */
    private static final long GIVE_UP_MS = 7_000L;

    private NearestHospital() { }

    @SuppressLint("MissingPermission")
    public static void go(final Activity act) {
        Toast.makeText(act, "Finding the nearest hospital...", Toast.LENGTH_SHORT).show();

        final AtomicBoolean done = new AtomicBoolean(false);
        // after 7 seconds, stop waiting and let Google Maps choose
        new Handler(Looper.getMainLooper()).postDelayed(() -> mapsChooses(act, done), GIVE_UP_MS);

        boolean hasLocation =
                ContextCompat.checkSelfPermission(act, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(act, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        if (!hasLocation) {
            mapsChooses(act, done);
            return;
        }

        final FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(act);
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnCompleteListener(t -> {
                    Location now = t.isSuccessful() ? t.getResult() : null;
                    if (now != null) {
                        loadAndChoose(act, done, now);
                    } else {
                        fused.getLastLocation().addOnCompleteListener(t2 -> {
                            Location last = t2.isSuccessful() ? t2.getResult() : null;
                            if (last != null) loadAndChoose(act, done, last);
                            else mapsChooses(act, done);
                        });
                    }
                });
    }

    /** Reads both hospital lists (works from the offline cache too), then chooses. */
    private static void loadAndChoose(final Activity act, final AtomicBoolean done, final Location loc) {
        final List<Hospital> all = new ArrayList<>();
        final int[] pending = {2};
        final Runnable oneDone = () -> {
            pending[0]--;
            if (pending[0] == 0) choose(act, done, loc, all);
        };

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("hospitals").limit(300).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot d : snap.getDocuments()) all.add(Hospital.fromSnapshot(d, false));
                    oneDone.run();
                })
                .addOnFailureListener(e -> oneDone.run());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            oneDone.run();
        } else {
            db.collection("users").document(user.getUid()).collection("myHospitals").limit(100).get()
                    .addOnSuccessListener(snap -> {
                        for (DocumentSnapshot d : snap.getDocuments()) all.add(Hospital.fromSnapshot(d, true));
                        oneDone.run();
                    })
                    .addOnFailureListener(e -> oneDone.run());
        }
    }

    private static void choose(Activity act, AtomicBoolean done, Location loc, List<Hospital> all) {
        // hospitals with 24h emergency come first, if there are any
        boolean anyEmergency = false;
        for (Hospital h : all) {
            if (h.hasCoords() && h.emergency) anyEmergency = true;
        }
        Hospital best = null;
        float bestDist = Float.MAX_VALUE;
        for (Hospital h : all) {
            if (!h.hasCoords() || h.name.isEmpty()) continue;
            if (anyEmergency && !h.emergency) continue;
            float[] r = new float[1];
            Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), h.lat, h.lng, r);
            if (r[0] < bestDist) {
                bestDist = r[0];
                best = h;
            }
        }

        if (!done.compareAndSet(false, true)) return;          // already handled (timeout)
        if (act.isFinishing() || act.isDestroyed()) return;

        if (best == null || bestDist > MAX_SAVED_DISTANCE_M) {
            Toast.makeText(act, "No saved hospital nearby. Google Maps will choose one.",
                    Toast.LENGTH_SHORT).show();
            openMapsNearest(act);
            return;
        }
        String dist = bestDist < 1000
                ? Math.round(bestDist) + " m"
                : String.format(Locale.US, "%.1f km", bestDist / 1000f);
        startNavigation(act, best);
    }

    /** Fallback used when the saved list cannot be used in time. */
    private static void mapsChooses(Activity act, AtomicBoolean done) {
        if (!done.compareAndSet(false, true)) return;
        if (act.isFinishing() || act.isDestroyed()) return;
        openMapsNearest(act);
    }

    private static void startNavigation(Activity act, Hospital h) {
        try {
            Intent nav = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(String.format(Locale.US, "google.navigation:q=%f,%f", h.lat, h.lng)));
            nav.setPackage("com.google.android.apps.maps");
            act.startActivity(nav);
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                        "geo:%f,%f?q=%f,%f(%s)", h.lat, h.lng, h.lat, h.lng, Uri.encode(h.name)))));
            } catch (Exception e2) {
                Toast.makeText(act, "No maps app found", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Google Maps picks the nearest hospital itself (no saved list needed). */
    private static void openMapsNearest(Activity act) {
        try {
            Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=hospital"));
            nav.setPackage("com.google.android.apps.maps");
            act.startActivity(nav);
            return;
        } catch (Exception ignored) {
            // Google Maps is not installed: try any maps app
        }
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=hospital")));
            return;
        } catch (Exception ignored) {
            // no maps app: open the website
        }
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/hospital")));
        } catch (Exception e) {
            Toast.makeText(act, "No maps app or browser found", Toast.LENGTH_SHORT).show();
        }
    }
}
