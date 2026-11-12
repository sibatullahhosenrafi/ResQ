package com.example.resq;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "I am safe": tells My Circle (SMS) and the chat rooms that the emergency is over.
 * It uses the same progress notification and delivery tracking as the SOS.
 */
public final class SafeMessage {

    private SafeMessage() { }

    /** The SMS. Plain English letters only, so it stays one short message (no Unicode, 160 characters per part). */
    public static String smsText(String name, String time) {
        String s = "I am SAFE now. Please ignore my earlier SOS, no more help is needed. "
                + "My live location sharing has stopped. Time: " + time;
        if (name != null && !name.isEmpty()) s += " - " + name;
        return s + " (ResQ)";
    }

    /** The chat message. */
    public static String chatText(String time) {
        return "\u2705 I am SAFE now.\n"
                + "Please ignore my earlier SOS, no more help is needed.\n"
                + "Live location sharing has stopped.\n"
                + "Time: " + time;
    }

    /** @return false if there was nobody to tell (nothing was sent) */
    public static boolean send(Context ctx) {
        Context app = ctx.getApplicationContext();
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return false;
        SharedPreferences prefs = app.getSharedPreferences("resq", Context.MODE_PRIVATE);

        List<CircleContact> contacts = new ArrayList<>(CircleStore.load(prefs, u.getUid()));
        boolean chatOn = prefs.getBoolean(MainActivity.PREF_SOS_TO_CHAT, true);

        boolean smsAllowed = ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
        if (!smsAllowed && !contacts.isEmpty()) {
            Toast.makeText(app, "Allow the SMS permission in Settings so ResQ can tell your contacts you are safe",
                    Toast.LENGTH_LONG).show();
            contacts.clear();
        }
        if (contacts.isEmpty() && !chatOn) return false;

        MedicalProfile med = MedicalProfile.load(prefs);
        String name = med.name == null ? "" : med.name.trim();
        if (name.isEmpty() && u.getDisplayName() != null) name = u.getDisplayName().trim();
        String time = new SimpleDateFormat("h:mm a, dd MMM", Locale.ENGLISH).format(new Date());

        long id = SosNotifier.begin(app, contacts.size(), chatOn, true);
        if (!contacts.isEmpty()) SmsSender.sendToAll(app, contacts, smsText(name, time), id);
        if (chatOn) ChatSos.postSafe(app, id, chatText(time));
        return true;
    }
}
