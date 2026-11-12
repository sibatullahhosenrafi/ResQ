package com.example.resq;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.resq.databinding.ActivityEmergencyContactBinding;

import java.util.ArrayList;
import java.util.Locale;

/**
 * "Add to My Circle" screen. Type a name and number, OR tap a person from your phone's contacts
 * (read with a Content Provider). The name and number are sent back with setResult(...).
 */
public class EmergencyContactActivity extends AppCompatActivity {

    public static final String EXTRA_NUMBER = "extra_number";
    public static final String EXTRA_NAME = "extra_name";
    /** Numbers already in My Circle (so this screen can reject duplicates). */
    public static final String EXTRA_EXISTING = "extra_existing";

    private ActivityEmergencyContactBinding binding;
    private ArrayList<String> existing = new ArrayList<>();

    // every phone contact, and the ones that match the search box right now
    private final ArrayList<String> allNames = new ArrayList<>();
    private final ArrayList<String> allPhones = new ArrayList<>();
    private final ArrayList<String> shownNames = new ArrayList<>();
    private final ArrayList<String> shownPhones = new ArrayList<>();
    private final ArrayList<String> shownRows = new ArrayList<>();
    private ArrayAdapter<String> contactsAdapter;

    // Request permissions dynamically if not already granted
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    loadContacts();
                } else {
                    Toast.makeText(this, "Contacts permission is needed to pick from your contacts. "
                            + "You can still type a number.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEmergencyContactBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setTitle("Add to My Circle");

        ArrayList<String> list = getIntent().getStringArrayListExtra(EXTRA_EXISTING);
        if (list != null) existing = list;

        // Check for READ_CONTACTS permission before querying the Content Provider
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadContacts();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS);
        }

        binding.btnSaveContact.setOnClickListener(v -> save());

        // Search: filter the contact list while the user types
        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        binding.etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
            return false;
        });
    }

    /** Shows only the contacts whose name or number contains what is typed in the search box. */
    private void applyFilter() {
        if (contactsAdapter == null) return;
        String q = binding.etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        String qDigits = q.replaceAll("[^0-9+]", "");

        shownNames.clear();
        shownPhones.clear();
        shownRows.clear();
        for (int i = 0; i < allNames.size(); i++) {
            String name = allNames.get(i);
            String phone = allPhones.get(i);
            boolean match = q.isEmpty()
                    || name.toLowerCase(Locale.ROOT).contains(q)
                    || phone.toLowerCase(Locale.ROOT).contains(q)
                    || (!qDigits.isEmpty() && CircleStore.normalize(phone).contains(qDigits));
            if (match) {
                shownNames.add(name);
                shownPhones.add(phone);
                shownRows.add(name + "\n" + phone);
            }
        }
        contactsAdapter.notifyDataSetChanged();
    }

    private void save() {
        String name = binding.etName.getText().toString().trim();
        String number = CircleStore.normalize(binding.etNumber.getText().toString());

        binding.etName.setError(null);
        binding.etNumber.setError(null);

        if (name.isEmpty()) {
            binding.etName.setError("Enter a name");
            return;
        }
        if (!CircleStore.isValidPhone(number)) {
            binding.etNumber.setError("Enter a valid phone number");
            return;
        }
        if (existing.contains(number)) {
            binding.etNumber.setError("This number is already in your circle");
            return;
        }

        // send the result back to My Circle
        Intent data = new Intent();
        data.putExtra(EXTRA_NAME, name);
        data.putExtra(EXTRA_NUMBER, number);
        setResult(RESULT_OK, data);
        finish();
    }

    // Queries the ContactsContract Content Provider
    private void loadContacts() {
        allNames.clear();
        allPhones.clear();

        // Content Provider query parameters
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String number = cursor.getString(1);
                if (name == null) name = "";
                if (number == null) continue;
                allNames.add(name);
                allPhones.add(number);
            }
            cursor.close();
        }

        contactsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, shownRows);
        binding.lvContacts.setAdapter(contactsAdapter);
        binding.lvContacts.setEmptyView(binding.tvNoMatch);
        applyFilter();     // also applies any text already in the search box (e.g. after rotation)

        // When a user taps a contact, fill in the name and the phone number
        // (positions refer to the filtered list that is on screen)
        binding.lvContacts.setOnItemClickListener((parent, view, position, id) -> {
            binding.etName.setText(shownNames.get(position));
            binding.etNumber.setText(shownPhones.get(position));
        });
    }
}
