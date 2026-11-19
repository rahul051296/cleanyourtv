package io.github.rahul051296.plexeddata;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;

/**
 * The owner's Plex account, for the launcher's "Who's watching?": linked once with Plex's own TV sign-in
 * (a code entered at plex.tv/link, no password on the TV), then the Plex Home profiles and, for the one picked,
 * that profile's key to the home server. Every key stays in this app's private preferences; the launcher only
 * ever sees profile names, avatars and which one is selected.
 */
final class PlexAccount {
    private static final String TAG = "PlexAccount";
    static final String API = "https://plex.tv/api/v2";

    private PlexAccount() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("plex_account", Context.MODE_PRIVATE);
    }

    /** This install's client id for plex.tv, made once. */
    static String clientId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String id = p.getString("client_id", null);
        if (id == null) {
            id = "plexed-data-" + UUID.randomUUID();
            p.edit().putString("client_id", id).apply();
        }
        return id;
    }

    static boolean linked(Context ctx) {
        return !prefs(ctx).getString("owner_token", "").isEmpty();
    }

    // ---- linking: a short code the owner enters at plex.tv/link -------------------------------------------------

    /** Asks plex.tv for a new code; returns {id, code}. */
    static String[] newPin(Context ctx) throws Exception {
        JSONObject o = new JSONObject(request(ctx, "POST", API + "/pins?strong=false", null));
        String[] pin = {String.valueOf(o.getLong("id")), o.getString("code")};
        prefs(ctx).edit().putString("pin_id", pin[0]).apply();
        return pin;
    }

    /** True once the code was entered: the owner's key is saved and the profiles fetched. */
    static boolean checkPin(Context ctx) throws Exception {
        String id = prefs(ctx).getString("pin_id", "");
        if (id.isEmpty()) return false;
        JSONObject o = new JSONObject(request(ctx, "GET", API + "/pins/" + id, null));
        String token = o.optString("authToken", "");
        if (token.isEmpty() || "null".equals(token)) return false;
        prefs(ctx).edit().putString("owner_token", token).remove("pin_id").apply();
        refreshProfiles(ctx);
        return true;
    }

    // ---- profiles -----------------------------------------------------------------------------------------------

    /** Plex Home's profiles, saved as JSON [{uuid, name, pin, avatar}], with their avatars in files/avatars. */
    static void refreshProfiles(Context ctx) throws Exception {
        String owner = prefs(ctx).getString("owner_token", "");
        if (owner.isEmpty()) return;
        JSONObject home = new JSONObject(request(ctx, "GET", API + "/home/users", owner));
        JSONArray users = home.optJSONArray("users");
        JSONArray out = new JSONArray();
        for (int i = 0; users != null && i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            JSONObject p = new JSONObject();
            String uuid = u.getString("uuid");
            p.put("uuid", uuid);
            p.put("name", u.optString("title", u.optString("username", "Profile")));
            p.put("pin", u.optBoolean("protected"));
            p.put("admin", u.optBoolean("admin"));
            String thumb = u.optString("thumb", "");
            p.put("avatar", !thumb.isEmpty() && saveAvatar(ctx, uuid, thumb));
            out.put(p);
        }
        SharedPreferences.Editor e = prefs(ctx).edit().putString("profiles", out.toString())
                .putLong("profiles_updated", System.currentTimeMillis());
        // Until someone picks in Who's watching, the selected profile is the account that linked (its own uuid):
        // that is whose list the row already shows. Plex Home's "admin" can be another member of the Home.
        if (!prefs(ctx).getBoolean("picked", false)) {
            String me = new JSONObject(request(ctx, "GET", API + "/user", owner)).optString("uuid", "");
            if (!me.isEmpty()) e.putString("selected", me);
        }
        e.apply();
    }

    /** Refreshes names and avatars now and then (and once after an update), in the background. */
    static void refreshIfOld(Context ctx) {
        if (!linked(ctx) || System.currentTimeMillis() - prefs(ctx).getLong("profiles_updated", 0) < 12 * 3600_000L) return;
        prefs(ctx).edit().putLong("profiles_updated", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                refreshProfiles(ctx);
                ctx.getContentResolver().notifyChange(PlexedProvider.PROFILES_URI, null);
            } catch (Exception e) {
                Log.w(TAG, "refresh", e);
            }
        }).start();
    }

    /**
     * First names only ("Sam Lee" -> "Sam"); two profiles with the same first name keep the
     * initial of the next word too ("Sam L"), so they stay apart.
     */
    static java.util.List<String> firstNames(java.util.List<String> full) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (String n : full) {
            String first = first(n);
            count.put(first, count.getOrDefault(first, 0) + 1);
        }
        for (String n : full) {
            String first = first(n);
            String[] words = n.trim().split("\\s+");
            out.add(count.get(first) > 1 && words.length > 1 && !words[1].isEmpty()
                    ? first + " " + words[1].substring(0, 1) : first);
        }
        return out;
    }

    private static String first(String name) {
        String t = name == null ? "" : name.trim();
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    static JSONArray profiles(Context ctx) {
        try {
            return new JSONArray(prefs(ctx).getString("profiles", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** The linked account's plex.tv key, or "" before linking. */
    static String ownerToken(Context ctx) {
        return prefs(ctx).getString("owner_token", "");
    }

    static String selected(Context ctx) {
        return prefs(ctx).getString("selected", "");
    }

    /**
     * Makes {@code uuid} the profile Recently watched follows: switches to it on plex.tv (with its PIN when it has
     * one) and keeps its key for the home server. Returns null when done, or why not ("pin" for a wrong or
     * missing PIN).
     */
    static String select(Context ctx, String uuid, String pin, String serverId) {
        String owner = prefs(ctx).getString("owner_token", "");
        if (owner.isEmpty()) return "not linked";
        // Switched to before and no PIN asked: its server key is already here, so the switch is instant.
        if ((pin == null || pin.isEmpty()) && !prefs(ctx).getString("token_" + uuid, "").isEmpty()) {
            prefs(ctx).edit().putString("selected", uuid).putBoolean("picked", true).apply();
            return null;
        }
        try {
            String url = API + "/home/users/" + uuid + "/switch" + (pin == null || pin.isEmpty() ? ""
                    : "?pin=" + URLEncoder.encode(pin, "UTF-8"));
            String body;
            try {
                body = request(ctx, "POST", url, owner);
            } catch (java.io.IOException e) {
                return "pin";
            }
            String userToken = new JSONObject(body).optString("authToken", "");
            if (userToken.isEmpty()) return "pin";
            // The profile's own key for the home server (what the server checks), else its plex.tv key.
            String serverToken = userToken;
            JSONArray resources = new JSONArray(request(ctx, "GET", API + "/resources?includeHttps=1", userToken));
            for (int i = 0; i < resources.length(); i++) {
                JSONObject r = resources.getJSONObject(i);
                if (serverId.equals(r.optString("clientIdentifier")) && !r.optString("accessToken").isEmpty()) {
                    serverToken = r.getString("accessToken");
                }
            }
            prefs(ctx).edit().putString("selected", uuid).putString("token_" + uuid, serverToken)
                    .putBoolean("picked", true).apply();
            return null;
        } catch (Exception e) {
            Log.w(TAG, "select", e);
            return "error";
        }
    }

    /** The selected profile's key for the home server, or "" (the server then answers as its owner). */
    static String serverToken(Context ctx) {
        String sel = selected(ctx);
        return sel.isEmpty() ? "" : prefs(ctx).getString("token_" + sel, "");
    }

    static File avatarFile(Context ctx, String uuid) {
        File d = new File(ctx.getFilesDir(), "avatars");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, uuid.replaceAll("[^A-Za-z0-9_-]", "") + ".png");
    }

    private static boolean saveAvatar(Context ctx, String uuid, String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(10000);
            if (con.getResponseCode() != 200) return false;
            Bitmap b;
            try (InputStream in = con.getInputStream()) {
                b = BitmapFactory.decodeStream(in);
            }
            if (b == null) return false;
            Bitmap s = Bitmap.createScaledBitmap(b, 96, 96, true);
            try (OutputStream o = new FileOutputStream(avatarFile(ctx, uuid))) {
                s.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "avatar", e);
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ---- plex.tv requests ---------------------------------------------------------------------------------------

    static String request(Context ctx, String method, String url, String token) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("X-Plex-Product", "Plexed data");
        con.setRequestProperty("X-Plex-Client-Identifier", clientId(ctx));
        con.setRequestProperty("X-Plex-Device-Name", "Home screen (TV)");
        if (token != null) con.setRequestProperty("X-Plex-Token", token);
        if ("POST".equals(method)) {
            con.setDoOutput(true);
            con.getOutputStream().close();
        }
        int code = con.getResponseCode();
        if (code < 200 || code >= 300) {
            con.disconnect();
            throw new java.io.IOException("HTTP " + code);
        }
        try (InputStream in = con.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }
}
