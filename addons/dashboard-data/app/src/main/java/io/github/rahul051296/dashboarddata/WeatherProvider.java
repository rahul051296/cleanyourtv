package io.github.rahul051296.dashboarddata;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only data for the launcher's Home widgets, fetched only when asked and only when old:
 * <ul>
 * <li>{@code /now}: the weather now, today's high and low, plus a {@code detail} JSON column with the next hours,
 * sunrise, sunset and the US AQI (Open-Meteo, refreshed after 30 minutes);</li>
 * <li>{@code /stocks}: a {@code json} column with each ticker's price and day change (Yahoo Finance chart data,
 * delayed, refreshed after 15 minutes).</li>
 * </ul>
 * A fetch runs in the background and notifies the URI when it lands, so the reader queries again.
 */
public final class WeatherProvider extends ContentProvider {
    static final String PREFS = "weather";
    static final String AUTHORITY = "io.github.rahul051296.dashboarddata.weather";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/now");
    static final Uri STOCKS_URI = Uri.parse("content://" + AUTHORITY + "/stocks");
    static final String DEFAULT_TICKERS = "";   // none until the owner sets them (README)
    private static final String[] COLUMNS = {"temp", "high", "low", "code", "is_day", "updated_ms", "unit", "detail",
            "place", "from_time_zone"};
    /** configured is 0 while no tickers are set: the launcher hides the stocks card then. */
    private static final String[] STOCK_COLUMNS = {"json", "updated_ms", "configured"};
    private static final long FRESH_MS = 30 * 60_000L, STOCKS_FRESH_MS = 15 * 60_000L, RETRY_MS = 5 * 60_000L;
    private static final AtomicBoolean FETCHING = new AtomicBoolean(), FETCHING_STOCKS = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context ctx = getContext();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if ("stocks".equals(uri.getLastPathSegment())) {
            MatrixCursor c = new MatrixCursor(STOCK_COLUMNS, 1);
            long updated = p.getLong("stocks_updated", 0);
            boolean configured = !tickers(p).isEmpty();
            if (configured && now - updated > STOCKS_FRESH_MS && now - p.getLong("stocks_tried", 0) > RETRY_MS) {
                fetchStocks(ctx);
            }
            c.addRow(new Object[] {updated > 0 && configured ? p.getString("stocks_json", "[]") : null, updated,
                    configured ? 1 : 0});
            c.setNotificationUri(ctx.getContentResolver(), STOCKS_URI);
            return c;
        }
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        TimeZoneCities.Place place = place(p);
        long updated = p.getLong("updated", 0);
        // A reading for another place (the time zone changed, or a place was set) counts as old.
        if (place != null && !key(place).equals(p.getString("fetched_for", ""))) updated = 0;
        if (place != null && now - updated > FRESH_MS && now - p.getLong("tried", 0) > RETRY_MS) fetchNow(ctx);
        if (updated > 0) {
            c.addRow(new Object[] {p.getInt("temp", 0), p.getInt("high", 0), p.getInt("low", 0), p.getInt("code", 0),
                    p.getInt("is_day", 1), updated, unit(p), p.getString("detail", "{}"),
                    place.name, place.fromTimeZone ? 1 : 0});
        }
        c.setNotificationUri(ctx.getContentResolver(), URI);
        return c;
    }

    /** The owner's place, or else the main city of the TV's time zone; null when neither is known. */
    static TimeZoneCities.Place place(SharedPreferences p) {
        if (p.contains("lat") && p.contains("lon")) {
            return new TimeZoneCities.Place(p.getString("lat", "0"), p.getString("lon", "0"), p.getString("place", ""), false);
        }
        return TimeZoneCities.forZone(java.util.TimeZone.getDefault().getID());
    }

    private static String key(TimeZoneCities.Place place) {
        return place.lat + "," + place.lon;
    }

    /** The owner's unit, or °F/°C by the TV's country. */
    static String unit(SharedPreferences p) {
        return p.getString("unit", TimeZoneCities.defaultUnit());
    }

    /** The owner's tickers that look like tickers, at most four. */
    static java.util.List<String> tickers(SharedPreferences p) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String raw : p.getString("tickers", DEFAULT_TICKERS).split(",")) {
            String t = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (t.matches("[A-Z0-9.^=-]{1,12}") && out.size() < 4) out.add(t);
        }
        return out;
    }

    static void fetchNow(Context app) {
        Context ctx = app.getApplicationContext();
        if (!FETCHING.compareAndSet(false, true)) return;
        new Thread(() -> {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putLong("tried", System.currentTimeMillis()).apply();
            try {
                TimeZoneCities.Place place = place(p);
                if (place == null) return;
                String unit = unit(p);
                String lat = place.lat, lon = place.lon;
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&hourly=temperature_2m,weather_code,is_day&forecast_hours=9"
                        + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
                        + "&forecast_days=1&timezone=auto"
                        + ("f".equals(unit) ? "&temperature_unit=fahrenheit" : "");
                JSONObject json;
                try {
                    json = new JSONObject(get(url, 6_000));
                } catch (Exception openMeteoDown) {
                    // api.open-meteo.com unreachable (blocked or down): the same reading from wttr.in.
                    android.util.Log.w("WeatherLine", "Open-Meteo failed, trying wttr.in: " + openMeteoDown);
                    fromWttr(ctx, p, lat, lon, unit);
                    return;
                }
                JSONObject cur = json.getJSONObject("current");
                JSONObject daily = json.getJSONObject("daily");
                JSONObject hourly = json.getJSONObject("hourly");
                JSONObject detail = new JSONObject();
                JSONArray hours = new JSONArray();
                JSONArray times = hourly.getJSONArray("time"), temps = hourly.getJSONArray("temperature_2m"),
                        codes = hourly.getJSONArray("weather_code"), days = hourly.getJSONArray("is_day");
                // Every second hour from the next one: four slots over the next eight hours.
                for (int i = 1; i < times.length() && hours.length() < 4; i += 2) {
                    hours.put(new JSONObject().put("time", times.getString(i))
                            .put("temp", (int) Math.round(temps.getDouble(i)))
                            .put("code", codes.getInt(i)).put("is_day", days.optInt(i, 1)));
                }
                detail.put("hours", hours);
                detail.put("sunrise", daily.getJSONArray("sunrise").getString(0));
                detail.put("sunset", daily.getJSONArray("sunset").getString(0));
                putAqi(detail, lat, lon);
                p.edit()
                        .putInt("temp", (int) Math.round(cur.getDouble("temperature_2m")))
                        .putInt("code", cur.getInt("weather_code"))
                        .putInt("is_day", cur.optInt("is_day", 1))
                        .putInt("high", (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                        .putInt("low", (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)))
                        .putString("detail", detail.toString())
                        .putString("fetched_for", lat + "," + lon)
                        .putLong("updated", System.currentTimeMillis())
                        .apply();
                ctx.getContentResolver().notifyChange(URI, null);
            } catch (Exception e) {
                // Offline or a bad answer: keep the last reading; the next query after RETRY_MS tries again.
                android.util.Log.w("WeatherLine", "weather fetch failed", e);
            } finally {
                FETCHING.set(false);
            }
        }, "weather-fetch").start();
    }

    private static void putAqi(JSONObject detail, String lat, String lon) {
        try {
            JSONObject aq = new JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
                    + lat + "&longitude=" + lon + "&current=us_aqi"));
            detail.put("aqi", aq.getJSONObject("current").getInt("us_aqi"));
        } catch (Exception ignored) {
            // Air quality is optional; the line leaves it out.
        }
    }

    /** wttr.in's j1 answer in the same shape as Open-Meteo's: WWO codes mapped to WMO, 3-hour slots. */
    private static void fromWttr(Context ctx, SharedPreferences p, String lat, String lon, String unit) throws Exception {
        boolean f = "f".equals(unit);
        JSONObject d = new JSONObject(get("https://wttr.in/" + lat + "," + lon + "?format=j1"));
        JSONObject cur = d.getJSONArray("current_condition").getJSONObject(0);
        JSONArray days = d.getJSONArray("weather");
        JSONObject today = days.getJSONObject(0);
        JSONObject astro = today.getJSONArray("astronomy").getJSONObject(0);
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US);
        java.text.SimpleDateFormat ampm = new java.text.SimpleDateFormat("yyyy-MM-dd hh:mm a", java.util.Locale.US);
        String date = today.getString("date");
        java.util.Date rise = ampm.parse(date + " " + astro.getString("sunrise"));
        java.util.Date set = ampm.parse(date + " " + astro.getString("sunset"));
        long now = System.currentTimeMillis();
        JSONArray hours = new JSONArray();
        for (int di = 0; di < days.length() && hours.length() < 4; di++) {
            JSONObject day = days.getJSONObject(di);
            JSONArray hourly = day.getJSONArray("hourly");
            for (int i = 0; i < hourly.length() && hours.length() < 4; i++) {
                JSONObject h = hourly.getJSONObject(i);
                int hhmm = Integer.parseInt(h.getString("time"));
                String when = String.format(java.util.Locale.US, "%sT%02d:%02d", day.getString("date"), hhmm / 100, hhmm % 100);
                java.util.Date t = iso.parse(when);
                if (t == null || t.getTime() <= now) continue;
                int hour = hhmm / 100;
                hours.put(new JSONObject().put("time", when).put("temp", h.getInt(f ? "tempF" : "tempC"))
                        .put("code", wmo(h.getInt("weatherCode"))).put("is_day", hour >= 7 && hour < 17 ? 1 : 0));
            }
        }
        JSONObject detail = new JSONObject().put("hours", hours)
                .put("sunrise", iso.format(rise)).put("sunset", iso.format(set));
        putAqi(detail, lat, lon);
        boolean day = now >= rise.getTime() && now < set.getTime();
        p.edit()
                .putInt("temp", cur.getInt(f ? "temp_F" : "temp_C"))
                .putInt("code", wmo(cur.getInt("weatherCode")))
                .putInt("is_day", day ? 1 : 0)
                .putInt("high", today.getInt(f ? "maxtempF" : "maxtempC"))
                .putInt("low", today.getInt(f ? "mintempF" : "mintempC"))
                .putString("detail", detail.toString())
                .putString("fetched_for", lat + "," + lon)
                .putLong("updated", System.currentTimeMillis())
                .apply();
        ctx.getContentResolver().notifyChange(URI, null);
    }

    /** A WorldWeatherOnline code (wttr.in) as the nearest WMO code (Open-Meteo). */
    static int wmo(int wwo) {
        switch (wwo) {
            case 113: return 0;
            case 116: return 2;
            case 119: case 122: return 3;
            case 143: case 248: case 260: return 45;
            case 200: case 386: case 389: case 392: case 395: return 95;
            case 179: case 182: case 185: case 227: case 230: case 317: case 320: case 323: case 326: case 329:
            case 332: case 335: case 338: case 350: case 362: case 365: case 368: case 371: case 374: case 377:
                return 71;
            default: return 61;
        }
    }

    static void fetchStocks(Context app) {
        Context ctx = app.getApplicationContext();
        if (!FETCHING_STOCKS.compareAndSet(false, true)) return;
        new Thread(() -> {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putLong("stocks_tried", System.currentTimeMillis()).apply();
            try {
                JSONArray out = new JSONArray();
                long now = System.currentTimeMillis() / 1000;
                for (String t : tickers(p)) {
                    try {
                        JSONObject meta = new JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/" + t
                                + "?range=1d&interval=1d")).getJSONObject("chart").getJSONArray("result")
                                .getJSONObject(0).getJSONObject("meta");
                        double price = meta.getDouble("regularMarketPrice");
                        double prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price));
                        boolean open = false;
                        JSONObject period = meta.optJSONObject("currentTradingPeriod");
                        if (period != null && period.optJSONObject("regular") != null) {
                            JSONObject reg = period.getJSONObject("regular");
                            open = now >= reg.optLong("start") && now < reg.optLong("end");
                        }
                        out.put(new JSONObject().put("symbol", t).put("price", price)
                                .put("change_pct", prev > 0 ? (price - prev) / prev * 100 : 0).put("open", open));
                    } catch (Exception one) {
                        // One ticker failing leaves the others.
                    }
                }
                if (out.length() > 0) {
                    p.edit().putString("stocks_json", out.toString()).putLong("stocks_updated", System.currentTimeMillis())
                            .apply();
                    ctx.getContentResolver().notifyChange(STOCKS_URI, null);
                }
            } finally {
                FETCHING_STOCKS.set(false);
            }
        }, "stocks-fetch").start();
    }

    private static String get(String url) throws Exception {
        return get(url, 15_000);
    }

    private static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(Math.min(10_000, timeoutMs));
        con.setReadTimeout(timeoutMs);
        // Yahoo refuses requests with no browser-like agent.
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
        try {
            if (con.getResponseCode() != 200) throw new IllegalStateException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            con.disconnect();
        }
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
