package com.anthropic.claudecode.util;

import java.util.Map;

/**
 * Theme definitions and color utilities.
 * Translated from src/utils/theme.ts
 *
 * <p>Provides {@link ThemeName}, {@link ThemeSetting}, the full {@link Theme} record,
 * named theme instances for all six variants (dark, light, daltonized, ANSI), and
 * a helper to convert theme colors to ANSI escape sequences.
 */
public class ThemeUtils {

    // ── ThemeName / ThemeSetting enums ────────────────────────────────────────

    /**
     * A renderable theme. Always resolvable to a concrete color palette.
     * Translated from {@code ThemeName} / {@code THEME_NAMES} in theme.ts.
     */
    public enum ThemeName {
        DARK("dark"),
        LIGHT("light"),
        LIGHT_DALTONIZED("light-daltonized"),
        DARK_DALTONIZED("dark-daltonized"),
        LIGHT_ANSI("light-ansi"),
        DARK_ANSI("dark-ansi");

        private final String value;

        ThemeName(String value) { this.value = value; }

        public String getValue() { return value; }
    }

    /**
     * A theme preference as stored in user config.
     * {@code AUTO} follows the system dark/light mode.
     * Translated from {@code ThemeSetting} / {@code THEME_SETTINGS} in theme.ts.
     */
    public enum ThemeSetting {
        AUTO("auto"),
        DARK("dark"),
        LIGHT("light"),
        LIGHT_DALTONIZED("light-daltonized"),
        DARK_DALTONIZED("dark-daltonized"),
        LIGHT_ANSI("light-ansi"),
        DARK_ANSI("dark-ansi");

        private final String value;

        ThemeSetting(String value) { this.value = value; }


        public static ThemeSetting fromString(String s) {
            for (ThemeSetting ts : values()) {
                if (ts.value.equalsIgnoreCase(s)) return ts;
            }
            return AUTO;
        }
    }

    // ── Theme record ──────────────────────────────────────────────────────────

    /**
     * Full color palette for one theme variant.
     * Translated from the {@code Theme} type in theme.ts.
     *
     * <p>Color values are either {@code rgb(R,G,B)} strings (true-color) or
     * {@code ansi:<name>} strings for ANSI-16 themes.
     */
    public record Theme(
            String autoAccept,
            String bashBorder,
            String claude,
            String claudeShimmer,
            String claudeBlueForSystemSpinner,
            String claudeBlueShimmerForSystemSpinner,
            String permission,
            String permissionShimmer,
            String planMode,
            String ide,
            String promptBorder,
            String promptBorderShimmer,
            String text,
            String inverseText,
            String inactive,
            String inactiveShimmer,
            String subtle,
            String suggestion,
            String remember,
            String background,
            String success,
            String error,
            String warning,
            String merged,
            String warningShimmer,
            String diffAdded,
            String diffRemoved,
            String diffAddedDimmed,
            String diffRemovedDimmed,
            String diffAddedWord,
            String diffRemovedWord,
            String redForSubagentsOnly,
            String blueForSubagentsOnly,
            String greenForSubagentsOnly,
            String yellowForSubagentsOnly,
            String purpleForSubagentsOnly,
            String orangeForSubagentsOnly,
            String pinkForSubagentsOnly,
            String cyanForSubagentsOnly,
            String professionalBlue,
            String chromeYellow,
            String clawdBody,
            String clawdBackground,
            String userMessageBackground,
            String userMessageBackgroundHover,
            String messageActionsBackground,
            String selectionBg,
            String bashMessageBackgroundColor,
            String memoryBackgroundColor,
            String rateLimitFill,
            String rateLimitEmpty,
            String fastMode,
            String fastModeShimmer,
            String briefLabelYou,
            String briefLabelClaude,
            String rainbowRed,
            String rainbowOrange,
            String rainbowYellow,
            String rainbowGreen,
            String rainbowBlue,
            String rainbowIndigo,
            String rainbowViolet,
            String rainbowRedShimmer,
            String rainbowOrangeShimmer,
            String rainbowYellowShimmer,
            String rainbowGreenShimmer,
            String rainbowBlueShimmer,
            String rainbowIndigoShimmer,
            String rainbowVioletShimmer
    ) {}

    // =========================================================================
    // Named theme instances
    // =========================================================================

    /** Light theme (true-color RGB). */
    public static final Theme LIGHT_THEME = new Theme(
            "rgb(135,0,255)", "rgb(255,0,135)", "rgb(215,119,87)", "rgb(245,149,117)",
            "rgb(87,105,247)", "rgb(117,135,255)", "rgb(87,105,247)", "rgb(137,155,255)",
            "rgb(0,102,102)", "rgb(71,130,200)", "rgb(153,153,153)", "rgb(183,183,183)",
            "rgb(0,0,0)", "rgb(255,255,255)", "rgb(102,102,102)", "rgb(142,142,142)",
            "rgb(175,175,175)", "rgb(87,105,247)", "rgb(0,0,255)", "rgb(0,153,153)",
            "rgb(44,122,57)", "rgb(171,43,63)", "rgb(150,108,30)", "rgb(135,0,255)",
            "rgb(200,158,80)", "rgb(105,219,124)", "rgb(255,168,180)", "rgb(199,225,203)",
            "rgb(253,210,216)", "rgb(47,157,68)", "rgb(209,69,75)",
            "rgb(220,38,38)", "rgb(37,99,235)", "rgb(22,163,74)", "rgb(202,138,4)",
            "rgb(147,51,234)", "rgb(234,88,12)", "rgb(219,39,119)", "rgb(8,145,178)",
            "rgb(106,155,204)", "rgb(251,188,4)",
            "rgb(215,119,87)", "rgb(0,0,0)", "rgb(240,240,240)", "rgb(252,252,252)",
            "rgb(232,236,244)", "rgb(180,213,255)", "rgb(250,245,250)",
            "rgb(230,245,250)", "rgb(87,105,247)", "rgb(39,47,111)",
            "rgb(255,106,0)", "rgb(255,150,50)", "rgb(37,99,235)", "rgb(215,119,87)",
            "rgb(235,95,87)", "rgb(245,139,87)", "rgb(250,195,95)", "rgb(145,200,130)",
            "rgb(130,170,220)", "rgb(155,130,200)", "rgb(200,130,180)",
            "rgb(250,155,147)", "rgb(255,185,137)", "rgb(255,225,155)", "rgb(185,230,180)",
            "rgb(180,205,240)", "rgb(195,180,230)", "rgb(230,180,210)"
    );

    /** Dark theme (true-color RGB). */
    public static final Theme DARK_THEME = new Theme(
            "rgb(175,135,255)", "rgb(253,93,177)", "rgb(215,119,87)", "rgb(235,159,127)",
            "rgb(147,165,255)", "rgb(177,195,255)", "rgb(177,185,249)", "rgb(207,215,255)",
            "rgb(72,150,140)", "rgb(71,130,200)", "rgb(136,136,136)", "rgb(166,166,166)",
            "rgb(255,255,255)", "rgb(0,0,0)", "rgb(153,153,153)", "rgb(193,193,193)",
            "rgb(80,80,80)", "rgb(177,185,249)", "rgb(177,185,249)", "rgb(0,204,204)",
            "rgb(78,186,101)", "rgb(255,107,128)", "rgb(255,193,7)", "rgb(175,135,255)",
            "rgb(255,223,57)", "rgb(34,92,43)", "rgb(122,41,54)", "rgb(71,88,74)",
            "rgb(105,72,77)", "rgb(56,166,96)", "rgb(179,89,107)",
            "rgb(220,38,38)", "rgb(37,99,235)", "rgb(22,163,74)", "rgb(202,138,4)",
            "rgb(147,51,234)", "rgb(234,88,12)", "rgb(219,39,119)", "rgb(8,145,178)",
            "rgb(106,155,204)", "rgb(251,188,4)",
            "rgb(215,119,87)", "rgb(0,0,0)", "rgb(55,55,55)", "rgb(70,70,70)",
            "rgb(44,50,62)", "rgb(38,79,120)", "rgb(65,60,65)",
            "rgb(55,65,70)", "rgb(177,185,249)", "rgb(80,83,112)",
            "rgb(255,120,20)", "rgb(255,165,70)", "rgb(122,180,232)", "rgb(215,119,87)",
            "rgb(235,95,87)", "rgb(245,139,87)", "rgb(250,195,95)", "rgb(145,200,130)",
            "rgb(130,170,220)", "rgb(155,130,200)", "rgb(200,130,180)",
            "rgb(250,155,147)", "rgb(255,185,137)", "rgb(255,225,155)", "rgb(185,230,180)",
            "rgb(180,205,240)", "rgb(195,180,230)", "rgb(230,180,210)"
    );

    /** Light daltonized theme (color-blind friendly, RGB). */
    public static final Theme LIGHT_DALTONIZED_THEME = new Theme(
            "rgb(135,0,255)", "rgb(0,102,204)", "rgb(255,153,51)", "rgb(255,183,101)",
            "rgb(51,102,255)", "rgb(101,152,255)", "rgb(51,102,255)", "rgb(101,152,255)",
            "rgb(51,102,102)", "rgb(71,130,200)", "rgb(153,153,153)", "rgb(183,183,183)",
            "rgb(0,0,0)", "rgb(255,255,255)", "rgb(102,102,102)", "rgb(142,142,142)",
            "rgb(175,175,175)", "rgb(51,102,255)", "rgb(51,102,255)", "rgb(0,153,153)",
            "rgb(0,102,153)", "rgb(204,0,0)", "rgb(255,153,0)", "rgb(135,0,255)",
            "rgb(255,183,50)", "rgb(153,204,255)", "rgb(255,204,204)", "rgb(209,231,253)",
            "rgb(255,233,233)", "rgb(51,102,204)", "rgb(153,51,51)",
            "rgb(204,0,0)", "rgb(0,102,204)", "rgb(0,204,0)", "rgb(255,204,0)",
            "rgb(128,0,128)", "rgb(255,128,0)", "rgb(255,102,178)", "rgb(0,178,178)",
            "rgb(106,155,204)", "rgb(251,188,4)",
            "rgb(215,119,87)", "rgb(0,0,0)", "rgb(220,220,220)", "rgb(232,232,232)",
            "rgb(210,216,226)", "rgb(180,213,255)", "rgb(250,245,250)",
            "rgb(230,245,250)", "rgb(51,102,255)", "rgb(23,46,114)",
            "rgb(255,106,0)", "rgb(255,150,50)", "rgb(37,99,235)", "rgb(255,153,51)",
            "rgb(235,95,87)", "rgb(245,139,87)", "rgb(250,195,95)", "rgb(145,200,130)",
            "rgb(130,170,220)", "rgb(155,130,200)", "rgb(200,130,180)",
            "rgb(250,155,147)", "rgb(255,185,137)", "rgb(255,225,155)", "rgb(185,230,180)",
            "rgb(180,205,240)", "rgb(195,180,230)", "rgb(230,180,210)"
    );

    /** Dark daltonized theme (color-blind friendly, RGB). */
    public static final Theme DARK_DALTONIZED_THEME = new Theme(
            "rgb(175,135,255)", "rgb(51,153,255)", "rgb(255,153,51)", "rgb(255,183,101)",
            "rgb(153,204,255)", "rgb(183,224,255)", "rgb(153,204,255)", "rgb(183,224,255)",
            "rgb(102,153,153)", "rgb(71,130,200)", "rgb(136,136,136)", "rgb(166,166,166)",
            "rgb(255,255,255)", "rgb(0,0,0)", "rgb(153,153,153)", "rgb(193,193,193)",
            "rgb(80,80,80)", "rgb(153,204,255)", "rgb(153,204,255)", "rgb(0,204,204)",
            "rgb(51,153,255)", "rgb(255,102,102)", "rgb(255,204,0)", "rgb(175,135,255)",
            "rgb(255,234,50)", "rgb(0,68,102)", "rgb(102,0,0)", "rgb(62,81,91)",
            "rgb(62,44,44)", "rgb(0,119,179)", "rgb(179,0,0)",
            "rgb(255,102,102)", "rgb(102,178,255)", "rgb(102,255,102)", "rgb(255,255,102)",
            "rgb(178,102,255)", "rgb(255,178,102)", "rgb(255,153,204)", "rgb(102,204,204)",
            "rgb(106,155,204)", "rgb(251,188,4)",
            "rgb(215,119,87)", "rgb(0,0,0)", "rgb(55,55,55)", "rgb(70,70,70)",
            "rgb(44,50,62)", "rgb(38,79,120)", "rgb(65,60,65)",
            "rgb(55,65,70)", "rgb(153,204,255)", "rgb(69,92,115)",
            "rgb(255,120,20)", "rgb(255,165,70)", "rgb(122,180,232)", "rgb(255,153,51)",
            "rgb(235,95,87)", "rgb(245,139,87)", "rgb(250,195,95)", "rgb(145,200,130)",
            "rgb(130,170,220)", "rgb(155,130,200)", "rgb(200,130,180)",
            "rgb(250,155,147)", "rgb(255,185,137)", "rgb(255,225,155)", "rgb(185,230,180)",
            "rgb(180,205,240)", "rgb(195,180,230)", "rgb(230,180,210)"
    );

    /** Light ANSI theme (16-color terminals). */
    public static final Theme LIGHT_ANSI_THEME = new Theme(
            "ansi:magenta", "ansi:magenta", "ansi:redBright", "ansi:yellowBright",
            "ansi:blue", "ansi:blueBright", "ansi:blue", "ansi:blueBright",
            "ansi:cyan", "ansi:blueBright", "ansi:white", "ansi:whiteBright",
            "ansi:black", "ansi:white", "ansi:blackBright", "ansi:white",
            "ansi:blackBright", "ansi:blue", "ansi:blue", "ansi:cyan",
            "ansi:green", "ansi:red", "ansi:yellow", "ansi:magenta",
            "ansi:yellowBright", "ansi:green", "ansi:red", "ansi:green",
            "ansi:red", "ansi:greenBright", "ansi:redBright",
            "ansi:red", "ansi:blue", "ansi:green", "ansi:yellow",
            "ansi:magenta", "ansi:redBright", "ansi:magentaBright", "ansi:cyan",
            "ansi:blueBright", "ansi:yellow",
            "ansi:redBright", "ansi:black", "ansi:white", "ansi:whiteBright",
            "ansi:white", "ansi:cyan", "ansi:whiteBright",
            "ansi:white", "ansi:yellow", "ansi:black",
            "ansi:red", "ansi:redBright", "ansi:blue", "ansi:redBright",
            "ansi:red", "ansi:redBright", "ansi:yellow", "ansi:green",
            "ansi:cyan", "ansi:blue", "ansi:magenta",
            "ansi:redBright", "ansi:yellow", "ansi:yellowBright", "ansi:greenBright",
            "ansi:cyanBright", "ansi:blueBright", "ansi:magentaBright"
    );

    /** Dark ANSI theme (16-color terminals). */
    public static final Theme DARK_ANSI_THEME = new Theme(
            "ansi:magentaBright", "ansi:magentaBright", "ansi:redBright", "ansi:yellowBright",
            "ansi:blueBright", "ansi:blueBright", "ansi:blueBright", "ansi:blueBright",
            "ansi:cyanBright", "ansi:blue", "ansi:white", "ansi:whiteBright",
            "ansi:whiteBright", "ansi:black", "ansi:white", "ansi:whiteBright",
            "ansi:white", "ansi:blueBright", "ansi:blueBright", "ansi:cyanBright",
            "ansi:greenBright", "ansi:redBright", "ansi:yellowBright", "ansi:magentaBright",
            "ansi:yellowBright", "ansi:green", "ansi:red", "ansi:green",
            "ansi:red", "ansi:greenBright", "ansi:redBright",
            "ansi:redBright", "ansi:blueBright", "ansi:greenBright", "ansi:yellowBright",
            "ansi:magentaBright", "ansi:redBright", "ansi:magentaBright", "ansi:cyanBright",
            "rgb(106,155,204)", "ansi:yellowBright",
            "ansi:redBright", "ansi:black", "ansi:blackBright", "ansi:white",
            "ansi:blackBright", "ansi:blue", "ansi:black",
            "ansi:blackBright", "ansi:yellow", "ansi:white",
            "ansi:redBright", "ansi:redBright", "ansi:blueBright", "ansi:redBright",
            "ansi:red", "ansi:redBright", "ansi:yellow", "ansi:green",
            "ansi:cyan", "ansi:blue", "ansi:magenta",
            "ansi:redBright", "ansi:yellow", "ansi:yellowBright", "ansi:greenBright",
            "ansi:cyanBright", "ansi:blueBright", "ansi:magentaBright"
    );

    // =========================================================================
    // getTheme
    // =========================================================================

    /**
     * Get the {@link Theme} for the given {@link ThemeName}.
     * Translated from {@code getTheme()} in theme.ts.
     */
    public static Theme getTheme(ThemeName name) {
        return switch (name) {
            case LIGHT            -> LIGHT_THEME;
            case LIGHT_ANSI       -> LIGHT_ANSI_THEME;
            case DARK_ANSI        -> DARK_ANSI_THEME;
            case LIGHT_DALTONIZED -> LIGHT_DALTONIZED_THEME;
            case DARK_DALTONIZED  -> DARK_DALTONIZED_THEME;
            default               -> DARK_THEME;
        };
    }

    // =========================================================================
    // themeColorToAnsi
    // =========================================================================

    /** ANSI reset escape sequence. */
    private static final String ANSI_RESET = "\u001B[0m";

    /** Simple ANSI-16 color name → escape code map (foreground). */
    private static final Map<String, String> ANSI_FG = Map.ofEntries(
            Map.entry("black",         "\u001B[30m"),
            Map.entry("red",           "\u001B[31m"),
            Map.entry("green",         "\u001B[32m"),
            Map.entry("yellow",        "\u001B[33m"),
            Map.entry("blue",          "\u001B[34m"),
            Map.entry("magenta",       "\u001B[35m"),
            Map.entry("cyan",          "\u001B[36m"),
            Map.entry("white",         "\u001B[37m"),
            Map.entry("blackBright",   "\u001B[90m"),
            Map.entry("redBright",     "\u001B[91m"),
            Map.entry("greenBright",   "\u001B[92m"),
            Map.entry("yellowBright",  "\u001B[93m"),
            Map.entry("blueBright",    "\u001B[94m"),
            Map.entry("magentaBright", "\u001B[95m"),
            Map.entry("cyanBright",    "\u001B[96m"),
            Map.entry("whiteBright",   "\u001B[97m")
    );

    /**
     * Convert a theme color value to an ANSI escape opening sequence.
     *
     * <p>Accepts:
     * <ul>
     *   <li>{@code rgb(R,G,B)} — emits a 24-bit truecolor sequence</li>
     *   <li>{@code ansi:<name>} — maps to the corresponding ANSI-16 code</li>
     * </ul>
     *
     * Falls back to magenta ({@code \u001B[35m}) if the format is unrecognized.
     * Translated from {@code themeColorToAnsi()} in theme.ts.
     *
     * @param themeColor color string from a {@link Theme} field
     * @return ANSI escape opening sequence (does NOT include a reset)
     */
    public static String themeColorToAnsi(String themeColor) {
        if (themeColor == null) return "\u001B[35m";

        // True-color: rgb(R,G,B)
        java.util.regex.Matcher rgbMatcher = java.util.regex.Pattern
                .compile("rgb\\(\\s?(\\d+),\\s?(\\d+),\\s?(\\d+)\\s?\\)")
                .matcher(themeColor);
        if (rgbMatcher.find()) {
            int r = Integer.parseInt(rgbMatcher.group(1));
            int g = Integer.parseInt(rgbMatcher.group(2));
            int b = Integer.parseInt(rgbMatcher.group(3));
            return "\u001B[38;2;" + r + ";" + g + ";" + b + "m";
        }

        // ANSI-16: ansi:<name>
        if (themeColor.startsWith("ansi:")) {
            String name = themeColor.substring(5);
            String code = ANSI_FG.get(name);
            return (code != null) ? code : "\u001B[35m";
        }

        return "\u001B[35m"; // Fallback: magenta
    }

    private ThemeUtils() {}
}
