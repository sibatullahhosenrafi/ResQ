package com.example.resq;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.resq.databinding.ActivityChatRoomsBinding;
import com.example.resq.databinding.ItemBloodRequestBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chat rooms: create a room (you get a 6-letter code), or join one with a code.
 * Firestore:
 *   chatRooms/{code}                    room (name, ownerUid)
 *   chatRooms/{code}/members/{uid}      who is in the room
 *   users/{uid}/chatRooms/{code}        this user's own list of rooms
 */
public class ChatRoomsActivity extends AppCompatActivity {

    private ActivityChatRoomsBinding binding;
    private FirebaseUser user;
    private FirebaseFirestore db;
    private CollectionReference myRoomsRef;
    private ChatRoomAdapter adapter;
    private ListenerRegistration listener;
    private ListenerRegistration bloodListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatRoomsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_circle, false);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        db = FirebaseFirestore.getInstance();
        myRoomsRef = db.collection("users").document(user.getUid()).collection("chatRooms");

        adapter = new ChatRoomAdapter(new ChatRoomAdapter.Listener() {
            @Override
            public void onOpen(ChatRoom room) {
                openChat(room.id, room.name);
            }

            @Override
            public void onLeave(ChatRoom room) {
                confirmLeave(room);
            }

            @Override
            public void onMute(ChatRoom room) {
                ChatMute.showDialog(ChatRoomsActivity.this, user.getUid(), room.id, room.name,
                        room.mutedUntil);
            }
        });
        // 1 column on phones, 2 or more on wide screens (see values-w720dp/responsive.xml)
        binding.rvRooms.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.list_columns)));
        binding.rvRooms.setAdapter(adapter);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnCreate.setOnClickListener(v -> askRoomName());
        binding.btnJoin.setOnClickListener(v -> askJoinCode());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (user == null) return;
        listener = myRoomsRef.orderBy("joinedAt").addSnapshotListener((snap, error) -> {
            if (error != null || snap == null) return;
            List<ChatRoom> rooms = new ArrayList<>();
            for (DocumentSnapshot d : snap.getDocuments()) {
                String name = d.getString("name");
                Long muted = d.getLong("mutedUntil");
                rooms.add(new ChatRoom(d.getId(), name == null ? "" : name,
                        muted == null ? 0L : muted));
            }
            adapter.setItems(rooms);
            if (!rooms.isEmpty()) {
                ChatNotifyService.start(this);
                askBackgroundOnce();
            }
            binding.tvEmpty.setVisibility(rooms.isEmpty() ? View.VISIBLE : View.GONE);
        });

        // blood-donor chats: requests waiting for me, and the status of the ones I asked for
        bloodListener = db.collection("chatRooms").whereArrayContains("participants", user.getUid())
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) return;
                    Map<String, String> status = new HashMap<>();
                    binding.requestsList.removeAllViews();
                    int waiting = 0;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        String st = d.getString("status");
                        if (st == null) st = "pending";
                        status.put(d.getId(), st);
                        String owner = d.getString("ownerUid");
                        boolean iAmTheDonor = owner != null && !owner.equals(user.getUid());
                        if (iAmTheDonor && "pending".equals(st)) {
                            addRequestRow(d);
                            waiting++;
                        }
                    }
                    binding.layoutRequests.setVisibility(waiting > 0 ? View.VISIBLE : View.GONE);
                    adapter.setBloodStatus(status);
                });
    }

    private final BloodChat.Listener bloodResult = new BloodChat.Listener() {
        @Override
        public void onOpen(String roomId, String roomName) {
            openChat(roomId, roomName);
        }

        @Override
        public void onMessage(String text) {
            Toast.makeText(ChatRoomsActivity.this, text, Toast.LENGTH_LONG).show();
        }
    };

    /** One "Rahim needs B+ blood" card with Accept / Decline. */
    private void addRequestRow(DocumentSnapshot d) {
        ItemBloodRequestBinding row = ItemBloodRequestBinding.inflate(
                getLayoutInflater(), binding.requestsList, false);
        String blood = d.getString("bloodGroup") == null ? "" : d.getString("bloodGroup");
        String requester = d.getString("requesterName") == null ? "Someone" : d.getString("requesterName");
        String note = d.getString("note");
        row.tvRequestTitle.setText(requester + " needs " + blood + " blood");
        row.tvRequestNote.setText(note == null || note.isEmpty()
                ? "Accept to start a chat. They will see your name."
                : "\"" + note + "\"");
        row.btnAccept.setOnClickListener(v -> {
            row.btnAccept.setEnabled(false);
            row.btnDecline.setEnabled(false);
            BloodChat.accept(this, d, bloodResult);
        });
        row.btnDecline.setOnClickListener(v -> {
            row.btnAccept.setEnabled(false);
            row.btnDecline.setEnabled(false);
            BloodChat.decline(d, bloodResult);
        });
        binding.requestsList.addView(row.getRoot());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listener != null) {
            listener.remove();
            listener = null;
        }
        if (bloodListener != null) {
            bloodListener.remove();
            bloodListener = null;
        }
    }

    // ---------------------------------------------------------------- create

    private void askRoomName() {
        EditText et = new EditText(this);
        et.setHint("e.g. Family");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});

        new AlertDialog.Builder(this)
                .setTitle("Name your chat room")
                .setView(wrap(et))
                .setPositiveButton("Create", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter a room name", Toast.LENGTH_SHORT).show();
                    } else {
                        createRoom(name, 0);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The code is the document id. If it is already taken, Firestore refuses and we try a new code. */
    private void createRoom(String name, int attempt) {
        setBusy(true);
        String code = TrackCode.generate();
        Map<String, Object> room = new HashMap<>();
        room.put("name", name);
        room.put("ownerUid", user.getUid());
        room.put("createdAt", System.currentTimeMillis());

        db.collection("chatRooms").document(code).set(room)
                .addOnSuccessListener(v -> joinRoom(code, name))
                .addOnFailureListener(e -> {
                    if (attempt < 2) {
                        createRoom(name, attempt + 1);
                    } else {
                        setBusy(false);
                        Toast.makeText(this, "Could not create the room. Check your internet "
                                + "and the Firestore rules. " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ------------------------------------------------------------------ join

    private void askJoinCode() {
        EditText et = new EditText(this);
        et.setHint("Room code (e.g. K7M4Q2)");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(TrackCode.LENGTH)});

        new AlertDialog.Builder(this)
                .setTitle("Join a chat room")
                .setView(wrap(et))
                .setPositiveButton("Join", (d, w) -> {
                    String code = et.getText().toString().trim().toUpperCase(Locale.ROOT);
                    if (!TrackCode.isValid(code)) {
                        Toast.makeText(this, "The code has " + TrackCode.LENGTH
                                + " letters or numbers", Toast.LENGTH_SHORT).show();
                    } else {
                        findAndJoin(code);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void findAndJoin(String code) {
        setBusy(true);
        db.collection("chatRooms").document(code).get()
                .addOnSuccessListener(snap -> {
                    if (!snap.exists()) {
                        setBusy(false);
                        Toast.makeText(this, "No room found with that code", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String name = snap.getString("name");
                    joinRoom(code, name == null ? "" : name);
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    Toast.makeText(this, "Could not find the room: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    /** Adds me to the room's members, then to my own list, then opens the chat. */
    private void joinRoom(String code, String name) {
        Map<String, Object> member = new HashMap<>();
        member.put("name", displayName());
        member.put("joinedAt", System.currentTimeMillis());
        member.put("photo", AvatarUtil.thumbFrom(AvatarUtil.loadLocal(
                getSharedPreferences("resq", MODE_PRIVATE))));   // small profile picture ("" if none)

        db.collection("chatRooms").document(code)
                .collection("members").document(user.getUid()).set(member)
                .addOnSuccessListener(v -> {
                    Map<String, Object> mine = new HashMap<>();
                    mine.put("name", name);
                    mine.put("joinedAt", System.currentTimeMillis());
                    myRoomsRef.document(code).set(mine);
                    ChatNotifyService.start(this);
                    setBusy(false);
                    openChat(code, name);
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    Toast.makeText(this, "Could not join the room: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    // ----------------------------------------------------------------- leave

    private void confirmLeave(ChatRoom room) {
        new AlertDialog.Builder(this)
                .setTitle("Leave this room?")
                .setMessage(BloodChat.isBloodRoom(room.id)
                        ? "You will leave this blood chat with \"" + room.name + "\"."
                        : "You will stop receiving messages from \"" + room.name
                        + "\". You can join again later with the code " + room.id + ".")
                .setPositiveButton("Leave", (d, w) -> {
                    db.collection("chatRooms").document(room.id)
                            .collection("members").document(user.getUid()).delete();
                    myRoomsRef.document(room.id).delete();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------------------------------------------- helpers

    /**
     * Once: asks to let ResQ run in the background, so chat alerts still arrive while the
     * screen is off (Android's battery saver otherwise pauses the connection).
     */
    private void askBackgroundOnce() {
        SharedPreferences sp = getSharedPreferences("resq", Context.MODE_PRIVATE);
        if (sp.getBoolean("battery_asked", false) || isFinishing()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        sp.edit().putBoolean("battery_asked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Get chat alerts on time")
                .setMessage("To be notified of new messages while your screen is off, allow ResQ "
                        + "to run in the background. You can mute any room with the bell icon.")
                .setPositiveButton("Allow", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void openChat(String code, String name) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_ROOM_ID, code);
        i.putExtra(ChatActivity.EXTRA_ROOM_NAME, name);
        startActivity(i);
    }

    private String displayName() {
        String n = user.getDisplayName();
        if (n != null && !n.trim().isEmpty()) return n.trim();
        String email = user.getEmail();
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "Member";
    }

    private void setBusy(boolean busy) {
        binding.btnCreate.setEnabled(!busy);
        binding.btnJoin.setEnabled(!busy);
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    /** Puts a little space around the text box inside a dialog. */
    private FrameLayout wrap(EditText et) {
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        return box;
    }
}
