package com.example.resq;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Registered in the manifest, so it still receives the SMS result when the app is in the
 * background or closed. It turns the result into the SOS progress notification.
 */
public class SmsResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long sosId = intent.getLongExtra(SosNotifier.EXTRA_SOS_ID, 0L);
        if (SosNotifier.ACTION_SMS_SENT.equals(action)) {
            int code = getResultCode();
            SosNotifier.onSent(context, sosId,
                    intent.getStringExtra(SosNotifier.EXTRA_TO),
                    intent.getStringExtra(SosNotifier.EXTRA_NUMBER),
                    intent.getStringExtra(SosNotifier.EXTRA_MESSAGE),
                    code == Activity.RESULT_OK,
                    SosNotifier.errorText(code));
        } else if (SosNotifier.ACTION_SMS_DELIVERED.equals(action)) {
            String number = intent.getStringExtra(SosNotifier.EXTRA_NUMBER);
            String name = intent.getStringExtra(SosNotifier.EXTRA_TO);
            SosNotifier.onDelivered(context, sosId, (number != null && !number.isEmpty()) ? number : name);
        } else if (SosNotifier.ACTION_SOS_TIMEOUT.equals(action)) {
            // stop waiting for delivery reports and show the final result
            SosNotifier.onTimeout(context, sosId);
        }
    }
}
