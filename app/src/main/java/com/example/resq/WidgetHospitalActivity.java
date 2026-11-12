package com.example.resq;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * Invisible helper for the widget's Hospital tile. NearestHospital needs an Activity, so this
 * one has no screen: it starts the same "navigate to the nearest hospital" logic as the app's
 * button, then closes itself. (It never stays on screen longer than a few seconds.)
 */
public class WidgetHospitalActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // let touches go through to the home screen while we wait
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        NearestHospital.go(this);
        // NearestHospital gives up after 7 s and opens Google Maps; close just after that
        handler.postDelayed(this::finish, 8_500L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
