package com.anthropic.claudecode.util.ink;

/**
 * Text measurement utilities for terminal rendering.
 *
 * <p>Computes the visual width and height of a text block given an optional
 * maximum column width. ANSI escape codes must be stripped before calling
 * {@link #measureText} if the input may contain them (escape codes have
 * zero visual width but non-zero string length).
 *
 * <p>Translated from {@code src/ink/measure-text.ts}.
 */
public final class TextMeasurer {

    private TextMeasurer() {}

    /** Result returned by {@link #measureText}. */
    public record Measurement(int width, int height) {}

    /**
     * Measure the visual dimensions of {@code text} when rendered inside a
     * container of {@code maxWidth} columns.
     *
     * <p>Single-pass implementation: computes both {@code width} and
     * {@code height} in one iteration rather than two separate passes. Uses
     * {@link String#indexOf} to avoid array allocation from {@code split('\n')}.
     *
     * @param text     the text to measure (should have ANSI codes stripped)
     * @param maxWidth maximum column width; {@code <= 0} or
     *                 {@link Double#POSITIVE_INFINITY} means no wrapping
     * @return the measured width (widest line) and height (total visual lines)
     */
    public static Measurement measureText(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return new Measurement(0, 0);
        }

        // Infinite or non-positive width means no wrapping — each logical line
        // is exactly one visual line.
        boolean noWrap = maxWidth <= 0;

        int width  = 0;
        int height = 0;
        int start  = 0;

        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            String line = (end == -1)
                ? text.substring(start)
                : text.substring(start, end);

            int w = lineWidth(line);
            if (w > width) width = w;

            if (noWrap) {
                height++;
            } else {
                height += (w == 0) ? 1 : (int) Math.ceil((double) w / maxWidth);
            }

            if (end == -1) break;
            start = end + 1;
        }

        return new Measurement(width, height);
    }

    /**
     * Convenience overload with no wrapping (equivalent to
     * {@code measureText(text, 0)}).
     */
    public static Measurement measureText(String text) {
        return measureText(text, 0);
    }

    // -------------------------------------------------------------------------
    // Visual width of a single line
    // -------------------------------------------------------------------------

    /**
     * Compute the visual (terminal-column) width of a single line. ANSI escape
     * sequences are skipped; wide (East-Asian / emoji) characters count as 2.
     *
     * <p>This is a simplified version of the {@code lineWidth} / {@code stringWidth}
     * helpers from the TypeScript source. It covers the common cases (SGR, OSC 8,
     * basic C0 controls) but does not implement every corner case.
     *
     * @param line a single logical line (no {@code '\n'})
     * @return column width
     */
    public static int lineWidth(String line) {
        int width = 0;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);

            // ESC introduces an escape sequence — skip it entirely
            if (c == '\u001b') {
                i = skipEscapeSequence(line, i);
                continue;
            }

            // C0 controls (except ESC, handled above): zero visual width
            if (c < 0x20) { i++; continue; }

            // Handle supplementary code points
            int cp = line.codePointAt(i);
            int charWidth = codePointWidth(cp);
            if (charWidth > 0) width += charWidth;
            i += Character.charCount(cp);
        }
        return width;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Advance past an ESC-introduced sequence starting at {@code start}
     * (where {@code line.charAt(start) == '\u001b'}).
     *
     * @return index of the first character after the sequence
     */
    private static int skipEscapeSequence(String line, int start) {
        int i = start + 1; // consume ESC
        if (i >= line.length()) return i;

        char next = line.charAt(i);

        if (next == '[') {
            // CSI: ESC [ ... final-byte (0x40–0x7E)
            i++;
            while (i < line.length()) {
                char ch = line.charAt(i++);
                if (ch >= 0x40 && ch <= 0x7e) break;
            }
        } else if (next == ']') {
            // OSC: ESC ] ... BEL or ESC \
            i++;
            while (i < line.length()) {
                char ch = line.charAt(i);
                if (ch == '\u0007') { i++; break; }
                if (ch == '\u001b' && i + 1 < line.length() && line.charAt(i + 1) == '\\') {
                    i += 2; break;
                }
                i++;
            }
        } else if (next == 'P' || next == '_' || next == '^' || next == 'X') {
            // DCS / APC / PM / SOS: same termination as OSC
            i++;
            while (i < line.length()) {
                char ch = line.charAt(i);
                if (ch == '\u0007') { i++; break; }
                if (ch == '\u001b' && i + 1 < line.length() && line.charAt(i + 1) == '\\') {
                    i += 2; break;
                }
                i++;
            }
        } else if (next >= 0x30 && next <= 0x7e) {
            // Two-character escape sequence (Fe / Fp / Fs ranges)
            i++;
        }
        // else: lone ESC — already advanced past ESC itself
        return i;
    }

    /**
     * Returns the terminal column width of a Unicode code point.
     * Wide characters (East-Asian, most emoji) return 2; zero-width
     * combining marks return 0; everything else returns 1.
     */
    private static int codePointWidth(int cp) {
        // Combining / zero-width characters
        if (cp == 0x00) return 0;
        if (Character.getType(cp) == Character.NON_SPACING_MARK) return 0;
        if (cp == 0x200b || cp == 0x200c || cp == 0x200d) return 0; // ZWS / ZWNJ / ZWJ
        if (cp == 0xfeff) return 0; // BOM

        // Emoji ranges
        if ((cp >= 0x2600 && cp <= 0x26ff)
         || (cp >= 0x2700 && cp <= 0x27bf)
         || (cp >= 0x1f300 && cp <= 0x1f9ff)
         || (cp >= 0x1fa00 && cp <= 0x1faff)
         || (cp >= 0x1f1e0 && cp <= 0x1f1ff)) return 2;

        // East Asian Wide ranges
        if ((cp >= 0x1100 && cp <= 0x115f)
         || (cp >= 0x2e80 && cp <= 0x9fff)
         || (cp >= 0xac00 && cp <= 0xd7a3)
         || (cp >= 0xf900 && cp <= 0xfaff)
         || (cp >= 0xfe10 && cp <= 0xfe1f)
         || (cp >= 0xfe30 && cp <= 0xfe6f)
         || (cp >= 0xff00 && cp <= 0xff60)
         || (cp >= 0xffe0 && cp <= 0xffe6)
         || (cp >= 0x20000 && cp <= 0x2fffd)
         || (cp >= 0x30000 && cp <= 0x3fffd)) return 2;

        return 1;
    }
}
