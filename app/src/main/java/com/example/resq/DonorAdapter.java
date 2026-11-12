package com.example.resq;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.resq.databinding.ItemDonorBinding;

import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter for the donor search results, with a Call button on each row. */
public class DonorAdapter extends RecyclerView.Adapter<DonorAdapter.Holder> {

    public interface OnCallListener {
        void onCall(Donor donor);
    }

    public interface OnChatListener {
        void onChat(Donor donor);
    }

    private final List<Donor> items = new ArrayList<>();
    private final OnCallListener listener;
    private OnChatListener chatListener;

    public void setChatListener(OnChatListener l) {
        this.chatListener = l;
    }

    public DonorAdapter(OnCallListener listener) {
        this.listener = listener;
    }

    public void setItems(List<Donor> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public List<Donor> getItems() {
        return new ArrayList<>(items);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemDonorBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Donor d = items.get(position);
        h.b.tvName.setText(d.name.isEmpty() ? "(no name)" : d.name);
        h.b.tvInfo.setText(d.bloodGroup + "  \u2022  " + (d.city.isEmpty() ? "City not given" : d.city));
        h.b.tvPhone.setText(d.phone);
        h.b.btnCall.setOnClickListener(v -> listener.onCall(d));
        h.b.btnChat.setOnClickListener(v -> {
            if (chatListener != null) chatListener.onChat(d);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemDonorBinding b;

        Holder(ItemDonorBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
