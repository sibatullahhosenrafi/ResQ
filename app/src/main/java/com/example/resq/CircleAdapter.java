package com.example.resq;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.resq.databinding.ItemCircleContactBinding;

import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter: one row per emergency contact, with a delete button. */
public class CircleAdapter extends RecyclerView.Adapter<CircleAdapter.Holder> {

    public interface OnDeleteListener {
        void onDelete(CircleContact contact);
    }

    private final List<CircleContact> items = new ArrayList<>();
    private final OnDeleteListener listener;

    public CircleAdapter(OnDeleteListener listener) {
        this.listener = listener;
    }

    public void setItems(List<CircleContact> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCircleContactBinding b = ItemCircleContactBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        CircleContact c = items.get(position);
        h.b.tvName.setText(c.name.isEmpty() ? "(no name)" : c.name);
        h.b.tvPhone.setText(c.phone);
        h.b.btnDelete.setOnClickListener(v -> listener.onDelete(c));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemCircleContactBinding b;

        Holder(ItemCircleContactBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
