package com.example.resq;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.firebase.firestore.FirebaseFirestore;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Mute / unmute one chat room for this user.
 * Stored in Firestore users/{uid}/chatRooms/{code}.mutedUntil (millis; 0 = not muted).
 * ChatNotifyService reads it, so a muted room makes no notification.
 */
public final class ChatMute {

    public static final long FOREVER = Long.MAX_VALUE;
    private static final long HOUR = 60L * 60L * 1000L;

    private ChatMute() { }

    public static boolean isMuted(long mutedUntil) {
        return mutedUntil > System.currentTimeMillis();
    }

    /** Saves the new mute time. update() so it never re-creates a room the user already left. */
    public static void set(Context ctx, String uid, String roomId, long until) {
        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("chatRooms").document(roomId)
                .update("mutedUntil", until)
                .addOnFailureListener(e -> Toast.makeText(ctx.getApplicationContext(),
                        "Could not change mute: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    public static String describe(long mutedUntil) {
        if (mutedUntil == FOREVER) return "until you turn it back on";
        return "until " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(mutedUntil));
    }

    /** Asks how long to mute (or offers to unmute). ctx must be an Activity. */
    public static void showDialog(Context ctx, String uid, String roomId, String roomName,
                                  long mutedUntil) {
        boolean muted = isMuted(mutedUntil);
        long now = System.currentTimeMillis();
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        if (muted) {
            labels.add("Turn notifications back on");
            values.add(0L);
        }
        labels.add("Mute for 1 hour");
        values.add(now + HOUR);
        labels.add("Mute for 8 hours");
        values.add(now + 8 * HOUR);
        labels.add("Mute for 1 week");
        values.add(now + 24 * 7 * HOUR);
        labels.add("Mute until I turn it back on");
        values.add(FOREVER);

        String name = (roomName == null || roomName.isEmpty()) ? "this room" : roomName;
        String title = muted ? "Muted " + describe(mutedUntil) : "Mute " + name + "?";

        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]),
                        (d, which) -> set(ctx, uid, roomId, values.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
