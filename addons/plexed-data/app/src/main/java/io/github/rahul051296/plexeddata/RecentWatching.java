package io.github.rahul051296.plexeddata;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * What the owner was watching, for the launcher's Recently watched row: the Plex server's Continue Watching
 * (read on the home network, where the server lets this TV in without signing in) and the TV's own Watch Next
 * list (the apps' continue-watching rows, READ_TV_LISTINGS). Posters are fetched here, scaled to 480x270 and
 * kept in the app's files, so the launcher never goes online. Nothing runs in the background: a fetch happens
 * only when the launcher asks and the last one is older than STALE_MS.
 *
 * A fetch reads the lists first and downloads pictures after, PICTURE_THREADS items at a time in the order Home
 * shows them, and publishes as it goes: the first finished item at once, then every few, then the whole list. Only
 * items whose pictures are done are published, so Home never shows one without its picture.
 */
final class RecentWatching {
    static final String COLUMNS[] = {"id", "source", "package", "title", "subtitle", "progress", "time_ms", "poster", "intent", "backdrop", "meta", "left", "summary", "kind"};
    private static final String TAG = "RecentWatching";
    /** A list this old is fetched again when Home reads it; Home coming back asks for MIN_AGE_MS instead. */
    private static final long STALE_MS = 5 * 60_000L, MIN_AGE_MS = 15_000L;
    /** After the Plex server did not answer: the next try, this long after (sooner than STALE_MS). */
    private static final long RETRY_MS = 2 * 60_000L;
    private static final long WATCH_NEXT_DAYS_MS = 30L * 24 * 60 * 60_000L;
    private static final int W = 480, H = 270;
    /** Backdrops: big enough to fill a 1080p TV once scaled, small enough to keep (~100-200 KB each). */
    private static final int BW = 1920, BH = 1080;
    private static volatile boolean running;
    /** Items whose pictures download at the same time; published again after every PUBLISH_EVERY more. */
    private static final int PICTURE_THREADS = 3, PUBLISH_EVERY = 3, RECENT_MAX = 12;

    private RecentWatching() {
    }

    /** A fetch is under way (the launcher keeps its loading state up meanwhile). */
    static boolean fetching() {
        return running;
    }

    static File posterDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "posters");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * The last fetch as rows of COLUMNS, newest first; starts a fetch when it is older than `maxAge` (STALE_MS, or
     * less when Home has just come back, so progress made in the Plex app shows a moment later; never under
     * MIN_AGE_MS).
     */
    static Object[][] rows(Context ctx, long maxAge) {
        return rows(ctx, "recent_json_", Math.max(MIN_AGE_MS, Math.min(maxAge, STALE_MS)));
    }

    /** The recent titles: films and shows added or released in the last 30 days that this profile has not started. */
    static Object[][] newRows(Context ctx) {
        return rows(ctx, "new_json_", STALE_MS);
    }

    private static Object[][] rows(Context ctx, String list, long maxAge) {
        SharedPreferences p = ctx.getSharedPreferences(PlexedProvider.PREFS, Context.MODE_PRIVATE);
        String who = PlexAccount.selected(ctx);
        if (System.currentTimeMillis() - p.getLong("recent_updated_" + who, 0) > maxAge) fetch(ctx.getApplicationContext());
        List<Object[]> out = new ArrayList<>();
        try {
            // Each profile keeps its own last list, so switching shows it at once while a fresh one is fetched.
            // A profile with no saved list yet shows nothing (the launcher masks that with a skeleton) rather than the
            // Watch Next items every profile shares, which would then jump to its own list when this arrives.
            boolean unseen = !who.isEmpty() && !p.contains(list + who);
            String fallback = list.equals("recent_json_") ? p.getString("recent_json", "[]") : "[]";
            JSONArray a = new JSONArray(unseen ? "[]" : p.getString(list + who, fallback));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Object[] {o.getString("id"), o.getString("source"), o.getString("package"),
                        o.getString("title"), o.optString("subtitle"), o.optInt("progress", -1), o.optLong("time_ms"),
                        o.optBoolean("poster") ? 1 : 0, o.optString("intent"), o.optBoolean("backdrop") ? 1 : 0,
                        o.optString("meta"), o.optString("left"), o.optString("summary"), o.optString("kind")});
            }
        } catch (Exception e) {
            Log.w(TAG, "bad cache", e);
        }
        return out.toArray(new Object[0][]);
    }

    static void fetch(Context ctx) {
        if (running) return;
        running = true;
        new Thread(() -> {
            SharedPreferences p = ctx.getSharedPreferences(PlexedProvider.PREFS, Context.MODE_PRIVATE);
            String who = PlexAccount.selected(ctx);
            p.edit().putLong("recent_updated_" + who, System.currentTimeMillis()).apply();
            if (p.getInt("bg_version", 0) < 5) {
                // Backdrops saved by an earlier version were reduced-colour and soft: fetched again, once.
                File[] saved = posterDir(ctx).listFiles();
                if (saved != null) {
                    for (File f : saved) {
                        if (f.getName().endsWith("-bg.jpg")) f.delete();
                    }
                }
                p.edit().putInt("bg_version", 5).apply();
            }
            List<JSONObject> items = new ArrayList<>();
            List<JSONObject> fresh = new ArrayList<>();
            Pictures pics = new Pictures();
            // A profile's first fetch skips the films' genres (one request each): the next refresh adds them.
            boolean first = !p.contains("recent_json_" + who);
            String plexBase = PlexServer.base(ctx);
            // Recently added and released are looked for while Continue watching is read: two waits at once.
            ExecutorService side = Executors.newSingleThreadExecutor();
            Future<List<Candidate>> candidates = plexBase.isEmpty() ? null
                    : side.submit(() -> newCandidates(plexBase, serverKey(ctx)));
            try {
                if (!plexBase.isEmpty()) items.addAll(plex(ctx, plexBase, pics, !first));
            } catch (Exception e) {
                side.shutdownNow();
                // The server did not answer (the TV in standby, or the server off). Lists from it now would hold
                // Watch Next alone: keep the ones saved last time, and try again in RETRY_MS.
                Log.w(TAG, "plex", e);
                PlexServer.forget(ctx);   // a found address is looked up again (it may have changed)
                p.edit().putLong("recent_updated_" + who, System.currentTimeMillis() - STALE_MS + RETRY_MS).apply();
                running = false;
                ctx.getContentResolver().notifyChange(PlexedProvider.RECENT_URI, null);   // the launcher stops waiting
                return;
            }
            boolean freshKept = false;
            try {
                if (candidates != null) fresh.addAll(plexNew(ctx, plexBase, candidates.get(), items, pics));
            } catch (Exception e) {
                Log.w(TAG, "plex new", e);
                freshKept = true;   // the recent titles stay as they were
            } finally {
                side.shutdown();
            }
            try {
                items.addAll(watchNext(ctx, pics));
            } catch (Exception e) {
                Log.w(TAG, "watch next", e);
            }
            items.sort((a, b) -> Long.compare(b.optLong("time_ms"), a.optLong("time_ms")));
            if (items.size() > RECENT_MAX) items = new ArrayList<>(items.subList(0, RECENT_MAX));
            JSONArray kept = freshKept ? saved(p, "new_json_" + who) : new JSONArray();
            // Pictures, published as they come: Home has the first item seconds in, not after the last picture.
            downloadAndPublish(ctx, p, who, items, fresh, kept, pics);
            JSONArray a = new JSONArray(items);
            JSONArray n = new JSONArray();
            for (int i = 0; i < kept.length(); i++) n.put(kept.optJSONObject(i));
            for (JSONObject o : fresh) n.put(o);
            // Pictures stay while any profile's saved list still shows them; the rest are deleted.
            List<String> keep = new ArrayList<>();
            List<String> lists = new ArrayList<>();
            lists.add(a.toString());
            lists.add(n.toString());
            for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
                boolean other = (e.getKey().startsWith("recent_json_") && !e.getKey().equals("recent_json_" + who))
                        || (e.getKey().startsWith("new_json_") && !e.getKey().equals("new_json_" + who));
                if (other && e.getValue() instanceof String) lists.add((String) e.getValue());
            }
            for (String list : lists) {
                try {
                    JSONArray l = new JSONArray(list);
                    for (int i = 0; i < l.length(); i++) {
                        keep.add(l.getJSONObject(i).optString("id") + ".jpg");
                        keep.add(l.getJSONObject(i).optString("id") + "-bg.jpg");
                    }
                } catch (Exception ignored) {
                }
            }
            File[] old = posterDir(ctx).listFiles();
            if (old != null) for (File f : old) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
                f.delete();
            p.edit().putString("recent_json_" + who, a.toString()).putString("new_json_" + who, n.toString())
                    .remove("recent_json")
                    .putLong("recent_updated_" + who, System.currentTimeMillis()).apply();
            running = false;
            // Someone switched profiles while this ran: their list is fetched right away, not at the next refresh.
            if (!who.equals(PlexAccount.selected(ctx))) fetch(ctx);
            else ctx.getContentResolver().notifyChange(PlexedProvider.RECENT_URI, null);
        }).start();
    }

    /**
     * The key the server gets: the profile picked in "Who's watching?", else the linked account's own (for the
     * server it found), else none (a server set by hand that lets the home network in without one).
     */
    private static String serverKey(Context ctx) {
        String profile = PlexAccount.serverToken(ctx);
        return profile.isEmpty() ? PlexServer.accountKey(ctx) : profile;
    }

    /** A list saved earlier, or an empty one. */
    private static JSONArray saved(SharedPreferences p, String key) {
        try {
            return new JSONArray(p.getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // ---- Plex: the server's Continue Watching -------------------------------------------------------------------

    private static List<JSONObject> plex(Context ctx, String base, Pictures pics, boolean genres) throws Exception {
        base = base.replaceAll("/+$", "");
        String token = serverKey(ctx);
        String machine = new JSONObject(get(base + "/identity", true, token)).getJSONObject("MediaContainer")
                .optString("machineIdentifier");
        PlexServer.rememberMachine(ctx, machine);
        String tokenParam = token.isEmpty() ? "" : "&X-Plex-Token=" + URLEncoder.encode(token, "UTF-8");
        JSONObject root = new JSONObject(get(base + "/hubs/continueWatching", true, token));
        List<JSONObject> meta = new ArrayList<>();
        collect(root, meta);
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject m : meta) {
            String type = m.optString("type");
            if (!"episode".equals(type) && !"movie".equals(type)) continue;
            String key = m.getString("ratingKey");
            long duration = m.optLong("duration"), offset = m.optLong("viewOffset");
            boolean episode = "episode".equals(type);
            // Plex's Continue watching also lists a show's next episode, not started yet: Up next, not a resume.
            boolean upNext = offset <= 0;
            String title = episode ? m.optString("grandparentTitle", m.optString("title")) : m.optString("title");
            StringBuilder sub = new StringBuilder();
            if (episode) {
                sub.append("S").append(m.optInt("parentIndex")).append(" E").append(m.optInt("index"));
                String ep = m.optString("title");
                if (!ep.isEmpty()) sub.append(" · ").append(ep);
            }
            if (upNext || duration > 0) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(upNext ? "Up next" : left(duration - offset));
            }
            // The hero's details line: "S1 E3 · The Pilot · 2024 · TV-14" or "2026 · Not Rated · Action · Comedy".
            List<String> bits = new ArrayList<>();
            if (episode) bits.add("S" + m.optInt("parentIndex") + " E" + m.optInt("index") + (m.optString("title").isEmpty() ? "" : " · " + m.optString("title")));
            int year = m.optInt("year", m.optInt("grandparentYear", 0));
            if (year > 0) bits.add(String.valueOf(year));
            if (!m.optString("contentRating").isEmpty()) bits.add(m.optString("contentRating"));
            if (!episode && genres) {
                try {
                    JSONObject full = new JSONObject(get(base + "/library/metadata/" + key, true, token))
                            .getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0);
                    JSONArray g = full.optJSONArray("Genre");
                    for (int i = 0; g != null && i < g.length() && i < 2; i++) bits.add(g.getJSONObject(i).optString("tag"));
                } catch (Exception ignored) {
                    // no genres: the line is shorter
                }
            }
            JSONObject o = new JSONObject();
            o.put("meta", TextUtils.join(" · ", bits));
            o.put("left", duration <= 0 ? "" : upNext ? length(duration) : left(duration - offset));
            if (upNext) o.put("kind", "next");
            o.put("id", "plex-" + key);
            o.put("plex_key", key);
            o.put("summary", m.optString("summary"));
            o.put("plex_show", m.optString("grandparentRatingKey"));
            o.put("source", "plex");
            o.put("package", "com.plexapp.android");
            o.put("title", title);
            o.put("subtitle", sub.toString());
            o.put("progress", duration > 0 && !upNext ? (int) Math.min(1000, offset * 1000 / duration) : -1);
            o.put("time_ms", m.optLong("lastViewedAt", m.optLong("updatedAt")) * 1000L);
            // Plex's TV app plays a server's own item from plex://watch/video?uri=server://<machine>/<library key>,
            // and offset (in seconds) resumes it where the owner stopped. Its watch.plex.tv links only reach the
            // catalog page, and plex://preplay is iOS only.
            String link = "plex://watch/video?uri=" + URLEncoder.encode("server://" + machine
                    + "/com.plexapp.plugins.library/library/metadata/" + key, "UTF-8");
            if (offset > 0) link += "&offset=" + offset / 1000;
            o.put("intent", link);
            // An episode's own still is already 16:9; a movie's background art is too.
            String art = episode ? m.optString("thumb", m.optString("grandparentArt"))
                    : m.optString("art", m.optString("thumb"));
            if (!art.isEmpty()) {
                pics.want(o, "poster", "plex-" + key, base + "/photo/:/transcode?width=" + W + "&height=" + H
                        + "&minSize=1&upscale=1&url=" + URLEncoder.encode(art, "UTF-8") + tokenParam, W, H);
            }
            // The backdrop behind Home while the tile is focused: the show's or the movie's own background art.
            String bg = m.optString("art", m.optString("grandparentArt"));
            if (!bg.isEmpty()) {
                pics.want(o, "backdrop", "plex-" + key + "-bg", base + "/photo/:/transcode?width=" + BW + "&height="
                        + BH + "&minSize=1&upscale=1&url=" + URLEncoder.encode(bg, "UTF-8") + tokenParam, BW, BH);
            }
            out.add(o);
        }
        return out;
    }

    // ---- Plex: Recently added and Recently released ----------------------------------------------------------------

    /** How far back "recent" goes, and how many recent titles the carousel picks its slides from. */
    private static final long NEW_DAYS = 30;
    private static final int NEW_POOL = 16;
    /** Server requests sent at once while looking for them: each one waits on the server, not on this TV. */
    private static final int REQUESTS_AT_ONCE = 4;

    /** A recent title found in a library section, before it is checked against what the profile is watching. */
    private static final class Candidate {
        /** The film's ratingKey, or the show's. */
        final String key;
        /** Seconds since 1970: when it was added or released, whichever is later. */
        final long since;
        /** The film's, the show's or (for a show with new episodes) the newest episode's metadata. */
        final JSONObject meta;
        final boolean show;
        /** Recent because it came out lately (else because it was added lately). */
        final boolean released;

        Candidate(String key, long since, JSONObject meta, boolean show, boolean released) {
            this.key = key;
            this.since = since;
            this.meta = meta;
            this.show = show;
            this.released = released;
        }

        /** The same title, marked as recently released. */
        Candidate asReleased() {
            return new Candidate(key, since, meta, show, true);
        }
    }

    /**
     * Films and shows added to the server, or released, in the last NEW_DAYS days, newest first. One request per
     * section and sort order, sent REQUESTS_AT_ONCE at a time. Films already started or seen are left out here;
     * what the profile is in the middle of is left out later, by plexNew.
     */
    private static List<Candidate> newCandidates(String base, String token) throws Exception {
        base = base.replaceAll("/+$", "");
        long cutoff = System.currentTimeMillis() / 1000 - NEW_DAYS * 86400;
        JSONArray dirs = new JSONObject(get(base + "/library/sections", true, token)).getJSONObject("MediaContainer")
                .optJSONArray("Directory");
        // What to ask each section, and what the answer holds: "movies", "episodes" or "shows".
        List<String> urls = new ArrayList<>(), holds = new ArrayList<>();
        for (int d = 0; dirs != null && d < dirs.length(); d++) {
            JSONObject dir = dirs.getJSONObject(d);
            String sec = base + "/library/sections/" + dir.optString("key") + "/all?unwatched=1&X-Plex-Container-Start=0";
            if ("movie".equals(dir.optString("type"))) {
                urls.add(sec + "&type=1&X-Plex-Container-Size=15&sort=addedAt:desc");
                holds.add("movies");
                urls.add(sec + "&type=1&X-Plex-Container-Size=15&sort=originallyAvailableAt:desc");
                holds.add("movies");
            } else if ("show".equals(dir.optString("type"))) {
                urls.add(sec + "&type=4&X-Plex-Container-Size=40&sort=addedAt:desc");
                holds.add("episodes");
                urls.add(sec + "&type=2&X-Plex-Container-Size=10&sort=originallyAvailableAt:desc");
                holds.add("shows");
            }
        }
        List<Callable<String>> requests = new ArrayList<>();
        for (String url : urls) requests.add(() -> get(url, true, token));
        List<String> answers = inParallel(requests);

        // In the order asked, so a film or show found twice keeps its first find (as when asked one by one).
        Map<String, Candidate> found = new java.util.LinkedHashMap<>();
        for (int i = 0; i < answers.size(); i++) {
            if (answers.get(i) == null) continue;   // that section did not answer: the others still count
            List<JSONObject> got = new ArrayList<>();
            collect(new JSONObject(answers.get(i)), got);
            for (JSONObject m : got) {
                switch (holds.get(i)) {
                    case "movies": {
                        String key = m.optString("ratingKey");
                        if (found.containsKey(key) || m.optLong("viewOffset") > 0 || m.optInt("viewCount") > 0) break;
                        long added = m.optLong("addedAt"), out = released(m);
                        if (Math.max(added, out) >= cutoff) {
                            found.put(key, new Candidate(key, Math.max(added, out), m, false, out >= cutoff));
                        }
                        break;
                    }
                    case "episodes": {   // new episodes: each show once, at its newest episode's time
                        String show = m.optString("grandparentRatingKey");
                        if (show.isEmpty() || found.containsKey(show)) break;
                        if (m.optLong("addedAt") >= cutoff) {
                            found.put(show, new Candidate(show, m.optLong("addedAt"), m, true, false));
                        }
                        break;
                    }
                    default: {   // "shows": shows that premiered lately
                        String key = m.optString("ratingKey");
                        long out = released(m);
                        if (out < cutoff) break;
                        // Already found by its new episodes: still recently released (its premiere is recent).
                        Candidate had = found.get(key);
                        found.put(key, had == null ? new Candidate(key, out, m, true, true) : had.asReleased());
                        break;
                    }
                }
            }
        }
        List<Candidate> ranked = new ArrayList<>(found.values());
        ranked.sort((x, y) -> Long.compare(y.since, x.since));
        return ranked;
    }

    /**
     * Up to NEW_POOL of the candidates, newest first, leaving out anything this profile is in the middle of (the
     * film, or the show of an episode in Continue watching). A show plays its first episode the profile has not
     * seen (one request each, in parallel); a film plays from the start.
     */
    private static List<JSONObject> plexNew(Context ctx, String base, List<Candidate> candidates,
                                            List<JSONObject> continuing, Pictures pics) throws Exception {
        base = base.replaceAll("/+$", "");
        SharedPreferences p = ctx.getSharedPreferences(PlexedProvider.PREFS, Context.MODE_PRIVATE);
        String machine = p.getString("plex_machine", "");
        String token = serverKey(ctx);
        String tokenParam = token.isEmpty() ? "" : "&X-Plex-Token=" + URLEncoder.encode(token, "UTF-8");
        Set<String> skip = new java.util.HashSet<>();
        for (JSONObject c : continuing) {
            if (!c.optString("plex_key").isEmpty()) skip.add(c.optString("plex_key"));
            if (!c.optString("plex_show").isEmpty()) skip.add(c.optString("plex_show"));
        }
        List<Candidate> chosen = new ArrayList<>();
        for (Candidate c : candidates) {
            if (chosen.size() == NEW_POOL) break;
            if (!skip.contains(c.key)) chosen.add(c);
        }

        // Each show's first unseen episode, asked for all of them at once.
        List<Callable<String>> requests = new ArrayList<>();
        for (Candidate c : chosen) {
            String url = base + "/library/metadata/" + c.key
                    + "/allLeaves?unwatched=1&X-Plex-Container-Start=0&X-Plex-Container-Size=1";
            requests.add(c.show ? () -> get(url, true, token) : () -> null);
        }
        List<String> firstEpisodes = inParallel(requests);

        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Candidate c = chosen.get(i);
            JSONObject m = c.meta;
            JSONObject play = m;   // what Play starts: the film, or the show's first unseen episode
            if (c.show) {
                List<JSONObject> first = new ArrayList<>();
                if (firstEpisodes.get(i) != null) collect(new JSONObject(firstEpisodes.get(i)), first);
                if (first.isEmpty()) continue;
                play = first.get(0);
            }
            String key = play.optString("ratingKey");
            List<String> bits = new ArrayList<>();
            if (c.show) bits.add("S" + play.optInt("parentIndex") + " E" + play.optInt("index")
                    + (play.optString("title").isEmpty() ? "" : " · " + play.optString("title")));
            int year = c.show ? play.optInt("year", m.optInt("year", 0)) : m.optInt("year", 0);
            if (year > 0) bits.add(String.valueOf(year));
            String rating = m.optString("contentRating", play.optString("contentRating"));
            if (!rating.isEmpty()) bits.add(rating);
            if (!c.show) {
                JSONArray g = m.optJSONArray("Genre");
                for (int k = 0; g != null && k < g.length() && k < 2; k++) bits.add(g.getJSONObject(k).optString("tag"));
            }
            String id = "plex-new-" + c.key;
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("source", "plex");
            o.put("package", "com.plexapp.android");
            o.put("kind", c.released ? "released" : "added");
            o.put("title", c.show ? play.optString("grandparentTitle", m.optString("title")) : m.optString("title"));
            o.put("subtitle", "");
            o.put("meta", TextUtils.join(" · ", bits));
            String about = play.optString("summary");
            o.put("summary", about.isEmpty() ? m.optString("summary") : about);
            o.put("left", play.optLong("duration") > 0 ? length(play.optLong("duration")) : "");
            o.put("progress", -1);
            o.put("time_ms", c.since * 1000L);
            o.put("intent", "plex://watch/video?uri=" + URLEncoder.encode("server://" + machine
                    + "/com.plexapp.plugins.library/library/metadata/" + key, "UTF-8"));
            String bg = c.show ? play.optString("grandparentArt", m.optString("art")) : m.optString("art");
            if (!bg.isEmpty()) {
                pics.want(o, "backdrop", id + "-bg", base + "/photo/:/transcode?width=" + BW + "&height=" + BH
                        + "&minSize=1&upscale=1&url=" + URLEncoder.encode(bg, "UTF-8") + tokenParam, BW, BH);
            }
            out.add(o);
        }
        return out;
    }

    /** Runs `jobs` REQUESTS_AT_ONCE at a time; their results in the same order, null for one that failed. */
    private static <T> List<T> inParallel(List<Callable<T>> jobs) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(REQUESTS_AT_ONCE, jobs.size())));
        try {
            List<T> results = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(jobs)) {
                try {
                    results.add(f.get());
                } catch (ExecutionException e) {
                    Log.w(TAG, "request", e.getCause());
                    results.add(null);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A release date ("2026-10-22") as seconds, or 0. */
    private static long released(JSONObject m) {
        String d = m.optString("originallyAvailableAt");
        if (d.length() < 10) return 0;
        try {
            return java.time.LocalDate.parse(d.substring(0, 10)).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        } catch (Exception e) {
            return 0;
        }
    }

    /** A running time: "48 min" or "1 h 31 min". */
    static String length(long ms) {
        long min = Math.max(1, Math.round(ms / 60_000.0));
        return min < 60 ? min + " min" : String.format(Locale.US, "%d h %02d min", min / 60, min % 60);
    }

    /** Every object with a ratingKey, at any depth (the hub's Metadata, wherever the server puts it). */
    private static void collect(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("ratingKey") && o.has("type")) {
                out.add(o);
                return;
            }
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) collect(o.opt(names.optString(i)), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out);
        }
    }

    // ---- Watch Next: the apps' own continue-watching rows ------------------------------------------------------

    private static List<JSONObject> watchNext(Context ctx, Pictures pics) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        long since = System.currentTimeMillis() - WATCH_NEXT_DAYS_MS;
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"),
                null, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String pkg = str(c, "package_name");
                if (pkg == null || "com.plexapp.android".equals(pkg)) continue;   // Plex comes from the server
                int wnType = c.getInt(c.getColumnIndexOrThrow("watch_next_type"));
                if (wnType != 0 && wnType != 1) continue;                          // continue and next only
                long when = c.getLong(c.getColumnIndexOrThrow("last_engagement_time_utc_millis"));
                if (when < since) continue;
                long id = c.getLong(c.getColumnIndexOrThrow("_id"));
                String title = str(c, "title"), episode = str(c, "episode_title");
                String season = str(c, "season_display_number"), number = str(c, "episode_display_number");
                long duration = lng(c, "duration_millis"), position = lng(c, "last_playback_position_millis");
                StringBuilder sub = new StringBuilder();
                if (number != null) {
                    if (season != null) sub.append("S").append(season).append(" ");
                    sub.append("E").append(number);
                    if (episode != null && !episode.isEmpty()) sub.append(" · ").append(episode);
                }
                if (wnType == 1) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append("Up next");
                } else if (duration > 0 && position > 0) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(left(duration - position));
                }
                JSONObject o = new JSONObject();
                String metaLine = number == null ? "" : (season != null ? "S" + season + " " : "") + "E" + number
                        + (episode != null && !episode.isEmpty() ? " · " + episode : "");
                boolean upNext = wnType == 1;
                o.put("meta", metaLine);   // the hero's tag says Up next
                o.put("left", duration <= 0 ? "" : upNext ? length(duration)
                        : position > 0 ? left(duration - position) : "");
                if (upNext) o.put("kind", "next");
                o.put("id", "wn-" + id);
                String about = str(c, "short_description");
                if (about == null || about.isEmpty()) about = str(c, "long_description");
                o.put("summary", about == null ? "" : about);
                o.put("source", "watchnext");
                o.put("package", pkg);
                o.put("title", title == null ? "" : title);
                o.put("subtitle", sub.toString());
                o.put("progress", duration > 0 && position > 0 ? (int) Math.min(1000, position * 1000 / duration) : -1);
                o.put("time_ms", when);
                String intent = str(c, "intent_uri");
                o.put("intent", intent == null ? "" : intent);
                String art = str(c, "poster_art_uri");
                if (art == null) art = str(c, "thumbnail_uri");
                if (art != null && art.startsWith("https://")) {
                    pics.want(o, "poster", "wn-" + id, art, W, H);
                    // Watch Next gives one picture; a larger copy of it is the backdrop.
                    pics.want(o, "backdrop", "wn-" + id + "-bg", art, BW, BH);
                }
                out.add(o);
            }
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    /** "18 min left", "1 h 05 min left". */
    static String left(long ms) {
        long min = Math.max(1, Math.round(ms / 60_000.0));
        return min < 60 ? min + " min left" : String.format(Locale.US, "%d h %02d min left", min / 60, min % 60);
    }

    /** The pictures a fetch still has to download, by item; until then the item's flags say it has none. */
    private static final class Pictures {
        private final Map<JSONObject, List<Object[]>> wanted = new IdentityHashMap<>();

        /** Item `o` gets file `file` (w x h) from `url`; its `flag` ("poster" or "backdrop") says if it worked. */
        void want(JSONObject o, String flag, String file, String url, int w, int h) throws Exception {
            o.put(flag, false);
            List<Object[]> list = wanted.get(o);
            if (list == null) wanted.put(o, list = new ArrayList<>());
            list.add(new Object[] {flag, file, url, w, h});
        }

        /** Downloads item `o`'s pictures one after another and sets its flags. */
        void download(Context ctx, JSONObject o) throws Exception {
            List<Object[]> list = wanted.get(o);
            if (list == null) return;
            for (Object[] w : list) {
                o.put((String) w[0], savePicture(ctx, (String) w[1], (String) w[2], (int) w[3], (int) w[4]));
            }
        }
    }

    /**
     * Downloads the pictures of `recent` and `fresh`, PICTURE_THREADS items at a time, the two lists interleaved as
     * Home mixes them, and publishes each time an item finishes early on (the first, then every PUBLISH_EVERY): only
     * finished items, so none shows without its picture. The caller then publishes the whole lists.
     */
    private static void downloadAndPublish(Context ctx, SharedPreferences p, String who, List<JSONObject> recent,
                                           List<JSONObject> fresh, JSONArray kept, Pictures pics) {
        List<JSONObject> order = new ArrayList<>();
        for (int i = 0; i < Math.max(recent.size(), fresh.size()); i++) {
            if (i < recent.size()) order.add(recent.get(i));
            if (i < fresh.size()) order.add(fresh.get(i));
        }
        if (order.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(PICTURE_THREADS);
        ExecutorCompletionService<JSONObject> done = new ExecutorCompletionService<>(pool);
        for (JSONObject o : order) {
            done.submit(() -> {
                pics.download(ctx, o);
                return o;
            });
        }
        Set<JSONObject> finished = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (int i = 1; i <= order.size(); i++) {
                finished.add(done.take().get());
                if (i == order.size()) break;   // the caller publishes the whole lists
                if (i == 1 || i % PUBLISH_EVERY == 0) {
                    JSONArray a = new JSONArray(), n = new JSONArray();
                    for (JSONObject o : recent) if (finished.contains(o)) a.put(o);
                    for (int k = 0; k < kept.length(); k++) n.put(kept.optJSONObject(k));
                    for (JSONObject o : fresh) if (finished.contains(o)) n.put(o);
                    p.edit().putString("recent_json_" + who, a.toString()).putString("new_json_" + who, n.toString())
                            .apply();
                    ctx.getContentResolver().notifyChange(PlexedProvider.RECENT_URI, null);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pictures", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Downloads, centre-crops to w x h and saves as id.jpg; an existing file is kept. */
    private static boolean savePicture(Context ctx, String id, String url, int W, int H) {
        File f = new File(posterDir(ctx), id + ".jpg");
        if (f.isFile() && f.length() > 0) return true;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(10000);
            con.setRequestProperty("User-Agent", "PlexedData/1.0");
            if (con.getResponseCode() != 200) return false;
            Bitmap src;
            try (InputStream in = con.getInputStream()) {
                src = BitmapFactory.decodeStream(in);
            }
            if (src == null) return false;
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);   // full colour: no banding in dark scenes
            float scale = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
            int cw = Math.round(W / scale), ch = Math.round(H / scale);
            int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
            new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, W, H),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            try (FileOutputStream fo = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, W >= 1000 ? 90 : 85, fo);
            }
            out.recycle();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "poster", e);
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String get(String url, boolean json, String token) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        if (!token.isEmpty()) con.setRequestProperty("X-Plex-Token", token);
        con.setConnectTimeout(5000);
        con.setReadTimeout(8000);
        if (json) con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("X-Plex-Product", "Plexed data");
        con.setRequestProperty("X-Plex-Client-Identifier", "plexed-data");
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
