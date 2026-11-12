package com.example.resq;

import android.content.SharedPreferences;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper for the emergency circle.
 *  - Cloud Firestore  users/{uid}/contacts = the cloud copy (synced across devices).
 *  - SharedPreferences                      = the local copy, so SOS works with NO internet.
 */
public final class CircleStore {

    /** Maximum contacts in a circle (every contact costs one SMS). */
    public static final int MAX = 5;

    private CircleStore() { }

    private static String key(String uid) {
        return "circle_" + uid;
    }

    /** Removes spaces, dashes and brackets, e.g. "017-1122 3344" becomes "01711223344". */
    public static String normalize(String phone) {
        if (phone == null) return "";
        return phone.trim().replaceAll("[^0-9+]", "");
    }

    /** A valid number is an optional "+" followed by 6 to 15 digits. */
    public static boolean isValidPhone(String normalized) {
        return normalized.matches("^\\+?[0-9]{6,15}$");
    }

    // ---------- local copy ----------
    public static List<CircleContact> load(SharedPreferences prefs, String uid) {
        List<CircleContact> list = new ArrayList<>();
        String json = prefs.getString(key(uid), "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new CircleContact(
                        o.optString("id"), o.optString("name"), o.optString("phone")));
            }
        } catch (Exception ignored) {
            // damaged data: return what we have (empty list)
        }
        return list;
    }

    public static void save(SharedPreferences prefs, String uid, List<CircleContact> list) {
        JSONArray arr = new JSONArray();
        try {
            for (CircleContact c : list) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("phone", c.phone);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(key(uid), arr.toString()).apply();
    }

    public static void clear(SharedPreferences prefs, String uid) {
        prefs.edit().remove(key(uid)).apply();
    }

    // ---------- cloud copy ----------
    public static List<CircleContact> fromSnapshot(QuerySnapshot snap) {
        List<CircleContact> list = new ArrayList<>();
        for (DocumentSnapshot d : snap.getDocuments()) {
            String phone = d.getString("phone");
            if (phone == null || phone.trim().isEmpty()) continue;
            String name = d.getString("name");
            list.add(new CircleContact(d.getId(), name == null ? "" : name, phone));
        }
        return list;
    }

    /** Refreshes the local copy from Firestore (used when the app opens). */
    public static void syncFromCloud(SharedPreferences prefs, String uid) {
        FirebaseFirestore.getInstance()
                .collection("users").document(uid).collection("contacts")
                .orderBy("createdAt")
                .get()
                .addOnSuccessListener(snap -> {
                    // an empty answer that came only from a cold cache must not wipe the local copy
                    if (snap.getMetadata().isFromCache() && snap.isEmpty()) return;
                    save(prefs, uid, fromSnapshot(snap));
                });
    }
}
