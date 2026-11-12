package com.example.resq;

import android.content.SharedPreferences;

import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;

/**
 * "Preparedness score": how ready the user is for an emergency.
 * Each step is either done or not; the score is done / total.
 *  - name, phone, blood group, circle contacts -> read from the local copy (instant, offline)
 *  - donor registration, saved hospital       -> asked from Firestore, then remembered locally
 */
public final class Readiness {

    public static final int GO_PROFILE = 0;
    public static final int GO_CIRCLE = 1;
    public static final int GO_DONOR = 2;
    public static final int GO_HOSPITAL = 3;

    /** The circle size that counts as "well prepared". */
    public static final int GOOD_CIRCLE = 3;

    public static final class Step {
        public final String title;
        public final String hint;
        public final boolean done;
        public final int goTo;

        Step(String title, String hint, boolean done, int goTo) {
            this.title = title;
            this.hint = hint;
            this.done = done;
            this.goTo = goTo;
        }
    }

    private Readiness() { }

    private static String donorKey(String uid) {
        return "ready_donor_" + uid;
    }

    private static String hospitalKey(String uid) {
        return "ready_hospital_" + uid;
    }

    /** Builds the checklist from what is stored on the phone right now. */
    public static List<Step> compute(SharedPreferences prefs, String uid) {
        MedicalProfile m = MedicalProfile.load(prefs);
        int contacts = CircleStore.load(prefs, uid).size();
        boolean donor = prefs.getBoolean(donorKey(uid), false);
        boolean hospital = prefs.getBoolean(hospitalKey(uid), false);

        List<Step> steps = new ArrayList<>();
        steps.add(new Step("Add your name",
                m.name.isEmpty() ? "Shown in your SOS message" : "Done", !m.name.isEmpty(), GO_PROFILE));
        steps.add(new Step("Add your phone number",
                m.phone.isEmpty() ? "So your circle can call you back" : "Done", !m.phone.isEmpty(), GO_PROFILE));
        steps.add(new Step("Choose your blood group",
                m.bloodGroup.isEmpty() ? "Helps doctors act faster" : "Done", !m.bloodGroup.isEmpty(), GO_PROFILE));
        steps.add(new Step("Add an emergency contact",
                contacts >= 1 ? "Done" : "Someone who receives your SOS", contacts >= 1, GO_CIRCLE));
        steps.add(new Step("Build a circle of " + GOOD_CIRCLE + " contacts",
                contacts >= GOOD_CIRCLE ? "Done" : contacts + " of " + GOOD_CIRCLE + " added",
                contacts >= GOOD_CIRCLE, GO_CIRCLE));
        steps.add(new Step("Register as a blood donor",
                donor ? "Done" : "Help someone in need", donor, GO_DONOR));
        steps.add(new Step("Add a hospital you trust",
                hospital ? "Done" : "In Google Maps: Share, then choose ResQ", hospital, GO_HOSPITAL));
        return steps;
    }

    public static int doneCount(List<Step> steps) {
        int n = 0;
        for (Step s : steps) if (s.done) n++;
        return n;
    }

    public static int percent(List<Step> steps) {
        if (steps.isEmpty()) return 0;
        return Math.round(doneCount(steps) * 100f / steps.size());
    }

    /** Asks Firestore about the donor registration and the saved hospital, then calls onDone. */
    public static void refreshCloud(SharedPreferences prefs, String uid, Runnable onDone) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        int[] waiting = {2};
        Runnable one = () -> {
            if (--waiting[0] == 0 && onDone != null) onDone.run();
        };

        db.collection("donors").document(uid).get()
                .addOnSuccessListener(doc -> {
                    // an "empty" answer that came only from a cold cache must not erase what we know
                    if (!(doc.getMetadata().isFromCache() && !doc.exists())) {
                        prefs.edit().putBoolean(donorKey(uid), doc.exists()).apply();
                    }
                    one.run();
                })
                .addOnFailureListener(e -> one.run());

        db.collection("users").document(uid).collection("myHospitals").limit(1).get()
                .addOnSuccessListener(snap -> {
                    if (!(snap.getMetadata().isFromCache() && snap.isEmpty())) {
                        prefs.edit().putBoolean(hospitalKey(uid), !snap.isEmpty()).apply();
                    }
                    one.run();
                })
                .addOnFailureListener(e -> one.run());
    }

    public static void clear(SharedPreferences prefs, String uid) {
        prefs.edit().remove(donorKey(uid)).remove(hospitalKey(uid)).apply();
    }
}
