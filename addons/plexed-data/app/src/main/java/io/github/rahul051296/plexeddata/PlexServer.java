package io.github.rahul051296.plexeddata;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The Plex server Recently watched reads from. Set by hand over ADB (SetupActivity's "plex" extra), or else found
 * once the owner's account is linked: plex.tv lists the servers the account can reach, and the first one that
 * answers as that server (its /identity carries the id plex.tv listed) is kept, with its id and the account's key
 * for it. A home-network address is tried before a remote one, and Plex's relay (slow, but enough for titles and
 * pictures) only when neither answers: a friend's server with Remote Access off, or behind a router that lets no one
 * in. When the kept address stops answering (a new one after a router restart), the server is looked up again on the
 * next refresh.
 */
final class PlexServer {
    private static final String TAG = "PlexServer";
    private static final String MANUAL = "plex", FOUND = "plex_found", MACHINE = "plex_machine", KEY = "plex_found_key";

    private PlexServer() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PlexedProvider.PREFS, Context.MODE_PRIVATE);
    }

    /** The server's address, or "" when none is set and none could be found. Blocks: never on the main thread. */
    static String base(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String manual = p.getString(MANUAL, "").trim();
        if (!manual.isEmpty()) return manual.replaceAll("/+$", "");
        String found = p.getString(FOUND, "");
        return found.isEmpty() ? find(ctx) : found;
    }

    /** The server's id, finding the server first when it is not known yet. Blocks: never on the main thread. */
    static String machine(Context ctx) {
        if (prefs(ctx).getString(MACHINE, "").isEmpty()) base(ctx);
        return prefs(ctx).getString(MACHINE, "");
    }

    static void rememberMachine(Context ctx, String id) {
        prefs(ctx).edit().putString(MACHINE, id).apply();
    }

    /** The linked account's own key for the server it found; "" for a server set by hand. */
    static String accountKey(Context ctx) {
        return prefs(ctx).getString(KEY, "");
    }

    /** The found address did not answer: look the server up again next time. A server set by hand stays. */
    static void forget(Context ctx) {
        prefs(ctx).edit().remove(FOUND).apply();
    }

    private static String find(Context ctx) {
        String account = PlexAccount.ownerToken(ctx);
        if (account.isEmpty()) return "";
        try {
            JSONArray resources = new JSONArray(PlexAccount.request(ctx, "GET",
                    PlexAccount.API + "/resources?includeHttps=1&includeRelay=1", account));
            // The account's own servers first, then ones shared with it.
            for (boolean owned : new boolean[] {true, false}) {
                for (int i = 0; i < resources.length(); i++) {
                    JSONObject r = resources.getJSONObject(i);
                    if (!r.optString("provides").contains("server") || r.optBoolean("owned") != owned) continue;
                    String key = r.optString("accessToken"), id = r.optString("clientIdentifier");
                    for (String address : addresses(r.optJSONArray("connections"))) {
                        if (!answersAs(address, key, id)) continue;
                        prefs(ctx).edit().putString(FOUND, address).putString(MACHINE, id).putString(KEY, key).apply();
                        return address;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "find", e);
        }
        return "";
    }

    /** A server's addresses to try, in order: on the home network (plain, then secure), then remote, then relay. */
    private static List<String> addresses(JSONArray connections) {
        List<String> plain = new ArrayList<>(), secure = new ArrayList<>(), remote = new ArrayList<>(),
                relay = new ArrayList<>();
        for (int i = 0; connections != null && i < connections.length(); i++) {
            JSONObject c = connections.optJSONObject(i);
            if (c == null || c.optString("uri").isEmpty()) continue;
            if (c.optBoolean("relay")) {
                relay.add(c.optString("uri"));
                continue;
            }
            if (!c.optBoolean("local")) {
                remote.add(c.optString("uri"));
                continue;
            }
            secure.add(c.optString("uri"));
            String host = c.optString("address");
            if (!host.isEmpty() && c.optInt("port") > 0) {
                plain.add("http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + c.optInt("port"));
            }
        }
        plain.addAll(secure);
        plain.addAll(remote);
        plain.addAll(relay);
        return plain;
    }

    /**
     * Whether the server with id `id` answers at this address, within a few seconds. Any Plex server answers
     * /identity, so its machineIdentifier is checked too: another network's server at the same home address (the
     * same 192.168.1.x on a friend's network) is not taken for this one.
     */
    private static boolean answersAs(String address, String key, String id) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(address + "/identity").openConnection();
            con.setConnectTimeout(2500);
            con.setReadTimeout(4000);
            con.setRequestProperty("X-Plex-Token", key);
            con.setRequestProperty("Accept", "application/json");
            if (con.getResponseCode() != 200) return false;
            String body;
            try (InputStream in = con.getInputStream()) {
                body = new String(readAll(in), UTF_8);
            }
            String machine = new JSONObject(body).getJSONObject("MediaContainer").optString("machineIdentifier");
            return !id.isEmpty() && id.equals(machine);
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
