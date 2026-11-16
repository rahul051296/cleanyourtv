package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * weather_line (a theme's config.json): the weather under the clock, "☁ 8°  H 11° L 4°", from an add-on that
 * publishes it (Weather line). The launcher only reads what the add-on publishes and stays offline; a reading
 * older than three hours is not shown.
 */
final class WeatherLine {
    static final String META = "com.gathin.tvhome.weather";
    private static final String[] COLUMNS = {"temp", "high", "low", "code", "is_day", "updated_ms", "unit"};
    private static final long STALE_MS = 3 * 60 * 60_000L;
    /** While Home stays in front, ask again this often; the add-on fetches only when its reading is old. */
    private static final long POLL_MS = 10 * 60_000L;

    interface Listener {
        void onWeather(String line);
    }

    private final Context ctx;
    private final Uri uri;
    private final Handler main;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ContentObserver observer;
    private final Runnable poll = this::pollTick;
    private boolean watching;

    private WeatherLine(Context ctx, Uri uri, Handler main, Listener listener) {
        this.ctx = ctx;
        this.uri = uri;
        this.main = main;
        this.listener = listener;
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
    }

    /**
     * The provider authority the add-on {@code pkg} names in its {@value #META} meta-data, if that provider is the
     * package's own; null when the package is empty, missing or names none. Naming the package is the owner's opt-in.
     */
    static String authority(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META);
            if (authority == null || authority.trim().isEmpty()) return null;
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            return provider != null && pkg.trim().equals(provider.packageName) ? authority.trim() : null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Null when the package is empty, not installed, or names no provider of its own in a {@value #META} meta-data. */
    static WeatherLine create(Context ctx, String pkg, Handler main, Listener listener) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META);
            if (authority == null || authority.trim().isEmpty()) return null;
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            if (provider == null || !pkg.trim().equals(provider.packageName)) return null;
            Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority.trim())
                    .appendPath("now").build();
            return new WeatherLine(ctx, uri, main, listener);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    void start() {
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(uri, false, observer);
                watching = true;
            } catch (SecurityException e) {
                // The add-on went away; the line keeps what it had.
            }
        }
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_MS);
        read();
    }

    void stop() {
        main.removeCallbacks(poll);
        if (watching) ctx.getContentResolver().unregisterContentObserver(observer);
        watching = false;
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    private void pollTick() {
        read();
        main.postDelayed(poll, POLL_MS);
    }

    private void read() {
        try {
            io.execute(() -> {
                String line = null;
                try (Cursor c = ctx.getContentResolver().query(uri, COLUMNS, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        long updated = c.getLong(5);
                        if (System.currentTimeMillis() - updated < STALE_MS) {
                            line = format(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4) != 0);
                        }
                    }
                } catch (RuntimeException gone) {
                    return;
                }
                String result = line;
                main.post(() -> listener.onWeather(result));
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    /** "☁ 8°  H 11° L 4°". The symbols carry U+FE0E so they draw as text, not as colour emoji. */
    static String format(int temp, int high, int low, int code, boolean day) {
        return symbol(code, day) + "︎  " + temp + "°    H " + high + "°  L " + low + "°";
    }

    /** A WMO weather code as one symbol. */
    static String symbol(int code, boolean day) {
        if (code == 0) return day ? "☀" : "☾";          // clear: sun, or a moon at night
        if (code <= 2) return day ? "⛅" : "☁";          // partly cloudy
        if (code == 3 || code == 45 || code == 48) return "☁"; // overcast, fog
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄"; // snow
        if (code >= 95) return "⚡";                         // thunderstorm
        return "☂";                                          // drizzle, rain, showers
    }
}
