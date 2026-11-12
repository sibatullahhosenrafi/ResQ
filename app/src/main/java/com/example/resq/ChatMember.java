package com.example.resq;

/** A member of a chat room (chatRooms/{code}/members/{uid}). lastReadAt = when they last had the chat open. */
public class ChatMember {

    public final String uid;
    public final String name;
    public final long lastReadAt;
    public final String photo;      // small Base64 JPEG thumbnail, "" = none

    public ChatMember(String uid, String name, long lastReadAt) {
        this(uid, name, lastReadAt, "");
    }

    public ChatMember(String uid, String name, long lastReadAt, String photo) {
        this.photo = photo == null ? "" : photo;
        this.uid = uid;
        this.name = (name == null || name.trim().isEmpty()) ? "Member" : name.trim();
        this.lastReadAt = lastReadAt;
    }

    /** True if this member has opened the chat since the message was sent. */
    public boolean hasSeen(long messageTime) {
        return lastReadAt >= messageTime;
    }
}
