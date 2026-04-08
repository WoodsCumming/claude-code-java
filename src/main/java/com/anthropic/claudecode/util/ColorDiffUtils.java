package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;

/**
 * Color-diff rendering utilities.
 * Translated from src/native-ts/color-diff/index.ts
 *
 * Pure Java port of the TypeScript color-diff module. Provides ANSI-colored
 * syntax-highlighted diff output for terminal display.
 *
 * API matches the TypeScript module — callers see ColorDiff, ColorFile, getSyntaxTheme.
 */
@Slf4j
public class ColorDiffUtils {



    // -------------------------------------------------------------------------
    // Public API types
    // -------------------------------------------------------------------------

    /** A unified-diff hunk with line metadata. Translated from Hunk in index.ts */
    public record Hunk(int oldStart, int oldLines, int newStart, int newLines, List<String> lines) {}

    /** Syntax theme selection result. Translated from SyntaxTheme in index.ts */
    public record SyntaxTheme(String theme, String source) {}

    // -------------------------------------------------------------------------
    // Color / ANSI escape helpers
    // -------------------------------------------------------------------------

    private static final String RESET = "\u001b[0m";
    private static final String DIM   = "\u001b[2m";
    private static final String UNDIM = "\u001b[22m";

    /** RGBA color carrier. a=255 means opaque RGB; a=0 means palette index in r; a=1 means terminal default. */
    public record Color(int r, int g, int b, int a) {}

    private static Color rgb(int r, int g, int b)  { return new Color(r, g, b, 255); }
    private static Color ansiIdx(int idx)           { return new Color(idx, 0, 0, 0); }
    private static final Color DEFAULT_BG = new Color(0, 0, 0, 1);

    public enum ColorMode { TRUECOLOR, COLOR256, ANSI }

    public record Style(Color foreground, Color background) {}

    /** A styled text block: [style, text]. */
    public record Block(Style style, String text) {}

    public enum Marker { ADD, DELETE, CONTEXT;
        @Override public String toString() { return this == ADD ? "+" : this == DELETE ? "-" : " "; }
    }

    // -------------------------------------------------------------------------
    // Color mode detection
    // -------------------------------------------------------------------------

    public static ColorMode detectColorMode(String theme) {
        if (theme.contains("ansi")) return ColorMode.ANSI;
        String ct = System.getenv("COLORTERM");
        if (ct == null) ct = "";
        return (ct.equals("truecolor") || ct.equals("24bit")) ? ColorMode.TRUECOLOR : ColorMode.COLOR256;
    }

    // -------------------------------------------------------------------------
    // ansi256 approximation (port of ansi_colours::ansi256_from_rgb)
    // -------------------------------------------------------------------------

    private static final int[] CUBE_LEVELS = {0, 95, 135, 175, 215, 255};

    public static int ansi256FromRgb(int r, int g, int b) {
        int qr = quantize(r), qg = quantize(g), qb = quantize(b);
        int cubeIdx = 16 + 36 * qr + 6 * qg + qb;
        int grey = Math.round((r + g + b) / 3.0f);
        if (grey < 5) return 16;
        if (grey > 244 && qr == qg && qg == qb) return cubeIdx;
        int greyLevel = Math.max(0, Math.min(23, Math.round((grey - 8) / 10.0f)));
        int greyIdx   = 232 + greyLevel;
        int greyRgb   = 8 + greyLevel * 10;
        int cr = CUBE_LEVELS[qr], cg = CUBE_LEVELS[qg], cb = CUBE_LEVELS[qb];
        int dCube = sq(r - cr) + sq(g - cg) + sq(b - cb);
        int dGrey = sq(r - greyRgb) + sq(g - greyRgb) + sq(b - greyRgb);
        return dGrey < dCube ? greyIdx : cubeIdx;
    }

    private static int quantize(int c) {
        return c < 48 ? 0 : c < 115 ? 1 : c < 155 ? 2 : c < 195 ? 3 : c < 235 ? 4 : 5;
    }
    private static int sq(int x) { return x * x; }

    public static String colorToEscape(Color c, boolean fg, ColorMode mode) {
        if (c.a() == 0) {
            int idx = c.r();
            if (idx < 8)  return "\u001b[" + ((fg ? 30 : 40) + idx) + "m";
            if (idx < 16) return "\u001b[" + ((fg ? 90 : 100) + (idx - 8)) + "m";
            return "\u001b[" + (fg ? 38 : 48) + ";5;" + idx + "m";
        }
        if (c.a() == 1) return fg ? "\u001b[39m" : "\u001b[49m";
        int codeType = fg ? 38 : 48;
        if (mode == ColorMode.TRUECOLOR) {
            return "\u001b[" + codeType + ";2;" + c.r() + ";" + c.g() + ";" + c.b() + "m";
        }
        return "\u001b[" + codeType + ";5;" + ansi256FromRgb(c.r(), c.g(), c.b()) + "m";
    }

    // -------------------------------------------------------------------------
    // Theme
    // -------------------------------------------------------------------------

    public record Theme(
        Color addLine, Color addWord, Color addDecoration,
        Color deleteLine, Color deleteWord, Color deleteDecoration,
        Color foreground, Color background,
        Map<String, Color> scopes
    ) {}

    /** Keywords that hljs tags as 'keyword' but syntect maps to storage.type (cyan). */
    private static final Set<String> STORAGE_KEYWORDS = Set.of(
        "const", "let", "var", "function", "class", "type", "interface",
        "enum", "namespace", "module", "def", "fn", "func", "struct", "trait", "impl"
    );

    private static final Map<String, Color> MONOKAI_SCOPES = Map.ofEntries(
        Map.entry("keyword",                    rgb(249, 38,  114)),
        Map.entry("_storage",                   rgb(102, 217, 239)),
        Map.entry("built_in",                   rgb(166, 226,  46)),
        Map.entry("type",                       rgb(166, 226,  46)),
        Map.entry("literal",                    rgb(190, 132, 255)),
        Map.entry("number",                     rgb(190, 132, 255)),
        Map.entry("string",                     rgb(230, 219, 116)),
        Map.entry("title",                      rgb(166, 226,  46)),
        Map.entry("title.function",             rgb(166, 226,  46)),
        Map.entry("title.class",                rgb(166, 226,  46)),
        Map.entry("title.class.inherited",      rgb(166, 226,  46)),
        Map.entry("params",                     rgb(253, 151,  31)),
        Map.entry("comment",                    rgb(117, 113,  94)),
        Map.entry("meta",                       rgb(117, 113,  94)),
        Map.entry("attr",                       rgb(166, 226,  46)),
        Map.entry("attribute",                  rgb(166, 226,  46)),
        Map.entry("variable",                   rgb(255, 255, 255)),
        Map.entry("variable.language",          rgb(255, 255, 255)),
        Map.entry("property",                   rgb(255, 255, 255)),
        Map.entry("operator",                   rgb(249,  38, 114)),
        Map.entry("punctuation",                rgb(248, 248, 242)),
        Map.entry("symbol",                     rgb(190, 132, 255)),
        Map.entry("regexp",                     rgb(230, 219, 116)),
        Map.entry("subst",                      rgb(248, 248, 242))
    );

    private static final Map<String, Color> GITHUB_SCOPES = Map.ofEntries(
        Map.entry("keyword",                    rgb(167,  29,  93)),
        Map.entry("_storage",                   rgb(167,  29,  93)),
        Map.entry("built_in",                   rgb(  0, 134, 179)),
        Map.entry("type",                       rgb(  0, 134, 179)),
        Map.entry("literal",                    rgb(  0, 134, 179)),
        Map.entry("number",                     rgb(  0, 134, 179)),
        Map.entry("string",                     rgb( 24,  54, 145)),
        Map.entry("title",                      rgb(121,  93, 163)),
        Map.entry("title.function",             rgb(121,  93, 163)),
        Map.entry("title.class",                rgb(  0,   0,   0)),
        Map.entry("title.class.inherited",      rgb(  0,   0,   0)),
        Map.entry("params",                     rgb(  0, 134, 179)),
        Map.entry("comment",                    rgb(150, 152, 150)),
        Map.entry("meta",                       rgb(150, 152, 150)),
        Map.entry("attr",                       rgb(  0, 134, 179)),
        Map.entry("attribute",                  rgb(  0, 134, 179)),
        Map.entry("variable",                   rgb(  0, 134, 179)),
        Map.entry("variable.language",          rgb(  0, 134, 179)),
        Map.entry("property",                   rgb(  0, 134, 179)),
        Map.entry("operator",                   rgb(167,  29,  93)),
        Map.entry("punctuation",                rgb( 51,  51,  51)),
        Map.entry("symbol",                     rgb(  0, 134, 179)),
        Map.entry("regexp",                     rgb( 24,  54, 145)),
        Map.entry("subst",                      rgb( 51,  51,  51))
    );

    private static final Map<String, Color> ANSI_SCOPES = Map.ofEntries(
        Map.entry("keyword",          ansiIdx(13)),
        Map.entry("_storage",         ansiIdx(14)),
        Map.entry("built_in",         ansiIdx(14)),
        Map.entry("type",             ansiIdx(14)),
        Map.entry("literal",          ansiIdx(12)),
        Map.entry("number",           ansiIdx(12)),
        Map.entry("string",           ansiIdx(10)),
        Map.entry("title",            ansiIdx(11)),
        Map.entry("title.function",   ansiIdx(11)),
        Map.entry("comment",          ansiIdx(8)),
        Map.entry("meta",             ansiIdx(8))
    );

    public static Theme buildTheme(String themeName, ColorMode mode) {
        boolean isDark       = themeName.contains("dark");
        boolean isAnsi       = themeName.contains("ansi");
        boolean isDaltonized = themeName.contains("daltonized");
        boolean tc           = mode == ColorMode.TRUECOLOR;

        if (isAnsi) {
            return new Theme(
                DEFAULT_BG, DEFAULT_BG, ansiIdx(10),
                DEFAULT_BG, DEFAULT_BG, ansiIdx(9),
                ansiIdx(7), DEFAULT_BG, ANSI_SCOPES
            );
        }

        if (isDark) {
            Color fg          = rgb(248, 248, 242);
            Color deleteLine  = rgb(61,  1,   0);
            Color deleteWord  = rgb(92,  2,   0);
            Color deleteDeco  = rgb(220, 90,  90);
            if (isDaltonized) {
                return new Theme(
                    tc ? rgb(0, 27, 41)  : ansiIdx(17),
                    tc ? rgb(0, 48, 71)  : ansiIdx(24),
                    rgb(81, 160, 200),
                    deleteLine, deleteWord, deleteDeco,
                    fg, DEFAULT_BG, MONOKAI_SCOPES
                );
            }
            return new Theme(
                tc ? rgb(2,  40,  0) : ansiIdx(22),
                tc ? rgb(4,  71,  0) : ansiIdx(28),
                rgb(80, 200, 80),
                deleteLine, deleteWord, deleteDeco,
                fg, DEFAULT_BG, MONOKAI_SCOPES
            );
        }

        // light
        Color fg         = rgb(51,  51,  51);
        Color deleteLine = rgb(255, 220, 220);
        Color deleteWord = rgb(255, 199, 199);
        Color deleteDeco = rgb(207,  34,  46);
        if (isDaltonized) {
            return new Theme(
                rgb(219, 237, 255), rgb(179, 217, 255), rgb(36, 87, 138),
                deleteLine, deleteWord, deleteDeco,
                fg, DEFAULT_BG, GITHUB_SCOPES
            );
        }
        return new Theme(
            rgb(220, 255, 220), rgb(178, 255, 178), rgb(36, 138, 61),
            deleteLine, deleteWord, deleteDeco,
            fg, DEFAULT_BG, GITHUB_SCOPES
        );
    }

    public static String defaultSyntaxThemeName(String themeName) {
        if (themeName.contains("ansi")) return "ansi";
        if (themeName.contains("dark")) return "Monokai Extended";
        return "GitHub";
    }

    // -------------------------------------------------------------------------
    // Theme helpers
    // -------------------------------------------------------------------------

    public static Style defaultStyle(Theme theme) {
        return new Style(theme.foreground(), theme.background());
    }

    public static Color lineBackground(Marker marker, Theme theme) {
        return switch (marker) {
            case ADD     -> theme.addLine();
            case DELETE  -> theme.deleteLine();
            case CONTEXT -> theme.background();
        };
    }

    public static Color wordBackground(Marker marker, Theme theme) {
        return switch (marker) {
            case ADD     -> theme.addWord();
            case DELETE  -> theme.deleteWord();
            case CONTEXT -> theme.background();
        };
    }

    public static Color decorationColor(Marker marker, Theme theme) {
        return switch (marker) {
            case ADD     -> theme.addDecoration();
            case DELETE  -> theme.deleteDecoration();
            case CONTEXT -> theme.foreground();
        };
    }

    // -------------------------------------------------------------------------
    // Language detection (filename-based — no external highlighter in Java)
    // -------------------------------------------------------------------------

    private static final Map<String, String> FILENAME_LANGS = Map.of(
        "Dockerfile", "dockerfile",
        "Makefile",   "makefile",
        "Rakefile",   "ruby",
        "Gemfile",    "ruby",
        "CMakeLists", "cmake"
    );

    public static String detectLanguage(String filePath, String firstLine) {
        if (filePath == null) return null;
        String base = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        int dotIdx  = base.lastIndexOf('.');
        String ext  = dotIdx >= 0 ? base.substring(dotIdx + 1) : "";
        String stem = dotIdx >= 0 ? base.substring(0, dotIdx) : base;

        String byName = FILENAME_LANGS.getOrDefault(base, FILENAME_LANGS.get(stem));
        if (byName != null) return byName;
        if (!ext.isEmpty()) return ext;

        if (firstLine != null) {
            String line = firstLine.startsWith("\ufeff") ? firstLine.substring(1) : firstLine;
            if (line.startsWith("#!")) {
                if (line.contains("bash") || line.contains("/sh"))   return "bash";
                if (line.contains("python"))                          return "python";
                if (line.contains("node"))                            return "javascript";
                if (line.contains("ruby"))                            return "ruby";
                if (line.contains("perl"))                            return "perl";
            }
            if (line.startsWith("<?php")) return "php";
            if (line.startsWith("<?xml")) return "xml";
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Rendering helpers
    // -------------------------------------------------------------------------

    public static String asTerminalEscaped(
            List<Block> blocks, ColorMode mode, boolean skipBackground, boolean dim) {
        StringBuilder sb = new StringBuilder(dim ? RESET + DIM : RESET);
        for (Block block : blocks) {
            sb.append(colorToEscape(block.style().foreground(), true,  mode));
            if (!skipBackground) {
                sb.append(colorToEscape(block.style().background(), false, mode));
            }
            sb.append(block.text());
        }
        sb.append(RESET);
        return sb.toString();
    }

    public static Marker parseMarker(String s) {
        return switch (s) {
            case "+" -> Marker.ADD;
            case "-" -> Marker.DELETE;
            default  -> Marker.CONTEXT;
        };
    }

    public static int maxLineNumber(Hunk hunk) {
        int oldEnd = Math.max(0, hunk.oldStart() + hunk.oldLines() - 1);
        int newEnd = Math.max(0, hunk.newStart() + hunk.newLines() - 1);
        return Math.max(oldEnd, newEnd);
    }

    // -------------------------------------------------------------------------
    // ColorDiff — renders a diff hunk with syntax highlighting
    // -------------------------------------------------------------------------

    /**
     * Renders a unified-diff hunk with ANSI syntax highlighting.
     * Translated from class ColorDiff in index.ts
     */
    public static class ColorDiff {
        private final Hunk hunk;
        private final String filePath;
        private final String firstLine;
        private final String prefixContent;

        public ColorDiff(Hunk hunk, String firstLine, String filePath, String prefixContent) {
            this.hunk = hunk;
            this.firstLine = firstLine;
            this.filePath = filePath;
            this.prefixContent = prefixContent;
        }

        /**
         * Render the hunk to a list of ANSI-escape-colored output lines.
         * Translated from render() in ColorDiff class.
         */
        public List<String> render(String themeName, int width, boolean dim) {
            ColorMode mode = detectColorMode(themeName);
            Theme theme    = buildTheme(themeName, mode);

            int maxDigits     = String.valueOf(maxLineNumber(hunk)).length();
            int oldLine       = hunk.oldStart();
            int newLine       = hunk.newStart();
            int effectiveWidth = Math.max(1, width - maxDigits - 3);

            record Entry(int lineNumber, Marker marker, String code) {}
            List<Entry> entries = new ArrayList<>();
            for (String rawLine : hunk.lines()) {
                Marker marker = parseMarker(rawLine.isEmpty() ? " " : rawLine.substring(0, 1));
                String code   = rawLine.isEmpty() ? "" : rawLine.substring(1);
                int lineNumber;
                switch (marker) {
                    case ADD    -> { lineNumber = newLine++; }
                    case DELETE -> { lineNumber = oldLine++; }
                    default     -> { lineNumber = newLine; oldLine++; newLine++; }
                }
                entries.add(new Entry(lineNumber, marker, code));
            }

            List<String> out = new ArrayList<>();
            for (Entry entry : entries) {
                List<Block> tokens = List.of(new Block(defaultStyle(theme), entry.code()));
                List<Block> withBg = applyLineBackground(tokens, entry.marker(), theme);
                String lineNum = (entry.lineNumber() > 0)
                        ? String.format(" %" + maxDigits + "d ", entry.lineNumber())
                        : " ".repeat(maxDigits + 2);

                Style decoStyle = new Style(
                    decorationColor(entry.marker(), theme),
                    lineBackground(entry.marker(), theme)
                );

                List<Block> line = new ArrayList<>();
                line.add(new Block(decoStyle, lineNum));
                if (entry.marker() != Marker.CONTEXT) {
                    line.add(new Block(decoStyle, entry.marker().toString()));
                }
                line.addAll(withBg);
                out.add(asTerminalEscaped(line, mode, false, dim));
            }
            return out;
        }

        private List<Block> applyLineBackground(List<Block> blocks, Marker marker, Theme theme) {
            Color bg = lineBackground(marker, theme);
            List<Block> result = new ArrayList<>(blocks.size());
            for (Block b : blocks) {
                result.add(new Block(new Style(b.style().foreground(), bg), b.text()));
            }
            return result;
        }
    }

    // -------------------------------------------------------------------------
    // ColorFile — renders a file with syntax highlighting
    // -------------------------------------------------------------------------

    /**
     * Renders a complete file with ANSI line-numbered output.
     * Translated from class ColorFile in index.ts
     */
    public static class ColorFile {
        private final String code;
        private final String filePath;

        public ColorFile(String code, String filePath) {
            this.code = code;
            this.filePath = filePath;
        }

        /**
         * Render to a list of ANSI-escape-colored output lines.
         * Translated from render() in ColorFile class.
         */
        public List<String> render(String themeName, int width, boolean dim) {
            ColorMode mode = detectColorMode(themeName);
            Theme theme    = buildTheme(themeName, mode);

            List<String> lines = new ArrayList<>(Arrays.asList(code.split("\n", -1)));
            // Rust .lines() drops trailing empty from trailing \n
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }

            int maxDigits      = String.valueOf(lines.size()).length();
            int effectiveWidth = Math.max(1, width - maxDigits - 2);

            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String lineText = lines.get(i);
                List<Block> tokens = List.of(new Block(defaultStyle(theme), lineText));
                String lineNum = String.format(" %" + maxDigits + "d ", i + 1);

                Style numStyle = new Style(theme.foreground(), theme.background());
                List<Block> line = new ArrayList<>();
                line.add(new Block(numStyle, lineNum));
                line.addAll(tokens);
                out.add(asTerminalEscaped(line, mode, true, dim));
            }
            return out;
        }
    }

    // -------------------------------------------------------------------------
    // getSyntaxTheme
    // -------------------------------------------------------------------------

    /**
     * Returns the syntax theme for the given theme name.
     * Translated from getSyntaxTheme() in index.ts
     */
    public static SyntaxTheme getSyntaxTheme(String themeName) {
        return new SyntaxTheme(defaultSyntaxThemeName(themeName), null);
    }

    // -------------------------------------------------------------------------
    // Legacy availability helpers (preserved from previous translation)
    // -------------------------------------------------------------------------

    public enum ColorModuleUnavailableReason { ENV }

    public static ColorModuleUnavailableReason getColorModuleUnavailableReason() {
        String value = System.getenv("CLAUDE_CODE_SYNTAX_HIGHLIGHT");
        return isEnvDefinedFalsy(value) ? ColorModuleUnavailableReason.ENV : null;
    }

    public static boolean isColorDiffAvailable() { return getColorModuleUnavailableReason() == null; }
    public static boolean isColorFileAvailable()  { return getColorModuleUnavailableReason() == null; }
    public static boolean isSyntaxThemeAvailable(String themeName) { return isColorDiffAvailable(); }

    static boolean isEnvDefinedFalsy(String value) {
        if (value == null) return false;
        return switch (value.toLowerCase().trim()) {
            case "0", "false", "no", "off", "" -> true;
            default -> false;
        };
    }

    private ColorDiffUtils() {}
}
