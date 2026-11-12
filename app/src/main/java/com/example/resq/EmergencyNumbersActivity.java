package com.example.resq;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Emergency helplines: names[] + numbers[] -> EmergencyNumberAdapter -> custom row -> ListView.
 * Tapping a row opens the dialer with that number.
 */
public class EmergencyNumbersActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_numbers);
        NavHelper.setup(this, R.id.nav_home, false);
        setTitle("Emergency Numbers");

        final String[] services = {
                "National Emergency",
                "Fire Service",
                "Health Call Center",
                "Women & Children Helpline",
                "Government Helpline"
        };

        // numbers[position] belongs to services[position]
        final String[] numbers = {
                "999",
                "16163",
                "16263",
                "109",
                "333"
        };

        final String[] names = services;
        ListView listCustom = findViewById(R.id.listCustom);
        EmergencyNumberAdapter customAdapter =
                new EmergencyNumberAdapter(this, names, numbers);
        listCustom.setAdapter(customAdapter);

        // position -> numbers[position] -> open the dialer (implicit intent)
        listCustom.setOnItemClickListener((parent, view, position, id) ->
                startActivity(new Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:" + numbers[position]))));
    }
}
