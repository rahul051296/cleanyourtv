package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Theme key recent_hero (the data add-on's package), Plexed: Home's hero is one carousel of what you are watching
 * (Continue watching, the most recent first) and titles added or released lately, picked afresh each time Home comes
 * back (RecentHeroLists.arrange). RecentHeroLists reads them from the add-on; RecentHeroArt composes each item's picture, wide and centred at the top in a U of its
 * own colours, for the wallpaper player to cross-fade. Under it: where it came from, the title, a details line, a
 * description that opens in full, Resume or Play, how far in, and which item this is. Left and Right step through
 * the items, and the carousel moves on by itself every 30 seconds. The launcher stays offline: everything comes from
 * the add-on.
 *
 * Nothing is shown half-built. While the first item's picture is on its way (Home's first list, a profile switch,
 * a profile with nothing saved yet), the whole screen behind Home is one loading state: a dark backdrop and
 * placeholders for the text pulse together, and the picture, text and buttons then fade in as one step. A step to
 * an item whose picture is not ready keeps the current item on screen until it is.
 */
final class RecentHero {

    final FrameLayout view;
    /** The loading state's full-screen backdrop: Home puts it right above the wallpaper, under everything else. */
    final View backdrop;
    /** Resume (Play for a recent title): where Home puts focus; Left and Right step through the items. */
    final TextView resume;
    /** The description: two lines, then "More" opens it in full. Focusable only while it has more to show. */
    final LinearLayout about;

    private final Context ctx;
    private final Handler main;
    private final RecentHeroLists lists;
    private final RecentHeroArt art;
    private final float dp;
    /** One background thread for reading the add-on and composing pictures. */
    private final ExecutorService io;
    private final ContentObserver observer;
    private boolean watching;

    private final LinearLayout column, tagRow, actions, dots, skeleton, progressGroup;
    private final View dotsBone;
    private final TextView title, meta, left, chipText, label, aboutText, more;
    private final GradientDrawable chipDot, barFillBg;
    private final FrameLayout bar;
    private final View barFill;
    private final List<ValueAnimator> pulses = new ArrayList<>();
    private boolean aboutOpen;

    private List<RecentShelf.Item> items = new ArrayList<>();
    private int index;
    /** Pick the recent titles afresh in the next list that arrives: set each time Home comes back. */
    private boolean repickNext = true;
    private final Random random = new Random();
    private String shownWho, skeletonFor;
    /** The loading state is up: the backdrop and placeholders pulse until the current item's picture is shown. */
    private boolean loading;
    /** The add-on said a fetch is under way (its last read), and when the loading state went up. */
    private boolean addOnFetching;
    private long loadingSince;
    private ValueAnimator backdropPulse;
    private boolean everShown;
    /** The art to put behind Home (the composed bitmap, and a key for the wallpaper player). */
    BiConsumer<Bitmap, String> onArt;
    /** Whether the hero has anything to show: Home then ignores per-app pictures and moves the dock lower. */
    Consumer<Boolean> onActive;
    /** The selected Plex profile's name and avatar (null while nothing is linked), for the profile capsule. */
    BiConsumer<String, Bitmap> onProfile;

    RecentHero(Context ctx, Handler main, String authority) {
        this.ctx = ctx;
        this.main = main;
        lists = new RecentHeroLists(ctx, authority);
        io = Executors.newSingleThreadExecutor();
        art = new RecentHeroArt(ctx, main, io, authority, (bitmap, key) -> {
            if (onArt != null) onArt.accept(bitmap, key);
        });
        dp = ctx.getResources().getDisplayMetrics().density;
        view = new FrameLayout(ctx);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        view.setVisibility(View.GONE);

        column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);

        tagRow = new LinearLayout(ctx);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout chip = new LinearLayout(ctx);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(px(8), px(4), px(10), px(4));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(px(12));
        chipBg.setColor(0x960A0A0E);
        chip.setBackground(chipBg);
        View dot = new View(ctx);
        chipDot = new GradientDrawable();
        chipDot.setShape(GradientDrawable.OVAL);
        chipDot.setColor(0xFFE5A00D);
        dot.setBackground(chipDot);
        chip.addView(dot, new LinearLayout.LayoutParams(px(6), px(6)));
        chipText = text("PLEX", 9, 0xEBFFFFFF);
        chipText.setLetterSpacing(0.08f);
        chipText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams ctp = wrap();
        ctp.setMarginStart(px(6));
        chip.addView(chipText, ctp);
        tagRow.addView(chip);
        label = text(ctx.getString(R.string.hero_continue_watching), 10, 0xAAFFFFFF);
        label.setLetterSpacing(0.1f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = wrap();
        lp.setMarginStart(px(12));
        tagRow.addView(label, lp);
        column.addView(tagRow);

        title = text("", 33, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setMaxWidth(px(560));
        LinearLayout.LayoutParams tp = wrap();
        tp.topMargin = px(6);
        column.addView(title, tp);

        meta = text("", 13, 0xD9FFFFFF);
        meta.setMaxWidth(px(620));
        LinearLayout.LayoutParams mp = wrap();
        mp.topMargin = px(2);
        column.addView(meta, mp);

        about = new LinearLayout(ctx);
        about.setGravity(Gravity.BOTTOM);
        about.setBaselineAligned(false);   // "More" at the end of the last line, not level with the first
        aboutText = text("", 12, 0);
        aboutText.setMaxWidth(px(460));
        aboutText.setLineSpacing(px(2), 1f);
        aboutText.setTextColor(focusColours(0xE0FFFFFF, 0xADFFFFFF));
        aboutText.setDuplicateParentStateEnabled(true);
        about.addView(aboutText);
        more = text(ctx.getString(R.string.hero_more), 10, 0);
        more.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        more.setPadding(px(5), px(1), px(6), px(2));   // the pill's room; at rest it reads as a space after "…"
        more.setTextColor(focusColours(0xFF18161C, 0x99FFFFFF));
        more.setBackground(focusBackground(0xF0FFFFFF, 0, 0, 9));
        more.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams mop = wrap();
        mop.setMarginStart(px(3));
        about.addView(more, mop);
        about.setOnClickListener(v -> setAboutOpen(!aboutOpen));
        // Moving away closes it again.
        about.setOnFocusChangeListener((v, focused) -> {
            if (!focused && aboutOpen) setAboutOpen(false);
        });
        LinearLayout.LayoutParams abp = wrap();
        abp.topMargin = px(8);
        column.addView(about, abp);

        actions = new LinearLayout(ctx);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        resume = new TextView(ctx);
        resume.setText(R.string.hero_resume);
        resume.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        resume.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        resume.setGravity(Gravity.CENTER);
        resume.setPadding(px(16), 0, px(20), 0);
        resume.setCompoundDrawablePadding(px(6));
        resume.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_resume_play, 0, 0, 0);
        ColorStateList ink = focusColours(0xFF18161C, 0xFFFFFFFF);
        resume.setTextColor(ink);
        resume.setCompoundDrawableTintList(ink);
        resume.setBackground(focusBackground(0xF8FFFFFF, 0x26FFFFFF, 0x50FFFFFF, 18));
        resume.setFocusable(true);
        resume.setOnClickListener(v -> {
            if (index >= 0 && index < items.size()) open(items.get(index));
        });
        actions.addView(resume, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36)));

        progressGroup = new LinearLayout(ctx);
        progressGroup.setGravity(Gravity.CENTER_VERTICAL);
        bar = new FrameLayout(ctx);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(px(2));
        track.setColor(0x46FFFFFF);
        bar.setBackground(track);
        barFill = new View(ctx);
        barFillBg = new GradientDrawable();
        barFillBg.setCornerRadius(px(2));
        barFillBg.setColor(0xFFE5A00D);
        barFill.setBackground(barFillBg);
        bar.addView(barFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        progressGroup.addView(bar, new LinearLayout.LayoutParams(px(190), px(4)));
        left = text("", 11, 0xB0FFFFFF);
        LinearLayout.LayoutParams lt = wrap();
        lt.setMarginStart(px(10));
        progressGroup.addView(left, lt);
        LinearLayout.LayoutParams pg = wrap();
        pg.setMarginStart(px(18));
        actions.addView(progressGroup, pg);
        LinearLayout.LayoutParams ap = wrap();
        ap.topMargin = px(12);
        column.addView(actions, ap);
        view.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM));

        dots = new LinearLayout(ctx);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        dl.bottomMargin = px(15);
        view.addView(dots, dl);

        // The loading state's placeholders, where the tag, title, details and button will be.
        skeleton = new LinearLayout(ctx);
        skeleton.setOrientation(LinearLayout.VERTICAL);
        skeleton.addView(bone(18), boneParams(130, 18, 0));
        skeleton.addView(bone(34), boneParams(300, 34, 8));
        skeleton.addView(bone(13), boneParams(210, 13, 8));
        skeleton.addView(bone(36), boneParams(120, 36, 14));
        skeleton.setVisibility(View.GONE);
        view.addView(skeleton, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM));
        dotsBone = bone(5);
        dotsBone.setVisibility(View.GONE);
        FrameLayout.LayoutParams db = new FrameLayout.LayoutParams(px(56), px(5), Gravity.END | Gravity.BOTTOM);
        db.bottomMargin = px(15);
        view.addView(dotsBone, db);

        backdrop = new View(ctx);
        backdrop.setBackgroundColor(BACKDROP_DARK);
        backdrop.setVisibility(View.GONE);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                load(false);
            }
        };
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    /** The slideshow: every SLIDE_MS (30 seconds) the next item, round to the first after the last. */
    private static final long SLIDE_MS = 30_000L;
    private boolean slideAway;
    private final Runnable slide = this::slideshowTick;

    /** Moves on only while the hero is on show, nothing is on top of it, and the description is not being read. */
    private void slideshowTick() {
        if (watching && !slideAway && hasItems() && items.size() > 1 && view.getVisibility() == View.VISIBLE
                && view.hasWindowFocus() && !about.hasFocus()) {
            show(index + 1, 1);
        } else {
            scheduleSlide();
        }
    }

    private void scheduleSlide() {
        main.removeCallbacks(slide);
        if (watching && items.size() > 1) main.postDelayed(slide, SLIDE_MS);
    }

    void start() {
        repickNext = true;
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(lists.recent, false, observer);
                ctx.getContentResolver().registerContentObserver(lists.profiles, false, observer);
                watching = true;
            } catch (SecurityException ignored) {
            }
        }
        load(true);   // Home came back: the add-on refreshes a list that is half a minute old
    }

    void stop() {
        if (watching) ctx.getContentResolver().unregisterContentObserver(observer);
        watching = false;
        main.removeCallbacks(slide);
        pausePulses(true);
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    boolean hasItems() {
        return !items.isEmpty() && skeletonFor == null;
    }

    /** Puts the art behind Home again (after the hero came back from under All apps). */
    void reapplyArt() {
        art.reapply();
    }

    void fadeOut() {
        slideAway = true;
        main.removeCallbacks(slide);
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(160).withEndAction(() -> view.setVisibility(View.INVISIBLE)).start();
    }

    void fadeIn() {
        slideAway = false;
        pausePulses(false);
        scheduleSlide();
        if (!hasItems() && skeletonFor == null) return;
        view.animate().cancel();
        view.setVisibility(View.VISIBLE);
        view.animate().alpha(1f).setDuration(220).start();
    }

    /** Left (-1) or Right (+1): the previous or next item, with its art; past either end it goes round. */
    void step(int by) {
        if (items.size() > 1) show(index + by, by);
    }

    /** Shows item i (round past either end); its text slides in from the side it came from (dir -1 or +1). */
    private void show(int i, int dir) {
        int n = items.size();
        index = ((i % n) + n) % n;
        stepDir = dir;
        bind(true);
        stepDir = 0;
    }

    /** The description has more than its two lines show, so Up from Resume stops on it. */
    boolean canFocusAbout() {
        return about.getVisibility() == View.VISIBLE && about.isFocusable();
    }

    /** Back on the description: closes it when it is open; false when there was nothing to close. */
    boolean closeAbout() {
        if (!aboutOpen) return false;
        setAboutOpen(false);
        return true;
    }

    /** Whether a view is one of the hero's own focusable parts. */
    boolean owns(View v) {
        return v == resume || v == about;
    }

    // ---- loading -----------------------------------------------------------------------------------------------

    private void load(boolean cameBack) {
        io.execute(() -> {
            RecentHeroLists.Snapshot read = lists.read(cameBack);
            main.post(() -> showFor(read));
        });
    }

    private void showFor(RecentHeroLists.Snapshot read) {
        String who = read.who;
        addOnFetching = read.fetching;
        if (onProfile != null) onProfile.accept(who, read.face);
        boolean switched = everShown && !Objects.equals(who, shownWho);
        boolean fromSkeleton = skeletonFor != null;
        boolean first = view.getVisibility() == View.GONE;
        shownWho = who;
        everShown = true;
        boolean repick = repickNext || switched || fromSkeleton || first;
        repickNext = false;
        List<RecentShelf.Item> list = RecentHeroLists.arrange(read.watching, read.recent, items, repick, random);
        if (list.isEmpty()) {
            items = list;
            // A new profile, or one still waiting while the add-on fetches: the loading state (stays) up.
            if (switched || (loading && read.fetching)) {
                skeletonFor = who == null ? "" : who;
                startLoading();
                if (onActive != null) onActive.accept(false);
            } else {
                skeletonFor = null;
                endLoading(false);
                art.forget();
                view.setVisibility(View.GONE);
                if (onActive != null) onActive.accept(false);
            }
            return;
        }
        String keep = !repick && !items.isEmpty() && index < items.size() ? items.get(index).id : null;
        items = list;
        skeletonFor = null;
        index = 0;
        if (keep != null) {
            for (int i = 0; i < items.size(); i++) if (keep.equals(items.get(i).id)) index = i;
        }
        if (view.getVisibility() != View.INVISIBLE) view.setVisibility(View.VISIBLE);
        if (onActive != null) onActive.accept(true);
        // A fresh start whose picture still has to be composed: the loading state until it is behind Home.
        if ((first || switched || fromSkeleton) && !art.ready(items.get(index).id)) startLoading();
        bind(switched || fromSkeleton || first);
    }

    // ---- showing an item ---------------------------------------------------------------------------------------

    /** Which way the last step went (-1, +1), or 0 when the item was not reached by a step. */
    private int stepDir;
    /** The description's height while closed, and at most when open (a long one stops short of the clock). */
    private static final int ABOUT_LINES = 2, ABOUT_OPEN_LINES = 8;

    /**
     * Asks for item `index`'s picture; its text, button and dots change only once the picture is behind Home, so
     * the two never part (the current item stays on screen meanwhile, or the loading state does).
     */
    private void bind(boolean animate) {
        if (index < 0 || index >= items.size()) return;
        int at = index, dir = stepDir;
        art.show(items, at, () -> reveal(at, dir, animate));
        scheduleSlide();   // any change, by key or by the slideshow, starts the slideshow's wait again
    }

    private void reveal(int at, int dir, boolean animate) {
        if (at >= items.size()) return;
        RecentShelf.Item it = items.get(at);
        String details = bindText(it);
        bindProgress(it, details);
        bindAbout(it);
        bindDots(at, animate);
        boolean wasLoading = loading;
        endLoading(true);
        if (animate || wasLoading) animateIn(wasLoading ? 0 : dir);
    }

    /** Source chip, label, title, details line and the button's word; returns the details line. */
    private String bindText(RecentShelf.Item it) {
        // Where it came from: Plex's own amber, or the app's name for anything else (Watch Next).
        boolean plex = "com.plexapp.android".equals(it.pkg);
        chipText.setText(plex ? "PLEX" : appLabel(it.pkg).toUpperCase(Locale.getDefault()));
        chipDot.setColor(plex ? 0xFFE5A00D : 0xE6FFFFFF);
        barFillBg.setColor(plex ? 0xFFE5A00D : 0xE6FFFFFF);
        label.setText(it.upNext ? R.string.hero_up_next : !it.fresh ? R.string.hero_continue_watching
                : it.released ? R.string.hero_recently_released : R.string.hero_recently_added);
        title.setText(it.title == null ? "" : it.title);
        String details = it.meta == null || it.meta.isEmpty() ? (it.subtitle == null ? "" : it.subtitle) : it.meta;
        meta.setText(details);
        meta.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        resume.setText(it.fresh || it.upNext ? R.string.hero_play : R.string.hero_resume);
        return details;
    }

    /** How far in (a bar) and the time left, or a new item's running time on its own. */
    private void bindProgress(RecentShelf.Item it, String details) {
        boolean hasProgress = it.progress >= 0;
        // Some sources put the time left in the details line already: not shown twice.
        boolean hasLeft = it.left != null && !it.left.isEmpty() && !it.left.equals(details);
        progressGroup.setVisibility(hasProgress || hasLeft ? View.VISIBLE : View.GONE);
        bar.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
        if (hasProgress) {
            ViewGroup.LayoutParams p = barFill.getLayoutParams();
            p.width = Math.round(px(190) * Math.max(it.progress, 20) / 1000f);
            barFill.setLayoutParams(p);
        }
        left.setText(hasLeft ? it.left : "");
        left.setVisibility(hasLeft ? View.VISIBLE : View.GONE);
    }

    /** The description, closed; none at all when the source has nothing to say. */
    private void bindAbout(RecentShelf.Item it) {
        String s = it.summary == null ? "" : it.summary.trim();
        aboutText.setText(s);
        about.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        setAboutOpen(false);
    }

    /** Opens the description in full, or closes it to two lines. */
    private void setAboutOpen(boolean open) {
        aboutOpen = open;
        aboutText.setMaxLines(open ? ABOUT_OPEN_LINES : ABOUT_LINES);
        aboutText.post(this::updateMore);
    }

    /** "More" shows only while two lines cut the text short; with nothing more to show, Up passes it by. */
    private void updateMore() {
        Layout l = aboutText.getLayout();
        boolean cut = l != null && l.getLineCount() > 0 && l.getEllipsisCount(l.getLineCount() - 1) > 0;
        more.setVisibility(cut && !aboutOpen ? View.VISIBLE : View.GONE);
        about.setFocusable(cut || aboutOpen);
        // The text box is as wide as its longest line: pull "More" back to just after the "…" on the last one.
        more.setTranslationX(cut ? -(aboutText.getWidth() - aboutText.getTotalPaddingLeft() - ellipsisEnd(l)) : 0f);
    }

    /** Where the last line of a cut-short description ends, its "…" included, from the text's left edge. */
    private float ellipsisEnd(Layout l) {
        int last = l.getLineCount() - 1;
        int start = l.getLineStart(last);
        String visible = aboutText.getText().subSequence(start, start + l.getEllipsisStart(last)).toString().trim();
        return l.getLineLeft(last) + aboutText.getPaint().measureText(visible + "…");
    }

    /** Which item this is: a dot each, the current one a longer pill. */
    private void bindDots(int at, boolean animate) {
        dots.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            View d = new View(ctx);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(3));
            g.setColor(i == at ? 0xF0FFFFFF : 0x55FFFFFF);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(5), px(5));
            lp.setMarginStart(i == 0 ? 0 : px(6));
            dots.addView(d, lp);
            if (i != at) continue;
            lp.height = px(6);
            ValueAnimator grow = ValueAnimator.ofInt(px(6), px(16));
            grow.setDuration(animate ? 240 : 0);
            grow.setInterpolator(new DecelerateInterpolator(2f));
            grow.addUpdateListener(a -> {
                lp.width = (int) a.getAnimatedValue();
                d.setLayoutParams(lp);
            });
            grow.start();
        }
    }

    /**
     * The text slides in a short way from the side the carousel moved towards, together, with no vertical motion;
     * the button stays where it is (it is what the eye rests on), only its progress fades.
     */
    private void animateIn(int dir) {
        View[] parts = {tagRow, title, meta, about};
        float from = dir * 18 * dp;
        for (int k = 0; k < parts.length; k++) {
            parts[k].animate().cancel();
            parts[k].setAlpha(0f);
            parts[k].setTranslationX(from);
            parts[k].animate().alpha(1f).translationX(0f).setStartDelay(k * 25L).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(2.2f)).start();
        }
        progressGroup.animate().cancel();
        progressGroup.setAlpha(0f);
        progressGroup.animate().alpha(1f).setDuration(260).setStartDelay(60).start();
        actions.animate().cancel();
        actions.setTranslationY(0f);
        actions.setAlpha(dir != 0 ? 1f : 0f);
        if (dir == 0) actions.animate().alpha(1f).setDuration(260).start();
    }

    // ---- the loading state -----------------------------------------------------------------------------------------

    /** The backdrop pulses between these two darks (opaque, so the theme's wallpaper never shows through). */
    private static final int BACKDROP_DARK = 0xFF17171C, BACKDROP_LIGHT = 0xFF24242B;
    private static final int PULSE_MS = 800, REVEAL_MS = 420;
    /**
     * The loading state is checked after LOADING_CHECK_MS: it gives way then unless the add-on is still fetching,
     * and after LOADING_MAX_MS in any case (a server that never answers does not hold Home behind it).
     */
    private static final long LOADING_CHECK_MS = 10_000L, LOADING_RECHECK_MS = 2_000L, LOADING_MAX_MS = 60_000L;
    private final Runnable giveUp = this::giveUpLoading;

    private void startLoading() {
        if (loading) return;
        loading = true;
        view.setVisibility(View.VISIBLE);
        view.setAlpha(1f);
        column.setVisibility(View.INVISIBLE);
        dots.setVisibility(View.GONE);
        skeleton.setVisibility(View.VISIBLE);
        dotsBone.setVisibility(View.VISIBLE);
        backdrop.animate().cancel();
        backdrop.setAlpha(1f);
        backdrop.setVisibility(View.VISIBLE);
        backdropPulse = ValueAnimator.ofArgb(BACKDROP_DARK, BACKDROP_LIGHT);
        backdropPulse.addUpdateListener(a -> backdrop.setBackgroundColor((int) a.getAnimatedValue()));
        pulse(backdropPulse, 0);
        for (int i = 0; i < skeleton.getChildCount(); i++) {
            pulse(ObjectAnimator.ofFloat(skeleton.getChildAt(i), View.ALPHA, 1f, 0.4f), i * 90L);
        }
        pulse(ObjectAnimator.ofFloat(dotsBone, View.ALPHA, 1f, 0.4f), 270L);
        loadingSince = android.os.SystemClock.uptimeMillis();
        main.postDelayed(giveUp, LOADING_CHECK_MS);
    }

    /** Still loading: waits on while the add-on fetches; then the item without its picture, or no hero for now. */
    private void giveUpLoading() {
        if (!loading) return;
        if (addOnFetching && android.os.SystemClock.uptimeMillis() - loadingSince < LOADING_MAX_MS) {
            main.postDelayed(giveUp, LOADING_RECHECK_MS);
            return;
        }
        if (!items.isEmpty() && index < items.size()) {
            reveal(index, 0, true);
            return;
        }
        skeletonFor = null;
        endLoading(true);
        view.setVisibility(View.GONE);
        if (onActive != null) onActive.accept(false);
    }

    private void pulse(ValueAnimator a, long delay) {
        a.setDuration(PULSE_MS);
        a.setStartDelay(delay);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
        pulses.add(a);
    }

    /** Hidden Home (stop) or back (fadeIn): the pulses rest meanwhile instead of drawing for nobody. */
    private void pausePulses(boolean pause) {
        for (ValueAnimator a : pulses) {
            if (pause) a.pause();
            else a.resume();
        }
    }

    /** Ends the loading state: the backdrop fades off the picture underneath (`fade`), or goes at once. */
    private void endLoading(boolean fade) {
        if (!loading) return;
        loading = false;
        main.removeCallbacks(giveUp);
        for (ValueAnimator a : pulses) a.cancel();
        pulses.clear();
        backdropPulse = null;
        skeleton.setVisibility(View.GONE);
        dotsBone.setVisibility(View.GONE);
        column.setVisibility(View.VISIBLE);
        dots.setVisibility(View.VISIBLE);
        backdrop.animate().cancel();
        if (!fade) {
            backdrop.setVisibility(View.GONE);
            return;
        }
        backdrop.animate().alpha(0f).setDuration(REVEAL_MS)
                .withEndAction(() -> backdrop.setVisibility(View.GONE)).start();
    }

    /** A placeholder bar; its size comes from its layout params (a bare View stretches to fill wrap_content). */
    private View bone(int heightDp) {
        View v = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(Math.min(heightDp / 2, 10)));
        g.setColor(0x2EFFFFFF);
        v.setBackground(g);
        return v;
    }

    private LinearLayout.LayoutParams boneParams(int widthDp, int heightDp, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(widthDp), px(heightDp));
        lp.topMargin = px(topDp);
        return lp;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private void open(RecentShelf.Item it) {
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

    private String appLabel(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg == null ? "" : pkg;
        }
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** One colour while focused (directly, or through the parent's state), another otherwise. */
    private static ColorStateList focusColours(int focused, int normal) {
        return new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {focused, normal});
    }

    /** A rounded fill while focused; otherwise `off` (0 for none) with an optional outline (0 for none). */
    private StateListDrawable focusBackground(int on, int off, int outline, float radiusDp) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(on);
        focused.setCornerRadius(px(radiusDp));
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(off);
        if (outline != 0) rest.setStroke(Math.max(1, px(1)), outline);
        rest.setCornerRadius(px(radiusDp));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, focused);
        bg.addState(new int[] {}, rest);
        return bg;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
