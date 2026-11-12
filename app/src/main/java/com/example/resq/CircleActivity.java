package com.example.resq;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.resq.databinding.ActivityCircleBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;

/**
 * My Circle: add, list and delete emergency contacts.
 * Cloud: Firestore users/{uid}/contacts. A snapshot listener keeps the list live
 * and a local copy (SharedPreferences) is updated each time, so SOS also works offline.
 */
public class CircleActivity extends AppCompatActivity {

    private ActivityCircleBinding binding;
    private FirebaseUser user;
    private CollectionReference contactsRef;
    private SharedPreferences prefs;
    private CircleAdapter adapter;
    private ListenerRegistration listener;
    private final List<CircleContact> current = new ArrayList<>();

    // The "Add to My Circle" screen sends the chosen name and number back here
    private final ActivityResultLauncher<Intent> addLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    saveContact(
                            result.getData().getStringExtra(EmergencyContactActivity.EXTRA_NAME),
                            result.getData().getStringExtra(EmergencyContactActivity.EXTRA_NUMBER));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCircleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_circle, true);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {          // safety: not logged in
            finish();
            return;
        }
        prefs = getSharedPreferences("resq", Context.MODE_PRIVATE);
        contactsRef = FirebaseFirestore.getInstance()
                .collection("users").document(user.getUid()).collection("contacts");

        adapter = new CircleAdapter(this::confirmDelete);
        // 1 column on phones, 2 or more on wide screens (see values-w720dp/responsive.xml)
        binding.rvContacts.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.list_columns)));
        binding.rvContacts.setAdapter(adapter);

        // show the local copy immediately (works offline)
        current.addAll(CircleStore.load(prefs, user.getUid()));
        render();

        binding.btnAdd.setOnClickListener(v -> openAddScreen());
        binding.btnTrack.setOnClickListener(v ->
                startActivity(new Intent(this, TrackActivity.class)));
        binding.btnChat.setOnClickListener(v ->
                startActivity(new Intent(this, ChatRoomsActivity.class)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (user == null) return;
        // live updates from Firestore (also fires at once for our own writes)
        listener = contactsRef.orderBy("createdAt").addSnapshotListener((snap, error) -> {
            if (error != null) {
                Toast.makeText(this, "Could not load contacts: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (snap == null) return;
            // an empty answer from a cold cache must not wipe the list
            if (snap.getMetadata().isFromCache() && snap.isEmpty()
                    && !snap.getMetadata().hasPendingWrites()) {
                return;
            }
            List<CircleContact> fresh = CircleStore.fromSnapshot(snap);
            current.clear();
            current.addAll(fresh);
            CircleStore.save(prefs, user.getUid(), fresh);
            render();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listener != null) {
            listener.remove();
            listener = null;
        }
    }

    private void render() {
        adapter.setItems(current);
        binding.tvCount.setText("Contacts (" + current.size() + "/" + CircleStore.MAX + ")");
        binding.tvEmpty.setVisibility(current.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openAddScreen() {
        if (current.size() >= CircleStore.MAX) {
            Toast.makeText(this, "You can add up to " + CircleStore.MAX + " contacts",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> existing = new ArrayList<>();
        for (CircleContact c : current) existing.add(CircleStore.normalize(c.phone));

        Intent i = new Intent(this, EmergencyContactActivity.class);
        i.putStringArrayListExtra(EmergencyContactActivity.EXTRA_EXISTING, existing);
        addLauncher.launch(i);
    }

    /** Called with the name and number that came back from the Add screen. */
    private void saveContact(String name, String rawPhone) {
        name = name == null ? "" : name.trim();
        String phone = CircleStore.normalize(rawPhone);

        if (name.isEmpty() || !CircleStore.isValidPhone(phone)) {
            Toast.makeText(this, "That contact was not valid", Toast.LENGTH_SHORT).show();
            return;
        }
        if (current.size() >= CircleStore.MAX) {
            Toast.makeText(this, "You can add up to " + CircleStore.MAX + " contacts",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        for (CircleContact c : current) {
            if (CircleStore.normalize(c.phone).equals(phone)) {
                Toast.makeText(this, "This number is already in your circle",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // Firestore queues this write and syncs it when internet returns
        contactsRef.add(CircleContact.toMap(name, phone))
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Cloud save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void confirmDelete(CircleContact c) {
        new AlertDialog.Builder(this)
                .setTitle("Remove contact?")
                .setMessage(c.name + " will no longer receive your SOS message.")
                .setPositiveButton("Remove", (d, w) -> contactsRef.document(c.id).delete()
                        .addOnFailureListener(e -> Toast.makeText(this,
                                "Could not delete: " + e.getMessage(), Toast.LENGTH_LONG).show()))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
