package com.example.resq;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.resq.databinding.ActivityAddHospitalBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Add the user's own hospital from Google Maps.
 *  - Google Maps -> Share -> ResQ   (this screen receives the shared text)
 *  - or paste the shared text / link / coordinates.
 * Saved privately in Firestore: users/{uid}/myHospitals/{id}.
 */
public class AddHospitalActivity extends AppCompatActivity {

    private ActivityAddHospitalBinding binding;
    private CollectionReference myRef;

    // ---------- patterns used to find coordinates ----------
    private static final String NUM = "(-?\\d{1,3}\\.\\d+)";
    private static final Pattern[] COORD_PATTERNS = {
            Pattern.compile("@" + NUM + "," + NUM),                                    // .../@22.81,89.55,17z
            Pattern.compile("!3d" + NUM + "!4d" + NUM),                                // ...!3d22.81!4d89.55
            Pattern.compile("[?&](?:q|ll|query|center|destination)=" + NUM + "(?:,|%2C)" + NUM) // ?q=22.81,89.55
    };
    private static final Pattern PLAIN_PAIR = Pattern.compile(NUM + "\\s*[,;\\s]\\s*" + NUM);
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddHospitalBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        NavHelper.setup(this, R.id.nav_hospitals, false);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Please log in to ResQ first, then share again.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        myRef = FirebaseFirestore.getInstance()
                .collection("users").document(user.getUid()).collection("myHospitals");

        binding.btnRead.setOnClickListener(v -> readFromText());
        binding.btnSave.setOnClickListener(v -> save());

        // Rotation: bring the status line back, and do NOT read the shared link a second time
        if (savedInstanceState != null) {
            String status = savedInstanceState.getString(S_STATUS);
            if (status != null) setStatus(status);
            return;
        }

        // Opened from Google Maps with "Share -> ResQ"
        Intent in = getIntent();
        if (Intent.ACTION_SEND.equals(in.getAction()) && in.getStringExtra(Intent.EXTRA_TEXT) != null) {
            binding.etLink.setText(in.getStringExtra(Intent.EXTRA_TEXT));
            readFromText();
        }
    }

    private static final String S_STATUS = "state_read_status";

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(S_STATUS, binding.tvReadStatus.getText().toString());
    }

    // ================= reading the shared text =================
    /** Values this screen filled in itself, so a new read may replace them (but never the user's typing). */
    private final java.util.Map<android.widget.EditText, String> autoVal = new java.util.HashMap<>();

    private void setAuto(android.widget.EditText et, String v) {
        if (v == null || v.trim().isEmpty()) return;
        String cur = et.getText().toString().trim();
        String last = autoVal.get(et);
        if (cur.isEmpty() || cur.equals(last)) {
            et.setText(v.trim());
            autoVal.put(et, v.trim());
        }
    }

    private void readFromText() {
        final String text = binding.etLink.getText().toString().trim();
        binding.tilLink.setError(null);
        if (text.isEmpty()) {
            binding.tilLink.setError("Paste the shared text, link or coordinates first");
            return;
        }

        // 1) name and address from the shared text lines (Google puts them first)
        String name = "";
        StringBuilder address = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || URL_PATTERN.matcher(t).find()) continue;
            if (parsePlain(t) != null) continue;               // a line with only coordinates
            if (name.isEmpty()) name = t;
            else address.append(address.length() == 0 ? "" : ", ").append(t);
        }
        setAuto(binding.etName, name);
        setAuto(binding.etAddress, address.toString());

        // 2) coordinates straight from the text (no internet needed)
        double[] c = parseCoords(text);
        if (c == null) c = parsePlain(text);
        final double[] textCoords = c;
        if (textCoords != null) setCoords(textCoords);

        // 3) a Google link, if there is one
        Matcher m = URL_PATTERN.matcher(text);
        final String url = m.find() ? m.group().replaceAll("[)>.,;]+$", "") : null;
        final String nameNow = binding.etName.getText().toString().trim();

        if (url == null && textCoords == null && nameNow.isEmpty()) {
            setStatus("No link or coordinates found. In Google Maps, long-press the hospital pin and paste the numbers shown at the top.");
            return;
        }
        if (!NetUtil.isOnline(this)) {
            setStatus(textCoords != null
                    ? "Location found in the text. Internet is needed to fill the address and phone."
                    : "Reading a Google Maps link needs internet. Or paste the coordinates instead.");
            return;
        }
        binding.progress.setVisibility(View.VISIBLE);
        setStatus("Reading the hospital...");

        new Thread(() -> {
            double[] found = textCoords;
            String placeName = "";
            try {
                if (url != null) {
                    String finalUrl = unwrapConsent(resolveRedirects(url));
                    if (found == null) found = parseCoords(finalUrl);
                    placeName = nameFromUrl(finalUrl);
                    boolean needName = nameNow.isEmpty() && placeName.isEmpty();
                    if ((found == null || needName) && isAllowed(new URL(finalUrl))) {
                        String body = fetchBody(finalUrl);          // look inside the Google page
                        if (found == null) {
                            found = parseCoords(body);
                            if (found == null) found = parseBodyCoords(body);
                        }
                        if (needName) placeName = nameFromPage(body);
                    }
                }
            } catch (Exception ignored) {
                // keep whatever was found so far
            }

            // Address / phone / missing name / missing coordinates from OpenStreetMap
            String lookupName = !nameNow.isEmpty() ? nameNow : placeName;
            String address2 = "";
            String phone = "";
            String osmName = "";
            boolean approximate = false;
            try {
                if (found != null) {
                    String[] r = osmReverse(found[0], found[1], lookupName);
                    address2 = r[0];
                    phone = r[1];
                    osmName = r[2];
                    if (phone.isEmpty() && !lookupName.isEmpty()) {
                        Object[] sr = osmSearch(lookupName, found);
                        if (sr != null) phone = (String) sr[3];
                    }
                } else if (!lookupName.isEmpty()) {
                    Object[] sr = osmSearch(lookupName, null);
                    if (sr != null) {
                        found = new double[]{(Double) sr[0], (Double) sr[1]};
                        address2 = (String) sr[2];
                        phone = (String) sr[3];
                        approximate = true;
                    }
                }
            } catch (Exception ignored) {
                // optional extras: ignore errors
            }
            // phone's own geocoder: by name if there are still no coordinates
            if (found == null && !lookupName.isEmpty()) {
                double[] g = geocoderByName(lookupName);
                if (g != null) {
                    found = g;
                    approximate = true;
                }
            }
            if (address2.isEmpty() && found != null) address2 = geocoderAddress(found[0], found[1]);

            final double[] result = found;
            final String finalName = !placeName.isEmpty() ? placeName : osmName;
            final String foundAddress = address2;
            final String foundPhone = phone;
            final boolean approx = approximate;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                binding.progress.setVisibility(View.GONE);
                setAuto(binding.etName, finalName);
                setAuto(binding.etAddress, foundAddress);
                setAuto(binding.etPhone, foundPhone);
                if (result != null) {
                    setCoords(result);
                    String msg = approx
                            ? "Found by the hospital name. Please check the location, then save."
                            : "Location found. Please check it and save.";
                    if (foundPhone.isEmpty()) msg += " No phone number was available, you can type it.";
                    setStatus(msg);
                } else {
                    setStatus("Could not find the location. Check the hospital name, or paste the coordinates (like 22.81310, 89.55628).");
                }
            });
        }).start();
    }

    private void setCoords(double[] c) {
        binding.etLat.setText(String.format(java.util.Locale.US, "%.6f", c[0]));
        binding.etLng.setText(String.format(java.util.Locale.US, "%.6f", c[1]));
    }

    private void setStatus(String s) {
        binding.tvReadStatus.setText(s);
    }

    // ================= saving =================
    private void save() {
        String name = binding.etName.getText().toString().trim();
        String address = binding.etAddress.getText().toString().trim();
        String phone = CircleStore.normalize(binding.etPhone.getText().toString());

        binding.tilName.setError(null);
        binding.tilPhone.setError(null);
        binding.tilLat.setError(null);
        binding.tilLng.setError(null);

        if (name.isEmpty()) {
            binding.tilName.setError("Enter the hospital name");
            return;
        }
        if (!phone.isEmpty() && !CircleStore.isValidPhone(phone)) {
            binding.tilPhone.setError("Enter a valid phone number or leave it empty");
            return;
        }
        double lat, lng;
        try {
            lat = Double.parseDouble(binding.etLat.getText().toString().trim());
        } catch (Exception e) {
            binding.tilLat.setError("Enter the latitude (a number)");
            return;
        }
        try {
            lng = Double.parseDouble(binding.etLng.getText().toString().trim());
        } catch (Exception e) {
            binding.tilLng.setError("Enter the longitude (a number)");
            return;
        }
        if (!validCoords(lat, lng)) {
            binding.tilLat.setError("Latitude must be -90 to 90 and longitude -180 to 180");
            return;
        }

        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("address", address);
        map.put("phone", phone);
        map.put("lat", lat);
        map.put("lng", lng);
        map.put("emergency", binding.switchEmergency.isChecked());
        map.put("createdAt", System.currentTimeMillis());

        // Firestore queues this write and syncs it when the internet returns
        final Context app = getApplicationContext();
        myRef.add(map).addOnFailureListener(e ->
                Toast.makeText(app, "Could not save the hospital: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
        Toast.makeText(this, name + " saved to your hospitals", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ================= helpers: parsing =================
    private static boolean validCoords(double lat, double lng) {
        return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    /** Finds coordinates inside a Google Maps link or page. Returns {lat, lng} or null. */
    static double[] parseCoords(String s) {
        if (s == null) return null;
        for (Pattern p : COORD_PATTERNS) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                double lat = Double.parseDouble(m.group(1));
                double lng = Double.parseDouble(m.group(2));
                if (validCoords(lat, lng)) return new double[]{lat, lng};
            }
        }
        return null;
    }

    /** Pasted coordinates such as "22.81310, 89.55628". */
    static double[] parsePlain(String s) {
        if (s == null) return null;
        Matcher m = PLAIN_PAIR.matcher(s.trim());
        if (m.matches()) {
            double lat = Double.parseDouble(m.group(1));
            double lng = Double.parseDouble(m.group(2));
            if (validCoords(lat, lng)) return new double[]{lat, lng};
        }
        // a line that contains coordinates somewhere inside
        Matcher f = PLAIN_PAIR.matcher(s);
        if (f.find() && s.length() < 60) {
            double lat = Double.parseDouble(f.group(1));
            double lng = Double.parseDouble(f.group(2));
            if (validCoords(lat, lng)) return new double[]{lat, lng};
        }
        return null;
    }

    /** ".../maps/place/Hospital+Name/@..." or "?q=Hospital+Name" gives the name. */
    private static String nameFromUrl(String url) {
        Matcher m = Pattern.compile("/maps/place/([^/@?]+)").matcher(url);
        if (m.find()) {
            String n = Uri.decode(m.group(1).replace('+', ' ')).trim();
            if (parsePlain(n) == null) return n;
        }
        try {
            Uri u = Uri.parse(url);
            for (String key : new String[]{"q", "query"}) {
                String q = u.getQueryParameter(key);
                if (q != null && !q.trim().isEmpty() && parsePlain(q) == null
                        && !q.toLowerCase(java.util.Locale.ROOT).startsWith("loc:")) {
                    return q.trim();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** The place name written in the title of a Google Maps page. */
    private static String nameFromPage(String body) {
        if (body == null) return "";
        Matcher m = Pattern.compile("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").matcher(body);
        String t = "";
        if (m.find()) t = m.group(1);
        else {
            Matcher t2 = Pattern.compile("<title>([^<]+)</title>").matcher(body);
            if (t2.find()) t = t2.group(1);
        }
        t = t.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replaceAll("\\s*[-\u2013\u00b7]\\s*Google\\s*Maps\\s*$", "").trim();
        if (t.equalsIgnoreCase("Google Maps") || parsePlain(t) != null) return "";
        return t;
    }

    // ================= helpers: extra lookups =================
    /** Google sometimes wraps the link in a consent page: take the real address from it. */
    private static String unwrapConsent(String url) {
        try {
            URL u = new URL(url);
            if (u.getHost() != null && u.getHost().startsWith("consent.")) {
                String cont = Uri.parse(url).getQueryParameter("continue");
                if (cont != null && !cont.isEmpty()) return cont;
            }
        } catch (Exception ignored) { }
        return url;
    }

    /** Coordinates written as [null,null,lat,lng] inside a Google Maps page. */
    private static double[] parseBodyCoords(String body) {
        if (body == null) return null;
        Matcher m = Pattern.compile("\\[null,null," + NUM + "," + NUM + "\\]").matcher(body);
        if (m.find()) {
            double lat = Double.parseDouble(m.group(1));
            double lng = Double.parseDouble(m.group(2));
            if (validCoords(lat, lng)) return new double[]{lat, lng};
        }
        return null;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "ResQ-Android/1.0 (emergency app)");
        c.setRequestProperty("Accept-Language", "en");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 200_000) sb.append(buf, 0, n);
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }

    private static final String[] GENERIC = {"hospital", "medical", "college", "clinic", "centre",
            "center", "and", "the", "general", "diagnostic", "health"};

    /** True when two names share a meaningful word (so a phone number belongs to this hospital). */
    private static boolean sameName(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        java.util.Set<String> ta = tokens(a);
        for (String t : tokens(b)) if (ta.contains(t)) return true;
        return false;
    }

    private static java.util.Set<String> tokens(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String t : s.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() < 3) continue;
            boolean generic = false;
            for (String g : GENERIC) if (g.equals(t)) generic = true;
            if (!generic) out.add(t);
        }
        return out;
    }

    private static String cleanPhone(org.json.JSONObject extra) {
        if (extra == null) return "";
        String p = extra.optString("phone", extra.optString("contact:phone",
                extra.optString("contact:mobile", "")));
        if (p.contains(";")) p = p.substring(0, p.indexOf(';'));
        return p.trim();
    }

    /** OpenStreetMap: {address, phone, name} of the place at these coordinates. */
    private static String[] osmReverse(double lat, double lng, String expectName) throws Exception {
        String json = httpGet(String.format(java.util.Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&extratags=1",
                lat, lng));
        org.json.JSONObject o = new org.json.JSONObject(json);
        String address = o.optString("display_name", "");
        String osmName = o.optString("name", "");
        String cat = o.optString("category", "");
        boolean isPlace = cat.equals("amenity") || cat.equals("healthcare");
        boolean noName = expectName == null || expectName.isEmpty();
        String phone = "";
        if ((noName && isPlace) || sameName(osmName, expectName)) {
            phone = cleanPhone(o.optJSONObject("extratags"));
        }
        return new String[]{address, phone, (noName && isPlace) ? osmName : ""};
    }

    /** OpenStreetMap: search by name. Returns {lat, lng, address, phone} or null. */
    private static Object[] osmSearch(String name, double[] near) throws Exception {
        StringBuilder q = new StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=3&extratags=1&q=")
                .append(Uri.encode(name));
        if (near != null) {
            q.append(String.format(java.util.Locale.US, "&viewbox=%.5f,%.5f,%.5f,%.5f&bounded=1",
                    near[1] - 0.01, near[0] + 0.01, near[1] + 0.01, near[0] - 0.01));
        } else {
            q.append("&countrycodes=bd");
        }
        org.json.JSONArray arr = new org.json.JSONArray(httpGet(q.toString()));
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.getJSONObject(i);
            if (!sameName(o.optString("name", ""), name) && !sameName(o.optString("display_name", ""), name)) continue;
            double la = Double.parseDouble(o.getString("lat"));
            double lo = Double.parseDouble(o.getString("lon"));
            if (!validCoords(la, lo)) continue;
            return new Object[]{la, lo, o.optString("display_name", ""), cleanPhone(o.optJSONObject("extratags"))};
        }
        return null;
    }

    /** Phone's own geocoder: coordinates from a hospital name. */
    private double[] geocoderByName(String name) {
        try {
            if (android.location.Geocoder.isPresent()) {
                java.util.List<android.location.Address> l =
                        new android.location.Geocoder(this, java.util.Locale.getDefault())
                                .getFromLocationName(name + ", Bangladesh", 1);
                if (l != null && !l.isEmpty() && validCoords(l.get(0).getLatitude(), l.get(0).getLongitude())) {
                    return new double[]{l.get(0).getLatitude(), l.get(0).getLongitude()};
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Phone's own geocoder as a last try for the address. */
    private String geocoderAddress(double lat, double lng) {
        try {
            if (android.location.Geocoder.isPresent()) {
                java.util.List<android.location.Address> l =
                        new android.location.Geocoder(this, java.util.Locale.getDefault()).getFromLocation(lat, lng, 1);
                if (l != null && !l.isEmpty() && l.get(0).getAddressLine(0) != null) {
                    return l.get(0).getAddressLine(0);
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    // ================= helpers: network (only Google addresses) =================
    /** Only Google Maps addresses may be opened, because other apps can send us any text. */
    private static boolean isAllowed(URL u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        return "https".equals(u.getProtocol())
                && host.matches("(.+\\.)?(google\\.[a-z.]{2,}|goo\\.gl)");
    }

    private static String resolveRedirects(String start) throws Exception {
        String current = start;
        for (int i = 0; i < 6; i++) {
            URL u = new URL(current);
            if (!isAllowed(u)) return current;
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (code >= 300 && code < 400 && loc != null) {
                current = new URL(u, loc).toString();
            } else {
                return current;
            }
        }
        return current;
    }

    private static String fetchBody(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 300_000) {
                sb.append(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }
}
