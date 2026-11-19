package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Theme key recent_shelf (the data add-on's package): a "Recently watched" row in the gap DockDecoration leaves
 * under the dock band. It holds the add-on's items (Plex Continue Watching and the TV's Watch Next, with posters
 * the add-on saved, so the launcher stays offline). Episodes and movies only, newest first.
 */
final class RecentShelf {
    private static final int MAX_ITEMS = 10;
    /** The row's tiles against the app tiles' width. */
    private static final float SCALE = 1f;

    /** One tile: an episode or movie from the add-on, or an app. */
    static final class Item {
        String pkg, title, subtitle, intent, id;
        /** The hero's details line and time-left text (recent_hero), from the add-on. */
        String meta, left;
        /** The add-on saved a backdrop for it (poster/<id>-bg.jpg). */
        boolean backdrop;
        /** recent_hero: from Newly added rather than Continue watching (Play, not Resume). */
        boolean fresh;
        /** recent_hero: a recent title that is recent because it came out lately (else: because it was added). */
        boolean released;
        /** recent_hero: Continue watching's next episode of a show, not started yet (else: one to resume). */
        boolean upNext;
        /** recent_hero: a few sentences about it, for the description under the details. */
        String summary;
        int progress = -1;          // 0..1000, or -1 for none
        long time;
        Bitmap poster;
        Drawable banner;
    }

    final LinearLayout view;
    private final TextView label;
    private final Uri profiles;
    /** Called with the selected Plex profile's name and avatar (both null while nothing is linked). */
    java.util.function.BiConsumer<String, Bitmap> onProfile;
    private final LinearLayout row;
    private final HorizontalScrollView scroller;
    private final Context ctx;
    private final Handler main;
    private final Uri recent;
    private final String authority;
    private final float dp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ContentObserver observer;
    private boolean watching;
    private int tileWidth, tileHeight;
    private final List<View> tiles = new ArrayList<>();

    RecentShelf(Context ctx, Handler main, String authority) {
        this.ctx = ctx;
        this.main = main;
        this.authority = authority;
        this.recent = Uri.parse("content://" + authority + "/recent");
        this.profiles = Uri.parse("content://" + authority + "/profiles");
        dp = ctx.getResources().getDisplayMetrics().density;
        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        label = new TextView(ctx);
        label.setText(R.string.recent_shelf_title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setLetterSpacing(0.08f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(0xB3FFFFFF);
        view.addView(label);
        scroller = new HorizontalScrollView(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setSmoothScrollingEnabled(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row = new LinearLayout(ctx);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        scroller.addView(row);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = px(2);
        view.addView(scroller, sp);
        view.setVisibility(View.GONE);
        // The row is placed with a translation, which Android's focus search does not see: it would think the
        // tiles sit at the top of the screen and send Up from the dock into them. So they only take focus while
        // Home has moved focus into the row on purpose (setFocusEnabled).
        view.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                load();
            }
        };
    }

    /** The row's height for a tile this wide, so DockDecoration can leave exactly that much room. */
    int heightFor(int width) {
        int h = Math.round(width * SCALE * 9f / 16f);
        return px(18) + px(8) + h + px(50) + px(14);
    }

    /** Lays the tiles out to match the grid's: left edge and tile width taken from the dock's first tile. */
    void setGeometry(int left, int width, int gap) {
        // A little smaller than the app tiles, so the whole row (titles included) fits under the band.
        width = Math.round(width * SCALE);
        int h = Math.round(width * 9f / 16f);
        if (width == tileWidth && h == tileHeight && view.getPaddingLeft() == left) return;
        tileWidth = width;
        tileHeight = h;
        view.setPadding(left, 0, 0, 0);
        row.setPadding(0, px(10), left, px(10));
        for (View t : tiles) size(t, gap);
        this.gap = gap;
    }

    private int gap;

    /** True while the row shows tiles, or its placeholders during a profile switch (so it keeps its room). */
    boolean hasItems() {
        return !tiles.isEmpty() || skeletonFor != null;
    }

    boolean contains(View v) {
        for (ViewGroupParent p = new ViewGroupParent(v); p.view != null; p.up()) if (p.view == view) return true;
        return false;
    }

    /** Lets the row's tiles take focus (true while Home has put focus in the row), or keeps focus out of them. */
    void setFocusEnabled(boolean on) {
        view.setDescendantFocusability(on ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    /** Focus the tile nearest to the screen x of {@code from}'s centre. */
    void focusNear(View from) {
        if (tiles.isEmpty()) return;
        setFocusEnabled(true);
        int[] at = new int[2];
        int x = 0;
        if (from != null) {
            from.getLocationOnScreen(at);
            x = at[0] + from.getWidth() / 2;
        }
        View best = tiles.get(0);
        int bestD = Integer.MAX_VALUE;
        for (View t : tiles) {
            t.getLocationOnScreen(at);
            int d = Math.abs(at[0] + t.getWidth() / 2 - x);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        best.requestFocus();
    }

    int focusedIndex() {
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).hasFocus()) return i;
        return -1;
    }

    int count() {
        return tiles.size();
    }

    void start() {
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(recent, false, observer);
                ctx.getContentResolver().registerContentObserver(profiles, false, observer);
                watching = true;
            } catch (SecurityException ignored) {
            }
        }
        load();
    }

    void stop() {
        if (watching) ctx.getContentResolver().unregisterContentObserver(observer);
        watching = false;
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    /** Called with the item a tile shows when it takes focus, so Home can put its backdrop behind the row. */
    java.util.function.Consumer<Item> onFocus;

    /**
     * The item's backdrop, darkened for the clock above and the text below it (the art stays clear, as the owner
     * chose), decoded off the main thread; null when it has none or it cannot be read.
     */
    /** recent_art_corner: backdrops go in the top-right corner over a gradient of their colours (cornerArt). */
    boolean artCorner;

    void loadBackdrop(Item it, java.util.function.Consumer<Bitmap> done) {
        if (!it.backdrop || it.id == null) {
            done.accept(null);
            return;
        }
        io.execute(() -> {
            Bitmap out = null;
            try (InputStream in = ctx.getContentResolver().openInputStream(
                    Uri.parse("content://" + authority + "/poster/" + it.id + "-bg.jpg"))) {
                Bitmap src = BitmapFactory.decodeStream(in);
                if (src != null && artCorner) {
                    out = cornerArt(src);
                    src.recycle();
                } else if (src != null) {
                    out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.RGB_565);
                    android.graphics.Canvas c = new android.graphics.Canvas(out);
                    c.drawBitmap(src, 0, 0, null);
                    src.recycle();
                    int h = out.getHeight();
                    android.graphics.Paint p = new android.graphics.Paint();
                    // Darker than the art's own: 55% at the top (the clock), 40% in the middle, 72% at the bottom.
                    p.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                            new int[] {0x8C000000, 0x66000000, 0x66000000, 0xB8000000},
                            new float[] {0f, 0.26f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                    c.drawRect(0, 0, out.getWidth(), h, p);
                }
            } catch (Exception ignored) {
                out = null;
            }
            Bitmap result = out;
            main.post(() -> done.accept(result));
        });
    }

    /**
     * recent_art_corner: the art in the top-right two thirds, fading out to the left and towards the bottom, over a
     * deep gradient of its own main colours (worked out here, from a small copy; nothing is saved).
     */
    private static Bitmap cornerArt(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] main = mainColours(src);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        // the gradient: the main colour, deep, at the top; the second one, deeper still, at the bottom
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, scale(main[0], 0.42f), scale(main[1], 0.17f),
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // a soft glow of the main colour where the art sits, so the art melts into it
        p.setShader(new android.graphics.RadialGradient(w * 0.78f, h * 0.22f, w * 0.62f,
                (0x9E << 24) | (scale(main[0], 0.62f) & 0xFFFFFF), scale(main[0], 0.62f) & 0xFFFFFF,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // the art, top right, a little dimmed, faded with two masks on a layer of its own
        int aw = Math.round(w * 0.646f), ah = Math.round(h * 0.646f), left = w - aw;
        int layer = c.saveLayer(left, 0, w, ah, null);
        android.graphics.Paint art = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
        dim.setScale(0.86f, 0.86f, 0.86f, 1f);
        art.setColorFilter(new android.graphics.ColorMatrixColorFilter(dim));
        c.drawBitmap(src, null, new android.graphics.Rect(left, 0, w, ah), art);
        android.graphics.Paint fade = new android.graphics.Paint();
        fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        fade.setShader(new android.graphics.LinearGradient(left, 0, left + aw * 0.48f, 0,
                new int[] {0x00000000, 0x40000000, 0xFF000000}, new float[] {0f, 0.45f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(left, 0, w, ah, fade);
        fade.setShader(new android.graphics.LinearGradient(0, ah * 0.58f, 0, ah,
                new int[] {0xFF000000, 0x50000000, 0x00000000}, new float[] {0f, 0.55f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(left, 0, w, ah, fade);
        c.restoreToCount(layer);
        return out;
    }

    /** The picture's two most common colours, leaving out near-black and near-white. */
    static int[] mainColours(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, 48, 27, true);
        java.util.Map<Integer, long[]> buckets = new java.util.HashMap<>();
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int px = small.getPixel(x, y);
                int r = (px >> 16) & 255, g = (px >> 8) & 255, b = px & 255;
                int lum = (r + g + b) / 3;
                if (lum < 30 || lum > 220) continue;
                int key = (r >> 5) << 6 | (g >> 5) << 3 | (b >> 5);
                long[] s = buckets.computeIfAbsent(key, k -> new long[4]);
                s[0] += r;
                s[1] += g;
                s[2] += b;
                s[3]++;
            }
        }
        small.recycle();
        java.util.List<long[]> sorted = new java.util.ArrayList<>(buckets.values());
        sorted.sort((a, b) -> Long.compare(b[3], a[3]));
        int[] out = {0xFF4A4A55, 0xFF2A2A35};
        for (int i = 0; i < 2 && i < sorted.size(); i++) {
            long[] s = sorted.get(i);
            out[i] = 0xFF000000 | (int) (s[0] / s[3]) << 16 | (int) (s[1] / s[3]) << 8 | (int) (s[2] / s[3]);
        }
        if (sorted.size() == 1) out[1] = out[0];
        return out;
    }

    static int scale(int colour, float f) {
        return 0xFF000000 | Math.round(((colour >> 16) & 255) * f) << 16 | Math.round(((colour >> 8) & 255) * f) << 8
                | Math.round((colour & 255) * f);
    }

    /** Called back with whether the row has anything, so Home can open or close its gap. */
    Runnable onChanged;

    private void load() {
        io.execute(() -> {
            List<Item> items = new ArrayList<>();
                try (Cursor c = ctx.getContentResolver().query(recent, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    Item it = new Item();
                    it.pkg = c.getString(c.getColumnIndexOrThrow("package"));
                    if (!installed(it.pkg)) continue;
                    it.title = c.getString(c.getColumnIndexOrThrow("title"));
                    it.subtitle = c.getString(c.getColumnIndexOrThrow("subtitle"));
                    it.progress = c.getInt(c.getColumnIndexOrThrow("progress"));
                    it.time = c.getLong(c.getColumnIndexOrThrow("time_ms"));
                    it.intent = c.getString(c.getColumnIndexOrThrow("intent"));
                    it.id = c.getString(c.getColumnIndexOrThrow("id"));
                    int bi = c.getColumnIndex("backdrop");
                    it.backdrop = bi >= 0 && c.getInt(bi) == 1;
                    if (c.getInt(c.getColumnIndexOrThrow("poster")) == 1) {
                        String id = c.getString(c.getColumnIndexOrThrow("id"));
                        try (InputStream in = ctx.getContentResolver().openInputStream(
                                Uri.parse("content://" + authority + "/poster/" + id + ".jpg"))) {
                            BitmapFactory.Options o = new BitmapFactory.Options();
                            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory, no visible loss
                            it.poster = BitmapFactory.decodeStream(in, null, o);
                        } catch (Exception ignored) {
                        }
                    }
                    if (it.poster == null) it.banner = banner(it.pkg);
                    items.add(it);
                }
            } catch (Exception ignored) {
                // No add-on, or it is busy: the apps below still fill the row.
            }
            items.sort((a, b) -> Long.compare(b.time, a.time));
            // Whose list this is: the profile picked in Who's watching, if a Plex account is linked.
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
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            String whoName = who;
            Bitmap whoFace = face;
            main.post(() -> {
                pendingWho = whoName;
                if (onProfile != null) onProfile.accept(whoName, whoFace);
            });
            List<Item> top = items.size() > MAX_ITEMS ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : items;
            main.post(() -> showFor(top));
        });
    }

    /** The profile the row shows now, and the one the latest load was for. */
    private String shownWho, pendingWho;
    /** The row holds placeholder tiles for this profile while its first list is fetched. */
    private String skeletonFor;
    private boolean everShown;

    /**
     * Shows a load's items. A profile switch masks its wait: the new profile's saved list comes in with a staggered
     * fade and slide, and a profile with nothing saved yet shows pulsing placeholders until its list arrives.
     */
    private void showFor(List<Item> items) {
        String who = pendingWho;
        boolean switched = everShown && !java.util.Objects.equals(who, shownWho);
        boolean fromSkeleton = skeletonFor != null && skeletonFor.equals(who);
        shownWho = who;
        everShown = true;
        String heading = who == null ? "RECENTLY WATCHED"
                : "RECENTLY WATCHED  ·  " + who.toUpperCase(java.util.Locale.getDefault());
        if (!heading.contentEquals(label.getText())) {
            label.setText(heading);
            if (switched) {
                label.setAlpha(0f);
                label.animate().alpha(1f).setDuration(260).start();
            }
        }
        if (switched && items.isEmpty()) {
            skeletonFor = who;
            showSkeleton();
            return;
        }
        skeletonFor = null;
        show(items);
        if (switched || fromSkeleton) stagger();
    }

    /** The tiles come in one after another: fade in and rise a little, 45 ms apart. */
    private void stagger() {
        for (int i = 0; i < tiles.size(); i++) {
            View t = tiles.get(i);
            t.setAlpha(0f);
            t.setTranslationY(14 * dp);
            t.animate().alpha(1f).translationY(0f).setStartDelay(i * 45L).setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    /** Five quiet placeholders, pulsing gently, the size of the real tiles. */
    private void showSkeleton() {
        row.removeAllViews();
        tiles.clear();
        for (int i = 0; i < 5; i++) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            View card = new View(ctx);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(10));
            g.setColor(0x26FFFFFF);
            card.setBackground(g);
            box.addView(card);
            for (int line = 0; line < 2; line++) {
                View bar = new View(ctx);
                GradientDrawable b = new GradientDrawable();
                b.setCornerRadius(px(3));
                b.setColor(line == 0 ? 0x2EFFFFFF : 0x1FFFFFFF);
                bar.setBackground(b);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        Math.round(tileWidth * (line == 0 ? 0.6f : 0.4f)), px(line == 0 ? 12 : 9));
                lp.topMargin = px(line == 0 ? 10 : 7);
                box.addView(bar, lp);
            }
            size(box, gap);
            row.addView(box);
            android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(box, View.ALPHA, 1f, 0.45f);
            pulse.setDuration(800);
            pulse.setStartDelay(i * 90L);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.start();
        }
        view.setVisibility(View.VISIBLE);
        if (onChanged != null) onChanged.run();
    }

    private void show(List<Item> items) {
        boolean hadFocus = view.hasFocus();
        int focused = focusedIndex();
        row.removeAllViews();
        tiles.clear();
        for (Item it : items) {
            View t = tile(it);
            tiles.add(t);
            row.addView(t);
        }
        view.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (hadFocus && !tiles.isEmpty()) tiles.get(Math.max(0, Math.min(focused, tiles.size() - 1))).requestFocus();
        if (onChanged != null) onChanged.run();
    }

    private View tile(Item it) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setClipChildren(false);
        FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(10));
        bg.setColor(0xFF1E1E22);
        card.setBackground(bg);
        card.setClipToOutline(true);
        ImageView img = new ImageView(ctx);
        if (it.poster != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(it.poster);
        } else if (it.banner != null) {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(it.banner);
        }
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (it.progress >= 0) {
            FrameLayout track = new FrameLayout(ctx);
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(px(2));
            tb.setColor(0x59FFFFFF);
            track.setBackground(tb);
            View fill = new View(ctx);
            GradientDrawable fb = new GradientDrawable();
            fb.setCornerRadius(px(2));
            fb.setColor(0xFFE5A00D);
            fill.setBackground(fb);
            track.addView(fill);
            fill.setTag(it.progress);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4),
                    Gravity.BOTTOM);
            tp.setMargins(px(10), 0, px(10), px(8));
            card.addView(track, tp);
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int w = Math.round((r - l) * Math.min(1000, Math.max(0, it.progress)) / 1000f);
                if (fill.getLayoutParams() == null || fill.getLayoutParams().width != w) {
                    fill.setLayoutParams(new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
                }
            });
        }
        box.addView(card);
        TextView title = text(it.title, 14, 0xD9FFFFFF);
        TextView sub = text(it.subtitle, 11, 0x8CFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(6);
        box.addView(title, lp);
        box.addView(sub);
        box.setOnFocusChangeListener((v, has) -> {
            card.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .translationY(has ? -6 * dp : 0f).translationZ(has ? 16 * dp : 0f)
                    .setDuration(has ? 300 : 200).setInterpolator(new DecelerateInterpolator(2f)).start();
            title.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
            if (has && onFocus != null) onFocus.accept(it);
            if (has) {
                // Keep the focused tile in view as the row scrolls.
                int x = v.getLeft() - px(24);
                scroller.smoothScrollTo(Math.max(0, x - scroller.getWidth() / 3), 0);
            }
        });
        box.setOnClickListener(v -> open(it));
        size(box, gap);
        return box;
    }

    private void size(View box, int gap) {
        if (tileWidth <= 0) return;
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = gap;
        box.setLayoutParams(p);
        View card = ((ViewGroup) box).getChildAt(0);
        card.setLayoutParams(new LinearLayout.LayoutParams(tileWidth, tileHeight));
    }

    private void open(Item it) {
        LastOpened.record(ctx, it.pkg);
        Intent intent = null;
        if (it.intent != null && !it.intent.isEmpty()) {
            try {
                intent = it.intent.startsWith("intent:") || it.intent.contains("#Intent;")
                        ? Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME)
                        : new Intent(Intent.ACTION_VIEW, Uri.parse(it.intent)).setPackage(it.pkg);
            } catch (Exception ignored) {
                intent = null;
            }
        }
        if (intent != null) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // fall through to the app itself
            }
        }
        RemoteKeys.launch(ctx, it.pkg);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private boolean installed(String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private Drawable banner(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable d = launch != null ? pm.getActivityBanner(launch) : null;
            if (d == null) d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
            return d;
        } catch (Exception e) {
            return null;
        }
    }



    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s == null ? "" : s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    /** Walks up a view's parents. */
    private static final class ViewGroupParent {
        View view;

        ViewGroupParent(View v) {
            view = v;
        }

        void up() {
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
    }
}
