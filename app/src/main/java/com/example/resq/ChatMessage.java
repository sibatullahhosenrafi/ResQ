package com.example.resq;

import java.util.HashMap;
import java.util.Map;

/** One chat message (chatRooms/{roomId}/messages/{id}). A message is text, or a shared location. */
public class ChatMessage {

    public final String id;
    public final String senderUid;
    public final String senderName;
    public final String text;
    public final double lat;     // NaN when the message has no location
    public final double lng;
    public final long createdAt;
    public final boolean pending;   // still being sent (not yet confirmed by the server)

    public ChatMessage(String id, String senderUid, String senderName, String text,
                       double lat, double lng, long createdAt) {
        this(id, senderUid, senderName, text, lat, lng, createdAt, false);
    }

    public ChatMessage(String id, String senderUid, String senderName, String text,
                       double lat, double lng, long createdAt, boolean pending) {
        this.pending = pending;
        this.id = id;
        this.senderUid = senderUid;
        this.senderName = senderName;
        this.text = text;
        this.lat = lat;
        this.lng = lng;
        this.createdAt = createdAt;
    }

    public boolean hasLocation() {
        return !Double.isNaN(lat) && !Double.isNaN(lng);
    }

    public static Map<String, Object> toMap(String uid, String name, String text,
                                            Double lat, Double lng) {
        Map<String, Object> map = new HashMap<>();
        map.put("senderUid", uid);
        map.put("senderName", name);
        map.put("text", text);
        if (lat != null && lng != null) {
            map.put("lat", lat);
            map.put("lng", lng);
        }
        map.put("createdAt", System.currentTimeMillis());
        return map;
    }
}
