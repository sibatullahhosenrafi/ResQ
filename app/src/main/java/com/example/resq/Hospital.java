package com.example.resq;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.Locale;

/**
 * One hospital. Two places in Firestore:
 *  hospitals/{id}                    shared list (you add it in the Firebase Console)
 *  users/{uid}/myHospitals/{id}      the user's own hospitals (added from Google Maps)
 * distanceM is filled in by the app.
 */
public class Hospital {

    public final String id;
    public final String name;
    public final String address;
    public final String phone;
    public final double lat;       // NaN when the document has no coordinates
    public final double lng;
    public final boolean emergency;
    public final boolean mine;     // true = the user's own hospital (can be deleted)
    public float distanceM = -1;   // -1 = unknown

    public Hospital(String id, String name, String address, String phone,
                    double lat, double lng, boolean emergency, boolean mine) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.lat = lat;
        this.lng = lng;
        this.emergency = emergency;
        this.mine = mine;
    }

    public static Hospital fromSnapshot(DocumentSnapshot d, boolean mine) {
        Double la = d.getDouble("lat");
        Double lo = d.getDouble("lng");
        Boolean em = d.getBoolean("emergency");
        return new Hospital(d.getId(),
                str(d.getString("name")),
                str(d.getString("address")),
                str(d.getString("phone")).trim(),
                la == null ? Double.NaN : la,
                lo == null ? Double.NaN : lo,
                em != null && em,
                mine);
    }

    public boolean hasCoords() {
        return !Double.isNaN(lat) && !Double.isNaN(lng);
    }

    /** "850 m away" or "2.4 km away". */
    public String distanceText() {
        if (distanceM < 0) return "Distance unknown";
        if (distanceM < 1000) return Math.round(distanceM) + " m away";
        return String.format(Locale.US, "%.1f km away", distanceM / 1000f);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
