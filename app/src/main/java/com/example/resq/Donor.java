package com.example.resq;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One blood donor (Firestore: donors/{uid}). */
public class Donor {

    public static final String[] BLOOD_GROUPS =
            {"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};

    public final String uid;
    public final String name;
    public final String phone;
    public final String bloodGroup;
    public final String city;

    public Donor(String uid, String name, String phone, String bloodGroup, String city) {
        this.uid = uid;
        this.name = name;
        this.phone = phone;
        this.bloodGroup = bloodGroup;
        this.city = city;
    }

    public static Donor fromSnapshot(DocumentSnapshot d) {
        return new Donor(d.getId(),
                str(d.getString("name")),
                str(d.getString("phone")),
                str(d.getString("bloodGroup")),
                str(d.getString("city")));
    }

    /** The fields saved in Firestore. cityLower is used to search without caring about capital letters. */
    public static Map<String, Object> toMap(String uid, String name, String phone,
                                            String bloodGroup, String city, boolean available) {
        Map<String, Object> map = new HashMap<>();
        map.put("uid", uid);
        map.put("name", name);
        map.put("phone", phone);
        map.put("bloodGroup", bloodGroup);
        map.put("city", city);
        map.put("cityLower", city.trim().toLowerCase(Locale.ROOT));
        map.put("available", available);
        map.put("updatedAt", System.currentTimeMillis());
        return map;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
