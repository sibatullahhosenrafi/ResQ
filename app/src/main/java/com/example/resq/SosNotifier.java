package com.example.resq;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ONE live progress notification for the whole SOS:
 *
 *   Sending SOS...
 *   My Circle: 2 of 3 sent, 1 delivered
 *   Chat rooms: 1 of 2 delivered
 *
 * It stays on top (ongoing, with a progress bar) until every contact has a delivery report and every
 * chat room has really been written to the server. Some carriers never send delivery reports, so after
 * DELIVERY_WAIT_MS the notification stops waiting and shows an honest final result instead of a bar that
 * never ends. A contact whose SMS failed gets its own heads-up notification with a "send from Messages" tap.
 *
 * Progress bar = (sent + delivered) for each contact + one step per chat room.
 * Everything appears in the status bar, the drawer, as a heads-up pop-up and on the lock screen
 * (the lock screen shows only counts, never names).
 */
public final class SosNotifier {

    public static final String ACTION_SMS_SENT = "com.example.resq.SMS_SENT";
    public static final String ACTION_SMS_DELIVERED = "com.example.resq.SMS_DELIVERED";
    public static final String ACTION_SOS_TIMEOUT = "com.example.resq.SOS_TIMEOUT";
    public static final String EXTRA_TO = "to_name";
    public static final String EXTRA_NUMBER = "to_number";
    public static final String EXTRA_MESSAGE = "sos_message";
    /** Which SOS a report belongs to, so a late report of an old SOS never changes the new one. */
    public static final String EXTRA_SOS_ID = "sos_id";

    /** Profile switch: may the STOP buttons work while the phone is locked? (default: no) */
    public static final String PREF_LOCK_STOP = "lockscreen_stop";

    /** How long we wait for delivery reports / chat confirmation before showing the final result. */
    private static final long DELIVERY_WAIT_MS = 60_000L;

    private static final String CHANNEL_ID = "sos_result_channel";            // final result (vibrates)
    private static final String CHANNEL_PROGRESS = "sos_progress_channel";    // live progress (silent pop-ups)
    private static final int ID_SUMMARY = 3101;     // live progress
    private static final int ID_RESULT = 3102;      // final result
    private static final int ID_TIMEOUT_PI = 3300;
    private static final int ID_FAIL_BASE = 3200;

    private static final String K_ID = "sos_id";
    private static final String K_KIND = "sos_kind";     // "sos" or "safe" (the "I am safe" message)
    private static final String K_DONE = "sos_done";
    private static final String K_TOTAL = "sos_total";
    private static final String K_OK = "sos_ok";
    private static final String K_FAIL = "sos_fail";
    private static final String K_DELIVERED = "sos_delivered";
    private static final String K_DELIVERED_KEYS = "sos_delivered_keys";   // which contacts already confirmed
    private static final String K_NAMES = "sos_failed_names";
    private static final String K_CHAT_ON = "sos_chat_on";
    private static final String K_CHAT_KNOWN = "sos_chat_known";     // do we know how many rooms there are?
    private static final String K_CHAT_TOTAL = "sos_chat_total";
    private static final String K_CHAT_OK = "sos_chat_ok";            // confirmed by the server
    private static final String K_CHAT_FAIL = "sos_chat_fail";
    private static final String K_CHAT_NOTE = "sos_chat_note";

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private SosNotifier() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("resq", Context.MODE_PRIVATE);
    }

    // ================= the state of the current SOS =================
    private static final class State {
        int total, ok, fail, delivered, chatTotal, chatOk, chatFail;
        boolean chatOn, chatKnown;
        String names, chatNote;

        static State read(SharedPreferences p) {
            State s = new State();
            s.total = p.getInt(K_TOTAL, 0);
            s.ok = p.getInt(K_OK, 0);
            s.fail = p.getInt(K_FAIL, 0);
            s.delivered = Math.min(p.getInt(K_DELIVERED, 0), s.ok);
            s.names = p.getString(K_NAMES, "");
            s.chatOn = p.getBoolean(K_CHAT_ON, false);
            s.chatKnown = p.getBoolean(K_CHAT_KNOWN, true);
            s.chatTotal = p.getInt(K_CHAT_TOTAL, 0);
            s.chatOk = p.getInt(K_CHAT_OK, 0);
            s.chatFail = p.getInt(K_CHAT_FAIL, 0);
            s.chatNote = p.getString(K_CHAT_NOTE, "");
            return s;
        }

        int waitingToSend() { return Math.max(0, total - ok - fail); }
        int chatWaiting() { return Math.max(0, chatTotal - chatOk - chatFail); }

        /** Every contact is either failed, or sent AND confirmed delivered. */
        boolean contactsDone() { return waitingToSend() == 0 && delivered >= ok; }

        boolean chatDone() { return !chatOn || (chatKnown && chatWaiting() == 0); }
    }

    private static boolean isSafe(Context ctx) {
        return "safe".equals(prefs(ctx).getString(K_KIND, "sos"));
    }

    /** "SOS" or "Safe message", for the titles. */
    private static String noun(Context ctx) {
        return isSafe(ctx) ? "Safe message" : "SOS";
    }

    private static boolean isStale(SharedPreferences p, long sosId) {
        return sosId != 0 && sosId != p.getLong(K_ID, 0);
    }

    // ================= called while an SOS is sent =================

    /**
     * A new SOS starts: reset everything and show the progress notification.
     *
     * @param contacts number of My Circle contacts the SMS goes to
     * @param chatOn   true if the SOS is also posted into the chat rooms
     * @return the id of this SOS (pass it to the SMS intents and to ChatSos)
     */
    public static long begin(Context ctx, int contacts, boolean chatOn) {
        return begin(ctx, contacts, chatOn, false);
    }

    /** safe = true: this is the "I am safe" message, so the texts say "safe message" instead of "SOS". */
    public static long begin(Context ctx, int contacts, boolean chatOn, boolean safe) {
        Context app = ctx.getApplicationContext();
        long id = System.currentTimeMillis();
        prefs(app).edit()
                .putLong(K_ID, id).putBoolean(K_DONE, false).putString(K_KIND, safe ? "safe" : "sos")
                .putInt(K_TOTAL, contacts).putInt(K_OK, 0).putInt(K_FAIL, 0)
                .putInt(K_DELIVERED, 0).remove(K_DELIVERED_KEYS).putString(K_NAMES, "")
                .putBoolean(K_CHAT_ON, chatOn).putBoolean(K_CHAT_KNOWN, !chatOn)
                .putInt(K_CHAT_TOTAL, 0).putInt(K_CHAT_OK, 0).putInt(K_CHAT_FAIL, 0)
                .putString(K_CHAT_NOTE, "")
                .apply();
        NotificationManagerCompat.from(app).cancel(ID_RESULT);   // remove the previous result
        scheduleTimeout(app, id);
        render(app);
        return id;
    }

    /** The phone reported the result of SENDING to one contact. */
    public static void onSent(Context ctx, long sosId, String name, String number, String message,
                              boolean ok, String reason) {
        SharedPreferences p = prefs(ctx);
        if (isStale(p, sosId)) return;
        if (name == null || name.isEmpty()) name = "your contact";
        if (ok) {
            p.edit().putInt(K_OK, p.getInt(K_OK, 0) + 1).apply();
        } else {
            String names = p.getString(K_NAMES, "");
            names = names.isEmpty() ? name : names + ", " + name;
            p.edit().putInt(K_FAIL, p.getInt(K_FAIL, 0) + 1).putString(K_NAMES, names).apply();
            showFailure(ctx, name, number, message, reason);
        }
        afterEvent(ctx);
    }

    /** A delivery report arrived (the contact's phone received the SMS). */
    public static void onDelivered(Context ctx, long sosId, String contactKey) {
        SharedPreferences p = prefs(ctx);
        if (isStale(p, sosId)) return;
        // Some networks send two delivery reports for one SMS (or one per part).
        // Count every CONTACT only once, so the text can never say "2 of 1".
        Set<String> keys = new HashSet<>(p.getStringSet(K_DELIVERED_KEYS, new HashSet<String>()));
        keys.add(contactKey == null ? "" : contactKey);
        p.edit().putStringSet(K_DELIVERED_KEYS, keys).putInt(K_DELIVERED, keys.size()).apply();
        afterEvent(ctx);
    }

    /** ChatSos found out how many chat rooms the SOS goes to (0 = none). */
    public static void chatRoomsFound(Context ctx, long sosId, int rooms) {
        SharedPreferences p = prefs(ctx);
        if (isStale(p, sosId)) return;
        p.edit().putBoolean(K_CHAT_KNOWN, true).putInt(K_CHAT_TOTAL, rooms).apply();
        afterEvent(ctx);
    }

    /** ChatSos could not even read the list of rooms. */
    public static void chatLookupFailed(Context ctx, long sosId, String reason) {
        SharedPreferences p = prefs(ctx);
        if (isStale(p, sosId)) return;
        p.edit().putBoolean(K_CHAT_KNOWN, true).putInt(K_CHAT_TOTAL, 0)
                .putString(K_CHAT_NOTE, reason).apply();
        afterEvent(ctx);
    }

    /** One chat room: ok = the SERVER confirmed the message (not just saved on the phone). */
    public static void chatRoomResult(Context ctx, long sosId, boolean ok) {
        SharedPreferences p = prefs(ctx);
        if (isStale(p, sosId)) return;
        p.edit().putInt(ok ? K_CHAT_OK : K_CHAT_FAIL, p.getInt(ok ? K_CHAT_OK : K_CHAT_FAIL, 0) + 1).apply();
        afterEvent(ctx);
    }

    /** The waiting time is over: show what we know (called by the alarm and by a timer). */
    public static void onTimeout(Context ctx, long sosId) {
        SharedPreferences p = prefs(ctx);
        if (isStale(p, sosId) || p.getBoolean(K_DONE, false)) return;
        finish(ctx);
    }

    // ================= deciding what to show =================

    private static void afterEvent(Context ctx) {
        if (prefs(ctx).getBoolean(K_DONE, false)) {
            // A late report after the final result: update that notification quietly
            if (isActive(ctx, ID_RESULT)) showFinal(ctx, false);
        } else {
            render(ctx);
        }
    }

    private static void render(Context ctx) {
        State s = State.read(prefs(ctx));
        if (s.contactsDone() && s.chatDone()) {
            finish(ctx);
        } else {
            showProgress(ctx, s);
        }
    }

    private static void finish(Context ctx) {
        prefs(ctx).edit().putBoolean(K_DONE, true).apply();
        cancelTimeout(ctx);
        NotificationManagerCompat.from(ctx).cancel(ID_SUMMARY);
        showFinal(ctx, true);
    }

    // ================= the notifications =================

    /** The live "Sending SOS..." notification with the progress bar. */
    private static void showProgress(Context ctx, State s) {
        ensureChannels(ctx);

        List<String> lines = new ArrayList<>();
        if (s.total > 0) {
            StringBuilder c = new StringBuilder("My Circle: ");
            c.append(s.ok).append(" of ").append(s.total).append(" sent");
            if (s.ok > 0) c.append(", ").append(s.delivered).append(" delivered");
            if (s.fail > 0) c.append(", ").append(s.fail).append(" failed");
            lines.add(c.toString());
        }
        if (s.chatOn) {
            if (!s.chatKnown) lines.add("Chat rooms: checking...");
            else if (s.chatTotal > 0) {
                lines.add("Chat rooms: " + s.chatOk + " of " + s.chatTotal + " delivered"
                        + (s.chatFail > 0 ? ", " + s.chatFail + " failed" : ""));
            }
        }
        if (s.total > 0 && s.waitingToSend() == 0 && s.ok > 0 && s.delivered < s.ok) {
            lines.add("Waiting for delivery reports (up to 1 minute)...");
        }
        if (lines.isEmpty()) lines.add("Please wait...");

        // contact: sent + delivered (a failed one is finished, so it counts for both)
        int max = Math.max(1, s.total * 2 + s.chatTotal);
        int done = s.ok + s.delivered + s.fail * 2 + s.chatOk + s.chatFail;

        String title = "Sending " + (isSafe(ctx) ? "safe message" : "SOS") + "...";
        String oneLine = join(lines, "  \u2022  ");
        Notification publicVersion = new NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText("Unlock your phone for details")
                .build();

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText(oneLine)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(join(lines, "\n")))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOngoing(true)                 // stays on top until the SOS is really delivered
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)          // every step pops up again (silently)
                .setProgress(max, Math.min(done, max), false)
                .setContentIntent(openApp(ctx))
                .build();
        post(ctx, ID_SUMMARY, n, null);     // no Toast for every step; the final result has one
    }

    /** The final result: what is confirmed and what is not. */
    private static void showFinal(Context ctx, boolean alert) {
        ensureChannels(ctx);
        State s = State.read(prefs(ctx));
        List<String> lines = new ArrayList<>();
        boolean problem = false;          // something was NOT sent / posted
        boolean reportsMissing = false;   // sent, but no delivery report

        if (s.total > 0) {
            int waiting = s.waitingToSend();
            if (s.fail == 0 && waiting == 0 && s.delivered >= s.ok) {
                lines.add(s.total == 1 ? "My Circle: delivered to your contact"
                        : "My Circle: delivered to all " + s.total + " contacts");
            } else {
                lines.add("My Circle: sent to " + s.ok + " of " + s.total
                        + (s.ok > 0 ? ", delivery confirmed for " + s.delivered : ""));
                if (s.fail > 0) {
                    problem = true;
                    lines.add("Not sent to: " + s.names);
                }
                if (waiting > 0) {
                    problem = true;
                    lines.add(waiting + " contact(s): the phone did not report back. Check SIM, signal and balance.");
                }
                if (s.delivered < s.ok) {
                    reportsMissing = true;
                    lines.add("No delivery report yet. The SMS was sent, but some carriers never send reports.");
                }
            }
        }

        if (s.chatOn) {
            if (!s.chatKnown) {
                problem = true;
                lines.add("Chat rooms: could not check your rooms");
            } else if (!s.chatNote.isEmpty()) {
                problem = true;
                lines.add("Chat rooms: " + s.chatNote);
            } else if (s.chatTotal > 0) {
                int waiting = s.chatWaiting();
                if (waiting == 0 && s.chatFail == 0) {
                    lines.add(s.chatTotal == 1 ? "Chat room: delivered"
                            : "Chat rooms: delivered to all " + s.chatTotal);
                } else {
                    problem = true;
                    String t = "Chat rooms: " + s.chatOk + " of " + s.chatTotal + " delivered";
                    if (waiting > 0) t += ". The rest is saved on your phone and posts when the internet is back.";
                    if (s.chatFail > 0) t += " " + s.chatFail + " rejected by the server.";
                    lines.add(t);
                }
            }
        }
        if (lines.isEmpty()) lines.add("Nothing was sent.");

        boolean anySuccess = s.ok > 0 || s.chatOk > 0;
        String title;
        String what = noun(ctx);
        if (!problem && !reportsMissing) title = what + " delivered";
        else if (!problem) title = what + " sent";
        else if (anySuccess) title = what + " partly delivered";
        else title = what + " NOT delivered";

        Notification publicVersion = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText("Unlock your phone for details")
                .build();

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(title)
                .setContentText(lines.get(0))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(join(lines, "\n")))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOnlyAlertOnce(!alert)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build();
        post(ctx, ID_RESULT, n, title + ". " + lines.get(0));
    }

    private static void showFailure(Context ctx, String name, String number, String message, String reason) {
        ensureChannels(ctx);
        String num = number == null ? "" : number;
        int id = ID_FAIL_BASE + (Math.abs(num.hashCode()) % 100);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent callPi = PendingIntent.getActivity(ctx, id + 1000,
                new Intent(Intent.ACTION_DIAL, Uri.parse("tel:999")), flags);

        // tapping opens the Messages app with this contact and the SOS text ready to send
        PendingIntent tapPi = openApp(ctx);
        if (!num.isEmpty()) {
            Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", num, null));
            sms.putExtra("sms_body", message == null ? "" : message);
            tapPi = PendingIntent.getActivity(ctx, id, sms, flags);
        }

        Notification publicVersion = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(noun(ctx) + " not sent")
                .setContentText("Unlock your phone to send it another way")
                .build();

        String text = "Reason: " + reason + ". Tap to send it from your Messages app.";
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sos)
                .setContentTitle(noun(ctx) + " NOT sent to " + name)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .setContentIntent(tapPi)
                .addAction(android.R.drawable.ic_menu_call, "CALL 999", callPi)
                .build();
        post(ctx, id, n, noun(ctx) + " NOT sent to " + name + ": " + reason);
    }

    // ================= the waiting time =================

    private static PendingIntent timeoutIntent(Context ctx, long sosId) {
        Intent i = new Intent(ctx, SmsResultReceiver.class)
                .setAction(ACTION_SOS_TIMEOUT)
                .putExtra(EXTRA_SOS_ID, sosId);
        return PendingIntent.getBroadcast(ctx, ID_TIMEOUT_PI, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * After DELIVERY_WAIT_MS the notification stops waiting. An alarm does it even if the app was closed;
     * a plain timer does it quickly while the app is running. Whichever comes first wins.
     */
    private static void scheduleTimeout(Context ctx, long sosId) {
        HANDLER.removeCallbacksAndMessages(null);
        HANDLER.postDelayed(() -> onTimeout(ctx, sosId), DELIVERY_WAIT_MS);
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + DELIVERY_WAIT_MS, timeoutIntent(ctx, sosId));
            }
        } catch (Exception ignored) {
            // the timer above still works while the app is running
        }
    }

    private static void cancelTimeout(Context ctx) {
        HANDLER.removeCallbacksAndMessages(null);
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(timeoutIntent(ctx, 0));
        } catch (Exception ignored) {
            // nothing to cancel
        }
    }

    private static String join(List<String> parts, String glue) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(glue);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ================= the STOP button used by the siren and live sharing =================
    /**
     * A STOP button for the siren / live-sharing notifications. On Android 12+ it asks for the
     * phone to be unlocked first, unless the user allowed it in Profile. That way someone who
     * grabs a locked phone cannot silence the siren or stop the sharing.
     */
    public static NotificationCompat.Action stopAction(Context ctx, String title, PendingIntent pi) {
        boolean allowFromLock = prefs(ctx).getBoolean(PREF_LOCK_STOP, false);
        return new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, title, pi)
                .setAuthenticationRequired(!allowFromLock)
                .build();
    }

    // ================= helpers =================
    private static PendingIntent openApp(Context ctx) {
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 3100, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SOS results", NotificationManager.IMPORTANCE_HIGH);   // HIGH = heads-up pop-up
            ch.setDescription("Tells you whether your SOS message was delivered");
            ch.enableVibration(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(ch);

            // live progress: pops up on every step, but without sound or vibration
            NotificationChannel pr = new NotificationChannel(
                    CHANNEL_PROGRESS, "SOS progress", NotificationManager.IMPORTANCE_HIGH);
            pr.setDescription("Shows the progress while your SOS is being delivered");
            pr.setSound(null, null);
            pr.enableVibration(false);
            pr.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(pr);
        }
    }

    private static boolean isActive(Context ctx, int id) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (sbn.getId() == id) return true;
        }
        return false;
    }

    /** Shows the notification; if notifications are switched off, a Toast tells the user instead. */
    @SuppressLint("MissingPermission")
    private static void post(Context ctx, int id, Notification n, String toastText) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        boolean shown = false;
        if (nm.areNotificationsEnabled()) {
            try {
                nm.notify(id, n);
                shown = true;
            } catch (SecurityException ignored) {
                // permission missing: fall back to the Toast below
            }
        }
        if (!shown && toastText != null) {
            Toast.makeText(ctx.getApplicationContext(), toastText, Toast.LENGTH_LONG).show();
        }
    }

    /** Plain-English reason for an SMS error code. */
    public static String errorText(int code) {
        switch (code) {
            case 1:   return "generic failure (check SIM, balance and SMS permission)";
            case 2:   return "airplane mode is on";
            case 3:   return "empty message";
            case 4:   return "no network signal";
            case 5:   return "Android SMS sending limit reached";
            case 16:  // modem error
            case 111: return "the phone's modem reported an error (usually weak signal or a SIM problem)";
            case 17:  // network error
            case 112: return "network error (usually weak signal)";
            case 100: return "the phone's radio is not available (airplane mode or no signal)";
            case 102: return "the network rejected the message";
            case 106: return "too many messages sent too quickly";
            case 120: return "no SIM card found";
            default:  return "error code " + code + " (the phone's SMS system could not send it)";
        }
    }
}
