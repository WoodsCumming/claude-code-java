package com.anthropic.claudecode.util;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminal dark/light mode detection for the 'auto' theme setting.
 * Translated from src/utils/systemTheme.ts
 *
 * <p>Detection is based on the terminal's actual background color (queried via
 * OSC 11) rather than the OS appearance setting — a dark terminal on a
 * light-mode OS should still resolve to 'dark'.
 *
 * <p>The detected theme is cached and seeded from {@code $COLORFGBG} (synchronous,
 * set by some terminals at launch) and can be updated by callers once the OSC 11
 * response arrives.
 */
public class SystemThemeUtils {

    // ── SystemTheme sealed hierarchy (mirrors TypeScript 'dark' | 'light') ───

    /** The current terminal/system theme. */
    public enum SystemTheme {
        DARK("dark"),
        LIGHT("light");

        private final String value;

        SystemTheme(String value) { this.value = value; }

        public String getValue() { return value; }
    }

    // ── Module-level cache ────────────────────────────────────────────────────

    private static final AtomicReference<SystemTheme> cachedSystemTheme =
            new AtomicReference<>(null);

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Get the current terminal theme.
     * Cached after first detection; callers should invoke
     * {@link #setCachedSystemTheme(SystemTheme)} when the OSC 11 response arrives.
     * Translated from {@code getSystemThemeName()} in systemTheme.ts.
     */
    public static SystemTheme getSystemThemeName() {
        SystemTheme cached = cachedSystemTheme.get();
        if (cached != null) {
            return cached;
        }
        SystemTheme detected = detectFromColorFgBg();
        SystemTheme result = (detected != null) ? detected : SystemTheme.DARK;
        cachedSystemTheme.compareAndSet(null, result);
        return cachedSystemTheme.get();
    }

    /**
     * Update the cached terminal theme.
     * Called by the watcher when the OSC 11 query returns so non-React call
     * sites stay in sync.
     * Translated from {@code setCachedSystemTheme()} in systemTheme.ts.
     *
     * @param theme the newly detected theme
     */
    public static void setCachedSystemTheme(SystemTheme theme) {
        cachedSystemTheme.set(theme);
    }

    /**
     * Resolve a {@link ThemeUtils.ThemeSetting} (which may be {@code AUTO}) to
     * a concrete {@link ThemeUtils.ThemeName}.
     * Translated from {@code resolveThemeSetting()} in systemTheme.ts.
     *
     * @param setting the theme setting to resolve
     * @return concrete theme name
     */
    public static ThemeUtils.ThemeName resolveThemeSetting(ThemeUtils.ThemeSetting setting) {
        if (setting == ThemeUtils.ThemeSetting.AUTO) {
            SystemTheme st = getSystemThemeName();
            return st == SystemTheme.LIGHT ? ThemeUtils.ThemeName.LIGHT : ThemeUtils.ThemeName.DARK;
        }
        // Direct mapping from setting name to theme name
        return switch (setting) {
            case LIGHT            -> ThemeUtils.ThemeName.LIGHT;
            case LIGHT_DALTONIZED -> ThemeUtils.ThemeName.LIGHT_DALTONIZED;
            case DARK_DALTONIZED  -> ThemeUtils.ThemeName.DARK_DALTONIZED;
            case LIGHT_ANSI       -> ThemeUtils.ThemeName.LIGHT_ANSI;
            case DARK_ANSI        -> ThemeUtils.ThemeName.DARK_ANSI;
            default               -> ThemeUtils.ThemeName.DARK;
        };
    }

    // =========================================================================
    // OSC color parsing
    // =========================================================================

    /**
     * Parse an OSC color response string into a theme.
     *
     * <p>Accepts XParseColor formats:
     * <ul>
     *   <li>{@code rgb:R/G/B} — 1–4 hex digits per component (xterm, iTerm2, kitty, …)</li>
     *   <li>{@code #RRGGBB} / {@code #RRRRGGGGBBBB} — hex shorthand</li>
     * </ul>
     *
     * Returns {@code null} for unrecognized formats.
     * Translated from {@code themeFromOscColor()} in systemTheme.ts.
     *
     * @param data the OSC 11 response data string
     * @return detected theme, or null if the format is unrecognized
     */
    public static SystemTheme themeFromOscColor(String data) {
        double[] rgb = parseOscRgb(data);
        if (rgb == null) return null;
        // ITU-R BT.709 relative luminance; midpoint split: > 0.5 is light
        double luminance = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
        return luminance > 0.5 ? SystemTheme.LIGHT : SystemTheme.DARK;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Regex for {@code rgb:RRRR/GGGG/BBBB} (optionally {@code rgba:…/…/…/…}). */
    private static final Pattern RGB_PATTERN =
            Pattern.compile("^rgba?:([0-9a-f]{1,4})/([0-9a-f]{1,4})/([0-9a-f]{1,4})",
                    Pattern.CASE_INSENSITIVE);

    /** Regex for {@code #RRGGBB} or {@code #RRRRGGGGBBBB}. */
    private static final Pattern HASH_PATTERN =
            Pattern.compile("^#([0-9a-f]+)$", Pattern.CASE_INSENSITIVE);

    /**
     * Parse OSC color string into a [r, g, b] triple normalized to [0, 1].
     * Translated from {@code parseOscRgb()} in systemTheme.ts.
     */
    private static double[] parseOscRgb(String data) {
        if (data == null) return null;

        Matcher rgbMatcher = RGB_PATTERN.matcher(data);
        if (rgbMatcher.find()) {
            return new double[]{
                    hexComponent(rgbMatcher.group(1)),
                    hexComponent(rgbMatcher.group(2)),
                    hexComponent(rgbMatcher.group(3))
            };
        }

        Matcher hashMatcher = HASH_PATTERN.matcher(data);
        if (hashMatcher.find()) {
            String hex = hashMatcher.group(1);
            if (hex.length() % 3 == 0) {
                int n = hex.length() / 3;
                return new double[]{
                        hexComponent(hex.substring(0, n)),
                        hexComponent(hex.substring(n, 2 * n)),
                        hexComponent(hex.substring(2 * n))
                };
            }
        }

        return null;
    }

    /**
     * Normalize a 1–4 digit hex component to [0, 1].
     * Translated from {@code hexComponent()} in systemTheme.ts.
     */
    private static double hexComponent(String hex) {
        double max = Math.pow(16, hex.length()) - 1;
        return Long.parseLong(hex, 16) / max;
    }

    /**
     * Read {@code $COLORFGBG} for a synchronous initial guess.
     *
     * <p>Format is {@code fg;bg} (or {@code fg;other;bg}) where values are ANSI color
     * indices.  Background 0–6 or 8 are dark; 7 and 9–15 are light.
     * Translated from {@code detectFromColorFgBg()} in systemTheme.ts.
     */
    private static SystemTheme detectFromColorFgBg() {
        String colorfgbg = System.getenv("COLORFGBG");
        if (colorfgbg == null || colorfgbg.isBlank()) return null;

        String[] parts = colorfgbg.split(";");
        String bg = parts[parts.length - 1];
        if (bg == null || bg.isBlank()) return null;

        int bgNum;
        try {
            bgNum = Integer.parseInt(bg);
        } catch (NumberFormatException e) {
            return null;
        }

        if (bgNum < 0 || bgNum > 15) return null;
        // 0–6 and 8 are dark ANSI colors; 7 (white) and 9–15 (bright) are light
        return (bgNum <= 6 || bgNum == 8) ? SystemTheme.DARK : SystemTheme.LIGHT;
    }

    private SystemThemeUtils() {}
}
