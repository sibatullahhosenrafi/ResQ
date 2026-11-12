package com.example.resq;

import android.content.Context;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.List;
import java.util.Map;

/**
 * Posts the SOS message into every chat room the user has joined.
 * The message is flagged sos=true, so other members get a notification even if they muted the room.
 *
 * Every room is reported to SosNotifier ONLY when the Firestore SERVER confirms the write, so the
 * progress notification shows real delivery, not "saved on the phone". With no internet the write
 * stays queued on the phone and is sent when the internet returns.
 */
public final class ChatSos {

    private ChatSos() { }

    /** The SOS: flagged sos=true. */
    public static void post(Context ctx, long sosId, String text) {
        postFlagged(ctx, sosId, text, "sos");
    }

    /** "I am safe": flagged safe=true, so it also reaches members who muted the room (they saw the SOS). */
    public static void postSafe(Context ctx, long sosId, String text) {
        postFlagged(ctx, sosId, text, "safe");
    }

    private static void postFlagged(Context ctx, long sosId, String text, String flag) {
        final Context app = ctx.getApplicationContext();
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null || text == null || text.isEmpty()) {
            SosNotifier.chatLookupFailed(app, sosId, "you are not logged in");
            return;
        }
        final String uid = u.getUid();
        final String name = displayName(u);
        final FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users").document(uid).collection("chatRooms").get()
                .addOnSuccessListener(snap -> {
                    List<DocumentSnapshot> rooms = snap.getDocuments();
                    SosNotifier.chatRoomsFound(app, sosId, rooms.size());
                    for (DocumentSnapshot d : rooms) {
                        Map<String, Object> msg = ChatMessage.toMap(uid, name, text, null, null);
                        msg.put(flag, true);
                        db.collection("chatRooms").document(d.getId())
                                .collection("messages").add(msg)
                                // these listeners run only after the server accepted (or refused) the message
                                .addOnSuccessListener(ref -> SosNotifier.chatRoomResult(app, sosId, true))
                                .addOnFailureListener(e -> SosNotifier.chatRoomResult(app, sosId, false));
                    }
                })
                .addOnFailureListener(e -> SosNotifier.chatLookupFailed(app, sosId,
                        "could not read your chat rooms"));
    }

    private static String displayName(FirebaseUser u) {
        String n = u.getDisplayName();
        if (n != null && !n.trim().isEmpty()) return n.trim();
        String email = u.getEmail();
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "Member";
    }
}
