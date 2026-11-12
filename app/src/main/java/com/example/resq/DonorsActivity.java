package com.example.resq;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.resq.databinding.ActivityDonorsBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Blood Donors: search donors by blood group (+ optional city) and call them.
 * Firestore query on the "donors" collection (equality filters only, so no index is needed).
 */
public class DonorsActivity extends AppCompatActivity {

    private ActivityDonorsBinding binding;
    private DonorAdapter adapter;
    private String myUid = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDonorsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_donors, true);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {          // safety: not logged in
            finish();
            return;
        }
        myUid = user.getUid();

        binding.actvBlood.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, Donor.BLOOD_GROUPS));

        adapter = new DonorAdapter(this::callDonor);
        adapter.setChatListener(this::askChatRequest);
        // 1 column on phones, 2 or more on wide screens (see values-w720dp/responsive.xml)
        binding.rvDonors.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.list_columns)));
        binding.rvDonors.setAdapter(adapter);

        binding.btnRegister.setOnClickListener(v ->
                startActivity(new Intent(this, DonorRegisterActivity.class)));
        binding.btnSearch.setOnClickListener(v -> search());

        // Rotation: put the search results back instead of losing them
        if (savedInstanceState != null) restoreResults(savedInstanceState);
    }

    private static final String S_DONORS = "state_donors";
    private static final String S_RESULTS_TEXT = "state_results_text";
    private static final String S_EMPTY_TEXT = "state_empty_text";
    private static final String S_EMPTY_VISIBLE = "state_empty_visible";

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle out) {
        super.onSaveInstanceState(out);
        // Donor is a plain class, so each donor is stored as 5 strings in one list
        ArrayList<String> flat = new ArrayList<>();
        for (Donor d : adapter.getItems()) {
            flat.add(d.uid);
            flat.add(d.name);
            flat.add(d.phone);
            flat.add(d.bloodGroup);
            flat.add(d.city);
        }
        out.putStringArrayList(S_DONORS, flat);
        out.putString(S_RESULTS_TEXT, binding.tvResults.getText().toString());
        out.putString(S_EMPTY_TEXT, binding.tvEmpty.getText().toString());
        out.putBoolean(S_EMPTY_VISIBLE, binding.tvEmpty.getVisibility() == View.VISIBLE);
    }

    private void restoreResults(Bundle in) {
        ArrayList<String> flat = in.getStringArrayList(S_DONORS);
        List<Donor> list = new ArrayList<>();
        if (flat != null) {
            for (int i = 0; i + 4 < flat.size(); i += 5) {
                list.add(new Donor(flat.get(i), flat.get(i + 1), flat.get(i + 2),
                        flat.get(i + 3), flat.get(i + 4)));
            }
        }
        adapter.setItems(list);
        String results = in.getString(S_RESULTS_TEXT);
        if (results != null) binding.tvResults.setText(results);
        String empty = in.getString(S_EMPTY_TEXT);
        if (empty != null) binding.tvEmpty.setText(empty);
        binding.tvEmpty.setVisibility(in.getBoolean(S_EMPTY_VISIBLE, true) ? View.VISIBLE : View.GONE);
    }

    private void search() {
        String blood = binding.actvBlood.getText().toString().trim();
        String city = binding.etCity.getText().toString().trim();

        binding.tilBlood.setError(null);
        if (blood.isEmpty()) {
            binding.tilBlood.setError("Choose the blood group you need");
            return;
        }
        if (!NetUtil.isOnline(this)) {
            Toast.makeText(this, "Searching donors needs an internet connection",
                    Toast.LENGTH_LONG).show();
            return;
        }

        hideKeyboard();
        binding.progress.setVisibility(View.VISIBLE);
        binding.tvEmpty.setVisibility(View.GONE);

        Query q = FirebaseFirestore.getInstance().collection("donors")
                .whereEqualTo("bloodGroup", blood)
                .whereEqualTo("available", true);
        if (!city.isEmpty()) {
            q = q.whereEqualTo("cityLower", city.toLowerCase(Locale.ROOT));
        }

        q.limit(50).get()
                .addOnSuccessListener(snap -> {
                    binding.progress.setVisibility(View.GONE);
                    List<Donor> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        if (d.getId().equals(myUid)) continue;     // don't list myself
                        Donor donor = Donor.fromSnapshot(d);
                        if (!donor.phone.isEmpty()) list.add(donor);
                    }
                    adapter.setItems(list);
                    binding.tvResults.setText("Donors found: " + list.size());

                    if (list.isEmpty()) {
                        String msg;
                        if (snap.size() > 0) {
                            msg = "The only matching donor is you. Other people will see your entry.";
                        } else {
                            msg = "No available " + blood + " donors found"
                                    + (city.isEmpty() ? "." : " in " + city + ". Try leaving the city empty.")
                                    + "\n(Donors who switched availability off are not shown.)";
                        }
                        binding.tvEmpty.setText(msg);
                        binding.tvEmpty.setVisibility(View.VISIBLE);
                    }
                    // make sure the results are on screen
                    binding.scroll.post(() ->
                            binding.scroll.smoothScrollTo(0, binding.tvResults.getTop()));
                })
                .addOnFailureListener(e -> {
                    binding.progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Search failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    /** Chat with an available donor. The donor has to accept before the chat starts. */
    private void askChatRequest(Donor d) {
        if (!NetUtil.isOnline(this)) {
            Toast.makeText(this, "Starting a chat needs an internet connection", Toast.LENGTH_LONG).show();
            return;
        }
        EditText et = new EditText(this);
        et.setText("Hi, I need " + d.bloodGroup + " blood urgently. Can you help?");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        et.setMinLines(2);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("Chat with " + (d.name.isEmpty() ? "this donor" : d.name))
                .setMessage("The donor has to accept your request before the chat starts.")
                .setView(box)
                .setPositiveButton("Send request", (x, y) -> {
                    String text = et.getText().toString().trim();
                    if (text.isEmpty()) return;
                    BloodChat.request(this, d, text, new BloodChat.Listener() {
                        @Override
                        public void onOpen(String roomId, String roomName) {
                            Intent i = new Intent(DonorsActivity.this, ChatActivity.class);
                            i.putExtra(ChatActivity.EXTRA_ROOM_ID, roomId);
                            i.putExtra(ChatActivity.EXTRA_ROOM_NAME, roomName);
                            startActivity(i);
                        }

                        @Override
                        public void onMessage(String msg) {
                            Toast.makeText(DonorsActivity.this, msg, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void callDonor(Donor d) {
        try {
            // ACTION_DIAL only opens the dialer, so no CALL_PHONE permission is needed
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", d.phone, null)));
        } catch (Exception e) {
            Toast.makeText(this, "No phone app found", Toast.LENGTH_SHORT).show();
        }
    }
}
