package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.ThemeColors;

/**
 * Design-system color utilities.
 * Translated from src/components/design-system/color.ts
 *
 * Provides a theme-aware colorization function that resolves theme keys to
 * raw color values and delegates to an ANSI/ink-style renderer.
 */
public class DesignSystemColors {

    /**
     * The type of color application — foreground text or background fill.
     * Corresponds to ColorType in TypeScript (ink/colorize.ts).
     */
    public enum ColorType {
        FOREGROUND, BACKGROUND
    }

    /**
     * A resolved color specifier — either a raw value (e.g. {@code "#ff0000"},
     * {@code "rgb(255,0,0)"}, {@code "ansi256(196)"}, {@code "ansi:red"}) or a
     * theme-key name.
     */
    public record ColorSpec(String value) {
        public boolean isRaw() {
            if (value == null) return false;
            return value.startsWith("rgb(")
                    || value.startsWith("#")
                    || value.startsWith("ansi256(")
                    || value.startsWith("ansi:");
        }
    }

    /**
     * Applies an ANSI color escape to {@code text} using the given color specifier
     * and color type.
     *
     * Raw color values are used directly; theme keys are looked up via
     * {@link ThemeColors#resolve(String, String)}.
     *
     * Translated from color() (the curried theme-aware version) in color.ts
     *
     * @param text      the text to colorize
     * @param colorKey  a theme key (e.g. {@code "warning"}) or raw color value;
     *                  {@code null} or blank returns text unchanged
     * @param themeName the active theme name (e.g. {@code "dark"})
     * @param type      whether to colorize the foreground or background
     * @return ANSI-wrapped text, or the original text if no color applies
     */
    public static String colorize(String text, String colorKey, String themeName, ColorType type) {
        if (colorKey == null || colorKey.isBlank()) {
            return text;
        }
        ColorSpec spec = new ColorSpec(colorKey);
        String resolvedColor;
        if (spec.isRaw()) {
            resolvedColor = colorKey;
        } else {
            // Theme key — resolve via theme registry
            resolvedColor = ThemeColors.resolve(colorKey, themeName);
        }
        return applyAnsiColor(text, resolvedColor, type);
    }

    /**
     * Overload that defaults to {@link ColorType#FOREGROUND}.
     * Matches the default parameter in the TypeScript source.
     */
    public static String colorize(String text, String colorKey, String themeName) {
        return colorize(text, colorKey, themeName, ColorType.FOREGROUND);
    }

    // -------------------------------------------------------------------------
    // Internal ANSI helpers
    // -------------------------------------------------------------------------

    /**
     * Wraps {@code text} with basic ANSI escape sequences for the given color.
     *
     * Supports:
     * <ul>
     *   <li>{@code #RRGGBB} hex colors → 24-bit ANSI (foreground: 38;2;r;g;b, background: 48;2;r;g;b)</li>
     *   <li>{@code ansi256(N)} → 256-color ANSI (38;5;N / 48;5;N)</li>
     *   <li>{@code ansi:NAME} → named ANSI color by index lookup</li>
     *   <li>{@code rgb(r,g,b)} → 24-bit ANSI</li>
     *   <li>Unknown values → text returned as-is</li>
     * </ul>
     */
    static String applyAnsiColor(String text, String color, ColorType type) {
        if (color == null || color.isBlank()) return text;

        int fgBg = (type == ColorType.BACKGROUND) ? 48 : 38;

        if (color.startsWith("#") && (color.length() == 7 || color.length() == 4)) {
            int[] rgb = parseHex(color);
            if (rgb != null) {
                return "\u001B[%d;2;%d;%d;%dm%s\u001B[0m".formatted(fgBg, rgb[0], rgb[1], rgb[2], text);
            }
        }

        if (color.startsWith("rgb(")) {
            int[] rgb = parseRgb(color);
            if (rgb != null) {
                return "\u001B[%d;2;%d;%d;%dm%s\u001B[0m".formatted(fgBg, rgb[0], rgb[1], rgb[2], text);
            }
        }

        if (color.startsWith("ansi256(") && color.endsWith(")")) {
            String inner = color.substring(8, color.length() - 1).trim();
            try {
                int n = Integer.parseInt(inner);
                int code = (type == ColorType.BACKGROUND) ? 48 : 38;
                return "\u001B[%d;5;%dm%s\u001B[0m".formatted(code, n, text);
            } catch (NumberFormatException ignored) {}
        }

        if (color.startsWith("ansi:")) {
            String name = color.substring(5).trim().toLowerCase();
            Integer code = ansiNamedCode(name, type == ColorType.BACKGROUND);
            if (code != null) {
                return "\u001B[%dm%s\u001B[0m".formatted(code, text);
            }
        }

        return text;
    }

    private static int[] parseHex(String hex) {
        try {
            String h = hex.substring(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return new int[]{r, g, b};
        } catch (Exception e) {
            return null;
        }
    }

    private static int[] parseRgb(String rgb) {
        try {
            String inner = rgb.substring(4, rgb.length() - 1);
            String[] parts = inner.split(",");
            if (parts.length != 3) return null;
            return new int[]{
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim())
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer ansiNamedCode(String name, boolean background) {
        int offset = background ? 10 : 0;
        return switch (name) {
            case "black" -> 30 + offset;
            case "red" -> 31 + offset;
            case "green" -> 32 + offset;
            case "yellow" -> 33 + offset;
            case "blue" -> 34 + offset;
            case "magenta" -> 35 + offset;
            case "cyan" -> 36 + offset;
            case "white" -> 37 + offset;
            case "bright-black", "gray", "grey" -> 90 + offset;
            case "bright-red" -> 91 + offset;
            case "bright-green" -> 92 + offset;
            case "bright-yellow" -> 93 + offset;
            case "bright-blue" -> 94 + offset;
            case "bright-magenta" -> 95 + offset;
            case "bright-cyan" -> 96 + offset;
            case "bright-white" -> 97 + offset;
            default -> null;
        };
    }

    private DesignSystemColors() {}
}
