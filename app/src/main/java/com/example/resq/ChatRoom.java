package com.example.resq;

/** A chat room the user has joined (users/{uid}/chatRooms/{code}). The document id is the join code. */
public class ChatRoom {

    public final String id;      // the 6-character join code
    public final String name;
    public final long mutedUntil; // 0 = notifications on; see ChatMute

    public ChatRoom(String id, String name) {
        this(id, name, 0L);
    }

    public ChatRoom(String id, String name, long mutedUntil) {
        this.id = id;
        this.name = name;
        this.mutedUntil = mutedUntil;
    }

    public boolean isMuted() {
        return ChatMute.isMuted(mutedUntil);
    }
}
