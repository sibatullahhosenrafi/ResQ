package com.example.resq;

import android.content.SharedPreferences;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * The user's emergency info (name, phone, blood group).
 *  - Cloud Firestore  (users/{uid})  = the cloud copy, synced across devices.
 *  - SharedPreferences = the local copy, so the SOS SMS can include it even with NO internet.
 */
public class MedicalProfile {

    public String name = "";
    public String phone = "";
    public String bloodGroup = "";

    private static final String P = "med_";

    // ---------- local copy ----------
    public static MedicalProfile load(SharedPreferences prefs) {
        MedicalProfile m = new MedicalProfile();
        m.name = prefs.getString(P + "name", "");
        m.phone = prefs.getString(P + "phone", "");
        m.bloodGroup = prefs.getString(P + "blood", "");
        return m;
    }

    public void save(SharedPreferences prefs) {
        prefs.edit()
                .putString(P + "name", name)
                .putString(P + "phone", phone)
                .putString(P + "blood", bloodGroup)
                .apply();
    }

    public static void clear(SharedPreferences prefs) {
        prefs.edit()
                .remove(P + "name").remove(P + "phone").remove(P + "blood")
                .remove(P + "allergies").remove(P + "conditions")   // old keys from the previous version
                .apply();
    }

    // ---------- cloud copy ----------
    public Map<String, Object> toMap(String email) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("email", email);
        map.put("phone", phone);
        map.put("bloodGroup", bloodGroup);
        map.put("updatedAt", System.currentTimeMillis());
        return map;
    }

    public static MedicalProfile fromSnapshot(DocumentSnapshot d) {
        MedicalProfile m = new MedicalProfile();
        m.name = str(d.getString("name"));
        m.phone = str(d.getString("phone"));
        m.bloodGroup = str(d.getString("bloodGroup"));
        return m;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    // ---------- SOS text ----------
    /**
     * Builds the SOS SMS text. Empty / unknown parts are left out.
     *
     * @param type          Medical / Fire / Accident / Disaster ("" = not chosen)
     * @param batteryPct    battery level 0-100 (-1 = unknown)
     * @param time          readable time, e.g. "9:42 PM, 30 Oct"
     * @param gps           plain "lat, lng" text ("" = no location)
     * @param locationLink  Google Maps link (or "Location unavailable")
     */
    public String buildSosMessage(String type, int batteryPct, String time,
                                  String gps, String locationLink) {
        return buildSosMessage(type, batteryPct, time, gps, locationLink, "");
    }

    /** Same as above, plus the live-tracking code ("" = not sharing live). */
    public String buildSosMessage(String type, int batteryPct, String time,
                                  String gps, String locationLink, String trackCode) {
        StringBuilder sb = new StringBuilder("SOS! I need help.");
        if (!type.isEmpty()) sb.append("\nType: ").append(type);
        if (!name.isEmpty()) sb.append("\nName: ").append(name);
        if (!bloodGroup.isEmpty()) sb.append("\nBlood group: ").append(bloodGroup);
        if (!phone.isEmpty()) sb.append("\nMy phone: ").append(phone);
        if (batteryPct >= 0) sb.append("\nBattery: ").append(batteryPct).append("%");
        if (!time.isEmpty()) sb.append("\nTime: ").append(time);
        if (!gps.isEmpty()) sb.append("\nGPS: ").append(gps);
        sb.append("\nLocation: ").append(locationLink);
        if (!trackCode.isEmpty()) sb.append("\nResQ tracking code: ").append(trackCode);
        return sb.toString();
    }
}
