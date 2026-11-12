package com.example.resq;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.resq.databinding.ItemFirstAidBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FirstAidAdapter extends RecyclerView.Adapter<FirstAidAdapter.VH> {

    static class VH extends RecyclerView.ViewHolder {
        final ItemFirstAidBinding b;

        VH(ItemFirstAidBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }

    private final List<FirstAidTopic> items;
    private final Set<Integer> expanded = new HashSet<>();

    public FirstAidAdapter(List<FirstAidTopic> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemFirstAidBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        FirstAidTopic topic = items.get(position);
        holder.b.tvTitle.setText(topic.getTitle());
        holder.b.tvSteps.setText(topic.getSteps());
        holder.b.tvSteps.setVisibility(expanded.contains(position) ? View.VISIBLE : View.GONE);

        holder.b.getRoot().setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (!expanded.remove(pos)) expanded.add(pos);
            notifyItemChanged(pos);
        });
    }
}
