package com.example.resq;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Profile picture helper. No Firebase Storage needed:
 *   picture -> square crop -> small JPEG -> Base64 text -> saved as a String in Firestore.
 *
 * Two sizes:
 *   FULL  (256 px, ~15-25 KB)  -> users/{uid}.photoBase64        (Profile screen)
 *   THUMB (96 px,  ~3-6 KB)    -> chatRooms/{code}/members/{uid}.photo  (chat bubbles)
 * A Firestore document can hold 1 MB, so both are far below the limit.
 */
public final class AvatarUtil {

    public static final int FULL_SIZE = 256;
    public static final int THUMB_SIZE = 96;
    /** Local copy of my own picture (so it shows instantly and offline). */
    public static final String PREF_PHOTO = "my_photo_b64";

    private AvatarUtil() {}

    // Decoded pictures are kept in memory, so scrolling the chat never decodes the same text twice.
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(40) { };

    // ------------------------------------------------------------ encode

    /** Reads the picked image and returns a Base64 JPEG (size x size), or null if it cannot be read. */
    public static String encodeFromUri(Context ctx, Uri uri, int size) {
        try {
            // 1) read only the size first, then load a smaller copy (saves memory)
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap src;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in, null, opts);
            }
            if (src == null) return null;
            src = fixRotation(ctx, uri, src);   // camera photos are often stored sideways

            // 2) centre-crop to a square and scale to size x size
            int side = Math.min(src.getWidth(), src.getHeight());
            int x = (src.getWidth() - side) / 2;
            int y = (src.getHeight() - side) / 2;
            Bitmap square = Bitmap.createBitmap(src, x, y, side, side);
            Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);

            // 3) JPEG -> Base64 (NO_WRAP: no line breaks inside the string)
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Turns the picture upright using the EXIF orientation tag (phone cameras need this). */
    private static Bitmap fixRotation(Context ctx, Uri uri, Bitmap src) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return src;
            int o = new android.media.ExifInterface(in).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            android.graphics.Matrix m = new android.graphics.Matrix();
            switch (o) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:  m.postRotate(90);  break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
                case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:   m.postScale(1, -1); break;
                default: return src;
            }
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Exception | OutOfMemoryError e) {
            return src;      // if anything goes wrong, use the picture as it is
        }
    }

    /** Makes the small chat version out of the big Base64 picture. */
    public static String thumbFrom(String fullBase64) {
        Bitmap b = decode(fullBase64);
        if (b == null) return "";
        Bitmap t = Bitmap.createScaledBitmap(b, THUMB_SIZE, THUMB_SIZE, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.compress(Bitmap.CompressFormat.JPEG, 70, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------ decode

    public static Bitmap decode(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        Bitmap cached = CACHE.get(base64);
        if (cached != null) return cached;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (b != null) CACHE.put(base64, b);
            return b;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Shows the picture in the view; if there is none, shows a coloured circle with the first letter. */
    public static void show(ImageView view, String base64, String name) {
        Bitmap b = decode(base64);
        if (b != null) {
            view.setImageBitmap(b);
        } else {
            view.setImageBitmap(placeholder(name));
        }
    }

    /** Round-looking letter placeholder (the view itself clips it to a circle). */
    private static Bitmap placeholder(String name) {
        int size = 128;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.parseColor("#C8202F"));     // brand_primary
        c.drawRect(0, 0, size, size, bg);

        String letter = "?";
        if (name != null && !name.trim().isEmpty()) {
            letter = name.trim().substring(0, 1).toUpperCase();
        }
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTextSize(size * 0.5f);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextAlign(Paint.Align.CENTER);
        float y = size / 2f - (tp.descent() + tp.ascent()) / 2f;
        c.drawText(letter, size / 2f, y, tp);
        return bmp;
    }

    // ------------------------------------------------------------ local copy

    public static String loadLocal(SharedPreferences prefs) {
        return prefs.getString(PREF_PHOTO, "");
    }

    public static void saveLocal(SharedPreferences prefs, String base64) {
        prefs.edit().putString(PREF_PHOTO, base64 == null ? "" : base64).apply();
    }

    public static void clearLocal(SharedPreferences prefs) {
        prefs.edit().remove(PREF_PHOTO).apply();
    }
}
