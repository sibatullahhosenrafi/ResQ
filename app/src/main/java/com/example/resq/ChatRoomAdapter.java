package com.example.resq;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.resq.databinding.ItemChatRoomBinding;

import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter: one row per chat room, tap to open, button to leave. */
public class ChatRoomAdapter extends RecyclerView.Adapter<ChatRoomAdapter.Holder> {

    public interface Listener {
        void onOpen(ChatRoom room);

        void onLeave(ChatRoom room);

        void onMute(ChatRoom room);
    }

    private final List<ChatRoom> items = new ArrayList<>();
    private final Listener listener;
    private final java.util.Map<String, String> bloodStatus = new java.util.HashMap<>();

    public ChatRoomAdapter(Listener listener) {
        this.listener = listener;
    }

    /** roomId -> pending / accepted / declined, for the blood-donor chats. */
    public void setBloodStatus(java.util.Map<String, String> status) {
        bloodStatus.clear();
        bloodStatus.putAll(status);
        notifyDataSetChanged();
    }

    public void setItems(List<ChatRoom> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemChatRoomBinding b = ItemChatRoomBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatRoom r = items.get(position);
        h.b.tvRoomName.setText(r.name.isEmpty() ? "(no name)" : r.name);
        String sub;
        if (BloodChat.isBloodRoom(r.id)) {          // a blood-donor chat has no join code
            String st = bloodStatus.get(r.id);
            if ("pending".equals(st)) sub = "Waiting for the donor to accept";
            else if ("declined".equals(st)) sub = "Request declined";
            else sub = "Blood chat";
        } else {
            sub = "Code: " + r.id;
        }
        h.b.tvRoomCode.setText(sub + (r.isMuted() ? "  \u00B7  Muted" : ""));
        h.b.btnMute.setImageResource(r.isMuted() ? R.drawable.ic_bell_off : R.drawable.ic_bell);
        h.b.btnMute.setContentDescription(r.isMuted() ? "Unmute notifications" : "Mute notifications");
        h.b.btnMute.setOnClickListener(v -> listener.onMute(r));
        h.b.getRoot().setOnClickListener(v -> listener.onOpen(r));
        h.b.btnLeave.setOnClickListener(v -> listener.onLeave(r));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemChatRoomBinding b;

        Holder(ItemChatRoomBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
