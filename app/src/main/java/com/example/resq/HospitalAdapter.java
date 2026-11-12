package com.example.resq;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.resq.databinding.ItemHospitalBinding;

import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter for the hospital list (Call and Navigate buttons on each row). */
public class HospitalAdapter extends RecyclerView.Adapter<HospitalAdapter.Holder> {

    public interface Listener {
        void onCall(Hospital hospital);

        void onNavigate(Hospital hospital);

        void onDelete(Hospital hospital);
    }

    private final List<Hospital> items = new ArrayList<>();
    private final Listener listener;

    public HospitalAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<Hospital> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemHospitalBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Hospital item = items.get(position);
        h.b.tvName.setText(item.name.isEmpty() ? "(no name)" : item.name);
        h.b.tvAddress.setText(item.address);
        h.b.tvAddress.setVisibility(item.address.isEmpty() ? View.GONE : View.VISIBLE);
        String extra = item.distanceText();
        if (item.emergency) extra += "  \u2022  24h emergency";
        if (item.mine) extra += "  \u2022  My hospital";
        h.b.tvDistance.setText(extra);
        h.b.btnDeleteHospital.setVisibility(item.mine ? View.VISIBLE : View.GONE);
        h.b.btnDeleteHospital.setOnClickListener(v -> listener.onDelete(item));

        h.b.btnCall.setEnabled(!item.phone.isEmpty());
        h.b.btnNavigate.setEnabled(item.hasCoords());
        h.b.btnCall.setOnClickListener(v -> listener.onCall(item));
        h.b.btnNavigate.setOnClickListener(v -> listener.onNavigate(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemHospitalBinding b;

        Holder(ItemHospitalBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
