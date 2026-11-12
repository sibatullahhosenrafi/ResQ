package com.example.resq;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.resq.databinding.ActivityHospitalsBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nearby hospitals: reads the "hospitals" collection from Firestore, measures the distance
 * from the phone's location, sorts the nearest first, and gives Call / Navigate buttons.
 */
public class HospitalsActivity extends AppCompatActivity {

    private ActivityHospitalsBinding binding;
    private HospitalAdapter adapter;
    private FusedLocationProviderClient fused;
    private Location myLoc = null;

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean ok = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (ok) {
                    findLocationThenLoad();
                } else {
                    Toast.makeText(this, "Without location the list cannot be sorted by distance",
                            Toast.LENGTH_LONG).show();
                    loadHospitals();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHospitalsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_hospitals, true);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {   // safety: not logged in
            finish();
            return;
        }
        fused = LocationServices.getFusedLocationProviderClient(this);

        adapter = new HospitalAdapter(new HospitalAdapter.Listener() {
            @Override
            public void onCall(Hospital h) {
                dial(h.phone);
            }

            @Override
            public void onNavigate(Hospital h) {
                navigate(h);
            }

            @Override
            public void onDelete(Hospital h) {
                confirmDelete(h);
            }
        });
        // 1 column on phones, 2 or more on wide screens (see values-w720dp/responsive.xml)
        binding.rvHospitals.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.list_columns)));
        binding.rvHospitals.setAdapter(adapter);

        binding.btnCall999.setOnClickListener(v -> dial("999"));
        binding.btnMapsMore.setOnClickListener(v -> searchInMaps());
        binding.btnRefresh.setOnClickListener(v -> start());
        binding.btnAddHospital.setOnClickListener(v ->
                startActivity(new Intent(this, AddHospitalActivity.class)));

        start();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void start() {
        if (hasLocationPermission()) {
            findLocationThenLoad();
        } else {
            permLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    /** Gets the phone location (waits at most 6 seconds), then loads the hospitals. */
    @SuppressLint("MissingPermission")
    private void findLocationThenLoad() {
        binding.progress.setVisibility(View.VISIBLE);
        binding.tvStatus.setText("Finding your location...");

        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> lastKnown(done);
        handler.postDelayed(timeout, 6000);

        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    handler.removeCallbacks(timeout);
                    if (loc != null) locationReady(done, loc);
                    else lastKnown(done);
                })
                .addOnFailureListener(e -> {
                    handler.removeCallbacks(timeout);
                    lastKnown(done);
                });
    }

    @SuppressLint("MissingPermission")
    private void lastKnown(AtomicBoolean done) {
        if (done.get()) return;
        fused.getLastLocation().addOnCompleteListener(t ->
                locationReady(done, t.isSuccessful() ? t.getResult() : null));
    }

    private void locationReady(AtomicBoolean done, Location loc) {
        if (!done.compareAndSet(false, true)) return;
        if (isFinishing() || isDestroyed()) return;
        myLoc = loc;
        loadHospitals();
    }

    /** Back from the "Add hospital" screen: show the new hospital. */
    @Override
    protected void onRestart() {
        super.onRestart();
        if (adapter != null) loadHospitals();
    }

    /** Loads the shared hospitals AND the user's own hospitals, then shows them together. */
    private void loadHospitals() {
        binding.progress.setVisibility(View.VISIBLE);
        binding.tvStatus.setText("Loading hospitals...");
        binding.tvEmpty.setVisibility(View.GONE);

        final List<Hospital> all = new ArrayList<>();
        final String[] error = {null};
        final int[] pending = {2};
        final Runnable oneDone = () -> {
            pending[0]--;
            if (pending[0] == 0) showHospitals(all, error[0]);
        };

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // 1) the shared list (added in the Firebase Console)
        db.collection("hospitals").limit(300).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        all.add(Hospital.fromSnapshot(d, false));
                    }
                    oneDone.run();
                })
                .addOnFailureListener(e -> {
                    error[0] = e.getMessage();
                    oneDone.run();
                });

        // 2) the user's own hospitals (added from Google Maps)
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            oneDone.run();
        } else {
            db.collection("users").document(user.getUid()).collection("myHospitals").limit(100).get()
                    .addOnSuccessListener(snap -> {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            all.add(Hospital.fromSnapshot(d, true));
                        }
                        oneDone.run();
                    })
                    .addOnFailureListener(e -> oneDone.run());
        }
    }

    private void showHospitals(List<Hospital> list, String error) {
        binding.progress.setVisibility(View.GONE);

        List<Hospital> shown = new ArrayList<>();
        for (Hospital h : list) {
            if (h.name.isEmpty()) continue;
            if (myLoc != null && h.hasCoords()) {
                float[] r = new float[1];
                Location.distanceBetween(myLoc.getLatitude(), myLoc.getLongitude(), h.lat, h.lng, r);
                h.distanceM = r[0];
            }
            shown.add(h);
        }
        // nearest first; hospitals without a distance go to the end (A to Z)
        Collections.sort(shown, (a, b) -> {
            boolean ka = a.distanceM >= 0, kb = b.distanceM >= 0;
            if (ka && kb) return Float.compare(a.distanceM, b.distanceM);
            if (ka) return -1;
            if (kb) return 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        adapter.setItems(shown);

        if (shown.isEmpty()) {
            if (error != null) {
                binding.tvStatus.setText("Could not load hospitals");
                binding.tvEmpty.setText(error.contains("PERMISSION_DENIED")
                        ? "The database rules do not allow reading hospitals yet.\nPublish the 'hospitals' rule in Firebase, then tap Refresh."
                        : "Check your internet connection and try Refresh.\n(" + error + ")");
            } else {
                binding.tvStatus.setText("No hospitals in the list yet");
                binding.tvEmpty.setText("No hospitals yet.\nTap \"Add a hospital\" to add one from Google Maps,"
                        + " or \"Find more in Google Maps\".");
            }
            binding.tvEmpty.setVisibility(View.VISIBLE);
        } else {
            String head = (myLoc != null)
                    ? "Hospitals near you (nearest first): "
                    : "Hospitals (location unavailable): ";
            binding.tvStatus.setText(head + shown.size());
            if (error != null) {
                Toast.makeText(this, "The shared hospital list could not be loaded; showing your own.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void confirmDelete(Hospital h) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this hospital?")
                .setMessage(h.name + " will be removed from your list.")
                .setPositiveButton("Delete", (d, w) -> {
                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    if (user == null) return;
                    FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                            .collection("myHospitals").document(h.id).delete()
                            .addOnSuccessListener(v -> loadHospitals())
                            .addOnFailureListener(e -> Toast.makeText(this,
                                    "Could not delete: " + e.getMessage(), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- buttons ----------
    private void dial(String number) {
        try {
            // ACTION_DIAL only opens the dialer, so no CALL_PHONE permission is needed
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)));
        } catch (Exception e) {
            Toast.makeText(this, "No phone app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void navigate(Hospital h) {
        if (!h.hasCoords()) return;
        try {
            // turn-by-turn navigation in Google Maps
            Intent nav = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(String.format(Locale.US, "google.navigation:q=%f,%f", h.lat, h.lng)));
            nav.setPackage("com.google.android.apps.maps");
            startActivity(nav);
        } catch (Exception e) {
            try {
                // any other map app
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                        "geo:%f,%f?q=%f,%f(%s)", h.lat, h.lng, h.lat, h.lng, Uri.encode(h.name)))));
            } catch (Exception e2) {
                Toast.makeText(this, "No maps app found", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Always-available backup: let Google Maps search for hospitals around the user. */
    private void searchInMaps() {
        String geo = (myLoc != null)
                ? String.format(Locale.US, "geo:%f,%f?q=hospital", myLoc.getLatitude(), myLoc.getLongitude())
                : "geo:0,0?q=hospital";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(geo)));
        } catch (Exception e) {
            Toast.makeText(this, "No maps app found", Toast.LENGTH_SHORT).show();
        }
    }
}
