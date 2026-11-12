package com.example.resq;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.resq.databinding.ItemChatMessageBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Chat bubbles: my messages on the right (red), other people's on the left (pink). */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {

    public interface OnLocationClick {
        void onLocationClick(ChatMessage message);
    }

    private final List<ChatMessage> items = new ArrayList<>();
    private final List<ChatMember> others = new ArrayList<>();   // room members except me
    private final String myUid;
    private final OnLocationClick locationClick;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());

    public ChatAdapter(String myUid, OnLocationClick locationClick) {
        this.myUid = myUid;
        this.locationClick = locationClick;
    }

    /** Members of the room (used for "Sent / Seen by"). I am left out of the list. */
    public void setMembers(List<ChatMember> all) {
        others.clear();
        for (ChatMember m : all) {
            if (!m.uid.equals(myUid)) others.add(m);
        }
        notifyDataSetChanged();
    }

    /** Thumbnail of a room member, "" if unknown (then a letter placeholder is shown). */
    private String photoOf(String uid) {
        for (ChatMember o : others) {
            if (o.uid.equals(uid)) return o.photo;
        }
        return "";
    }

    public void setItems(List<ChatMessage> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemChatMessageBinding b = ItemChatMessageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatMessage m = items.get(position);
        boolean mine = m.senderUid != null && m.senderUid.equals(myUid);
        float d = h.b.getRoot().getResources().getDisplayMetrics().density;

        // the row = [avatar][bubble]; it is pushed to the right (mine) or left (others)
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.b.row.getLayoutParams();
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.setMarginStart(mine ? (int) (56 * d) : 0);
        lp.setMarginEnd(mine ? 0 : (int) (40 * d));
        h.b.row.setLayoutParams(lp);

        // profile picture of the sender (not shown for my own messages)
        h.b.ivAvatar.setVisibility(mine ? View.GONE : View.VISIBLE);
        if (!mine) {
            AvatarUtil.show(h.b.ivAvatar, photoOf(m.senderUid), m.senderName);
        }
        h.b.bubble.setBackgroundResource(mine ? R.drawable.bg_bubble_mine : R.drawable.bg_bubble_other);

        int textColor = h.b.getRoot().getResources().getColor(
                mine ? R.color.white : R.color.text_primary, null);
        int subColor = h.b.getRoot().getResources().getColor(
                mine ? R.color.on_brand_soft : R.color.text_secondary, null);

        // other people's names are shown above the message
        h.b.tvSender.setVisibility(mine ? View.GONE : View.VISIBLE);
        h.b.tvSender.setText(m.senderName == null ? "" : m.senderName);

        if (m.hasLocation()) {
            h.b.tvText.setText("Shared a location - tap to open in Maps");
            h.b.tvText.setPaintFlags(h.b.tvText.getPaintFlags()
                    | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            h.b.bubble.setOnClickListener(v -> locationClick.onLocationClick(m));
        } else {
            h.b.tvText.setText(m.text);
            h.b.tvText.setPaintFlags(h.b.tvText.getPaintFlags()
                    & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            h.b.bubble.setOnClickListener(null);
            h.b.bubble.setClickable(false);
        }
        // long-press: copy. An SOS message copies just its tracking code.
        h.b.bubble.setOnLongClickListener(v -> {
            android.content.Context c = v.getContext();
            if (m.hasLocation()) {
                CodeClipboard.copy(c, "Location", String.format(java.util.Locale.US,
                        "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", m.lat, m.lng),
                        "Location link copied");
                return true;
            }
            String code = CodeClipboard.findCode(m.text);
            if (code != null && m.text != null && m.text.contains("Track live")) {
                CodeClipboard.copy(c, "ResQ tracking code", code, "Tracking code copied: " + code);
            } else {
                CodeClipboard.copy(c, "Message", m.text, "Message copied");
            }
            return true;
        });
        bindStatus(h, m, mine, subColor);
        h.b.tvText.setTextColor(textColor);
        h.b.tvText.setLinkTextColor(textColor);
        h.b.tvTime.setTextColor(subColor);
        h.b.tvTime.setText(timeFormat.format(new Date(m.createdAt)));
    }

    // ---------------------------------------------------------- sent / seen

    /** Under my own messages: "Sending...", "Sent" or "Seen by Rahim, Karim". Tap it for details. */
    private void bindStatus(Holder h, ChatMessage m, boolean mine, int color) {
        if (!mine) {
            h.b.tvStatus.setVisibility(View.GONE);
            h.b.tvStatus.setOnClickListener(null);
            return;
        }
        h.b.tvStatus.setVisibility(View.VISIBLE);
        h.b.tvStatus.setTextColor(color);

        if (m.pending) {
            h.b.tvStatus.setText("\u23F1 Sending...");
            h.b.tvStatus.setOnClickListener(null);
            h.b.tvStatus.setClickable(false);
            return;
        }
        List<String> seen = new ArrayList<>();
        for (ChatMember o : others) {
            if (o.hasSeen(m.createdAt)) seen.add(o.name);
        }
        String text;
        if (seen.isEmpty()) {
            text = "\u2713 Sent";
        } else if (seen.size() == others.size() && others.size() > 2) {
            text = "\u2713\u2713 Seen by everyone";
        } else if (seen.size() <= 2) {
            text = "\u2713\u2713 Seen by " + join(seen);
        } else {
            text = "\u2713\u2713 Seen by " + join(seen.subList(0, 2)) + " +" + (seen.size() - 2);
        }
        h.b.tvStatus.setText(text);
        h.b.tvStatus.setOnClickListener(v -> showInfo(v.getContext(), m));
    }

    /** "Message info": who has seen it and who has not. */
    private void showInfo(android.content.Context ctx, ChatMessage m) {
        List<String> seen = new ArrayList<>();
        List<String> notSeen = new ArrayList<>();
        for (ChatMember o : others) {
            if (o.hasSeen(m.createdAt)) seen.add(o.name);
            else notSeen.add(o.name);
        }
        StringBuilder sb = new StringBuilder();
        if (others.isEmpty()) {
            sb.append("Nobody else is in this room yet.");
        } else {
            sb.append("Seen by:\n");
            if (seen.isEmpty()) sb.append("  nobody yet\n");
            for (String n : seen) sb.append("  \u2713\u2713 ").append(n).append("\n");
            sb.append("\nNot seen yet:\n");
            if (notSeen.isEmpty()) sb.append("  everybody has seen it\n");
            for (String n : notSeen) sb.append("  \u2713 ").append(n).append("\n");
        }
        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Message info")
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null)
                .show();
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemChatMessageBinding b;

        Holder(ItemChatMessageBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
