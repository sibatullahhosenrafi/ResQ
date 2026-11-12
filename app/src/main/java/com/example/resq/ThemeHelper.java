package com.example.resq;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Light / dark mode for the whole app. The choice is saved on the phone and applied when the app
 * starts (see ResQApp). The default is the normal light look until the user taps the sun/moon toggle.
 */
public final class ThemeHelper {

    private static final String PREFS = "resq";            // same file the other settings use
    private static final String PREF_DARK = "dark_mode";

    private ThemeHelper() { }

    public static boolean isDark(Context ctx) {
        return prefs(ctx).getBoolean(PREF_DARK, false);
    }

    /** Applies the saved choice to every screen (call once at app start). */
    public static void apply(Context ctx) {
        AppCompatDelegate.setDefaultNightMode(isDark(ctx)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    /** Saves the choice and switches every open screen to it. */
    public static void setDark(Context ctx, boolean dark) {
        prefs(ctx).edit().putBoolean(PREF_DARK, dark).apply();
        apply(ctx);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
