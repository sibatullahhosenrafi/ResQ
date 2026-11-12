package com.example.resq;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.resq.databinding.ActivityDonorRegisterBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Register (or update / remove) the logged-in user as a blood donor.
 * Firestore document: donors/{uid}  (one donor entry per user).
 */
public class DonorRegisterActivity extends AppCompatActivity {

    private ActivityDonorRegisterBinding binding;
    private FirebaseUser user;
    private DocumentReference donorRef;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDonorRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_donors, false);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {          // safety: not logged in
            finish();
            return;
        }
        prefs = getSharedPreferences("resq", Context.MODE_PRIVATE);
        donorRef = FirebaseFirestore.getInstance().collection("donors").document(user.getUid());

        binding.actvBlood.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, Donor.BLOOD_GROUPS));

        // 1) start with the Profile info so the user does not type it twice
        MedicalProfile med = MedicalProfile.load(prefs);
        binding.etName.setText(med.name);
        binding.etPhone.setText(med.phone);
        binding.actvBlood.setText(med.bloodGroup, false);

        // 2) if the user is already a donor, show the saved donor details instead
        loadExisting();

        binding.btnSave.setOnClickListener(v -> save());
        binding.btnRemove.setOnClickListener(v -> confirmRemove());
    }

    private void loadExisting() {
        if (!NetUtil.isOnline(this)) return;
        binding.progress.setVisibility(View.VISIBLE);
        donorRef.get()
                .addOnSuccessListener(doc -> {
                    binding.progress.setVisibility(View.GONE);
                    if (doc.exists()) {
                        Donor d = Donor.fromSnapshot(doc);
                        binding.etName.setText(d.name);
                        binding.etPhone.setText(d.phone);
                        binding.actvBlood.setText(d.bloodGroup, false);
                        binding.etCity.setText(d.city);
                        Boolean available = doc.getBoolean("available");
                        binding.switchAvailable.setChecked(available == null || available);
                        binding.btnSave.setText("Update donor details");
                        binding.btnRemove.setVisibility(View.VISIBLE);
                    }
                })
                .addOnFailureListener(e -> binding.progress.setVisibility(View.GONE));
    }

    private void save() {
        String name = binding.etName.getText().toString().trim();
        String phone = CircleStore.normalize(binding.etPhone.getText().toString());
        String blood = binding.actvBlood.getText().toString().trim();
        String city = binding.etCity.getText().toString().trim();

        binding.tilName.setError(null);
        binding.tilPhone.setError(null);
        binding.tilBlood.setError(null);
        binding.tilCity.setError(null);

        if (name.isEmpty()) {
            binding.tilName.setError("Enter your name");
            return;
        }
        if (!CircleStore.isValidPhone(phone)) {
            binding.tilPhone.setError("Enter a valid phone number");
            return;
        }
        if (blood.isEmpty()) {
            binding.tilBlood.setError("Choose your blood group");
            return;
        }
        if (city.isEmpty()) {
            binding.tilCity.setError("Enter your city");
            return;
        }
        if (!NetUtil.isOnline(this)) {
            Toast.makeText(this, "Connect to the internet to register as a donor",
                    Toast.LENGTH_LONG).show();
            return;
        }

        binding.btnSave.setEnabled(false);
        binding.progress.setVisibility(View.VISIBLE);
        donorRef.set(Donor.toMap(user.getUid(), name, phone, blood, city,
                        binding.switchAvailable.isChecked()))
                .addOnSuccessListener(v -> {
                    ChatNotifyService.start(this);      // so blood-chat requests reach this donor
                    Toast.makeText(this, "You are listed as a blood donor. Thank you!",
                            Toast.LENGTH_LONG).show();
                    finish();
                })
                .addOnFailureListener(e -> {
                    binding.btnSave.setEnabled(true);
                    binding.progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Could not save: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle("Remove me from donors?")
                .setMessage("Your name and phone number will no longer be visible in donor searches.")
                .setPositiveButton("Remove", (d, w) -> remove())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void remove() {
        if (!NetUtil.isOnline(this)) {
            Toast.makeText(this, "Connect to the internet to remove your donor entry",
                    Toast.LENGTH_LONG).show();
            return;
        }
        donorRef.delete()
                .addOnSuccessListener(v -> {
                    Toast.makeText(this, "You were removed from the donor list",
                            Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Could not remove: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }
}
