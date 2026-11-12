package com.example.resq;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Custom ListView adapter: DATA -> CUSTOM ADAPTER -> item_emergency_number.xml -> ListView.
 * getView() builds / fills ONE row for the given position.
 */
public class EmergencyNumberAdapter extends ArrayAdapter<String> {

    private final Context context;
    private final String[] names;
    private final String[] numbers;

    public EmergencyNumberAdapter(Context context, String[] names, String[] numbers) {
        super(context, R.layout.item_emergency_number, names);
        this.context = context;
        this.names = names;
        this.numbers = numbers;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            // XML design -> real View object (only when there is no old row to reuse)
            row = LayoutInflater.from(context)
                    .inflate(R.layout.item_emergency_number, parent, false);
        }

        // Search inside this row, not inside the Activity
        TextView txtName = row.findViewById(R.id.txtServiceName);
        TextView txtNumber = row.findViewById(R.id.txtServiceNumber);

        // position chooses the correct data
        txtName.setText(names[position]);
        txtNumber.setText(numbers[position]);
        return row;
    }
}
