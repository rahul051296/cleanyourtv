package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Themes kept on the TV under Config.dir/library/<name>/ (config.json, optional wallpaper.jpg and
 * wallpapers/), pushed there once by adb. Applying one copies it over the active theme files, the same
 * files tools/theme.sh pushes, then moves the theme revision and Launcher settings' change count so Home
 * rebuilds with it when it comes back to the front.
 */
final class ThemeLibrary {
    static final String FOLDER = "library";
    private static final String PREFS = "theme_library";
    private static final String CURRENT_KEY = "current";
    /** Stored as the current name when the built-in look (no theme) was chosen. */
    static final String BUILT_IN = "";

    private ThemeLibrary() {}

    /** Theme folder names that hold a config.json, sorted. */
    static List<String> names(Context ctx) {
        List<String> names = new ArrayList<>();
        File dir = Config.dir(ctx);
        File[] folders = dir == null ? null : new File(dir, FOLDER).listFiles(File::isDirectory);
        if (folders != null) {
            for (File f : folders) if (new File(f, Config.FILE_NAME).isFile()) names.add(f.getName());
        }
        Collections.sort(names);
        return names;
    }

    /** "red-rain" as "Red Rain", "ambient_glass" as "Ambient Glass": every word capitalised. */
    static String label(String name) {
        String s = name.replace('-', ' ').replace('_', ' ').trim();
        if (s.isEmpty()) return name;
        StringBuilder out = new StringBuilder(s.length());
        boolean start = true;
        for (char ch : s.toCharArray()) {
            out.append(start ? Character.toUpperCase(ch) : ch);
            start = ch == ' ';
        }
        return out.toString();
    }

    /** The name last applied from the picker, BUILT_IN for the built-in look, or null if never used. */
    static String current(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CURRENT_KEY, null);
    }

    /** Make the library theme {@code name} the active theme, or remove the active theme for BUILT_IN. */
    // commit(), not apply(): the choice must be on disk before Home restarts on the new theme.
    @SuppressLint("ApplySharedPref")
    static void apply(Context ctx, String name) throws IOException {
        File dir = Config.dir(ctx);
        if (dir == null) throw new IOException("no theme folder");
        File source = BUILT_IN.equals(name) ? null : new File(new File(dir, FOLDER), name);
        if (source != null && !new File(source, Config.FILE_NAME).isFile()) throw new IOException("no theme " + name);

        File wallpapers = new File(dir, ThemeFiles.WALLPAPERS);
        File[] old = wallpapers.listFiles(File::isFile);
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "wallpaper.jpg").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, ThemeFiles.FONT).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, Config.FILE_NAME).delete();

        if (source != null) {
            File font = new File(source, ThemeFiles.FONT);
            if (font.isFile()) copy(font, new File(dir, ThemeFiles.FONT));
            File wallpaper = new File(source, "wallpaper.jpg");
            if (wallpaper.isFile()) copy(wallpaper, new File(dir, "wallpaper.jpg"));
            File[] images = new File(source, ThemeFiles.WALLPAPERS).listFiles(File::isFile);
            if (images != null && images.length > 0) {
                if (!wallpapers.isDirectory() && !wallpapers.mkdirs()) throw new IOException("no wallpapers folder");
                for (File f : images) copy(f, new File(wallpapers, f.getName()));
            }
            // config.json last: a half-copied theme never looks complete to Config.load.
            copy(new File(source, Config.FILE_NAME), new File(dir, Config.FILE_NAME));
        }

        try (OutputStream out = new FileOutputStream(new File(dir, "theme_revision"))) {
            out.write(("picker-" + System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CURRENT_KEY, name).commit();
        SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .commit();
    }

    private static void copy(File from, File to) throws IOException {
        File tmp = new File(to.getParentFile(), to.getName() + ".tmp");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(to)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not write " + to);
        }
    }
}
