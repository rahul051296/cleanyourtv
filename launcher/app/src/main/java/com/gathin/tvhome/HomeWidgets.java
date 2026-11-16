package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * console_layout's four glass cards along the bottom: the weather (now, the next hours, sun times, air), memory (RAM and
 * storage rings with Optimize, which closes apps idling in the background), a stocks watchlist and the month.
 * Weather and stocks come from the Weather line add-on's provider; the launcher itself stays offline.
 */
final class HomeWidgets {
    /** The data add-on's provider (the theme's weather_line package names it); null shows empty cards. */
    private final Uri WEATHER, STOCKS;
    private static final long POLL_MS = 10 * 60_000L, STALE_MS = 3 * 60 * 60_000L;
    private static final int DIM = 0xB3FFFFFF, FAINT = 0x80FFFFFF, MUTED = 0x73FFFFFF;
    private static final int BLUE = 0xFF85B7EB, WARM = 0xFFF0997B, GOLD = 0xFFFAC775, GREEN = 0xFF97C459,
            TEAL = 0xFF5DCAA5, LAVENDER = 0xFFAFA9EC;
    private static final int CARD_HEIGHT_DP = 176;

    private final Context ctx;
    private final Handler main;
    private final float dp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    final LinearLayout root;
    final TextView optimize;
    private final LinearLayout weatherBody, stocksRows, calendarGrid;
    private final TextView weatherUpdated, stocksUpdated, stocksFoot, calendarTitle, calendarWeek, calendarFoot;
    private final Ring ramRing, storageRing;
    private final TextView ramText, storageText;
    private final boolean twelveHour;
    private final String place;
    private TextView weatherTitle;
    private View stocksCard;
    /** The add-on's state, read with each reading; null before the first read. */
    private volatile String addonPlace;
    private volatile boolean addonPlaceFromZone, stocksConfigured;
    private final ContentObserver observer;
    private final Runnable poll = this::pollTick;
    private boolean watching;
    private volatile long stocksAt;

    HomeWidgets(Context ctx, Handler main, boolean twelveHour, String authority, String place) {
        this.ctx = ctx;
        WEATHER = authority == null ? null : Uri.parse("content://" + authority + "/now");
        STOCKS = authority == null ? null : Uri.parse("content://" + authority + "/stocks");
        this.main = main;
        this.twelveHour = twelveHour;
        this.place = place;
        dp = ctx.getResources().getDisplayMetrics().density;
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setClipChildren(false);

        // Weather
        LinearLayout weather = card();
        weatherUpdated = header(weather, place);
        weatherTitle = (TextView) ((ViewGroup) weatherUpdated.getParent()).getChildAt(0);
        weatherBody = new LinearLayout(ctx);
        weatherBody.setOrientation(LinearLayout.VERTICAL);
        weather.addView(weatherBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Memory: two rings, Optimize along the bottom
        LinearLayout memory = card();
        LinearLayout rings = new LinearLayout(ctx);
        rings.setGravity(Gravity.CENTER_VERTICAL);
        ramRing = new Ring(TEAL);
        storageRing = new Ring(LAVENDER);
        ramText = gauge(rings, "RAM", ramRing);
        storageText = gauge(rings, "Storage", storageRing);
        memory.addView(rings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        optimize = text("Optimize", 14, Color.WHITE);
        optimize.setFocusable(true);
        android.graphics.drawable.Drawable sparkles = ctx.getDrawable(R.drawable.ic_sparkles);
        if (sparkles != null) {
            sparkles.setBounds(0, 0, px(17), px(17));
            optimize.setCompoundDrawablesRelative(sparkles, null, null, null);
            optimize.setCompoundDrawablePadding(px(7));
            optimize.setCompoundDrawableTintList(new android.content.res.ColorStateList(
                    new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFF2C2628, Color.WHITE}));
        }
        optimize.setGravity(Gravity.CENTER);
        optimize.setPadding(0, px(7), 0, px(7));
        // Full-width pill: pad both sides so the sparkles and the word sit together in the middle.
        optimize.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> centreOptimize());
        optimize.setBackground(pill());
        optimize.setOnFocusChangeListener((v, has) -> optimize.setTextColor(has ? 0xFF2C2628 : Color.WHITE));
        optimize.setOnClickListener(v -> optimizeNow());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = px(8);
        memory.addView(optimize, op);

        // Stocks
        LinearLayout stocks = card();
        stocksCard = stocks;
        stocksUpdated = header(stocks, "");
        stocksRows = new LinearLayout(ctx);
        stocksRows.setOrientation(LinearLayout.VERTICAL);
        stocks.addView(stocksRows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        stocksFoot = text("", 11, FAINT);
        stocks.addView(stocksFoot);

        // Calendar
        LinearLayout cal = card();
        LinearLayout calHead = new LinearLayout(ctx);
        calHead.setGravity(Gravity.CENTER_VERTICAL);
        calendarTitle = text("", 13, DIM);
        calHead.addView(calendarTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        calendarWeek = text("", 10, MUTED);
        calHead.addView(calendarWeek);
        cal.addView(calHead);
        calendarGrid = new LinearLayout(ctx);
        calendarGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gp.topMargin = px(4);
        cal.addView(calendarGrid, gp);
        calendarFoot = text("", 10, FAINT);
        cal.addView(calendarFoot);

        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
    }

    /** Pin the cards along the bottom of the screen, above everything else. */
    void attach(FrameLayout screen, int sideDp, int bottomDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(CARD_HEIGHT_DP),
                Gravity.BOTTOM);
        lp.leftMargin = px(sideDp);
        lp.rightMargin = px(sideDp);
        lp.bottomMargin = px(bottomDp);
        screen.addView(root, lp);
    }

    void start() {
        if (!watching && WEATHER != null) {
            try {
                ctx.getContentResolver().registerContentObserver(WEATHER, false, observer);
                ctx.getContentResolver().registerContentObserver(STOCKS, false, observer);
                watching = true;
            } catch (SecurityException e) {
                // No add-on: the weather and stocks cards stay as they are.
            }
        }
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_MS);
        read();
        updateSystem();
        showCalendar();
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
        updateSystem();
        showCalendar();
        main.postDelayed(poll, POLL_MS);
    }

    // ---- data ----------------------------------------------------------------------------------

    private void read() {
        if (WEATHER == null) {
            // No data add-on: say how to get one instead of an empty card, and keep stocks out of the way.
            weatherBody.removeAllViews();
            weatherBody.addView(text("Install the Dashboard data add-on for the weather", 12, FAINT));
            stocksCard.setVisibility(View.GONE);
            return;
        }
        try {
            io.execute(() -> {
                Object[] w = null;
                String s = null;
                try (Cursor c = ctx.getContentResolver().query(WEATHER, null, null, null, null)) {
                    if (c != null && c.moveToFirst() && System.currentTimeMillis() - c.getLong(5) < STALE_MS) {
                        w = new Object[] {c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4) != 0,
                                c.getColumnCount() > 7 ? c.getString(7) : "{}", c.getLong(5)};
                        int pi = c.getColumnIndex("place"), zi = c.getColumnIndex("from_time_zone");
                        addonPlace = pi >= 0 ? c.getString(pi) : null;
                        addonPlaceFromZone = zi >= 0 && c.getInt(zi) == 1;
                    }
                } catch (RuntimeException ignored) {
                    // Add-on missing or mid-update.
                }
                try (Cursor c = ctx.getContentResolver().query(STOCKS, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        s = c.getString(0);
                        stocksAt = c.getLong(1);
                        int ci = c.getColumnIndex("configured");
                        stocksConfigured = ci < 0 || c.getInt(ci) == 1;   // an older add-on has no such column
                    } else {
                        stocksConfigured = false;
                    }
                } catch (RuntimeException ignored) {
                    // Add-on missing or mid-update.
                }
                Object[] weather = w;
                String stocks = s;
                main.post(() -> {
                    showWeather(weather);
                    showStocks(stocks);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Home is being destroyed.
        }
    }

    private void showWeather(Object[] w) {
        weatherBody.removeAllViews();
        weatherUpdated.setText(w == null ? "" : (place.isEmpty() && addonPlaceFromZone
                ? "Auto · " + updated((Long) w[6]).replace("Updated ", "") : updated((Long) w[6])));
        // The theme's weather_place wins; otherwise the add-on's place, marked when it is only the time zone's city.
        if (place.isEmpty() && addonPlace != null && !addonPlace.isEmpty()) {
            weatherTitle.setText(addonPlace);
        }
        if (w == null) {
            weatherBody.addView(text("Getting the weather…", 13, FAINT));
            return;
        }
        int code = (Integer) w[3];
        LinearLayout now = new LinearLayout(ctx);
        now.setGravity(Gravity.CENTER_VERTICAL);
        now.addView(text(WeatherLine.symbol(code, (Boolean) w[4]) + "︎", 26, BLUE));
        TextView temp = text(" " + w[0] + "°", 34, Color.WHITE);
        now.addView(temp);
        LinearLayout desc = new LinearLayout(ctx);
        desc.setOrientation(LinearLayout.VERTICAL);
        desc.setPadding(px(10), 0, 0, 0);
        // One line each, shortened with an ellipsis: a narrow card (four in the row) must not wrap them.
        desc.addView(oneLine(text(describe(code), 14, Color.WHITE)));
        SpannableStringBuilder hl = new SpannableStringBuilder();
        append(hl, "H " + w[1] + "°", WARM);
        append(hl, "  ·  ", FAINT);
        append(hl, "L " + w[2] + "°", BLUE);
        TextView hlv = oneLine(text("", 12, Color.WHITE));
        hlv.setText(hl);
        desc.addView(hlv);
        now.addView(desc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nowLp.topMargin = px(6);
        weatherBody.addView(now, nowLp);
        weatherBody.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1f));   // the rest sits at the bottom
        try {
            JSONObject d = new JSONObject((String) w[5]);
            JSONArray hours = d.optJSONArray("hours");
            if (hours != null && hours.length() > 0) {
                View rule = new View(ctx);
                rule.setBackgroundColor(0x1FFFFFFF);
                weatherBody.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
                LinearLayout row = new LinearLayout(ctx);
                row.setPadding(0, px(5), 0, 0);
                for (int i = 0; i < hours.length() && i < 4; i++) {
                    JSONObject h = hours.getJSONObject(i);
                    SpannableStringBuilder sb = new SpannableStringBuilder();
                    append(sb, hourLabel(h.getString("time")) + "\n", DIM);
                    append(sb, WeatherLine.symbol(h.getInt("code"), h.optInt("is_day", 1) == 1) + "︎\n", BLUE);
                    append(sb, h.getInt("temp") + "°", Color.WHITE);
                    TextView t = text("", 12, Color.WHITE);
                    t.setText(sb);
                    t.setGravity(Gravity.CENTER_HORIZONTAL);
                    t.setLineSpacing(px(1), 1f);
                    row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                weatherBody.addView(row);
            }
            SpannableStringBuilder foot = new SpannableStringBuilder();
            if (d.has("sunrise")) {
                append(foot, "☀︎ ", GOLD);
                append(foot, hourMinute(d.getString("sunrise")), FAINT);
            }
            if (d.has("sunset")) {
                append(foot, "  ·  ", FAINT);
                append(foot, "☾︎ ", WARM);
                append(foot, hourMinute(d.getString("sunset")), FAINT);
            }
            if (d.has("aqi")) {
                int aqi = d.getInt("aqi");
                append(foot, "  ·  Air ", FAINT);
                append(foot, aqiWord(aqi), aqi <= 50 ? GREEN : aqi <= 100 ? GOLD : WARM);
            }
            if (foot.length() > 0) {
                TextView f = oneLine(text("", 10, FAINT));
                f.setText(foot);
                ((LinearLayout.LayoutParams) f.getLayoutParams()).topMargin = px(5);
                weatherBody.addView(f);
            }
        } catch (Exception ignored) {
            // Older add-on without detail: the current reading alone.
        }
    }

    private void showStocks(String json) {
        // No tickers set: no card at all, and the other three share the row.
        stocksCard.setVisibility(stocksConfigured ? View.VISIBLE : View.GONE);
        if (!stocksConfigured) return;
        stocksRows.removeAllViews();
        stocksUpdated.setText(json == null ? "" : updated(stocksAt));
        if (json == null) {
            stocksFoot.setText("Getting prices…");
            return;
        }
        boolean open = false;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length() && i < 4; i++) {
                JSONObject s = a.getJSONObject(i);
                double pct = s.optDouble("change_pct");
                open |= s.optBoolean("open");
                LinearLayout row = new LinearLayout(ctx);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(text(s.getString("symbol"), 14, Color.WHITE),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(text(String.format(Locale.US, "%.2f", s.getDouble("price")), 14, Color.WHITE));
                boolean up = pct >= 0;
                TextView ch = text(String.format(Locale.US, "%s %.1f%%", up ? "▲" : "▼", Math.abs(pct)), 12,
                        up ? 0xFFC0DD97 : 0xFFF7C1C1);
                ch.setGravity(Gravity.END);
                ch.setMinWidth(px(62));
                ch.setPadding(px(5), px(1), px(5), px(1));
                GradientDrawable tag = new GradientDrawable();
                tag.setColor(up ? 0x47639922 : 0x40E24B4A);
                tag.setCornerRadius(px(4));
                ch.setBackground(tag);
                LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                chLp.leftMargin = px(8);
                row.addView(ch, chLp);
                stocksRows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
        } catch (Exception ignored) {
            // Keep the card empty rather than wrong.
        }
        SpannableStringBuilder foot = new SpannableStringBuilder();
        append(foot, "● ", open ? GREEN : WARM);
        append(foot, open ? "Market open" : "Market closed", FAINT);
        stocksFoot.setText(foot);
    }

    private void updateSystem() {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long ramUsed = mi.totalMem - mi.availMem;
        ramText.setText(gb(ramUsed) + " / " + gb(mi.totalMem) + " GB");
        ramRing.set(ramUsed / (float) mi.totalMem);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), used = total - fs.getAvailableBytes();
        storageText.setText(gb(used) + " / " + gb(total) + " GB");
        storageRing.set(used / (float) total);
    }

    /** Closes apps idling in the background (what Android lets a launcher close), then says how much it freed. */
    private android.animation.ObjectAnimator optimizePulse;
    private boolean optimizing;

    /**
     * Closes apps idling in the background (what Android lets a launcher close), then says how much it freed. Only
     * apps with a TV or phone launch entry are asked, one at a time on a low-priority thread, so the request burst
     * never stalls the screen; the button pulses softly meanwhile and keeps its width.
     */
    private void optimizeNow() {
        if (optimizing) return;
        optimizing = true;
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        setOptimizeText("Optimizing…");
        optimizePulse = android.animation.ObjectAnimator.ofFloat(optimize, View.ALPHA, 1f, 0.55f);
        optimizePulse.setDuration(650);
        optimizePulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        optimizePulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        optimizePulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        optimizePulse.start();
        Thread worker = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            java.util.Set<String> targets = new java.util.LinkedHashSet<>();
            for (String category : new String[] {android.content.Intent.CATEGORY_LEANBACK_LAUNCHER,
                    android.content.Intent.CATEGORY_LAUNCHER}) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(category);
                for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) targets.add(r.activityInfo.packageName);
            }
            targets.remove(ctx.getPackageName());
            for (String pkg : targets) {
                try {
                    am.killBackgroundProcesses(pkg);
                } catch (SecurityException ignored) {
                    // Not allowed for this one: skip it.
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    break;
                }
            }
            main.postDelayed(() -> {
                ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(after);
                long freed = Math.max(0, after.availMem - before.availMem) / (1024 * 1024);
                String free = gb(after.availMem) + " GB free";
                if (optimizePulse != null) optimizePulse.cancel();
                optimize.animate().alpha(1f).setDuration(200).start();
                setOptimizeText("Optimize");
                updateSystem();
                optimizing = false;
                // Android does not tell a launcher which other apps were running, only how much memory came back.
                Toast.makeText(ctx, freed >= 20
                                ? "Closed background apps · freed " + freed + " MB · " + free
                                : "Nothing idle to close · " + free,
                        Toast.LENGTH_LONG).show();
            }, 600);
        }, "optimize");
        worker.start();
    }

    /** New label and its centring padding in the same frame, so the words never jump sideways. */
    private void setOptimizeText(String label) {
        optimize.setText(label);
        int w = optimize.getWidth();
        if (w <= 0) return;
        int content = Math.round(optimize.getPaint().measureText(label)) + px(17) + optimize.getCompoundDrawablePadding();
        int side = Math.max(0, (w - content) / 2);
        optimize.setPadding(side, px(7), side, px(7));
    }

    // ---- calendar ------------------------------------------------------------------------------

    private void showCalendar() {
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR), month = now.get(Calendar.MONTH), today = now.get(Calendar.DAY_OF_MONTH);
        calendarTitle.setText(new SimpleDateFormat("MMMM yyyy", Locale.US).format(now.getTime()));
        calendarWeek.setText("Week " + now.get(Calendar.WEEK_OF_YEAR));
        calendarGrid.removeAllViews();
        LinearLayout head = new LinearLayout(ctx);
        for (String d : new String[] {"S", "M", "T", "W", "T", "F", "S"}) head.addView(day(d, 0x4DFFFFFF, false));
        calendarGrid.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, 1);
        int lead = c.get(Calendar.DAY_OF_WEEK) - 1;
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        int cells = (int) Math.ceil((lead + days) / 7.0) * 7;
        String holiday = null;
        int holidayDay = -1;
        LinearLayout row = null;
        for (int i = 0; i < cells; i++) {
            if (i % 7 == 0) {
                row = new LinearLayout(ctx);
                calendarGrid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            int n = i - lead + 1;
            if (n < 1 || n > days) {
                int shown = n < 1 ? 0 : n - days;
                row.addView(day(shown <= 0 ? "" : String.valueOf(shown), 0x4DFFFFFF, false));
                continue;
            }
            String h = holiday(year, month, n);
            if (h != null && (holiday == null || n >= today && (holidayDay < today))) {
                holiday = h;
                holidayDay = n;
            }
            row.addView(day(String.valueOf(n), h != null ? GOLD : Color.WHITE, n == today));
        }
        if (holiday != null) {
            c.set(year, month, holidayDay);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            append(sb, "● ", GOLD);
            append(sb, holiday + " · " + new SimpleDateFormat("EEE d", Locale.US).format(c.getTime()), FAINT);
            calendarFoot.setText(sb);
            calendarFoot.setVisibility(View.VISIBLE);
        } else {
            calendarFoot.setVisibility(View.GONE);
        }
    }

    private View day(String label, int color, boolean today) {
        FrameLayout cell = new FrameLayout(ctx);
        TextView t = text(label, 11, today ? 0xFF04342C : color);
        t.setGravity(Gravity.CENTER);
        if (today) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(TEAL);
            t.setBackground(dot);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        int size = px(18);
        cell.addView(t, new FrameLayout.LayoutParams(today ? size : ViewGroup.LayoutParams.WRAP_CONTENT,
                today ? size : ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return cell;
    }

    /** US federal holidays, worked out on the TV. */
    static String holiday(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int nth = (day - 1) / 7 + 1;
        boolean last = day + 7 > c.getActualMaximum(Calendar.DAY_OF_MONTH);
        switch (month) {
            case Calendar.JANUARY:
                if (day == 1) return "New Year's Day";
                if (dow == Calendar.MONDAY && nth == 3) return "MLK Day";
                return null;
            case Calendar.FEBRUARY: return dow == Calendar.MONDAY && nth == 3 ? "Presidents' Day" : null;
            case Calendar.MAY: return dow == Calendar.MONDAY && last ? "Memorial Day" : null;
            case Calendar.JUNE: return day == 19 ? "Juneteenth" : null;
            case Calendar.JULY: return day == 4 ? "Independence Day" : null;
            case Calendar.SEPTEMBER: return dow == Calendar.MONDAY && nth == 1 ? "Labor Day" : null;
            case Calendar.OCTOBER: return dow == Calendar.MONDAY && nth == 2 ? "Columbus Day" : null;
            case Calendar.NOVEMBER:
                if (day == 11) return "Veterans Day";
                if (dow == Calendar.THURSDAY && nth == 4) return "Thanksgiving";
                return null;
            case Calendar.DECEMBER: return day == 25 ? "Christmas" : null;
            default: return null;
        }
    }

    // ---- formatting ----------------------------------------------------------------------------

    /** "8:47 PM"; with the date when it is from another day, so an old reading reads as old. */
    private String updated(long ms) {
        if (ms <= 0) return "";
        boolean today = android.text.format.DateUtils.isToday(ms);
        String pattern = (today ? "" : "MMM d, ") + (twelveHour ? "h:mm a" : "HH:mm");
        return "Updated " + new SimpleDateFormat(pattern, Locale.US).format(new Date(ms));
    }

    private String hourLabel(String iso) {
        Date d = parse(iso);
        if (d == null) return "";
        return new SimpleDateFormat(twelveHour ? "h a" : "HH:mm", Locale.US).format(d);
    }

    private String hourMinute(String iso) {
        Date d = parse(iso);
        if (d == null) return "";
        return new SimpleDateFormat(twelveHour ? "h:mm a" : "HH:mm", Locale.US).format(d);
    }

    private static Date parse(String iso) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "Rain";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Storms";
        return "";
    }

    private static String aqiWord(int aqi) {
        if (aqi <= 50) return "good";
        if (aqi <= 100) return "moderate";
        if (aqi <= 150) return "poor";
        return "unhealthy";
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1_073_741_824f);
    }

    private static void append(SpannableStringBuilder sb, String s, int color) {
        int start = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---- views ---------------------------------------------------------------------------------

    private int px(float v) {
        return Math.round(v * dp);
    }

    /** One line that shrinks its text (down to 8sp) to fit a narrow card instead of wrapping or cutting it. */
    private static TextView oneLine(TextView t) {
        // maxLines, not singleLine: singleLine scrolls sideways, which switches auto-sizing off.
        t.setMaxLines(1);
        t.setHorizontallyScrolling(false);
        int size = Math.round(t.getTextSize() / t.getResources().getDisplayMetrics().scaledDensity);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(t.getTextSize() * 1.35f)));
        t.setAutoSizeTextTypeUniformWithConfiguration(8, Math.max(9, size), 1, TypedValue.COMPLEX_UNIT_SP);
        return t;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(18), px(14), px(18), px(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1AFFFFFF);
        g.setStroke(Math.max(1, px(1)), 0x38FFFFFF);
        g.setCornerRadius(px(14));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (root.getChildCount() > 0) lp.leftMargin = px(14);
        root.addView(c, lp);
        return c;
    }

    /** The card's title on the left and its small, muted update time on the right. */
    private TextView header(LinearLayout card, String title) {
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 12, DIM);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView when = text("", 9, MUTED);
        when.setPadding(px(6), 0, 0, 0);
        r.addView(when);
        card.addView(r);
        return when;
    }

    /** A label above a ring, the GB figures on one line under it. */
    private TextView gauge(LinearLayout parent, String label, Ring ring) {
        LinearLayout g = new LinearLayout(ctx);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView name = text(label, 12, 0xCCFFFFFF);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        g.addView(name);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(px(64), px(64));
        rp.topMargin = px(5);
        rp.bottomMargin = px(5);
        g.addView(ring, rp);
        TextView value = text("", 11, 0x8CFFFFFF);
        value.setSingleLine(true);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        g.addView(value);
        parent.addView(g, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return value;
    }

    private void centreOptimize() {
        int w = optimize.getWidth();
        if (w <= 0) return;
        float textW = optimize.getPaint().measureText(optimize.getText().toString());
        int content = Math.round(textW) + px(17) + optimize.getCompoundDrawablePadding();
        int side = Math.max(0, (w - content) / 2);
        if (optimize.getPaddingLeft() != side) optimize.post(() -> optimize.setPadding(side, px(7), side, px(7)));
    }

    private StateListDrawable pill() {
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0x29FFFFFF);
        idle.setCornerRadius(px(20));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.WHITE);
        focused.setCornerRadius(px(20));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, focused);
        s.addState(new int[] {}, idle);
        return s;
    }

    /** A percentage ring: a faint track, a coloured arc from the top, the percentage in the middle. */
    private final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float fraction;

        Ring(int color) {
            super(ctx);
            float stroke = 5.5f * dp;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(0x26FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(color);
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
        }

        void set(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float inset = arc.getStrokeWidth() / 2 + 1;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            c.drawOval(box, track);
            c.drawArc(box, -90, 360 * fraction, false, arc);
            Paint.FontMetrics fm = label.getFontMetrics();
            c.drawText(Math.round(fraction * 100) + "%", getWidth() / 2f,
                    getHeight() / 2f - (fm.ascent + fm.descent) / 2, label);
        }
    }
}
