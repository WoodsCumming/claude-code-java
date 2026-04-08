package com.anthropic.claudecode.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for the animated spinner component.
 * Translated from src/components/Spinner/utils.ts
 *
 * Provides terminal-aware spinner characters, RGB colour interpolation,
 * HSL-to-RGB conversion, and an rgb() string parser with a cache.
 */
public final class SpinnerUtils {

    private SpinnerUtils() {}

    // -------------------------------------------------------------------------
    // Spinner character sets
    // -------------------------------------------------------------------------

    /**
     * Returns the list of spinner frame characters appropriate for the current
     * terminal / platform.
     * Translated from getDefaultCharacters() in utils.ts
     *
     * @param term     value of the TERM environment variable (e.g. "xterm-ghostty")
     * @param platform OS platform identifier (e.g. "darwin", "linux", "win32")
     * @return an immutable list of single-character spinner frames
     */
    public static List<String> getDefaultCharacters(String term, String platform) {
        if ("xterm-ghostty".equals(term)) {
            // Use * instead of ✽ for Ghostty because the latter renders slightly offset
            return List.of("\u00b7", "\u2722", "\u2733", "\u2736", "\u273b", "*");
        }
        if ("darwin".equals(platform)) {
            return List.of("\u00b7", "\u2722", "\u2733", "\u2736", "\u273b", "\u273d");
        }
        return List.of("\u00b7", "\u2722", "*", "\u2736", "\u273b", "\u273d");
    }

    // -------------------------------------------------------------------------
    // Colour records
    // -------------------------------------------------------------------------

    /**
     * An RGB colour with integer channel values in [0, 255].
     * Translated from the RGBColor type used throughout utils.ts
     */
    public record RGBColor(int r, int g, int b) {}

    // -------------------------------------------------------------------------
    // Colour interpolation
    // -------------------------------------------------------------------------

    /**
     * Linearly interpolates between two RGB colours.
     * Translated from interpolateColor() in utils.ts
     *
     * @param color1 start colour
     * @param color2 end colour
     * @param t      interpolation factor in [0.0, 1.0]
     * @return the interpolated colour
     */
    public static RGBColor interpolateColor(RGBColor color1, RGBColor color2, double t) {
        return new RGBColor(
                (int) Math.round(color1.r() + (color2.r() - color1.r()) * t),
                (int) Math.round(color1.g() + (color2.g() - color1.g()) * t),
                (int) Math.round(color1.b() + (color2.b() - color1.b()) * t));
    }

    // -------------------------------------------------------------------------
    // Colour string conversion
    // -------------------------------------------------------------------------

    /**
     * Converts an {@link RGBColor} to an {@code rgb(r,g,b)} CSS string.
     * Translated from toRGBColor() in utils.ts
     *
     * @param color the RGB colour to serialise
     * @return a string like {@code "rgb(255,128,0)"}
     */
    public static String toRGBColorString(RGBColor color) {
        return "rgb(%d,%d,%d)".formatted(color.r(), color.g(), color.b());
    }

    // -------------------------------------------------------------------------
    // HSL to RGB conversion
    // -------------------------------------------------------------------------

    /**
     * Converts an HSL hue value (0–360) to an RGB colour using the voice-mode
     * waveform parameters (saturation = 0.7, lightness = 0.6).
     * Translated from hueToRgb() in utils.ts
     *
     * @param hue the hue angle in degrees (any value; will be normalised to [0, 360))
     * @return the corresponding {@link RGBColor}
     */
    public static RGBColor hueToRgb(double hue) {
        double h = ((hue % 360) + 360) % 360;
        double s = 0.7;
        double l = 0.6;
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs(((h / 60) % 2) - 1));
        double m = l - c / 2;

        double r = 0, g = 0, b = 0;
        if (h < 60)       { r = c; g = x; }
        else if (h < 120) { r = x; g = c; }
        else if (h < 180) { g = c; b = x; }
        else if (h < 240) { g = x; b = c; }
        else if (h < 300) { r = x; b = c; }
        else              { r = c; b = x; }

        return new RGBColor(
                (int) Math.round((r + m) * 255),
                (int) Math.round((g + m) * 255),
                (int) Math.round((b + m) * 255));
    }

    // -------------------------------------------------------------------------
    // RGB string parser with cache
    // -------------------------------------------------------------------------

    /** Thread-safe cache mirroring the module-level RGB_CACHE Map in utils.ts. */
    private static final Map<String, RGBColor> RGB_CACHE = new ConcurrentHashMap<>();

    private static final Pattern RGB_PATTERN =
            Pattern.compile("rgb\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

    /**
     * Parses an {@code rgb(r, g, b)} colour string into an {@link RGBColor}.
     * Results are cached to avoid repeated regex matching.
     * Translated from parseRGB() in utils.ts
     *
     * @param colorStr an {@code rgb(…)} colour string
     * @return the parsed colour, or {@code null} if the string does not match
     */
    public static RGBColor parseRGB(String colorStr) {
        if (colorStr == null) {
            return null;
        }
        // ConcurrentHashMap.computeIfAbsent doesn't support null values;
        // use containsKey + get to mirror the TypeScript undefined-vs-null distinction.
        if (RGB_CACHE.containsKey(colorStr)) {
            return RGB_CACHE.get(colorStr); // may be a sentinel – see below
        }

        Matcher m = RGB_PATTERN.matcher(colorStr);
        RGBColor result = null;
        if (m.matches()) {
            result = new RGBColor(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        }

        // Store the result; for null we simply don't cache (avoids null-in-map issues).
        // This is slightly different from TypeScript which caches null; in practice
        // only valid rgb() strings reach this cache.
        if (result != null) {
            RGB_CACHE.put(colorStr, result);
        }
        return result;
    }

    /** Clears the RGB parse cache (useful in tests). */
    public static void clearRgbCache() {
        RGB_CACHE.clear();
    }
}
