package com.example.resq;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-tap copy / paste for the tracking code (and other text). */
public final class CodeClipboard {

    /** "ResQ tracking code: K7M4Q2" (SOS SMS) or "Track live in ResQ: K7M4Q2" (chat / older SMS). */
    private static final Pattern SOS_LINE = Pattern.compile(
            "(?:Track live in ResQ|ResQ tracking code):\\s*([A-Za-z0-9]{" + TrackCode.LENGTH + "})", Pattern.CASE_INSENSITIVE);

    private CodeClipboard() { }

    /** Copies text. Android 13+ shows its own "Copied" preview, so only older phones get a Toast. */
    public static void copy(Context ctx, String label, String text, String toast) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || text == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        if (Build.VERSION.SDK_INT < 33 && toast != null) {
            Toast.makeText(ctx.getApplicationContext(), toast, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Finds a tracking code in text: either the whole text is the code,
     * or it is a whole SOS message that contains "ResQ tracking code: CODE" (or the older "Track live in ResQ: CODE"). Otherwise null.
     */
    public static String findCode(String text) {
        if (text == null) return null;
        String t = text.trim().toUpperCase(Locale.ROOT);
        if (TrackCode.isValid(t)) return t;
        Matcher m = SOS_LINE.matcher(text);
        if (m.find()) {
            String c = m.group(1).toUpperCase(Locale.ROOT);
            if (TrackCode.isValid(c)) return c;
        }
        return null;
    }

    /** The tracking code currently on the clipboard, or null. */
    public static String readCode(Context ctx) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) return null;
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
        return cs == null ? null : findCode(cs.toString());
    }
}
