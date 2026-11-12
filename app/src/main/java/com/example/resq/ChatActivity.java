package com.example.resq;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.resq.databinding.ActivityChatBinding;
import com.google.android.material.chip.Chip;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One chat room. Messages live in Firestore chatRooms/{code}/messages and appear live
 * on every phone in the room. "Send my location" posts a map link as a message.
 */
public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_ROOM_ID = "room_id";
    public static final String EXTRA_ROOM_NAME = "room_name";
    private static final int MAX_MESSAGES = 200;

    private ActivityChatBinding binding;
    private FirebaseUser user;
    private String roomId;
    private String roomName;
    private CollectionReference messagesRef;
    private ListenerRegistration listener;
    private ListenerRegistration roomListener;
    private ListenerRegistration membersListener;
    private ListenerRegistration statusListener;      // blood chats: pending / accepted / declined
    private com.google.firebase.firestore.DocumentReference myMemberRef;
    private long lastMarkedAt = 0L;
    private int lastCount = -1;
    private long mutedUntil = 0L;
    private ChatAdapter adapter;

    private final ActivityResultLauncher<String> locationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    sendLocation();
                } else {
                    Toast.makeText(this, "Location permission is needed to share your location",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        user = FirebaseAuth.getInstance().getCurrentUser();
        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        roomName = getIntent().getStringExtra(EXTRA_ROOM_NAME);
        if (user == null || roomId == null) {
            finish();
            return;
        }
        if (roomName == null) roomName = "";

        messagesRef = FirebaseFirestore.getInstance()
                .collection("chatRooms").document(roomId).collection("messages");
        myMemberRef = FirebaseFirestore.getInstance()
                .collection("chatRooms").document(roomId).collection("members").document(user.getUid());

        binding.tvRoomTitle.setText(roomName.isEmpty() ? "Chat" : roomName);
        binding.tvRoomCode.setText(BloodChat.isBloodRoom(roomId) ? "Blood chat" : "Code: " + roomId);
        if (BloodChat.isBloodRoom(roomId)) binding.btnShareCode.setVisibility(View.GONE);   // no code to share

        adapter = new ChatAdapter(user.getUid(), this::openMap);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(lm);
        binding.rvMessages.setAdapter(adapter);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnShareCode.setOnClickListener(v -> shareCode());
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnLocation.setOnClickListener(v -> sendLocation());
        binding.btnMute.setOnClickListener(v ->
                ChatMute.showDialog(this, user.getUid(), roomId, roomName, mutedUntil));

        setupQuickMessages();
    }

    // ------------------------------------------------------------- quick messages

    /** True for a moment after a chip was tapped, so a double tap does not send twice. */
    private boolean quickBusy = false;

    /**
     * Builds the row of quick-message chips from res/values/arrays.xml.
     * Tapping a chip sends its full message to the room at once.
     */
    private void setupQuickMessages() {
        final String[] labels = getResources().getStringArray(R.array.chat_quick_labels);
        final String[] messages = getResources().getStringArray(R.array.chat_quick_messages);
        int count = Math.min(labels.length, messages.length);
        float density = getResources().getDisplayMetrics().density;

        for (int i = 0; i < count; i++) {
            final String message = messages[i];

            Chip chip = new Chip(this);
            chip.setText(labels[i]);
            chip.setCheckable(false);
            chip.setChipBackgroundColorResource(R.color.pink_bg);
            chip.setChipStrokeColorResource(R.color.sos_orange_start);
            chip.setChipStrokeWidth(density);
            chip.setTextColor(ContextCompat.getColor(this, R.color.accent_text));
            chip.setOnClickListener(v -> sendQuick(message));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * density));
            binding.quickChips.addView(chip, lp);
        }
    }

    private void sendQuick(String message) {
        if (quickBusy) return;
        quickBusy = true;
        binding.getRoot().postDelayed(() -> quickBusy = false, 1200);
        post(message, null, null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (user == null || roomId == null) return;

        // I am looking at this room, so no notification for it; clear the old one
        ChatNotifyService.openRoomId = roomId;
        ChatNotifyService.clearRoom(this, roomId);
        ChatNotifyService.start(this);

        // my mute setting for this room (drives the bell icon)
        roomListener = FirebaseFirestore.getInstance()
                .collection("users").document(user.getUid())
                .collection("chatRooms").document(roomId)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) return;
                    Long m = snap.getLong("mutedUntil");
                    mutedUntil = m == null ? 0L : m;
                    boolean muted = ChatMute.isMuted(mutedUntil);
                    binding.btnMute.setImageResource(muted ? R.drawable.ic_bell_off : R.drawable.ic_bell);
                    binding.btnMute.setContentDescription(muted ? "Unmute notifications" : "Mute notifications");
                });

        // blood chat: show whether the donor has accepted yet
        if (BloodChat.isBloodRoom(roomId)) {
            statusListener = FirebaseFirestore.getInstance().collection("chatRooms").document(roomId)
                    .addSnapshotListener((snap, error) -> {
                        if (error != null || snap == null || !snap.exists()) return;
                        String st = snap.getString("status");
                        boolean iAsked = user.getUid().equals(snap.getString("ownerUid"));
                        if ("pending".equals(st) && iAsked) binding.tvRoomCode.setText("Waiting for the donor to accept");
                        else if ("declined".equals(st)) binding.tvRoomCode.setText("Request declined");
                        else binding.tvRoomCode.setText("Blood chat");
                    });
        }

        // who is in the room and when each person last had the chat open ("Seen by")
        membersListener = FirebaseFirestore.getInstance()
                .collection("chatRooms").document(roomId).collection("members")
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) return;
                    List<ChatMember> members = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Long read = d.getLong("lastReadAt");
                        members.add(new ChatMember(d.getId(), d.getString("name"),
                                read == null ? 0L : read, d.getString("photo")));
                    }
                    adapter.setMembers(members);
                });

        lastMarkedAt = 0L;
        lastCount = -1;       // scroll to the newest message each time the chat opens
        // MetadataChanges.INCLUDE: also tells us when a message changes from "Sending" to "Sent"
        listener = messagesRef.orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(MAX_MESSAGES)
                .addSnapshotListener(MetadataChanges.INCLUDE, (snap, error) -> {
                    if (error != null) {
                        Toast.makeText(this, "Could not load messages: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (snap == null) return;
                    List<ChatMessage> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Double lat = d.getDouble("lat");
                        Double lng = d.getDouble("lng");
                        Long at = d.getLong("createdAt");
                        list.add(new ChatMessage(
                                d.getId(),
                                d.getString("senderUid"),
                                d.getString("senderName"),
                                d.getString("text"),
                                lat == null ? Double.NaN : lat,
                                lng == null ? Double.NaN : lng,
                                at == null ? System.currentTimeMillis() : at,
                                d.getMetadata().hasPendingWrites()));
                    }
                    adapter.setItems(list);
                    binding.tvEmpty.setVisibility(list.isEmpty()
                            ? android.view.View.VISIBLE : android.view.View.GONE);
                    if (!list.isEmpty() && list.size() != lastCount) {
                        binding.rvMessages.scrollToPosition(list.size() - 1);
                    }
                    lastCount = list.size();
                    markSeenIfNeeded(list);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listener != null) {
            listener.remove();
            listener = null;
        }
        if (roomListener != null) {
            roomListener.remove();
            roomListener = null;
        }
        if (membersListener != null) {
            membersListener.remove();
            membersListener = null;
        }
        if (statusListener != null) {
            statusListener.remove();
            statusListener = null;
        }
        if (user != null && roomId != null) {
            if (roomId.equals(ChatNotifyService.openRoomId)) ChatNotifyService.openRoomId = null;
            ChatNotifyService.markRead(this, user.getUid(), roomId);   // what I saw is read
        }
    }

    // ------------------------------------------------------------- seen

    /**
     * Tells the room "I have seen everything up to now" (my member document: lastReadAt).
     * Others compare it with each message's time to show "Seen by ...".
     */
    private void markSeenIfNeeded(List<ChatMessage> list) {
        long newestOther = 0L;
        long newest = 0L;
        for (ChatMessage m : list) {
            if (m.createdAt > newest) newest = m.createdAt;
            if (!user.getUid().equals(m.senderUid) && m.createdAt > newestOther) newestOther = m.createdAt;
        }
        // first time after the chat opened, or a new message from somebody else
        if (lastMarkedAt != 0L && newestOther <= lastMarkedAt) return;
        long t = Math.max(System.currentTimeMillis(), newest);
        boolean first = lastMarkedAt == 0L;
        lastMarkedAt = t;
        if (first) {
            // first time in this chat session: also publish my small profile picture to the room
            String full = AvatarUtil.loadLocal(
                    getSharedPreferences("resq", android.content.Context.MODE_PRIVATE));
            String thumb = AvatarUtil.thumbFrom(full);      // "" if I have no picture
            myMemberRef.update("lastReadAt", t, "photo", thumb);
        } else {
            myMemberRef.update("lastReadAt", t);   // fails quietly if I already left the room
        }
    }

    // ------------------------------------------------------------- sending

    private void sendText() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        binding.etMessage.setText("");
        post(text, null, null);
    }

    private void sendLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        binding.btnLocation.setEnabled(false);
        Toast.makeText(this, "Getting your location...", Toast.LENGTH_SHORT).show();
        try {
            LocationServices.getFusedLocationProviderClient(this)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(loc -> {
                        binding.btnLocation.setEnabled(true);
                        if (loc == null) {
                            Toast.makeText(this, "Could not get your location. Turn on GPS and try again.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        post("My location", loc.getLatitude(), loc.getLongitude());
                    })
                    .addOnFailureListener(e -> {
                        binding.btnLocation.setEnabled(true);
                        Toast.makeText(this, "Could not get your location: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
        } catch (SecurityException e) {
            binding.btnLocation.setEnabled(true);
        }
    }

    /** Firestore queues the write, so the message shows at once and syncs when the internet is back. */
    private void post(String text, Double lat, Double lng) {
        messagesRef.add(ChatMessage.toMap(user.getUid(), displayName(), text, lat, lng))
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Message not sent: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------- helpers

    private void openMap(ChatMessage m) {
        Uri uri = Uri.parse(String.format(Locale.US,
                "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", m.lat, m.lng));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "No app can open the map", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareCode() {
        String msg = "Join my \"" + roomName + "\" chat room on ResQ.\n"
                + "Open ResQ > My Circle > Chat rooms > Join a room, and enter this code: " + roomId;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, msg);
        startActivity(Intent.createChooser(send, "Share room code"));
    }

    private String displayName() {
        String n = user.getDisplayName();
        if (n != null && !n.trim().isEmpty()) return n.trim();
        String email = user.getEmail();
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "Member";
    }
}
