package com.example.resq;

import java.util.HashMap;
import java.util.Map;

/** One person in the user's emergency circle (users/{uid}/contacts/{id}). */
public class CircleContact {

    public final String id;      // Firestore document id ("" for the old single contact)
    public final String name;
    public final String phone;

    public CircleContact(String id, String name, String phone) {
        this.id = id;
        this.name = name;
        this.phone = phone;
    }

    /** The fields stored in Firestore. */
    public static Map<String, Object> toMap(String name, String phone) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("phone", phone);
        map.put("createdAt", System.currentTimeMillis());
        return map;
    }
}
