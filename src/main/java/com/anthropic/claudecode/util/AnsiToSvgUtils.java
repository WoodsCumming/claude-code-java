package com.anthropic.claudecode.util;

import java.util.*;

/**
 * ANSI terminal text to SVG conversion.
 * Translated from src/utils/ansiToSvg.ts
 *
 * Supports basic ANSI color codes (foreground colors).
 */
public class AnsiToSvgUtils {

    // -------------------------------------------------------------------------
    // Color records
    // -------------------------------------------------------------------------

    /**
     * Represents an RGB color.
     * Translated from AnsiColor type in ansiToSvg.ts
     */
    public record AnsiColor(int r, int g, int b) {
        public String toRgbString() {
            return "rgb(" + r + ", " + g + ", " + b + ")";
        }
    }

    /**
     * A single styled text span within a line.
     * Translated from TextSpan type in ansiToSvg.ts
     */
    public record TextSpan(String text, AnsiColor color, boolean bold) {}

    /**
     * Options for SVG generation.
     * Translated from AnsiToSvgOptions type in ansiToSvg.ts
     */
    public record AnsiToSvgOptions(
            String fontFamily,
            int fontSize,
            int lineHeight,
            int paddingX,
            int paddingY,
            String backgroundColor,
            int borderRadius
    ) {
        public static AnsiToSvgOptions defaults() {
            return new AnsiToSvgOptions(
                    "Menlo, Monaco, monospace",
                    14,
                    22,
                    24,
                    24,
                    "rgb(30, 30, 30)",
                    8
            );
        }
    }

    // -------------------------------------------------------------------------
    // Default colors
    // -------------------------------------------------------------------------

    public static final AnsiColor DEFAULT_FG = new AnsiColor(229, 229, 229);
    public static final AnsiColor DEFAULT_BG = new AnsiColor(30, 30, 30);

    // ANSI 4-bit color palette (30–37, 90–97)
    private static final Map<Integer, AnsiColor> ANSI_COLORS = new HashMap<>();

    static {
        ANSI_COLORS.put(30, new AnsiColor(0, 0, 0));
        ANSI_COLORS.put(31, new AnsiColor(205, 49, 49));
        ANSI_COLORS.put(32, new AnsiColor(13, 188, 121));
        ANSI_COLORS.put(33, new AnsiColor(229, 229, 16));
        ANSI_COLORS.put(34, new AnsiColor(36, 114, 200));
        ANSI_COLORS.put(35, new AnsiColor(188, 63, 188));
        ANSI_COLORS.put(36, new AnsiColor(17, 168, 205));
        ANSI_COLORS.put(37, new AnsiColor(229, 229, 229));
        ANSI_COLORS.put(90, new AnsiColor(102, 102, 102));
        ANSI_COLORS.put(91, new AnsiColor(241, 76, 76));
        ANSI_COLORS.put(92, new AnsiColor(35, 209, 139));
        ANSI_COLORS.put(93, new AnsiColor(245, 245, 67));
        ANSI_COLORS.put(94, new AnsiColor(59, 142, 234));
        ANSI_COLORS.put(95, new AnsiColor(214, 112, 214));
        ANSI_COLORS.put(96, new AnsiColor(41, 184, 219));
        ANSI_COLORS.put(97, new AnsiColor(255, 255, 255));
    }

    // -------------------------------------------------------------------------
    // parseAnsi
    // -------------------------------------------------------------------------

    /**
     * Parse ANSI escape sequences from text into styled lines.
     * Supports basic colors (30-37, 90-97), 256-color (38;5;n), and true color (38;2;r;g;b).
     * Translated from parseAnsi() in ansiToSvg.ts
     *
     * @param text raw text with ANSI escape sequences
     * @return list of lines, each line being a list of TextSpan
     */
    public static List<List<TextSpan>> parseAnsi(String text) {
        List<List<TextSpan>> lines = new ArrayList<>();
        String[] rawLines = text.split("\n", -1);

        for (String line : rawLines) {
            List<TextSpan> spans = new ArrayList<>();
            AnsiColor currentColor = DEFAULT_FG;
            boolean bold = false;
            int i = 0;

            while (i < line.length()) {
                // Check for ANSI escape sequence ESC [
                if (i + 1 < line.length() && line.charAt(i) == '\u001B' && line.charAt(i + 1) == '[') {
                    // Find end of escape sequence (terminated by a letter)
                    int j = i + 2;
                    while (j < line.length() && !Character.isLetter(line.charAt(j))) {
                        j++;
                    }

                    if (j < line.length() && line.charAt(j) == 'm') {
                        // Parse color/style codes
                        String codeStr = line.substring(i + 2, j);
                        int[] codes;
                        if (codeStr.isEmpty()) {
                            codes = new int[]{0};
                        } else {
                            String[] parts = codeStr.split(";");
                            codes = new int[parts.length];
                            for (int x = 0; x < parts.length; x++) {
                                try {
                                    codes[x] = Integer.parseInt(parts[x]);
                                } catch (NumberFormatException e) {
                                    codes[x] = 0;
                                }
                            }
                        }

                        int k = 0;
                        while (k < codes.length) {
                            int code = codes[k];
                            if (code == 0) {
                                currentColor = DEFAULT_FG;
                                bold = false;
                            } else if (code == 1) {
                                bold = true;
                            } else if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) {
                                currentColor = ANSI_COLORS.getOrDefault(code, DEFAULT_FG);
                            } else if (code == 39) {
                                currentColor = DEFAULT_FG;
                            } else if (code == 38) {
                                if (k + 1 < codes.length && codes[k + 1] == 5 && k + 2 < codes.length) {
                                    // 256-color: 38;5;n
                                    currentColor = get256Color(codes[k + 2]);
                                    k += 2;
                                } else if (k + 1 < codes.length && codes[k + 1] == 2
                                        && k + 4 < codes.length) {
                                    // True color: 38;2;r;g;b
                                    currentColor = new AnsiColor(codes[k + 2], codes[k + 3], codes[k + 4]);
                                    k += 4;
                                }
                            }
                            k++;
                        }
                    }

                    i = j + 1;
                    continue;
                }

                // Regular text — accumulate until next ESC
                int textStart = i;
                while (i < line.length() && line.charAt(i) != '\u001B') {
                    i++;
                }
                String spanText = line.substring(textStart, i);
                if (!spanText.isEmpty()) {
                    spans.add(new TextSpan(spanText, currentColor, bold));
                }
            }

            // Preserve empty lines
            if (spans.isEmpty()) {
                spans.add(new TextSpan("", DEFAULT_FG, false));
            }
            lines.add(spans);
        }

        return lines;
    }

    /**
     * Get color from the 256-color xterm palette.
     * Translated from get256Color() in ansiToSvg.ts
     *
     * @param index palette index (0–255)
     * @return AnsiColor
     */
    public static AnsiColor get256Color(int index) {
        // Standard 16 colors
        if (index < 16) {
            AnsiColor[] standard = {
                new AnsiColor(0, 0, 0),
                new AnsiColor(128, 0, 0),
                new AnsiColor(0, 128, 0),
                new AnsiColor(128, 128, 0),
                new AnsiColor(0, 0, 128),
                new AnsiColor(128, 0, 128),
                new AnsiColor(0, 128, 128),
                new AnsiColor(192, 192, 192),
                new AnsiColor(128, 128, 128),
                new AnsiColor(255, 0, 0),
                new AnsiColor(0, 255, 0),
                new AnsiColor(255, 255, 0),
                new AnsiColor(0, 0, 255),
                new AnsiColor(255, 0, 255),
                new AnsiColor(0, 255, 255),
                new AnsiColor(255, 255, 255),
            };
            return index < standard.length ? standard[index] : DEFAULT_FG;
        }

        // 216-color cube (indices 16–231)
        if (index < 232) {
            int i = index - 16;
            int r = i / 36;
            int g = (i % 36) / 6;
            int b = i % 6;
            return new AnsiColor(
                    r == 0 ? 0 : 55 + r * 40,
                    g == 0 ? 0 : 55 + g * 40,
                    b == 0 ? 0 : 55 + b * 40
            );
        }

        // Grayscale ramp (indices 232–255)
        int gray = (index - 232) * 10 + 8;
        return new AnsiColor(gray, gray, gray);
    }

    // -------------------------------------------------------------------------
    // ansiToSvg
    // -------------------------------------------------------------------------

    /**
     * Convert ANSI-escaped terminal text to an SVG string using default options.
     * Translated from ansiToSvg() in ansiToSvg.ts
     *
     * @param ansiText terminal text with ANSI escape sequences
     * @return SVG string
     */
    public static String ansiToSvg(String ansiText) {
        return ansiToSvg(ansiText, AnsiToSvgOptions.defaults());
    }

    /**
     * Convert ANSI-escaped terminal text to an SVG string.
     * Uses {@code <tspan>} elements inside a single {@code <text>} per line so
     * the renderer handles character spacing natively.
     * Translated from ansiToSvg() in ansiToSvg.ts
     *
     * @param ansiText terminal text with ANSI escape sequences
     * @param options  SVG rendering options
     * @return SVG string
     */
    public static String ansiToSvg(String ansiText, AnsiToSvgOptions options) {
        if (ansiText == null) ansiText = "";

        String fontFamily = options.fontFamily();
        int fontSize = options.fontSize();
        int lineHeight = options.lineHeight();
        int paddingX = options.paddingX();
        int paddingY = options.paddingY();
        String backgroundColor = options.backgroundColor();
        int borderRadius = options.borderRadius();

        List<List<TextSpan>> lines = parseAnsi(ansiText);

        // Trim trailing empty lines
        while (!lines.isEmpty()) {
            List<TextSpan> lastLine = lines.get(lines.size() - 1);
            boolean allEmpty = lastLine.stream().allMatch(s -> s.text().isBlank());
            if (allEmpty) {
                lines.remove(lines.size() - 1);
            } else {
                break;
            }
        }

        // Estimate SVG dimensions
        double charWidthEstimate = fontSize * 0.6;
        int maxLineLength = lines.stream()
                .mapToInt(spans -> spans.stream().mapToInt(s -> s.text().length()).sum())
                .max()
                .orElse(0);
        int width = (int) Math.ceil(maxLineLength * charWidthEstimate + paddingX * 2);
        int height = lines.size() * lineHeight + paddingY * 2;

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
                width, height, width, height));
        svg.append(String.format(
                "  <rect width=\"100%%\" height=\"100%%\" fill=\"%s\" rx=\"%d\" ry=\"%d\"/>\n",
                backgroundColor, borderRadius, borderRadius));
        svg.append("  <style>\n");
        svg.append(String.format(
                "    text { font-family: %s; font-size: %dpx; white-space: pre; }\n",
                fontFamily, fontSize));
        svg.append("    .b { font-weight: bold; }\n");
        svg.append("  </style>\n");

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            List<TextSpan> spans = lines.get(lineIndex);
            double y = paddingY + (lineIndex + 1) * (double) lineHeight - (lineHeight - fontSize) / 2.0;

            svg.append(String.format("  <text x=\"%d\" y=\"%.1f\" xml:space=\"preserve\">", paddingX, y));

            for (TextSpan span : spans) {
                if (span.text().isEmpty()) continue;
                String colorStr = span.color().toRgbString();
                String boldAttr = span.bold() ? " class=\"b\"" : "";
                svg.append(String.format(
                        "<tspan fill=\"%s\"%s>%s</tspan>",
                        colorStr, boldAttr, XmlUtils.escapeXml(span.text())));
            }

            svg.append("</text>\n");
        }

        svg.append("</svg>");
        return svg.toString();
    }

    private AnsiToSvgUtils() {}
}
