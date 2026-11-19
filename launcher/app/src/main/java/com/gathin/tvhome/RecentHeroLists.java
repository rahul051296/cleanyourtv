package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * recent_hero's data: the add-on's two lists, Continue watching and the recent titles (added or released lately),
 * and the Plex profile they follow, read off the main thread; and the rule for their order in the carousel.
 */
final class RecentHeroLists {
    /** The carousel's slides, and how many of them go to Continue watching (the rest: recent titles at random). */
    static final int SLIDES = 12, WATCHING_SLIDES = 4;

    /** One read of the add-on. */
    static final class Snapshot {
        final List<RecentShelf.Item> watching, recent;
        /** The profile's first name, and its picture; null while no Plex account is linked. */
        final String who;
        final Bitmap face;
        /** The add-on is fetching: more is on its way. */
        final boolean fetching;

        Snapshot(List<RecentShelf.Item> watching, List<RecentShelf.Item> recent, String who, Bitmap face,
                 boolean fetching) {
            this.watching = watching;
            this.recent = recent;
            this.who = who;
            this.face = face;
            this.fetching = fetching;
        }
    }

    final Uri recent, profiles;
    private final Uri fresh;
    private final Context ctx;
    private final String authority;
    /** Set by the last read of /recent (its extras); an older add-on never sets it. */
    private boolean fetching;

    RecentHeroLists(Context ctx, String authority) {
        this.ctx = ctx;
        this.authority = authority;
        recent = Uri.parse("content://" + authority + "/recent");
        profiles = Uri.parse("content://" + authority + "/profiles");
        fresh = Uri.parse("content://" + authority + "/new");
    }

    /** Home came back: a list older than this is fetched again (what was watched meanwhile shows a moment later). */
    private static final long COME_BACK_MAX_AGE_MS = 30_000L;

    /**
     * Both lists and the profile, as the add-on has them now; `cameBack` asks it to fetch a fresh one when its list
     * is more than COME_BACK_MAX_AGE_MS old. Blocks: call it off the main thread.
     */
    Snapshot read(boolean cameBack) {
        String who = null;
        Bitmap face = null;
        try (Cursor p = ctx.getContentResolver().query(profiles, null, null, null, null)) {
            while (p != null && p.moveToNext()) {
                if (p.getInt(p.getColumnIndexOrThrow("selected")) != 1) continue;
                who = p.getString(p.getColumnIndexOrThrow("name"));
                if (p.getInt(p.getColumnIndexOrThrow("avatar")) == 1) {
                    String uuid = p.getString(p.getColumnIndexOrThrow("uuid")).replaceAll("[^A-Za-z0-9_-]", "");
                    try (InputStream in = ctx.getContentResolver().openInputStream(
                            Uri.parse("content://" + authority + "/avatar/" + uuid + ".png"))) {
                        face = BitmapFactory.decodeStream(in);
                    } catch (Exception ignored) {
                        // no picture: the capsule shows the name alone
                    }
                }
            }
        } catch (Exception ignored) {
            // no add-on, or an older one without profiles: no capsule
        }
        fetching = false;
        List<RecentShelf.Item> watching = list(cameBack
                ? recent.buildUpon().appendQueryParameter("max_age", String.valueOf(COME_BACK_MAX_AGE_MS)).build()
                : recent, false);
        return new Snapshot(watching, list(fresh, true), who, face, fetching);
    }

    /**
     * The carousel's order, SLIDES in all: first the WATCHING_SLIDES most recently watched, newest first, then
     * recent titles picked at random (more of them when fewer are being watched), then, when there are not enough
     * recent titles, more of Continue watching. `repick` (each time Home comes back, or for a new profile) picks the
     * recent titles afresh; otherwise the ones on screen stay where they are and only gaps get new picks, so nothing
     * moves under the eye when a fresh list comes in.
     */
    static List<RecentShelf.Item> arrange(List<RecentShelf.Item> watching, List<RecentShelf.Item> recent,
            List<RecentShelf.Item> previous, boolean repick, Random random) {
        int first = Math.min(WATCHING_SLIDES, watching.size());
        List<RecentShelf.Item> slides = new ArrayList<>(watching.subList(0, first));
        for (RecentShelf.Item it : picks(recent, previous, repick, random)) {
            if (slides.size() == SLIDES) break;
            slides.add(it);
        }
        for (int i = first; i < watching.size() && slides.size() < SLIDES; i++) slides.add(watching.get(i));
        return slides;
    }

    /** The recent titles in slide order: shuffled, or (keeping) those already on screen first, in their order. */
    private static List<RecentShelf.Item> picks(List<RecentShelf.Item> recent, List<RecentShelf.Item> previous,
            boolean repick, Random random) {
        List<RecentShelf.Item> rest = new ArrayList<>(recent);
        Collections.shuffle(rest, random);
        if (repick) return rest;
        Map<String, RecentShelf.Item> byId = new LinkedHashMap<>();
        for (RecentShelf.Item it : rest) byId.put(it.id, it);
        List<RecentShelf.Item> kept = new ArrayList<>();
        for (RecentShelf.Item old : previous) {
            RecentShelf.Item same = byId.remove(old.id);
            if (same != null) kept.add(same);
        }
        kept.addAll(byId.values());
        return kept;
    }

    /** One of the add-on's lists, newest first; `fresh` for the recent titles. */
    private List<RecentShelf.Item> list(Uri uri, boolean fresh) {
        List<RecentShelf.Item> list = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && !fresh) fetching = c.getExtras().getBoolean("fetching");
            while (c != null && c.moveToNext()) {
                RecentShelf.Item it = new RecentShelf.Item();
                it.pkg = c.getString(c.getColumnIndexOrThrow("package"));
                if (!installed(it.pkg)) continue;
                it.id = c.getString(c.getColumnIndexOrThrow("id"));
                it.title = c.getString(c.getColumnIndexOrThrow("title"));
                it.subtitle = c.getString(c.getColumnIndexOrThrow("subtitle"));
                it.progress = c.getInt(c.getColumnIndexOrThrow("progress"));
                it.time = c.getLong(c.getColumnIndexOrThrow("time_ms"));
                it.intent = c.getString(c.getColumnIndexOrThrow("intent"));
                it.backdrop = "1".equals(optional(c, "backdrop"));
                it.meta = optional(c, "meta");
                it.left = optional(c, "left");
                it.summary = optional(c, "summary");
                it.fresh = fresh;
                String kind = optional(c, "kind");
                it.released = "released".equals(kind);
                it.upNext = "next".equals(kind);
                list.add(it);
            }
        } catch (Exception ignored) {
            // No add-on, or it is busy: the hero stays as it was.
        }
        list.sort((a, b) -> Long.compare(b.time, a.time));
        return list;
    }

    /** A column an older add-on may not have, as text ("" when it is missing or empty). */
    private static String optional(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        String v = i < 0 ? null : c.getString(i);
        return v == null ? "" : v;
    }

    private boolean installed(String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
