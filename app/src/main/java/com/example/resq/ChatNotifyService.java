package com.example.resq;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chat notifications WITHOUT a server (works on the free Firebase Spark plan).
 *
 * A foreground service keeps a Firestore listener on every room you joined and shows a
 * notification when somebody else posts. It stays silent when
 *   - the room is muted (ChatMute / users/{uid}/chatRooms/{code}.mutedUntil), or
 *   - you are looking at that room right now (openRoomId, set by ChatActivity).
 * Messages that arrived while the service was not running are shown when it starts again.
 */
public class ChatNotifyService extends Service {

    private static final String TAG = "ChatNotifyService";
    public static final String CHANNEL_MESSAGES = "chat_messages";
    private static final String CHANNEL_SERVICE = "chat_service";
    private static final int FG_ID = 4001;
    private static final int NOTIF_BASE = 100000;
    private static final int MAX_LINES = 5;
    private static final String PREFS = "resq_chat_notify";

    /** The room that is on screen right now (set by ChatActivity), or null. */
    public static volatile String openRoomId = null;

    private static final Map<String, List<String>> LINES = new HashMap<>();
    private static final Map<String, Integer> COUNTS = new HashMap<>();

    private FirebaseFirestore db;
    private String uid;
    private ListenerRegistration roomsListener;
    private final Map<String, ListenerRegistration> msgListeners = new HashMap<>();
    private final Map<String, Long> mutedUntil = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    // blood-donor chats
    private ListenerRegistration bloodListener;
    private ListenerRegistration donorListener;
    private final Set<String> bloodIds = new HashSet<>();
    private boolean roomsEmpty;      // I am in no room
    private boolean donorKnown;      // we know whether I am a registered donor
    private boolean isDonor;
    private boolean bloodKnown;      // we know my blood chats

    // ------------------------------------------------------------------ static helpers

    /** Starts the service if a user is logged in. Safe to call many times. */
    public static void start(Context ctx) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        try {
            ContextCompat.startForegroundService(ctx.getApplicationContext(),
                    new Intent(ctx.getApplicationContext(), ChatNotifyService.class));
        } catch (Exception e) {
            Log.w(TAG, "Could not start chat notifications now", e);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ChatNotifyService.class));
    }

    /** Remove this room's notification (call when the user opens the room). */
    public static void clearRoom(Context ctx, String roomId) {
        LINES.remove(roomId);
        COUNTS.remove(roomId);
        NotificationManagerCompat.from(ctx).cancel(notifId(roomId));
    }

    /** Everything up to now has been read, so it will not be notified again after a restart. */
    public static void markRead(Context ctx, String uid, String roomId) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(uid + "_" + roomId, System.currentTimeMillis()).apply();
    }

    private static int notifId(String roomId) {
        return NOTIF_BASE + (roomId.hashCode() & 0xFFFF);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels();
        // Must be called right away after startForegroundService()
        try {
            Notification n = buildForegroundNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(FG_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start foreground", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (roomsListener != null && u.getUid().equals(uid)) return START_STICKY;   // already listening

        stopListening();
        uid = u.getUid();
        roomsListener = db.collection("users").document(uid).collection("chatRooms")
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) return;
                    Set<String> current = new HashSet<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        String id = d.getId();
                        current.add(id);
                        String name = d.getString("name");
                        names.put(id, name == null ? "" : name);
                        Long m = d.getLong("mutedUntil");
                        mutedUntil.put(id, m == null ? 0L : m);
                        if (!msgListeners.containsKey(id)) listenRoom(id);
                    }
                    // rooms the user left
                    Iterator<Map.Entry<String, ListenerRegistration>> it = msgListeners.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, ListenerRegistration> e = it.next();
                        if (!current.contains(e.getKey())) {
                            e.getValue().remove();
                            it.remove();
                        }
                    }
                    // nothing to watch: remove the permanent notification (see maybeStop)
                    roomsEmpty = current.isEmpty() && !snap.getMetadata().isFromCache();
                    maybeStop();
                });
        listenBloodChats();
        listenDonorFlag();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopListening();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopListening() {
        if (bloodListener != null) {
            bloodListener.remove();
            bloodListener = null;
        }
        if (donorListener != null) {
            donorListener.remove();
            donorListener = null;
        }
        bloodIds.clear();
        roomsEmpty = false;
        donorKnown = false;
        isDonor = false;
        bloodKnown = false;
        if (roomsListener != null) {
            roomsListener.remove();
            roomsListener = null;
        }
        for (ListenerRegistration r : msgListeners.values()) r.remove();
        msgListeners.clear();
    }

    // ------------------------------------------------------------------ listening

    private void listenRoom(final String roomId) {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String key = uid + "_" + roomId;
        long since = p.getLong(key, 0);
        if (since == 0) {                      // first time for this room: only NEW messages count
            since = System.currentTimeMillis();
            p.edit().putLong(key, since).apply();
        }
        final long start = since;

        ListenerRegistration reg = db.collection("chatRooms").document(roomId)
                .collection("messages")
                .whereGreaterThan("createdAt", start)
                .orderBy("createdAt")
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) return;
                    long maxAt = 0;
                    List<String> fresh = new ArrayList<>();
                    List<String> freshSos = new ArrayList<>();      // SOS and "I am safe": they bypass mute
                    boolean anySos = false;
                    boolean anySafe = false;
                    for (DocumentChange c : snap.getDocumentChanges()) {
                        if (c.getType() != DocumentChange.Type.ADDED) continue;
                        DocumentSnapshot d = c.getDocument();
                        Long at = d.getLong("createdAt");
                        if (at != null && at > maxAt) maxAt = at;
                        if (uid.equals(d.getString("senderUid"))) continue;       // my own message
                        String sender = d.getString("senderName");
                        if (sender == null || sender.isEmpty()) sender = "Someone";
                        String text = d.getString("text");
                        boolean loc = d.getDouble("lat") != null && d.getDouble("lng") != null;
                        String body = loc ? "shared a location"
                                : (text == null ? "" : text.replace("\n", " \u00B7 "));
                        String line = sender + ": " + body;
                        fresh.add(line);
                        if (Boolean.TRUE.equals(d.getBoolean("sos"))) {
                            freshSos.add(line);
                            anySos = true;
                        } else if (Boolean.TRUE.equals(d.getBoolean("safe"))) {
                            freshSos.add(line);
                            anySafe = true;
                        }
                    }
                    if (maxAt > 0) p.edit().putLong(key, maxAt).apply();
                    if (fresh.isEmpty()) return;

                    Long mu = mutedUntil.get(roomId);
                    if (mu != null && ChatMute.isMuted(mu)) {
                        fresh = freshSos;                  // muted: only an SOS / "I am safe" gets through
                        if (fresh.isEmpty()) return;
                    }
                    if (roomId.equals(openRoomId)) return;                        // I am in that chat
                    showNotification(roomId, fresh, anySos, anySafe);
                });
        msgListeners.put(roomId, reg);
    }

    // ------------------------------------------------------------------ notifications

    // ------------------------------------------------------------------ blood-donor chats

    /** Stop only if there is really nothing to wait for: no room, not a donor, no blood chat. */
    private void maybeStop() {
        if (roomsEmpty && donorKnown && !isDonor && bloodKnown && bloodIds.isEmpty()) stopSelf();
    }

    /** Am I a registered donor? Then requests can arrive, so the service must keep running. */
    private void listenDonorFlag() {
        donorListener = db.collection("donors").document(uid).addSnapshotListener((doc, error) -> {
            if (error != null || doc == null) return;
            donorKnown = doc.exists() || !doc.getMetadata().isFromCache();
            isDonor = doc.exists();
            maybeStop();
        });
    }

    /**
     * Blood chats I am part of. Notifies the donor about a new request, and the person who asked when
     * the donor accepts or declines. The last status per room is remembered, so nothing is shown twice
     * and nothing is missed while the phone was off.
     */
    private void listenBloodChats() {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        bloodListener = db.collection("chatRooms").whereArrayContains("participants", uid)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) return;
                    for (DocumentChange c : snap.getDocumentChanges()) {
                        DocumentSnapshot d = c.getDocument();
                        String id = d.getId();
                        if (c.getType() == DocumentChange.Type.REMOVED) {
                            bloodIds.remove(id);
                            continue;
                        }
                        bloodIds.add(id);
                        String status = d.getString("status");
                        if (status == null) status = "pending";
                        String owner = d.getString("ownerUid");
                        boolean iAmTheDonor = owner != null && !owner.equals(uid);

                        String key = "blood_" + uid + "_" + id;
                        String last = p.getString(key, null);
                        if (status.equals(last)) continue;
                        p.edit().putString(key, status).apply();

                        if (iAmTheDonor) {
                            if ("pending".equals(status)) notifyBloodRequest(id, d);
                        } else if (!"pending".equals(status)) {
                            notifyBloodReply(id, d, status);
                        }
                    }
                    if (!snap.getMetadata().isFromCache()) bloodKnown = true;
                    maybeStop();
                });
    }

    private void notifyBloodRequest(String roomId, DocumentSnapshot d) {
        String blood = d.getString("bloodGroup") == null ? "" : d.getString("bloodGroup");
        String who = d.getString("requesterName") == null ? "Someone" : d.getString("requesterName");
        PendingIntent pi = TaskStackBuilder.create(this)
                .addNextIntent(new Intent(this, MainActivity.class))
                .addNextIntent(new Intent(this, ChatRoomsActivity.class))
                .getPendingIntent(notifId(roomId), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        postSimple(notifId(roomId), "Blood request - " + blood,
                who + " needs " + blood + " blood. Open ResQ to accept.", "Blood request", pi);
    }

    private void notifyBloodReply(String roomId, DocumentSnapshot d, String status) {
        String blood = d.getString("bloodGroup") == null ? "" : d.getString("bloodGroup");
        String donor = d.getString("donorName") == null ? "The donor" : d.getString("donorName");
        TaskStackBuilder stack = TaskStackBuilder.create(this)
                .addNextIntent(new Intent(this, MainActivity.class))
                .addNextIntent(new Intent(this, ChatRoomsActivity.class));
        if ("accepted".equals(status)) {
            stack.addNextIntent(new Intent(this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_ROOM_ID, roomId)
                    .putExtra(ChatActivity.EXTRA_ROOM_NAME, BloodChat.donorTitle(donor, blood)));
        }
        PendingIntent pi = stack.getPendingIntent(notifId(roomId),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if ("accepted".equals(status)) {
            postSimple(notifId(roomId), "Request accepted",
                    donor + " accepted your " + blood + " blood request. Tap to chat.", "Blood request", pi);
        } else {
            postSimple(notifId(roomId), "Request declined",
                    donor + " cannot help right now. You can try another donor.", "Blood request", pi);
        }
    }

    @SuppressLint("MissingPermission")
    private void postSimple(int id, String title, String text, String publicText, PendingIntent pi) {
        Notification publicVersion = new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText(publicText)
                .build();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        if (!nm.areNotificationsEnabled()) return;
        try {
            nm.notify(id, n);
        } catch (SecurityException ignored) {
            // notification permission missing
        }
    }

    // ------------------------------------------------------------------ notifications

    @SuppressLint("MissingPermission")
    private void showNotification(String roomId, List<String> fresh, boolean sos, boolean safe) {
        List<String> lines = LINES.get(roomId);
        if (lines == null) {
            lines = new ArrayList<>();
            LINES.put(roomId, lines);
        }
        lines.addAll(fresh);
        while (lines.size() > MAX_LINES) lines.remove(0);
        Integer old = COUNTS.get(roomId);
        int count = (old == null ? 0 : old) + fresh.size();
        COUNTS.put(roomId, count);

        String room = names.get(roomId);
        if (room == null || room.isEmpty()) room = "Chat room";
        String title = sos ? "SOS - " + room : (safe ? "Safe - " + room : room);
        String last = lines.get(lines.size() - 1);
        String summary = count == 1 ? last : count + " new messages";

        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle()
                .setBigContentTitle(title);
        for (String l : lines) style.addLine(l);

        // Back from the chat goes to Chat rooms, then Home
        Intent chat = new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_ROOM_ID, roomId)
                .putExtra(ChatActivity.EXTRA_ROOM_NAME, room);
        PendingIntent pi = TaskStackBuilder.create(this)
                .addNextIntent(new Intent(this, MainActivity.class))
                .addNextIntent(new Intent(this, ChatRoomsActivity.class))
                .addNextIntent(chat)
                .getPendingIntent(notifId(roomId),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Lock screen shows no message text
        Notification publicVersion = new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText(sos ? "Someone needs help. Open ResQ."
                        : (safe ? "A member says they are safe" : "New chat message"))
                .build();

        Notification n = new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(style)
                .setNumber(count)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        if (!nm.areNotificationsEnabled()) return;
        try {
            nm.notify(notifId(roomId), n);
        } catch (SecurityException ignored) {
            // notification permission missing
        }
    }

    private Notification buildForegroundNotification() {
        PendingIntent open = PendingIntent.getActivity(this, FG_ID,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle("ResQ chat is on")
                .setContentText("Listening for new chat messages")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(open)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel msg = new NotificationChannel(CHANNEL_MESSAGES, "Chat messages",
                NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("New messages in your chat rooms");
        msg.enableVibration(true);
        msg.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(msg);

        NotificationChannel svc = new NotificationChannel(CHANNEL_SERVICE, "Chat connection",
                NotificationManager.IMPORTANCE_MIN);
        svc.setDescription("Keeps ResQ listening for chat messages. You can hide this one.");
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);
    }
}
