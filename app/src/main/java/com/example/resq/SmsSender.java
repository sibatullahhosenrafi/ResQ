package com.example.resq;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.List;

/** Sends one text to many contacts, each with its own sent / delivered report (used by SOS and "I am safe"). */
public final class SmsSender {

    private SmsSender() { }

    /** Uses the SIM that is set as the default for SMS (important on dual-SIM phones). */
    @SuppressWarnings("deprecation")
    public static SmsManager smsManager(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SmsManager m = ctx.getSystemService(SmsManager.class);
            if (m != null) return m;
        }
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return SmsManager.getSmsManagerForSubscriptionId(subId);
        }
        return SmsManager.getDefault();
    }

    /** Sends the same message to every contact. Results go to SosNotifier (progress notification). */
    public static void sendToAll(Context ctx, List<CircleContact> targets, String message, long sosId) {
        SmsManager sms = smsManager(ctx);
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        // a long message is sent in parts
        ArrayList<String> parts = sms.divideMessage(message);

        for (int i = 0; i < targets.size(); i++) {
            CircleContact c = targets.get(i);
            try {
                // The data Uri makes every message / contact PendingIntent unique, so a late report of an
                // older message can never be mixed into this one. An explicit receiver class is used
                // because an intent with data would not match the manifest filter.
                PendingIntent sentPI = PendingIntent.getBroadcast(ctx, 100 + i * 2,
                        new Intent(ctx, SmsResultReceiver.class)
                                .setAction(SosNotifier.ACTION_SMS_SENT)
                                .setData(Uri.parse("resq://sos/" + sosId + "/sent/" + i))
                                .putExtra(SosNotifier.EXTRA_SOS_ID, sosId)
                                .putExtra(SosNotifier.EXTRA_TO, c.name)
                                .putExtra(SosNotifier.EXTRA_NUMBER, c.phone)
                                .putExtra(SosNotifier.EXTRA_MESSAGE, message),
                        piFlags);
                PendingIntent deliveredPI = PendingIntent.getBroadcast(ctx, 101 + i * 2,
                        new Intent(ctx, SmsResultReceiver.class)
                                .setAction(SosNotifier.ACTION_SMS_DELIVERED)
                                .setData(Uri.parse("resq://sos/" + sosId + "/delivered/" + i))
                                .putExtra(SosNotifier.EXTRA_SOS_ID, sosId)
                                .putExtra(SosNotifier.EXTRA_TO, c.name)
                                .putExtra(SosNotifier.EXTRA_NUMBER, c.phone),
                        piFlags);
                ArrayList<PendingIntent> sentList = new ArrayList<>();
                ArrayList<PendingIntent> deliveredList = new ArrayList<>();
                for (int k = 0; k < parts.size(); k++) {
                    boolean last = (k == parts.size() - 1);
                    sentList.add(last ? sentPI : null);
                    deliveredList.add(last ? deliveredPI : null);
                }
                sms.sendMultipartTextMessage(c.phone, null, parts, sentList, deliveredList);
            } catch (Exception e) {
                SosNotifier.onSent(ctx, sosId, c.name, c.phone, message, false,
                        "could not start sending (" + e.getMessage() + ")");
            }
        }
    }
}
