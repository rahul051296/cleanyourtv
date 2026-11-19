package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 * recent_hero's backgrounds. Each item's backdrop comes from the data add-on and is composed on the TV: wide and
 * centred at the top, fading into a U of its own colours. The current item's picture and its two neighbours' are
 * kept ready (three in all), so a step or the slideshow's turn shows the new picture with the new text.
 */
final class RecentHeroArt {
    private static final int KEPT = 3;
    /** For an item with no backdrop of its own. */
    /** The neutral colour for anything without one of its own. */
    static final int PLAIN = 0xFF5A6478;

    private final Context ctx;
    private final Handler main;
    private final ExecutorService io;
    private final String authority;
    /** Puts a composed picture behind Home (the bitmap, and a key for the wallpaper player). */
    private final BiConsumer<Bitmap, String> onArt;
    private final Map<String, Bitmap> kept = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > KEPT;
        }
    };
    private final Set<String> preparing = new HashSet<>();
    private String wanted, currentKey;
    /** Run once the wanted item's picture is behind Home (the hero then shows that item's text). */
    private Runnable onShown;
    private Bitmap current;

    RecentHeroArt(Context ctx, Handler main, ExecutorService io, String authority, BiConsumer<Bitmap, String> onArt) {
        this.ctx = ctx;
        this.main = main;
        this.io = io;
        this.authority = authority;
        this.onArt = onArt;
    }

    /** Whether item `id`'s picture is composed already, so showing it is immediate. */
    boolean ready(String id) {
        return id != null && kept.containsKey(id);
    }

    /**
     * Shows item `at`'s picture (at once when it is ready, else when it is), runs `shown` once it is behind Home,
     * then readies its neighbours'. A later call replaces an earlier one still waiting: only the last is shown.
     */
    void show(List<RecentShelf.Item> items, int at, Runnable shown) {
        RecentShelf.Item it = items.get(at);
        wanted = it.id;
        onShown = shown;
        Bitmap ready = kept.get(it.id);
        if (ready != null) {
            apply(it.id, ready);
            prepareNeighbours(items, at);
            return;
        }
        if (!it.backdrop || it.id == null) {
            apply(it.id, colourGradient(PLAIN));
            return;
        }
        io.execute(() -> {
            Bitmap result = compose(it);
            main.post(() -> {
                if (result != null) kept.put(it.id, result);
                if (it.id.equals(wanted)) apply(it.id, result != null ? result : colourGradient(PLAIN));
                if (result != null) prepareNeighbours(items, at);
            });
        });
    }

    /** Puts the current picture behind Home again (after Home came back from under All apps). */
    void reapply() {
        if (current != null) onArt.accept(current, currentKey);
    }

    /** Nothing to show any more. */
    void forget() {
        current = null;
    }

    private void apply(String id, Bitmap art) {
        current = art;
        currentKey = "hero:" + id;
        onArt.accept(art, currentKey);
        Runnable shown = onShown;
        onShown = null;
        if (shown != null) shown.run();
    }

    /** The next and the previous item's pictures, composed ahead (the cache holds them with the current one). */
    private void prepareNeighbours(List<RecentShelf.Item> items, int at) {
        int n = items.size();
        if (n < 2) return;
        for (int d : new int[] {1, -1}) {
            RecentShelf.Item nb = items.get((at + d + n) % n);
            if (nb.id == null || !nb.backdrop || kept.containsKey(nb.id) || !preparing.add(nb.id)) continue;
            io.execute(() -> {
                Bitmap result = compose(nb);
                main.post(() -> {
                    preparing.remove(nb.id);
                    if (result != null && !kept.containsKey(nb.id)) kept.put(nb.id, result);
                });
            });
        }
    }

    /** An item's backdrop from the add-on, composed (U gradient and all); null when it cannot be read. */
    private Bitmap compose(RecentShelf.Item it) {
        try (InputStream in = ctx.getContentResolver().openInputStream(
                Uri.parse("content://" + authority + "/poster/" + it.id + "-bg.jpg"))) {
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null) return null;
            Bitmap trimmed = trimBars(src);
            Bitmap out = uArt(trimmed);
            if (trimmed != src) trimmed.recycle();
            src.recycle();
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- art composition -----------------------------------------------------------------------------------------

    /** Some art carries black bars of its own; trims them, keeping 16:9 about the middle. */
    static Bitmap trimBars(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int top = 0, bottom = h - 1;
        while (top < h && rowDark(src, top)) top++;
        while (bottom > top && rowDark(src, bottom)) bottom--;
        int cut = top + (h - 1 - bottom);
        if (cut < 3 || top > h * 0.15 || h - 1 - bottom > h * 0.15) return src;   // none, or too much to be bars
        int nh = bottom - top + 1;
        int nw = Math.min(w, nh * 16 / 9);
        return Bitmap.createBitmap(src, (w - nw) / 2, top, nw, nh);
    }

    private static boolean rowDark(Bitmap b, int y) {
        int w = b.getWidth(), sum = 0;
        for (int i = 0; i < 32; i++) {
            int px = b.getPixel(i * (w - 1) / 31, y);
            sum += (Color.red(px) + Color.green(px) + Color.blue(px)) / 3;
        }
        return sum / 32 < 16;
    }

    /**
     * The picture over the whole screen, with a U of its own colours over it: deep on the left and right edges and
     * along the bottom, clear in the middle and at the top. The text sits in the bottom of the U.
     */
    static Bitmap uArt(Bitmap src) {
        // 1920x1080: the picture the add-on saves, drawn at the screen's own size.
        final int w = 1920, h = 1080;
        int[] main = RecentShelf.mainColours(src);
        int side = RecentShelf.scale(main[0], 0.34f) & 0xFFFFFF;
        int base = RecentShelf.scale(main[1], 0.20f) & 0xFFFFFF;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        // the page colour: the art's own tone at the top, deeper towards the bottom
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF000000 | side, 0xFF000000 | base, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // the art: 90% of the screen, centred at the top, its left, right and bottom edges melting into the page colour
        int aw = Math.round(w * 0.9f), ah = Math.round(h * 0.9f), left = (w - aw) / 2;
        int layer = c.saveLayer(left, 0, left + aw, ah, null);
        Paint art = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.92f, 0.92f, 0.92f, 1f);
        art.setColorFilter(new ColorMatrixColorFilter(dim));
        c.drawBitmap(src, null, new Rect(left, 0, left + aw, ah), art);
        Paint mask = new Paint();
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        mask.setShader(new LinearGradient(left, 0, left + aw, 0,
                new int[] {0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(left, 0, left + aw, ah, mask);
        mask.setShader(new LinearGradient(0, 0, 0, ah, new int[] {0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(left, 0, left + aw, ah, mask);
        c.restoreToCount(layer);
        // a little shade behind the clock and the buttons
        p.setShader(new LinearGradient(0, 0, 0, h * 0.2f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.2f, p);
        return out;
    }

    /** A background from one colour: a glow of it at the top right, a faint echo bottom left, darker below. */
    static Bitmap colourGradient(int colour) {
        final int w = 960, h = 540;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF07080B);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int main = lift(colour, 215) & 0xFFFFFF, echo = lift(colour, 165) & 0xFFFFFF;
        p.setShader(new RadialGradient(w * 0.84f, h * 0.10f, w * 0.95f, (0xA8 << 24) | main, main,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.02f, h * 1.05f, w * 0.62f, (0x55 << 24) | echo, echo,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    private static int lift(int colour, int peak) {
        int r = Color.red(colour), g = Color.green(colour), bl = Color.blue(colour);
        int m = Math.max(1, Math.max(r, Math.max(g, bl)));
        return Color.rgb(Math.min(255, r * peak / m), Math.min(255, g * peak / m), Math.min(255, bl * peak / m));
    }
}
