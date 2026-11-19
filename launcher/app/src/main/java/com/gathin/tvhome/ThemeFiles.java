package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    /** A theme's own typeface for the clock, greeting, date and the big app name. */
    static final String FONT = "font.ttf";
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    private ThemeFiles() {}

    /** The active theme's font.ttf as a Typeface, or null when the theme has none (or it cannot be read). */
    static android.graphics.Typeface font(android.content.Context context) {
        java.io.File dir = Config.dir(context);
        java.io.File file = dir == null ? null : new java.io.File(dir, FONT);
        if (file == null || !file.isFile() || file.length() > 4 * 1024 * 1024) return null;
        try {
            return android.graphics.Typeface.createFromFile(file);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The path relative to the theme directory, e.g. "config.json" or "wallpapers/dusk.jpg", or null if refused. */
    static String path(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    || FONT.equals(name) ? name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        // The theme picker's library: library/<theme>/config.json, wallpaper.jpg, font.ttf or wallpapers/<image>.
        if (segments.size() >= 3 && ThemeLibrary.FOLDER.equals(segments.get(0))
                && LIBRARY_NAME.matcher(segments.get(1)).matches()) {
            String inner = path(segments.subList(2, segments.size()));
            if (inner != null && !"theme_revision".equals(inner)) {
                return ThemeLibrary.FOLDER + "/" + segments.get(1) + "/" + inner;
            }
        }
        return null;
    }

    private static final Pattern LIBRARY_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    /** True for "wallpapers" alone: a delete of it clears the collection's images. */
    static boolean isCollection(List<String> segments) {
        return segments != null && segments.size() == 1 && WALLPAPERS.equals(segments.get(0));
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }
}
