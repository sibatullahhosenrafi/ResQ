package com.example.resq;

import android.content.Context;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Chat between someone who needs blood (requester) and an AVAILABLE donor. The donor must accept first.
 *
 *   chatRooms/d_{donorUid}_{requesterUid}   the room (type "donor", status pending / accepted / declined,
 *                                           participants [donorUid, requesterUid], names, bloodGroup, note)
 *   .../members/{uid}                       the requester joins at once, the donor joins when accepting
 *   users/{uid}/chatRooms/d_...             each person's own list (so the normal chat screens work)
 *
 * One fixed room id per donor + requester pair, so a person cannot flood a donor with requests.
 */
public final class BloodChat {

    public static final String PREFIX = "d_";

    public interface Listener {
        /** The chat exists and can be opened. */
        void onOpen(String roomId, String roomName);

        /** Something to tell the person (already requested, declined, error...). */
        void onMessage(String text);
    }

    private BloodChat() { }

    public static boolean isBloodRoom(String roomId) {
        return roomId != null && roomId.startsWith(PREFIX);
    }

    public static String roomId(String donorUid, String requesterUid) {
        return PREFIX + donorUid + "_" + requesterUid;
    }

    /** What the REQUESTER sees in the list: "Rahim (B+ donor)". */
    public static String donorTitle(String donorName, String blood) {
        return (donorName == null || donorName.isEmpty() ? "Donor" : donorName) + " (" + blood + " donor)";
    }

    /** What the DONOR sees in the list: "Karim needs B+". */
    public static String requesterTitle(String requesterName, String blood) {
        return (requesterName == null || requesterName.isEmpty() ? "Someone" : requesterName)
                + " needs " + blood;
    }

    // ------------------------------------------------------------ requester: ask a donor

    public static void request(Context ctx, Donor donor, String firstMessage, Listener out) {
        FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) return;
        if (donor.uid.equals(me.getUid())) {
            out.onMessage("You cannot chat with yourself");
            return;
        }
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String id = roomId(donor.uid, me.getUid());
        DocumentReference roomRef = db.collection("chatRooms").document(id);
        String myName = displayName(me);
        String title = donorTitle(donor.name, donor.bloodGroup);

        roomRef.get().addOnSuccessListener(snap -> {
            if (snap.exists()) {                       // asked before: do not create it twice
                String st = snap.getString("status");
                if ("accepted".equals(st)) out.onOpen(id, title);
                else if ("declined".equals(st)) out.onMessage("This donor declined your request");
                else out.onMessage("Your request is already sent. Waiting for the donor to accept.");
                return;
            }
            Map<String, Object> room = new HashMap<>();
            room.put("name", "Blood request " + donor.bloodGroup);       // rule: 40 characters at most
            room.put("ownerUid", me.getUid());
            room.put("type", "donor");
            room.put("status", "pending");
            room.put("participants", Arrays.asList(donor.uid, me.getUid()));   // [donor, requester]
            room.put("donorName", donor.name);
            room.put("requesterName", myName);
            room.put("bloodGroup", donor.bloodGroup);
            room.put("note", firstMessage.length() > 200 ? firstMessage.substring(0, 200) : firstMessage);
            room.put("createdAt", System.currentTimeMillis());

            roomRef.set(room)
                    .addOnSuccessListener(v -> addMember(ctx, roomRef, me, myName)
                            .addOnSuccessListener(v2 -> {
                                saveMyEntry(db, me.getUid(), id, title);
                                roomRef.collection("messages").add(
                                        ChatMessage.toMap(me.getUid(), myName, firstMessage, null, null));
                                ChatNotifyService.start(ctx);
                                out.onOpen(id, title);
                            })
                            .addOnFailureListener(e -> out.onMessage("Could not start the chat: " + e.getMessage())))
                    .addOnFailureListener(e -> out.onMessage(
                            "Could not send the request. This donor may have just become unavailable. "
                                    + e.getMessage()));
        }).addOnFailureListener(e -> out.onMessage("Could not reach the server: " + e.getMessage()));
    }

    // ------------------------------------------------------------ donor: accept / decline

    public static void accept(Context ctx, DocumentSnapshot room, Listener out) {
        FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) return;
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String id = room.getId();
        String title = requesterTitle(room.getString("requesterName"), str(room.getString("bloodGroup")));
        DocumentReference roomRef = room.getReference();

        roomRef.update("status", "accepted")
                .addOnSuccessListener(v -> addMember(ctx, roomRef, me, displayName(me))
                        .addOnSuccessListener(v2 -> {
                            saveMyEntry(db, me.getUid(), id, title);
                            ChatNotifyService.start(ctx);
                            out.onOpen(id, title);
                        })
                        .addOnFailureListener(e -> out.onMessage("Could not open the chat: " + e.getMessage())))
                .addOnFailureListener(e -> out.onMessage("Could not accept: " + e.getMessage()));
    }

    public static void decline(DocumentSnapshot room, Listener out) {
        room.getReference().update("status", "declined")
                .addOnFailureListener(e -> out.onMessage("Could not decline: " + e.getMessage()));
    }

    // ------------------------------------------------------------ helpers

    /** Puts me into the room's members (name + small profile picture), like joining a normal room. */
    private static Task<Void> addMember(Context ctx, DocumentReference roomRef, FirebaseUser me, String name) {
        Map<String, Object> member = new HashMap<>();
        member.put("name", name);
        member.put("joinedAt", System.currentTimeMillis());
        member.put("photo", AvatarUtil.thumbFrom(AvatarUtil.loadLocal(
                ctx.getSharedPreferences("resq", Context.MODE_PRIVATE))));
        return roomRef.collection("members").document(me.getUid()).set(member);
    }

    /** My own list of rooms, so the normal Chat rooms screen, notifications and mute work for it. */
    private static void saveMyEntry(FirebaseFirestore db, String uid, String roomId, String title) {
        Map<String, Object> mine = new HashMap<>();
        mine.put("name", title);
        mine.put("joinedAt", System.currentTimeMillis());
        db.collection("users").document(uid).collection("chatRooms").document(roomId).set(mine);
    }

    private static String displayName(FirebaseUser u) {
        String n = u.getDisplayName();
        if (n != null && !n.trim().isEmpty()) return n.trim();
        String email = u.getEmail();
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "Member";
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
