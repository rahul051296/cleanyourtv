package io.github.rahul051296.plexeddata;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only data for the launcher's Plexed theme (recent_hero) and Recently watched row (recent_shelf):
 * <ul>
 * <li>{@code /profiles}: the Plex Home profiles: first names, whether each has a PIN or a picture, which one is
 * selected, and whether an account is linked. No keys;</li>
 * <li>{@code /recent}: Continue watching (Plex, and the TV's Watch Next), newest first; {@code kind} is "next" for
 * a show's next episode, not started yet (Up next). Its extras' boolean
 * {@code fetching} says a fetch is under way. {@code ?max_age=<ms>} asks for a fetch when the list is older than
 * that (Home coming back), 15 seconds at least;</li>
 * <li>{@code /new}: films and shows added to the Plex server or released in the last 30 days, not started yet;
 * its {@code kind} column says which ("added" or "released");</li>
 * <li>{@code /poster/<id>.jpg}, {@code /poster/<id>-bg.jpg}, {@code /avatar/<uuid>.png}: their pictures.</li>
 * </ul>
 * {@code call("select", uuid)} switches the profile. Lists are fetched only when the launcher asks and the last
 * fetch is old, and the URIs are notified when a fresh one lands.
 */
public final class PlexedProvider extends ContentProvider {
    static final String PREFS = "plexed";
    static final String AUTHORITY = "io.github.rahul051296.plexeddata.recent";
    /** Continue watching and the recent titles (RecentWatching). */
    static final Uri RECENT_URI = Uri.parse("content://" + AUTHORITY + "/recent");
    /** "Who's watching?": the Plex Home profiles (PlexAccount); names, pictures and the selected one, no keys. */
    static final Uri PROFILES_URI = Uri.parse("content://" + AUTHORITY + "/profiles");
    private static final String[] PROFILE_COLUMNS = {"uuid", "name", "pin", "avatar", "selected", "linked"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context ctx = getContext();
        if ("profiles".equals(uri.getLastPathSegment())) {
            MatrixCursor c = new MatrixCursor(PROFILE_COLUMNS);
            PlexAccount.refreshIfOld(ctx.getApplicationContext());
            if (!PlexAccount.linked(ctx)) {
                c.addRow(new Object[] {"", "", 0, 0, 0, 0});   // nothing linked yet: the launcher offers to link
            } else {
                org.json.JSONArray ps = PlexAccount.profiles(ctx);
                String sel = PlexAccount.selected(ctx);
                java.util.List<String> names = new java.util.ArrayList<>();
                for (int i = 0; i < ps.length(); i++) {
                    org.json.JSONObject o = ps.optJSONObject(i);
                    names.add(o == null ? "" : o.optString("name"));
                }
                java.util.List<String> shown = PlexAccount.firstNames(names);
                for (int i = 0; i < ps.length(); i++) {
                    org.json.JSONObject o = ps.optJSONObject(i);
                    if (o == null) continue;
                    c.addRow(new Object[] {o.optString("uuid"), shown.get(i), o.optBoolean("pin") ? 1 : 0,
                            o.optBoolean("avatar") ? 1 : 0, o.optString("uuid").equals(sel) ? 1 : 0, 1});
                }
            }
            c.setNotificationUri(ctx.getContentResolver(), PROFILES_URI);
            return c;
        }
        if ("recent".equals(uri.getLastPathSegment())) {
            MatrixCursor c = new MatrixCursor(RecentWatching.COLUMNS);
            long maxAge = Long.MAX_VALUE;
            try {
                String a = uri.getQueryParameter("max_age");
                if (a != null) maxAge = Long.parseLong(a);
            } catch (NumberFormatException ignored) {
                // no hint: the usual age
            }
            for (Object[] row : RecentWatching.rows(ctx, maxAge)) c.addRow(row);
            c.setNotificationUri(ctx.getContentResolver(), RECENT_URI);
            // Whether more is on its way: the launcher keeps its loading state up while it is.
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBoolean("fetching", RecentWatching.fetching());
            c.setExtras(extras);
            return c;
        }
        // The recent titles: the same columns; it changes with Recently watched, so it shares that notification.
        if ("new".equals(uri.getLastPathSegment())) {
            MatrixCursor c = new MatrixCursor(RecentWatching.COLUMNS);
            for (Object[] row : RecentWatching.newRows(ctx)) c.addRow(row);
            c.setNotificationUri(ctx.getContentResolver(), RECENT_URI);
            return c;
        }
        return null;
    }

    /**
     * call("select", uuid, {pin}): makes that profile the one Recently watched follows; the result's "error" is
     * absent on success, "pin" when its PIN is needed or wrong. call("link"): opens the plex.tv/link screen.
     */
    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        Context ctx = getContext();
        android.os.Bundle out = new android.os.Bundle();
        if ("select".equals(method) && arg != null) {
            String server = PlexServer.machine(ctx);
            String error = PlexAccount.select(ctx, arg, extras == null ? null : extras.getString("pin"), server);
            if (error != null) {
                out.putString("error", error);
            } else {
                // The new profile's saved list shows at once (PROFILES_URI); a fresh one follows (RECENT_URI).
                ctx.getContentResolver().notifyChange(PROFILES_URI, null);
                RecentWatching.fetch(ctx.getApplicationContext());
            }
        }
        return out;
    }

    /** content://.../poster/<id>.jpg (a poster RecentWatching saved) or .../avatar/<uuid>.png, read-only. */
    @Override
    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 2 && "avatar".equals(seg.get(0)) && "r".equals(mode)
                && seg.get(1).matches("[A-Za-z0-9_-]+[.]png")) {
            java.io.File f = PlexAccount.avatarFile(getContext(), seg.get(1).substring(0, seg.get(1).length() - 4));
            return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (seg.size() != 2 || !"poster".equals(seg.get(0)) || !seg.get(1).matches("[A-Za-z0-9_-]+[.]jpg")
                || !"r".equals(mode)) {
            throw new java.io.FileNotFoundException(uri.toString());
        }
        java.io.File f = new java.io.File(RecentWatching.posterDir(getContext()), seg.get(1));
        return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException();
    }
}
